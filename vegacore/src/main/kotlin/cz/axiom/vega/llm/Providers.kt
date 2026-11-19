package cz.axiom.vega.llm

/** Poskytovatelé s OpenAI API, které VEGA umí. Pořadí v řetězu určuje aplikace (zdarma první, placené až po schválení). */
object Providers {
    data class Provider(val id: String, val name: String, val base: String, val preferred: List<String>, val signup: String,
                        val paid: Boolean = false, val maxOut: Int = 8192, val contextChars: Int = 60_000, val dataNote: String = "")
    data class Member(val provider: Provider, val key: String, val model: String)

    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"

    val ALL = listOf(
        Provider("gemini", "Gemini", GEMINI_BASE, listOf("gemini-4-hydrogen", "gemini-3-pro", "gemini-2.5-pro", "gemini-2.5-flash"), "aistudio.google.com/apikey",
            maxOut = 8192, contextChars = 120_000, dataNote = "Google Gemini API, bezplatná kvóta (podmínky Google pro neplacené projekty)."),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905", "llama-3.3-70b-versatile", "qwen/qwen3-32b"), "console.groq.com/keys",
            contextChars = 60_000, dataNote = "Groq (servery v USA)."),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1", listOf("gpt-oss-120b", "qwen-3-235b-a22b-instruct-2507", "llama-3.3-70b"), "cloud.cerebras.ai",
            contextChars = 60_000, dataNote = "Cerebras (servery v USA)."),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", listOf("openai/gpt-oss-120b:free", "deepseek/deepseek-chat-v3.1:free", "qwen/qwen3-235b-a22b:free", "meta-llama/llama-3.3-70b-instruct:free"), "openrouter.ai/keys",
            contextChars = 60_000, dataNote = "OpenRouter – bezplatné modely; poskytovatel modelu může vstupy ukládat."),
        Provider("geminipro", "Gemini (kredit Pro)", GEMINI_BASE, listOf("gemini-4-hydrogen", "gemini-3-pro", "gemini-2.5-pro"), "aistudio.google.com (Google AI Pro → kredity)",
            paid = true, maxOut = 16_384, contextChars = 400_000, dataNote = "Google Gemini API, placený projekt (data se nepoužívají k tréninku)."),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", listOf("deepseek-chat", "deepseek-reasoner"), "platform.deepseek.com",
            paid = true, maxOut = 8192, contextChars = 120_000, dataNote = "DeepSeek (servery v Číně) – citlivé podklady raději ne."),
    )
    fun provider(id: String) = ALL.firstOrNull { it.id == id }

    /**
     * Model z nabídky poskytovatele: první preferovaný, který skutečně nabízí; jinak nejsilnější podle názvu
     * (velikost, „pro“, „instruct“), jinak první preferovaný. [avoid] = modely, které právě selhaly.
     */
    fun pickModel(p: Provider, available: List<String>, avoid: Set<String> = emptySet()): String {
        val avail = available.map { it.removePrefix("models/") }.filter { a -> avoid.none { it.equals(a, true) } }
        val wanted = p.preferred.filter { w -> avoid.none { it.equals(w, true) } }
        if (avail.isEmpty()) return wanted.firstOrNull() ?: p.preferred.first()
        wanted.firstOrNull { w -> avail.any { it.equals(w, true) } }?.let { w -> return avail.first { it.equals(w, true) } }
        fun score(m: String): Int { val x = m.lowercase(); var s = 0
            Regex("(\\d+)b").find(x)?.groupValues?.get(1)?.toIntOrNull()?.let { s += minOf(it, 400) / 4 }
            if ("pro" in x) s += 30; if ("instruct" in x || "chat" in x) s += 10; if ("flash" in x) s += 15
            if (Regex("embed|whisper|tts|guard|vision-only|image|audio|moderation").containsMatchIn(x)) s -= 100
            if (p.id == "openrouter" && !x.endsWith(":free")) s -= 100
            return s }
        return avail.filter { score(it) > -50 }.maxByOrNull { score(it) } ?: wanted.firstOrNull() ?: p.preferred.first()
    }

    fun engine(m: Member): LlmEngine = OpenAiEngine(m.provider.base, m.key, m.model, m.provider.name, m.provider.contextChars)
}
