package cz.axiom.omega.core.llm

/**
 * CLOUD ZDARMA – výkonné otevřené modely na cizích serverech s bezplatným tarifem.
 *
 * Proč: telefon nenačítá nic do RAM (0 MB), odpověď teče stovky až tisíce tokenů za sekundu
 * a model (gpt-oss-120b, Llama 3.3 70B, Qwen 3…) je řádově silnější než mozek v telefonu.
 *
 *  • Groq       – api.groq.com/openai/v1   (LPU, velmi nízká latence)
 *  • Cerebras   – api.cerebras.ai/v1        (wafer-scale, nejvyšší rychlost výstupu)
 *  • OpenRouter – openrouter.ai/api/v1      (modely s příponou „:free“)
 *
 * Všichni mluví OpenAI Chat Completions, klíč je zdarma bez karty. Bezplatné tarify mají limity
 * (požadavky/tokeny za minutu a den) a seznam modelů se mění – proto:
 *  1) model se vybírá z aktuálního `/models` podle pořadí preferencí (žádné natvrdo zastaralé ID),
 *  2) když jeden poskytovatel vrátí limit (429), výpadek nebo neznámý model DŘÍV, než začne psát,
 *     dotaz plynule přejde na dalšího – uživatel nic nepozná.
 * DLP štít a air-gap platí stejně jako pro ostatní cloudy (obaluje to Omega.shield / Http).
 */
object FreeCloud {

    data class Provider(
        val id: String,
        val name: String,
        val base: String,
        /** Modely od nejlepšího; vybere se první, který poskytovatel právě nabízí. */
        val preferred: List<String>,
        /** Rychlý model pro jednoduché dotazy (Turbo); null = stejný jako hlavní. */
        val fast: List<String> = emptyList(),
        val signup: String,
        /** Vlastní filtr pro „cokoli zdarma“, když nic z preferencí není v seznamu. */
        val anyFree: (String) -> Boolean = { false },
        /** Placený (levný) tarif – silnější/stabilnější, platí se kreditem nebo paušálem. */
        val paid: Boolean = false,
        /**
         * Strop max_tokens. Orchestrátor posílá cloudu až 32 000 – DeepSeek ale přijímá max. 8 192 (jinak HTTP 400)
         * a bezplatné tarify Groq/Cerebras počítají požadovaný výstup do limitu tokenů za minutu (6–8 tis.) → 413/429.
         */
        val maxOut: Int = 4096,
        /** Kolik znaků historie a podkladů poslat (free tarify mají malé okno a limit tokenů za minutu). */
        val contextChars: Int = 20_000,
        /** Vlastní pořadí síly modelů, když nic z preferencí není v nabídce (null = podle velikosti v názvu). */
        val rank: ((String) -> Double)? = null,
        /** Modely, které nejsou obecný chat (obrázky, řeč, embeddingy…) – nikdy je nevybírat. */
        val exclude: Regex? = null,
        /** Poslat stream_options.include_usage → skutečný počet tokenů (přesná útrata kreditu místo odhadu). */
        val usageInStream: Boolean = false,
        /** Co poskytovatel dělá s daty (ukáže se před odesláním podkladů). */
        val dataNote: String = "",
    )

    val GEMINI_EXCLUDE = Regex("embed|tts|image|imagen|veo|live|native-audio|audio|computer-use|robotics|aqa|learnlm|gemma|vision")

    val PROVIDERS = listOf(
        Provider(
            "cerebras", "Cerebras", "https://api.cerebras.ai/v1",
            preferred = listOf("gpt-oss-120b", "qwen-3-235b-a22b-instruct-2507", "zai-glm-4.7", "llama-3.3-70b", "qwen-3-32b", "llama3.1-8b"),
            fast = listOf("gpt-oss-120b", "llama3.1-8b"),
            signup = "cloud.cerebras.ai",
        ),
        Provider(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            preferred = listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905", "moonshotai/kimi-k2-instruct", "llama-3.3-70b-versatile", "qwen/qwen3-32b", "llama-3.1-8b-instant"),
            fast = listOf("openai/gpt-oss-20b", "llama-3.1-8b-instant"),
            signup = "console.groq.com",
        ),
        Provider(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            preferred = listOf("openai/gpt-oss-120b:free", "deepseek/deepseek-chat-v3.1:free", "qwen/qwen3-235b-a22b:free", "meta-llama/llama-3.3-70b-instruct:free"),
            signup = "openrouter.ai/keys",
            anyFree = { it.endsWith(":free") },
        ),
        // ---- GOOGLE GEMINI (OpenAI-kompatibilní endpoint Gemini API, klíč z aistudio.google.com/apikey)
        // Bezplatná kvóta: klíč z projektu BEZ fakturace. Model se vybírá z nabídky klíče – Gemini 4 Hydrogen Google
        // zpřístupňuje postupně, takže když ho klíč ještě nemá, odpovídá nejsilnější dostupný Gemini.
        Provider(
            "gemini", "Gemini", GEMINI_BASE,
            preferred = listOf("gemini-4-hydrogen"),
            fast = listOf("gemini-4-hydrogen"),
            signup = "aistudio.google.com/apikey", maxOut = 8192, contextChars = 120_000,
            rank = ::geminiRank, exclude = GEMINI_EXCLUDE, usageInStream = true,
            dataNote = "Gemini API – bezplatná kvóta. Podle podmínek Google platí v EHP (vč. Česka), Švýcarsku a UK i pro bezplatnou kvótu " +
                "podmínky placených služeb (obsah se nepoužívá ke zlepšování produktů). Mimo tyto oblasti ho Google může použít ke zlepšování " +
                "a mohou ho číst lidé – citlivé údaje neposílej.",
        ),
        // Kredit z předplatného Google AI Pro (měsíční kredit na Gemini API po aktivaci v AI Studiu). Je to PLACENÝ projekt:
        // čerpá kredit, po jeho vyčerpání Google účtuje kartu v Cloud Billing → hlídá ho CostGuard (povolení + rozpočet).
        Provider(
            "geminipro", "Gemini (kredit Pro)", GEMINI_BASE,
            preferred = listOf("gemini-4-hydrogen"),
            fast = listOf("gemini-4-hydrogen"),
            signup = "aistudio.google.com (Google AI Pro → kredity)", paid = true, maxOut = 16_384, contextChars = 400_000,
            rank = ::geminiRank, exclude = GEMINI_EXCLUDE, usageInStream = true,
            dataNote = "Gemini API – placený projekt (kredit Google AI Pro): obsah se podle podmínek Google nepoužívá ke zlepšování produktů. " +
                "Po vyčerpání kreditu účtuje Google kartu v Cloud Billing – nastav si tam rozpočet.",
        ),
        // ---- LEVNÉ PLACENÉ (jednotky dolarů měsíčně při běžném používání)
        Provider(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            preferred = listOf("deepseek-chat", "deepseek-reasoner"),
            signup = "platform.deepseek.com", paid = true, maxOut = 8192, contextChars = 120_000,
        ),
        Provider(
            "chutes", "Chutes", "https://llm.chutes.ai/v1",
            preferred = listOf("deepseek-ai/DeepSeek-V3.2", "moonshotai/Kimi-K2.5", "zai-org/GLM-5", "deepseek-ai/DeepSeek-V3.1", "Qwen/Qwen3-235B-A22B-Instruct-2507", "openai/gpt-oss-120b"),
            fast = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            signup = "chutes.ai", paid = true, maxOut = 8192, contextChars = 120_000,
        ),
        // ---- PAUŠÁL za open-source modely (Kimi, GLM, DeepSeek, Qwen) – jeden klíč, pevná měsíční cena
        Provider(
            "nanogpt", "NanoGPT", "https://nano-gpt.com/api/v1",
            preferred = listOf("deepseek-ai/DeepSeek-V3.1-Terminus", "moonshotai/Kimi-K2-Instruct-0905", "zai-org/GLM-4.6", "deepseek-ai/DeepSeek-V3.1", "Qwen/Qwen3-235B-A22B-Instruct-2507", "openai/gpt-oss-120b"),
            fast = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            signup = "nano-gpt.com", paid = true, maxOut = 8192, contextChars = 120_000,
        ),
        Provider(
            "ollama", "Ollama Cloud", "https://ollama.com/v1",
            preferred = listOf("deepseek-v3.1:671b", "kimi-k2:1t", "glm-4.6", "qwen3-coder:480b", "gpt-oss:120b"),
            fast = listOf("gpt-oss:120b", "gpt-oss:20b"),
            signup = "ollama.com/settings/keys", paid = true, maxOut = 8192, contextChars = 120_000,
        ),
        Provider(
            "synthetic", "Synthetic", "https://api.synthetic.new/openai/v1",
            preferred = listOf("hf:deepseek-ai/DeepSeek-V3.1-Terminus", "hf:moonshotai/Kimi-K2-Instruct-0905", "hf:zai-org/GLM-4.6", "hf:deepseek-ai/DeepSeek-V3.1", "hf:Qwen/Qwen3-235B-A22B-Instruct-2507", "hf:openai/gpt-oss-120b"),
            fast = listOf("hf:openai/gpt-oss-120b"),
            signup = "synthetic.new", paid = true, maxOut = 8192, contextChars = 120_000,
        ),
    )

    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"

    /**
     * Síla modelu Gemini z názvu (nabídka se mění): vyšší verze > Hydrogen > Pro > Flash > Flash-Lite; náhledy mírně níž.
     * Neznámé kódové názvy (Argon…) se nevolí samy – můžou být bez bezplatné kvóty; uživatel je může zvolit ručně.
     */
    fun geminiRank(id: String): Double {
        val m = id.removePrefix("models/").lowercase()
        if (!m.startsWith("gemini")) return -100.0
        var s = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(m)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        s += when {
            "hydrogen" in m -> 3.0
            Regex("-pro\\b|-pro-").containsMatchIn(m) -> 2.0
            "flash-lite" in m -> 0.0
            "flash" in m -> 1.0
            Regex("gemini-\\d+(\\.\\d+)?-[a-z]+$").containsMatchIn(m) -> return -60.0 // jiný kódový název (cena/tarif neznámé) – nikdy automaticky
            else -> 0.5
        }
        if (Regex("preview|exp|latest").containsMatchIn(m)) s -= 0.3
        return s
    }

    /** Pořadí členů: „rychlost“ = nejdřív nejrychlejší servery zdarma, „kvalita“ = nejdřív placené silné modely. */
    fun order(quality: Boolean, primary: String? = null): List<Provider> {
        val base = if (quality) PROVIDERS.sortedByDescending { it.paid } else PROVIDERS
        // HLAVNÍ CLOUD zvolený uživatelem jde vždy první, ostatní zůstávají jako záloha
        return if (primary.isNullOrBlank()) base else base.sortedBy { if (it.id == primary) 0 else 1 }
    }

    /** Chatovací modely z nabídky poskytovatele pro výběr v UI: preferované první, pak podle síly; bez embeddingů, audia… */
    fun usableModels(p: Provider, available: List<String>, limit: Int = 60): List<String> {
        val usable = available.distinct().filter { !Regex("embed|whisper|tts|guard|vision|image|audio|rerank|moderat|dall-e|flux|sdxl").containsMatchIn(it.lowercase()) }
        val pref = (p.preferred + p.fast).map { it.lowercase() }
        return usable.sortedWith(compareBy<String> { m -> pref.indexOf(m.removePrefix("models/").lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
            .thenByDescending { modelScore(it) }).take(limit)
    }

    fun provider(id: String) = PROVIDERS.firstOrNull { it.id == id }

    /**
     * Vybere model: první z [wanted], který je v [available] (bez ohledu na „models/“ prefix a velikost písmen);
     * když seznam neznáme (prázdný), vezme první preferovaný.
     */
    fun pickModel(p: Provider, available: List<String>, fast: Boolean = false, avoid: Set<String> = emptySet()): String {
        val wanted = (if (fast && p.fast.isNotEmpty()) p.fast + p.preferred else p.preferred).filter { w -> avoid.none { it.removePrefix("models/").equals(w, true) } }
        val avail = available.map { it.removePrefix("models/") }.filter { a -> avoid.none { it.removePrefix("models/").equals(a, true) } }
        if (avail.isEmpty()) return wanted.firstOrNull() ?: p.preferred.first()
        val norm = avail.associateBy { it.lowercase() }
        wanted.firstOrNull { it.lowercase() in norm }?.let { return norm.getValue(it.lowercase()) }
        // nic z preferencí → nejsilnější obecný model z nabídky (dřív první v abecedě – i malý model na kód)
        val usable = avail.filter { !Regex("embed|whisper|tts|guard|vision|image|audio|rerank|moderat").containsMatchIn(it.lowercase()) && p.exclude?.containsMatchIn(it.lowercase()) != true }
        val pool = usable.filter(p.anyFree).ifEmpty { usable }
        val score = p.rank ?: ::modelScore
        return pool.filter { score(it) > -50 }.maxByOrNull { score(it) } ?: wanted.firstOrNull() ?: p.preferred.first()
    }

    /** Odhad síly obecného chatovacího modelu z názvu: velikost (B), bez specializací (kód, mini, vision). */
    fun modelScore(id: String): Double {
        val m = id.lowercase()
        val size = Regex("(\\d+(?:\\.\\d+)?)b\\b").findAll(m).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 20.0
        var s = kotlin.math.ln(size + 1)
        if (Regex("code|coder|math|mini|small|tiny|nano|lite|vl\\b|-vision").containsMatchIn(m)) s -= 2.0
        if (Regex("instruct|chat|-it\\b|gpt-oss|deepseek|qwen|llama|kimi|glm").containsMatchIn(m)) s += 0.5
        return s
    }

    /** gpt-oss a Qwen 3 přijímají reasoning_effort low|medium|high; ostatní ho ignorují nebo odmítnou → neposílat. */
    fun effortFor(model: String): ((String) -> String?)? =
        // Gemini (OpenAI-kompatibilní vrstva) mapuje reasoning_effort na úroveň přemýšlení; u novějších modelů ho nejde vypnout
        if (Regex("gpt-oss|^(models/)?gemini").containsMatchIn(model)) { e -> when (e.lowercase()) { "low" -> "low"; "high", "xhigh", "max" -> "high"; else -> "medium" } } else null

    data class Member(val provider: Provider, val key: String, val model: String)

    /** Jeden poskytovatel jako mozek (OpenAI-kompatibilní klient). */
    fun engine(m: Member): LlmEngine = KimiEngine(
        m.provider.base, m.key, m.model, EngineKind.FREE,
        effortMapper = effortFor(m.model), vendor = m.provider.name,
        labelOverride = "${m.provider.name} (${m.model.substringAfterLast('/').removeSuffix(":free")})",
        streamUsage = m.provider.usageInStream,
    )
}

/**
 * Řetěz poskytovatelů zdarma s přepnutím při chybě. Přepíná jen dokud nepřišel žádný text –
 * rozepsaná odpověď se nikdy nemíchá ze dvou modelů.
 */
class FreeCloudEngine(
    private val members: List<FreeCloud.Member>,
    private val factory: (FreeCloud.Member) -> LlmEngine = FreeCloud::engine,
    private val onFallback: (String) -> Unit = {},
    /** Seznam modelů poskytovatele (samooprava, když server model nezná). */
    private val listModels: (FreeCloud.Provider, String) -> List<String> = { p, k -> KimiEngine.listModels(p.base, k) },
    /** Zapamatovat opravený model (aby se příště nezkoušel ten neplatný). */
    private val onModel: (String, String) -> Unit = { _, _ -> },
    /** COST GUARD: placení členové jen s povolením a v rozpočtu (null = testy / bez ochrany) */
    private val guard: CostGuard? = null,
    /** Skutečná spotřeba úspěšného volání (VEGA: stínové měření bezplatného klíče). */
    private val onUsage: (provider: String, model: String, inTok: Long?, outTok: Long?) -> Unit = { _, _, _, _ -> },
) : LlmEngine {
    init { require(members.isNotEmpty()) { "Cloud: chybí klíč (Cerebras, Groq, OpenRouter, DeepSeek nebo Chutes)." } }

    override val id = "free:" + members.joinToString(",") { it.provider.id + "/" + it.model }
    override val label get() = lastLabel
    override val kind = EngineKind.FREE
    /** Podle prvního (preferovaného) poskytovatele – free tarify mají malé okno, placené velké. */
    override val contextBudgetChars = members.first().provider.contextChars

    @Volatile private var lastLabel = factory(members.first()).label

    /** Proč který člen řetězu neodpověděl (přísný režim: souhrn pro uživatele i rozhodnutí o schválení). */
    data class Failure(val provider: String, val name: String, val model: String, val message: String, val budget: Boolean, val rateLimited: Boolean, val estUsd: Double = 0.0)

    /** Všichni členové selhali – [failures] po jednom za člena. */
    class ChainException(msg: String, val failures: List<Failure>, cause: Throwable?) : EngineException(msg, retryable = true, cause = cause, billed = failures.isEmpty() && (cause as? EngineException)?.billed == true)

    private val strict get() = guard?.strict == true

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        var last: Exception? = null
        val failures = ArrayList<Failure>()
        for ((i, m0) in members.withIndex()) {
            var m = m0
            var healed = false
            while (true) {
                cancel.check()
                val eng = factory(m)
                var started = false
                val opts = options.copy(maxTokens = options.maxTokens.coerceIn(256, m.provider.maxOut))
                val fitted = fit(messages, m.provider.contextChars)
                val chars = fitted.sumOf { it.content.length }
                // PLACENÝ ČLEN: bez povolení / nad rozpočet se přeskočí – nikdy tichý přechod na placený endpoint
                if (m.provider.paid && guard != null) {
                    val d = guard.check(m.provider.id, m.model, chars, opts.maxTokens)
                    if (d is CostGuard.Decision.Deny) {
                        val est = guard.estimateUsd(m.provider.id, m.model, chars, opts.maxTokens)
                        last = CostGuard.BudgetException(d.reason, m.provider.id, m.model, est)
                        failures += Failure(m.provider.id, m.provider.name, m.model, d.reason, budget = true, rateLimited = false, estUsd = est)
                        break
                    }
                }
                // PŘÍSNĚ: odhad se zarezervuje předem (strop hlídá i rozběhnuté volání)
                val reserved = if (strict && m.provider.paid) guard!!.reserve(m.provider.id, m.model, chars, opts.maxTokens) else 0.0
                try {
                    val r = eng.generate(fitted, opts, cancel) { d -> started = true; onDelta(d) }
                    lastLabel = eng.label
                    if (m.provider.paid) {
                        if (strict) guard!!.settle(m.provider.id, m.model, reserved, r.inputTokens, r.outputTokens, chars, r.text.length + r.reasoning.length, opts.maxTokens)
                        else guard?.record(m.provider.id, m.model, r.inputTokens, r.outputTokens, chars, r.text.length + r.reasoning.length)
                    }
                    onUsage(m.provider.id, m.model, r.inputTokens, r.outputTokens)
                    return r
                } catch (e: CancelledException) {
                    throw e // rezervace zůstává: poskytovatel mohl už účtovat
                } catch (e: Exception) {
                    last = e
                    val pre = CostGuard.preResponse(e)
                    if (reserved > 0 && pre) guard!!.refund(m.provider.id, reserved)
                    if (started) throw e // už psal → nikdy nemíchat dvě odpovědi
                    // PŘÍSNĚ: zaplacený pokus (odpověď přišla, pak chyba) → neplatit podruhé u dalšího člena
                    if (strict && m.provider.paid && !pre) throw e
                    if (strict) failures += Failure(m.provider.id, m.provider.name, m.model, shortReason(e), budget = false, rateLimited = Regex("HTTP 429").containsMatchIn(e.message.orEmpty()))
                    // SAMOOPRAVA: model zmizel z nabídky (free tarify je často mění) → vybrat platný a zkusit znovu
                    // MODEL MIMO TARIF (Gemini: 429 „limit: 0“ / „not available on the free tier“ – Hydrogen zpřístupňovaný
                    // postupně) → jiný model téhož poskytovatele, ne hned jiný poskytovatel
                    // (běžné vyčerpání denní kvóty „free_tier_requests, limit: 250“ NENÍ důvod měnit model – jen „limit: 0“)
                    val zeroQuota = Regex("limit: 0\\b|limit\":\\s*0\\b|not available (for|on|in) (your|the) (free )?tier|not available on the free tier|not supported for", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())
                    val notFound = Regex("HTTP 404|model.{0,40}(not found|nenalezen|does not exist|not exist)", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())
                    if (!healed && (zeroQuota || notFound)) {
                        healed = true
                        val fixed = runCatching { FreeCloud.pickModel(m.provider, listModels(m.provider, m.key), avoid = setOf(m.model)) }.getOrNull()
                        // placený člen: nikdy samovolně na DRAŽŠÍ model (povolení a náhled ceny platily pro původní)
                        val pricier = fixed != null && m.provider.paid && guard != null &&
                            guard.priceOf(m.provider.id, fixed).let { n -> guard.priceOf(m.provider.id, m.model).let { o -> n.inPerM > o.inPerM || n.outPerM > o.outPerM } }
                        if (fixed != null && fixed != m.model && !pricier) {
                            m = m.copy(model = fixed)
                            // trvale si zapamatovat jen zmizelý model; kvóta se může změnit (přísně), jinak jako dřív
                            if (!strict || notFound) onModel(m.provider.id, fixed)
                            if (strict) failures.removeAt(failures.lastIndex)
                            continue
                        }
                    }
                    break
                }
            }
            if (i < members.lastIndex) onFallback("${m.provider.name}: ${last?.message?.take(80)} → ${members[i + 1].provider.name}")
        }
        if (strict) throw ChainException(failures.joinToString(" · ") { "${it.name}: ${it.message}" }.ifBlank { last?.message ?: "Cloud neodpověděl." }, failures, last)
        throw (last as? EngineException) ?: EngineException("Cloud: ${last?.message}", retryable = true, cause = last)
    }

    /** Krátký český důvod (bez syrového JSON): limit, klíč, výpadek, síť. */
    private fun shortReason(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            Regex("HTTP 429").containsMatchIn(m) && Regex("(?i)per ?day|daily|PerDay").containsMatchIn(m) -> "denní limit zdarma vyčerpán"
            Regex("HTTP 429").containsMatchIn(m) -> "limit – zkus za minutu"
            Regex("HTTP 40[013]").containsMatchIn(m) && Regex("(?i)key|klíč|auth").containsMatchIn(m) -> "neplatný klíč"
            Regex("HTTP 5\\d\\d").containsMatchIn(m) -> "výpadek serveru"
            Regex("(?i)Spojení|connect|resolve host|timeout").containsMatchIn(m) -> "bez spojení"
            else -> m.substringBefore(". {").substringBefore(" [{").take(90)
        }
    }

    /** Ořízne nejstarší historii, aby se dotaz vešel do okna poskytovatele (systém a poslední dotaz zůstanou). */
    private fun fit(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (messages.sumOf { it.content.length } <= budget) return messages
        val sys = messages.filter { it.role == Role.SYSTEM }
        val rest = messages.filter { it.role != Role.SYSTEM }.toMutableList()
        var total = messages.sumOf { it.content.length }
        // první zpráva (u agenta CÍL úkolu) a poslední zůstávají – ořezává se od druhé nejstarší
        while (rest.size > 2 && total > budget) total -= rest.removeAt(1).content.length
        // i tak moc dlouhé (obří podklady v systému) → zkrátit systémovou zprávu od konce podkladů
        val sysText = sys.joinToString("\n\n") { it.content }
        val room = (budget - rest.sumOf { it.content.length }).coerceAtLeast(4_000)
        val sysMsg = if (sysText.isEmpty()) emptyList() else listOf(ChatMessage(Role.SYSTEM, if (sysText.length > room) sysText.take(room) + "\n[…zkráceno]" else sysText))
        return sysMsg + rest
    }
}
