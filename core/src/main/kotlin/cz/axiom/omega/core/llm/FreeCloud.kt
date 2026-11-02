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
    )

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
        // ---- LEVNÉ PLACENÉ (jednotky dolarů měsíčně při běžném používání)
        Provider(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            preferred = listOf("deepseek-chat", "deepseek-reasoner"),
            signup = "platform.deepseek.com", paid = true,
        ),
        Provider(
            "chutes", "Chutes", "https://llm.chutes.ai/v1",
            preferred = listOf("deepseek-ai/DeepSeek-V3.2", "moonshotai/Kimi-K2.5", "zai-org/GLM-5", "deepseek-ai/DeepSeek-V3.1", "Qwen/Qwen3-235B-A22B-Instruct-2507", "openai/gpt-oss-120b"),
            fast = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            signup = "chutes.ai", paid = true,
        ),
    )

    /** Pořadí členů: „rychlost“ = nejdřív nejrychlejší servery zdarma, „kvalita“ = nejdřív placené silné modely. */
    fun order(quality: Boolean): List<Provider> = if (quality) PROVIDERS.sortedByDescending { it.paid } else PROVIDERS

    fun provider(id: String) = PROVIDERS.firstOrNull { it.id == id }

    /**
     * Vybere model: první z [wanted], který je v [available] (bez ohledu na „models/“ prefix a velikost písmen);
     * když seznam neznáme (prázdný), vezme první preferovaný.
     */
    fun pickModel(p: Provider, available: List<String>, fast: Boolean = false): String {
        val wanted = if (fast && p.fast.isNotEmpty()) p.fast + p.preferred else p.preferred
        if (available.isEmpty()) return wanted.first()
        val norm = available.associateBy { it.removePrefix("models/").lowercase() }
        wanted.firstOrNull { it.lowercase() in norm }?.let { return norm.getValue(it.lowercase()) }
        return available.firstOrNull(p.anyFree) ?: available.firstOrNull { !Regex("embed|whisper|tts|guard|vision|image|audio").containsMatchIn(it.lowercase()) } ?: wanted.first()
    }

    /** gpt-oss a Qwen 3 přijímají reasoning_effort low|medium|high; ostatní ho ignorují nebo odmítnou → neposílat. */
    fun effortFor(model: String): ((String) -> String?)? =
        if (Regex("gpt-oss").containsMatchIn(model)) { e -> when (e.lowercase()) { "low" -> "low"; "high", "xhigh", "max" -> "high"; else -> "medium" } } else null

    data class Member(val provider: Provider, val key: String, val model: String)

    /** Jeden poskytovatel jako mozek (OpenAI-kompatibilní klient). */
    fun engine(m: Member): LlmEngine = KimiEngine(
        m.provider.base, m.key, m.model, EngineKind.FREE,
        effortMapper = effortFor(m.model), vendor = m.provider.name,
        labelOverride = "${m.provider.name} (${m.model.substringAfterLast('/').removeSuffix(":free")})",
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
) : LlmEngine {
    init { require(members.isNotEmpty()) { "Cloud: chybí klíč (Cerebras, Groq, OpenRouter, DeepSeek nebo Chutes)." } }

    override val id = "free:" + members.joinToString(",") { it.provider.id + "/" + it.model }
    override val label get() = lastLabel
    override val kind = EngineKind.FREE
    /** Bezplatné tarify mívají menší kontext a limit tokenů za minutu – posílat úsporně. */
    override val contextBudgetChars = 48_000

    @Volatile private var lastLabel = factory(members.first()).label

    override fun generate(messages: List<ChatMessage>, options: GenOptions, cancel: CancelToken, onDelta: (Delta) -> Unit): GenResult {
        var last: Exception? = null
        for ((i, m) in members.withIndex()) {
            cancel.check()
            val eng = factory(m)
            var started = false
            try {
                val r = eng.generate(messages, options, cancel) { d -> started = true; onDelta(d) }
                lastLabel = eng.label
                return r
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                last = e
                // už psal → nepřepínat (odpověď by byla slepená); jinak zkusit dalšího
                if (started || i == members.lastIndex) break
                onFallback("${m.provider.name}: ${e.message?.take(80)} → ${members[i + 1].provider.name}")
            }
        }
        throw (last as? EngineException) ?: EngineException("Cloud zdarma: ${last?.message}", retryable = true, cause = last)
    }
}
