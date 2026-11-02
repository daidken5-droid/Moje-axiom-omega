// AVIOR RAG: dávkový výpočet vektorů pro offline knihovnu (běží v CI, ne v telefonu).
//
// Tokenizace a pooling jsou STEJNÉ jako v telefonu (app/src/main/cpp/omega_jni.cpp → nativeEmbed):
//   common_tokenize(add_special=true, parse_special=false), pooling z metadat modelu (Qwen3-Embedding: last),
//   pak L2 normalizace → zkrácení na prvních DIM složek (Matryoshka) → nová normalizace → int8.
//
// Vstup (stdin): řádky "rowid<TAB>text" (text bez tabulátorů/nových řádků).
// Výstup (soubor): záznamy [int32 rowid][float32 scale][DIM × int8], little-endian.
//
// Použití: avior_embed model.gguf out.bin [dim=256] [threads=4] [max_tokens=128] [n_batch=512] [n_seq=8]
#include "llama.h"
#include "common.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>
#include <chrono>

struct Doc { int32_t id; std::vector<llama_token> toks; };

static void write_vec(FILE *f, int32_t id, const float *e, int n_embd, int dim) {
    double n = 0; for (int i = 0; i < n_embd; ++i) n += (double) e[i] * e[i];
    n = std::sqrt(n); if (n <= 0) n = 1;
    std::vector<float> v(dim);
    double m = 0; for (int i = 0; i < dim; ++i) { v[i] = (float) (e[i] / n); m += (double) v[i] * v[i]; }
    m = std::sqrt(m); if (m <= 0) m = 1;
    float amax = 0; for (int i = 0; i < dim; ++i) { v[i] = (float) (v[i] / m); amax = std::max(amax, std::fabs(v[i])); }
    const float scale = amax > 0 ? amax / 127.0f : 1.0f;
    std::vector<int8_t> q(dim);
    for (int i = 0; i < dim; ++i) { int x = (int) std::lround(v[i] / scale); q[i] = (int8_t) std::max(-127, std::min(127, x)); }
    fwrite(&id, 4, 1, f); fwrite(&scale, 4, 1, f); fwrite(q.data(), 1, dim, f);
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "avior_embed model.gguf out.bin [dim] [threads] [max_tokens]\n"); return 1; }
    const int dim = argc > 3 ? atoi(argv[3]) : 256;
    const int threads = argc > 4 ? atoi(argv[4]) : 4;
    const int max_tokens = argc > 5 ? atoi(argv[5]) : 128;
    // malé dávky: pozornost roste s kvadrátem délky dávky → 512 tokenů je výrazně rychlejší než 4096
    const int n_batch = argc > 6 ? atoi(argv[6]) : 512;
    const int n_seq = argc > 7 ? atoi(argv[7]) : 8;

    llama_backend_init();
    llama_log_set([](ggml_log_level lvl, const char *t, void *) { if (lvl >= GGML_LOG_LEVEL_WARN) fputs(t, stderr); }, nullptr);
    llama_model *model = llama_model_load_from_file(argv[1], llama_model_default_params());
    if (!model) { fprintf(stderr, "model se nenačetl\n"); return 1; }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_batch; cp.n_batch = n_batch; cp.n_ubatch = n_batch; cp.n_seq_max = n_seq;
    cp.embeddings = true; cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;
    cp.n_threads = threads; cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { fprintf(stderr, "kontext se nevytvořil\n"); return 1; }
    const int n_embd = llama_model_n_embd(model);
    if (dim > n_embd) { fprintf(stderr, "dim > n_embd\n"); return 1; }
    FILE *out = fopen(argv[2], "wb");
    if (!out) { perror("out"); return 1; }

    std::vector<Doc> pending;
    int total_tok = 0; long done = 0;
    auto t0 = std::chrono::steady_clock::now();
    auto flush = [&]() {
        if (pending.empty()) return;
        llama_batch b = llama_batch_init(total_tok, 0, 1);
        for (int s = 0; s < (int) pending.size(); ++s) {
            auto &d = pending[s];
            for (int i = 0; i < (int) d.toks.size(); ++i) {
                const int k = b.n_tokens++;
                b.token[k] = d.toks[i]; b.pos[k] = i; b.n_seq_id[k] = 1; b.seq_id[k][0] = s;
                b.logits[k] = true;
            }
        }
        llama_memory_clear(llama_get_memory(ctx), true);
        if (llama_decode(ctx, b) != 0) { fprintf(stderr, "decode selhal\n"); exit(2); }
        for (int s = 0; s < (int) pending.size(); ++s) {
            const float *e = llama_get_embeddings_seq(ctx, s);
            if (!e) { fprintf(stderr, "bez vektoru\n"); exit(3); }
            write_vec(out, pending[s].id, e, n_embd, dim);
        }
        llama_batch_free(b);
        done += (long) pending.size();
        pending.clear(); total_tok = 0;
        if (done % 2048 < n_seq) {
            double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
            fprintf(stderr, "%ld článků · %.1f/s\n", done, done / sec);
        }
    };

    std::string line;
    while (std::getline(std::cin, line)) {
        auto tab = line.find('\t');
        if (tab == std::string::npos) continue;
        Doc d; d.id = (int32_t) atol(line.substr(0, tab).c_str());
        d.toks = common_tokenize(vocab, line.substr(tab + 1), /*add_special*/ true, /*parse_special*/ false);
        if (d.toks.empty()) continue;
        if ((int) d.toks.size() > max_tokens) {
            // stejně jako v telefonu zachovat koncový speciální token (EOS) – pooling „last“ ho čte
            const llama_token last = d.toks.back();
            d.toks.resize(max_tokens);
            if (llama_vocab_is_eog(vocab, last)) d.toks.back() = last;
        }
        if (total_tok + (int) d.toks.size() > n_batch || (int) pending.size() >= n_seq) flush();
        total_tok += (int) d.toks.size();
        pending.push_back(std::move(d));
    }
    flush();
    fclose(out);
    double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    fprintf(stderr, "hotovo: %ld článků za %.0f s\n", done, sec);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
