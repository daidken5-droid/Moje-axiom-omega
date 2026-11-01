// AVIOR – nativní most k llama.cpp (lokální mozek v telefonu).
//
// Oproti libaxiom.so z AXIOM 2.1:
//  • šablona chatu se bere z modelu (Jinja přes common/chat) – funguje pro Qwen3/3.5, Llama, Gemma…
//  • kontext 4–8k místo 2048, výstup podle RANDY úrovně místo pevných 512 tokenů,
//  • streamování po tokenech do Kotlinu (UTF-8 bezpečně po celých znacích),
//  • zrušení i během zpracování dlouhého promptu (abort callback),
//  • znovupoužití KV cache pro společný prefix (vícekrokové RANDY průchody sdílí systémový prompt),
//  • automatické zkrácení historie, když se prompt nevejde do kontextu,
//  • text uživatele a dokumentů se tokenizuje BEZ speciálních tokenů (ochrana proti prompt injection).

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OmegaNative", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OmegaNative", __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, "[omega] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[omega:E] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#endif

namespace {

constexpr int RC_OK = 0;
constexpr int RC_CANCELLED = 1;
constexpr int RC_LENGTH = 2;
constexpr int RC_BAD_HANDLE = -1;
constexpr int RC_TEMPLATE = -2;
constexpr int RC_TOO_LONG = -3;
constexpr int RC_DECODE = -4;

struct Omega {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    common_chat_templates_ptr tmpls;
    std::vector<llama_token> cached;  // tokeny, které právě leží v KV cache
    std::atomic<bool> cancel{false};
    std::mutex mu;
    int n_ctx = 0;
    int n_batch = 512;
    // statistiky posledního běhu
    int st_prompt = 0, st_reused = 0, st_gen = 0, st_dropped = 0;
    double st_prompt_ms = 0, st_gen_ms = 0;
};

bool g_backend_ready = false;
std::mutex g_init_mu;

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java String → UTF-8 přes String.getBytes("UTF-8") (GetStringUTFChars vrací "modified UTF-8"
// a rozbil by emoji a jiné znaky mimo BMP).
std::string jstr_utf8(JNIEnv *env, jstring s) {
    if (!s) return {};
    jclass cls = env->GetObjectClass(s);
    jmethodID mid = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(s, mid, enc);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(cls);
    if (!bytes) return {};
    jsize len = env->GetArrayLength(bytes);
    std::string out((size_t) len, '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(bytes);
    return out;
}

// Délka nejdelšího prefixu, který končí na hranici celého UTF-8 znaku.
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {  // začátek znaku
            size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
            size_t have = n - (i - 1);
            return have >= need ? n : i - 1;
        }
        --i;
        ++back;
    }
    return n;
}

bool abort_cb(void *data) {
    auto *o = static_cast<Omega *>(data);
    return o->cancel.load();
}

struct Formatted {
    std::vector<llama_token> tokens;
    bool starts_in_think = false;
};

// Vyrenderuje chat šablonou modelu a tokenizuje. Uživatelský obsah bez speciálních tokenů.
bool format_and_tokenize(Omega *o, const std::vector<common_chat_msg> &msgs, bool thinking, Formatted &out, std::string &err) {
    common_chat_templates_inputs in;
    in.messages = msgs;
    in.add_generation_prompt = true;
    in.enable_thinking = thinking;
    in.add_bos = llama_vocab_get_add_bos(o->vocab);
    in.add_eos = llama_vocab_get_add_eos(o->vocab);

    common_chat_params params;
    bool ok = false;
    for (int attempt = 0; attempt < 2 && !ok; ++attempt) {
        in.use_jinja = attempt == 0;
        try {
            params = common_chat_templates_apply(o->tmpls.get(), in);
            ok = true;
        } catch (const std::exception &e) {
            err = e.what();
            LOGE("šablona (jinja=%d) selhala: %s", (int) in.use_jinja, e.what());
        }
    }
    if (!ok) return false;

    out.tokens.clear();
    bool first = true;
    std::string tail;
    for (const auto &part : params.prompt.parts) {
        if (part.text.empty()) continue;
        auto toks = common_tokenize(o->vocab, part.text, /*add_special*/ first && in.add_bos, /*parse_special*/ !part.is_input);
        out.tokens.insert(out.tokens.end(), toks.begin(), toks.end());
        first = false;
        if (!part.is_input) tail = part.text; else tail.clear();
    }
    if (first) {  // prázdné části (nemělo by nastat) – použij celý řetězec
        out.tokens = common_tokenize(o->vocab, params.prompt.str(), in.add_bos, true);
        tail = params.prompt.str();
    }
    // Šablona Qwen3/3.5 s přemýšlením otevře <think> už v promptu – výstup pak začíná uvnitř úvahy.
    std::string t = tail;
    while (!t.empty() && (t.back() == '\n' || t.back() == ' ')) t.pop_back();
    out.starts_in_think = t.size() >= 7 && t.compare(t.size() - 7, 7, "<think>") == 0;
    return true;
}

int decode_tokens(Omega *o, const llama_token *toks, int n, int start_pos) {
    llama_batch batch = llama_batch_init(o->n_batch, 0, 1);
    int rc = 0;
    for (int i = 0; i < n && rc == 0; i += o->n_batch) {
        const int cur = std::min(o->n_batch, n - i);
        batch.n_tokens = cur;
        for (int j = 0; j < cur; ++j) {
            batch.token[j] = toks[i + j];
            batch.pos[j] = start_pos + i + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == n - 1);
        }
        if (o->cancel.load()) { rc = 1; break; }
        const int r = llama_decode(o->ctx, batch);
        if (r != 0) rc = (r == 2 || o->cancel.load()) ? 1 : -1;  // 2 = přerušeno abort callbackem
    }
    llama_batch_free(batch);
    return rc;
}

bool call_sink(JNIEnv *env, jobject sink, jmethodID mid, const std::string &bytes) {
    if (bytes.empty()) return true;
    jbyteArray arr = env->NewByteArray((jsize) bytes.size());
    env->SetByteArrayRegion(arr, 0, (jsize) bytes.size(), reinterpret_cast<const jbyte *>(bytes.data()));
    const jboolean keep = env->CallBooleanMethod(sink, mid, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return keep == JNI_TRUE;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeInit(JNIEnv *env, jobject, jstring jlibdir) {
    std::lock_guard<std::mutex> lk(g_init_mu);
    if (g_backend_ready) return;
    const std::string dir = jstr(env, jlibdir);
    if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    g_backend_ready = true;
    LOGI("backend připraven (%s)", llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("model se nenačetl: %s", path.c_str()); return 0; }

    auto *o = new Omega();
    o->model = model;
    o->vocab = llama_model_get_vocab(model);

    const int train = llama_model_n_ctx_train(model);
    int ctx_size = std::max(1024, (int) n_ctx);
    if (train > 0) ctx_size = std::min(ctx_size, train);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) ctx_size;
    cp.n_batch = (uint32_t) o->n_batch;
    cp.n_ubatch = (uint32_t) o->n_batch;
    cp.n_threads = std::max(1, (int) n_threads);
    cp.n_threads_batch = std::max(1, (int) n_threads);
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = o;
    // Výkon: flash attention (auto) + KV cache v Q8_0 = cca poloviční paměť kontextu, delší kontext na telefonu.
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;
    o->ctx = llama_init_from_model(model, cp);
    if (!o->ctx) {
        LOGI("Q8 KV cache nepodporována – zkouším F16");
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;
        o->ctx = llama_init_from_model(model, cp);
    }
    if (!o->ctx && ctx_size > 4096) {
        LOGI("málo paměti pro kontext %d – zkouším 4096", ctx_size);
        cp.n_ctx = 4096;
        o->ctx = llama_init_from_model(model, cp);
    }
    if (!o->ctx) {
        LOGE("kontext se nevytvořil (n_ctx=%d)", ctx_size);
        llama_model_free(model);
        delete o;
        return 0;
    }
    o->n_ctx = (int) llama_n_ctx(o->ctx);
    try {
        o->tmpls = common_chat_templates_init(model, "");
    } catch (const std::exception &e) {
        LOGE("šablona chatu: %s", e.what());
    }
    LOGI("model načten: ctx=%d vláken=%d", o->n_ctx, (int) n_threads);
    return reinterpret_cast<jlong>(o);
}

JNIEXPORT jstring JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeInfo(JNIEnv *env, jobject, jlong h) {
    auto *o = reinterpret_cast<Omega *>(h);
    if (!o) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(o->model, desc, sizeof(desc));
    const char *tmpl = llama_model_chat_template(o->model, nullptr);
    std::string s = std::string(desc) + "|" + std::to_string(llama_model_n_params(o->model)) + "|" +
                    std::to_string(llama_model_size(o->model)) + "|" + std::to_string(o->n_ctx) + "|" +
                    std::to_string(llama_model_n_ctx_train(o->model)) + "|" + (tmpl ? "template" : "no-template");
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto *o = reinterpret_cast<Omega *>(h);
    if (o) o->cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeStats(JNIEnv *env, jobject, jlong h) {
    auto *o = reinterpret_cast<Omega *>(h);
    if (!o) return env->NewStringUTF("");
    char buf[256];
    std::snprintf(buf, sizeof(buf), "%d|%d|%d|%.0f|%.0f|%d", o->st_prompt, o->st_reused, o->st_gen, o->st_prompt_ms, o->st_gen_ms, o->st_dropped);
    return env->NewStringUTF(buf);
}

/**
 * Vygeneruje odpověď. Bajty UTF-8 posílá do sink.onBytes(byte[]) → Boolean (false = stop).
 * Návratové kódy: 0 hotovo, 1 zrušeno, 2 limit délky, záporné = chyba.
 */
JNIEXPORT jint JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeGenerate(
        JNIEnv *env, jobject, jlong h, jobjectArray jroles, jobjectArray jcontents,
        jint max_tokens, jfloat temp, jfloat top_p, jfloat min_p, jfloat repeat_penalty,
        jboolean thinking, jobject sink) {
    auto *o = reinterpret_cast<Omega *>(h);
    if (!o || !o->ctx) return RC_BAD_HANDLE;
    std::lock_guard<std::mutex> lk(o->mu);
    o->cancel.store(false);
    o->st_prompt = o->st_reused = o->st_gen = o->st_dropped = 0;
    o->st_prompt_ms = o->st_gen_ms = 0;

    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_bytes = env->GetMethodID(sink_cls, "onBytes", "([B)Z");
    if (!on_bytes) return RC_BAD_HANDLE;

    std::vector<common_chat_msg> msgs;
    const jsize n_msgs = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n_msgs; ++i) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jc = (jstring) env->GetObjectArrayElement(jcontents, i);
        common_chat_msg m;
        m.role = jstr(env, jr);
        m.content = jstr_utf8(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs.push_back(std::move(m));
    }
    if (msgs.empty()) return RC_TEMPLATE;
    if (!o->tmpls) return RC_TEMPLATE;

    const int gen_budget = std::max(64, (int) max_tokens);
    const int max_prompt = o->n_ctx - std::min(gen_budget, o->n_ctx / 2) - 8;

    // Zkracování: nejdřív nejstarší zprávy mezi systémem a posledním dotazem, pak konec posledního dotazu.
    Formatted f;
    std::string err;
    for (int guard = 0; guard < 64; ++guard) {
        if (!format_and_tokenize(o, msgs, thinking == JNI_TRUE, f, err)) return RC_TEMPLATE;
        if ((int) f.tokens.size() <= max_prompt) break;
        const size_t first_non_system = (msgs.size() > 1 && msgs[0].role == "system") ? 1 : 0;
        if (msgs.size() - first_non_system > 1) {
            msgs.erase(msgs.begin() + (long) first_non_system);
            o->st_dropped++;
            continue;
        }
        // jediná zpráva je příliš dlouhá → zkrať její obsah
        auto &c = msgs.back().content;
        const double ratio = (double) max_prompt / (double) f.tokens.size();
        size_t keep = (size_t) ((double) c.size() * ratio * 0.9);
        if (keep < 200 || keep >= c.size()) return RC_TOO_LONG;
        while (keep > 0 && (((unsigned char) c[keep]) & 0xC0) == 0x80) --keep;
        c = c.substr(0, keep) + "\n[…zkráceno…]";
        o->st_dropped++;
    }
    if ((int) f.tokens.size() > max_prompt) return RC_TOO_LONG;

    // Znovupoužití KV cache: společný prefix s minulým během.
    size_t common = 0;
    while (common < o->cached.size() && common < f.tokens.size() && o->cached[common] == f.tokens[common]) ++common;
    if (common == f.tokens.size() && common > 0) --common;  // aspoň 1 token musí projít dekódováním kvůli logitům
    llama_memory_t mem = llama_get_memory(o->ctx);
    if (common == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    o->cached.assign(f.tokens.begin(), f.tokens.begin() + (long) common);

    const auto t0 = std::chrono::steady_clock::now();
    const int drc = decode_tokens(o, f.tokens.data() + common, (int) (f.tokens.size() - common), (int) common);
    const auto t1 = std::chrono::steady_clock::now();
    o->st_prompt = (int) f.tokens.size();
    o->st_reused = (int) common;
    o->st_prompt_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    if (drc != 0) {
        llama_memory_clear(mem, true);
        o->cached.clear();
        return drc > 0 ? RC_CANCELLED : RC_DECODE;
    }
    o->cached = f.tokens;

    // Vzorkování
    auto sp = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(o->vocab), 64, std::max(1.0f, (float) repeat_penalty), 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(o->vocab), 64, std::max(1.0f, (float) repeat_penalty), 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p > 0 ? top_p : 0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p > 0 ? min_p : 0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) std::chrono::steady_clock::now().time_since_epoch().count()));
    }

    int rc = RC_OK;
    std::string pending;
    if (f.starts_in_think) pending = "<think>\n";
    int pos = (int) f.tokens.size();
    llama_batch one = llama_batch_init(1, 0, 1);
    const auto g0 = std::chrono::steady_clock::now();
    for (int i = 0; i < gen_budget; ++i) {
        if (o->cancel.load()) { rc = RC_CANCELLED; break; }
        const llama_token tok = llama_sampler_sample(smpl, o->ctx, -1);
        if (llama_vocab_is_eog(o->vocab, tok)) break;  // llama_sampler_sample už token přijal (accept)

        char piece[256];
        const int n = llama_token_to_piece(o->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, (size_t) n);
        const size_t ready = utf8_complete_prefix(pending);
        if (ready > 0) {
            if (!call_sink(env, sink, on_bytes, pending.substr(0, ready))) { rc = RC_CANCELLED; break; }
            pending.erase(0, ready);
        }
        o->st_gen++;

        if (pos + 1 >= o->n_ctx) { rc = RC_LENGTH; break; }
        one.n_tokens = 1;
        one.token[0] = tok;
        one.pos[0] = pos;
        one.n_seq_id[0] = 1;
        one.seq_id[0][0] = 0;
        one.logits[0] = true;
        const int r = llama_decode(o->ctx, one);
        if (r != 0) { rc = (r == 2 || o->cancel.load()) ? RC_CANCELLED : RC_DECODE; break; }
        o->cached.push_back(tok);
        ++pos;
        if (i == gen_budget - 1) rc = RC_LENGTH;
    }
    if (!pending.empty() && rc != RC_CANCELLED) call_sink(env, sink, on_bytes, pending);
    o->st_gen_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - g0).count();
    llama_batch_free(one);
    llama_sampler_free(smpl);
    if (rc == RC_DECODE || rc == RC_CANCELLED) {
        // stav KV po chybě/zrušení je nejistý → příště čistý start
        llama_memory_clear(mem, true);
        o->cached.clear();
    }
    env->DeleteLocalRef(sink_cls);
    return rc;
}

/**
 * Sémantická paměť: načte embedding model (např. Qwen3-Embedding) s vlastním kontextem.
 * Pooling se bere z metadat modelu (LLAMA_POOLING_TYPE_UNSPECIFIED).
 */
JNIEXPORT jlong JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeLoadEmbedder(JNIEnv *env, jobject, jstring jpath, jint n_threads) {
    const std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("embedding model se nenačetl: %s", path.c_str()); return 0; }
    auto *o = new Omega();
    o->model = model;
    o->vocab = llama_model_get_vocab(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 2048;
    cp.n_ubatch = 2048;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;
    cp.n_threads = std::max(1, (int) n_threads);
    cp.n_threads_batch = std::max(1, (int) n_threads);
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = o;
    o->ctx = llama_init_from_model(model, cp);
    if (!o->ctx) { llama_model_free(model); delete o; return 0; }
    o->n_ctx = (int) llama_n_ctx(o->ctx);
    o->n_batch = 2048;
    return reinterpret_cast<jlong>(o);
}

/** Vektor významu textu (nenormalizovaný; normalizuje Kotlin). null = chyba. */
JNIEXPORT jfloatArray JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeEmbed(JNIEnv *env, jobject, jlong h, jstring jtext) {
    auto *o = reinterpret_cast<Omega *>(h);
    if (!o || !o->ctx) return nullptr;
    std::lock_guard<std::mutex> lk(o->mu);
    o->cancel.store(false);
    const std::string text = jstr_utf8(env, jtext);
    auto toks = common_tokenize(o->vocab, text, /*add_special*/ true, /*parse_special*/ false);
    if (toks.empty()) return nullptr;
    const int max_tok = std::min(o->n_ctx, o->n_batch) - 1;
    if ((int) toks.size() > max_tok) toks.resize((size_t) max_tok);
    llama_memory_t mem = llama_get_memory(o->ctx);
    if (mem) llama_memory_clear(mem, true);
    llama_batch batch = llama_batch_init((int) toks.size(), 0, 1);
    batch.n_tokens = (int) toks.size();
    for (int i = 0; i < batch.n_tokens; ++i) {
        batch.token[i] = toks[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = true;
    }
    int rc;
    if (llama_model_has_encoder(o->model) && !llama_model_has_decoder(o->model)) rc = llama_encode(o->ctx, batch);
    else rc = llama_decode(o->ctx, batch);
    jfloatArray out = nullptr;
    if (rc == 0) {
        const int n_embd = llama_model_n_embd(o->model);
        const float *emb = llama_get_embeddings_seq(o->ctx, 0);
        if (!emb) emb = llama_get_embeddings_ith(o->ctx, batch.n_tokens - 1);
        if (emb && n_embd > 0) {
            out = env->NewFloatArray(n_embd);
            env->SetFloatArrayRegion(out, 0, n_embd, emb);
        }
    } else {
        LOGE("embedding decode selhal: %d", rc);
    }
    llama_batch_free(batch);
    return out;
}

JNIEXPORT void JNICALL
Java_cz_axiom_omega_engine_NativeLlama_nativeFree(JNIEnv *, jobject, jlong h) {
    auto *o = reinterpret_cast<Omega *>(h);
    if (!o) return;
    o->cancel.store(true);
    {
        std::lock_guard<std::mutex> lk(o->mu);  // počkej na doběhnutí generování
        o->tmpls.reset();
        if (o->ctx) llama_free(o->ctx);
        if (o->model) llama_model_free(o->model);
        o->ctx = nullptr;
        o->model = nullptr;
    }
    delete o;
}

}  // extern "C"
