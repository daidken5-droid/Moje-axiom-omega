package cz.axiom.vega.core

import cz.axiom.vega.base.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * DENÍK SLABIN (autonomie, krok 1): VEGA si lokálně zapisuje, kde selhala nebo kde ji uživatel opravil. Z deníku
 * pak navrhuje vylepšení (příklady routeru, úpravy postupů, nové nástroje). Nic se nikam neposílá.
 *
 * Soukromí: ukládá se jen štítek oblasti (záměr / nástroj), druh signálu a ZKRÁCENÁ, ANONYMIZOVANÁ věta uživatele
 * (čísla, e-maily, adresy, klíče nahrazené značkou) – nikdy obsah souborů. Deník je po dnech, drží [keepDays] dní.
 */
class SelfTune(private val kv: Kv, private val version: String = "", private val clock: () -> Long = System::currentTimeMillis, private val keepDays: Int = 30) {
    enum class Kind(val label: String) {
        ERROR("chyba"), STOP("zastaveno uživatelem"), CORRECTION("oprava od uživatele"), REPHRASE("přeformulovaný dotaz"),
        WRONG_INTENT("špatně pochopený záměr"), SLOW("pomalý krok"), UNVERIFIED("neověřené tvrzení")
    }
    @Serializable data class Signal(val kind: String, val area: String, val excerpt: String = "", val detail: String = "", val prevExcerpt: String = "",
                                    val version: String = "", val at: Long = 0,
                                    /** Kam navazující zpráva nakonec patřila (oblast) – podklad pro naučené pravidlo routeru. */
                                    val resolved: String = "")
    data class Group(val kind: Kind, val area: String, val count: Int, val examples: List<Signal>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()
    @Volatile private var lastSignal: Pair<String, Long>? = null
    private fun dayKey(t: Long) = "selftune/journal/" + java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneOffset.UTC).toLocalDate() + ".json"
    private fun day(k: String): List<Signal> = kv.read(k)?.let { runCatching { json.decodeFromString(ListSerializer(Signal.serializer()), it) }.getOrNull() } ?: emptyList()

    fun observe(kind: Kind, area: String, excerpt: String = "", detail: String = "", prevExcerpt: String = "") = synchronized(lock) {
        val now = clock(); val k = dayKey(now)
        val s = Signal(kind.name, area.take(60), anonymize(excerpt), anonymize(detail).take(200), anonymize(prevExcerpt), version, now)
        kv.write(k, json.encodeToString(ListSerializer(Signal.serializer()), (day(k) + s).takeLast(MAX_PER_DAY)))
        lastSignal = k to now
        val cutoff = dayKey(now - keepDays * 86_400_000L)
        kv.keys("selftune/journal/").filter { it < cutoff }.forEach { kv.delete(it) }
    }

    fun signals(days: Int = keepDays): List<Signal> {
        val from = dayKey(clock() - days * 86_400_000L)
        return kv.keys("selftune/journal/").filter { it >= from }.sorted().flatMap { day(it) }
    }

    /** Opakované slabiny (nejčastější první) – podklad pro návrhy vylepšení. */
    fun groups(days: Int = keepDays, min: Int = 1): List<Group> = signals(days)
        .groupBy { it.kind to it.area }.mapNotNull { (k, v) -> runCatching { Kind.valueOf(k.first) }.getOrNull()?.let { Group(it, k.second, v.size, v.takeLast(5)) } }
        .filter { it.count >= min }.sortedByDescending { it.count }

    // ---------------------------------------------------------------- navazující tah uživatele
    private data class Turn(val area: String, val text: String, val at: Long)
    @Volatile private var last: Turn? = null

    /**
     * Zavolat s KAŽDOU novou zprávou uživatele (před zpracováním): pozná opravu („ne, myslel jsem…“) a přeformulování
     * téhož dotazu do 90 s – obojí znamená, že předchozí odpověď (oblast [Turn.area]) nebyla dobrá. Vrací zapsaný druh.
     */
    fun userTurn(text: String): Kind? {
        val now = clock(); val prev = last
        val k = prev?.let { followUpKind(it.text, text, now - it.at) }
        last = Turn("?", text, now); lastSignal = null
        if (prev != null && k != null) observe(k, prev.area, text, prevExcerpt = prev.text)
        return k
    }
    /** Jak byla poslední zpráva zpracovaná (záměr / nástroj) – doplní se po rozhodnutí. */
    fun routed(area: String) {
        last = last?.copy(area = area)
        // signál z téže zprávy (oprava / přeformulování) doplnit o to, kam zpráva skutečně patřila
        val ls = lastSignal ?: return; lastSignal = null
        val at = last?.at ?: return; if (ls.second < at) return
        synchronized(lock) {
            val list = day(ls.first); val i = list.indexOfLast { it.at == ls.second }
            if (i >= 0 && list[i].resolved.isEmpty()) kv.write(ls.first, json.encodeToString(ListSerializer(Signal.serializer()), list.toMutableList().also { it[i] = it[i].copy(resolved = area.take(60)) }))
        }
    }
    fun lastArea(): String = last?.area ?: "?"

    companion object {
        const val MAX_PER_DAY = 400
        /** Štítek oblasti podle záměru routeru (nástroj s id, postup s názvem). */
        fun area(i: Router.Intent): String = when (i) {
            is Router.Intent.Tool -> "nástroj:${i.id}"
            is Router.Intent.RunProcedure -> "postup:${i.procedure.name}"
            else -> "záměr:" + (i::class.simpleName ?: "?")
        }
        private val correction = Regex("^(ne[,!.]|ne,? ?ne|nene|to ne|tohle ne|spatne|to je spatne|to neni|myslel jsem|myslela jsem|chtel jsem|chtela jsem|nemyslel|ja jsem chtel|ty jsi|nepochopil|nerozumis|to jsem nechtel|neni to|neodpovedel)")

        fun followUpKind(prev: String, cur: String, gapMs: Long): Kind? {
            val c = Text.fold(cur).lowercase().trim()
            if (correction.containsMatchIn(c)) return Kind.CORRECTION
            if (gapMs in 0..90_000) {
                val a = Text.words(prev).map { it.lowercase() }.toSet(); val b = Text.words(cur).map { it.lowercase() }.toSet()
                if (a.size >= 2 && b.size >= 2 && a != b) { val j = (a intersect b).size.toDouble() / (a union b).size; if (j >= 0.4) return Kind.REPHRASE }
            }
            return null
        }

        /** Zkrátí a anonymizuje: klíče, e-maily, URL (jen doména), IBAN/čísla účtů a delší čísla → značky. */
        fun anonymize(s: String): String = s.replace(Regex("\\s+"), " ").trim()
            .replace(Regex("(sk-|AIza|gsk_|csk-|ghp_|github_pat_|xox[bp]-)[A-Za-z0-9_\\-]{8,}"), "‹klíč›")
            .replace(Regex("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_.\\-]*"), "‹token›")
            .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9_.\\-]{8,}"), "$1‹token›")
            .replace(Regex("(?i)(heslo|hesla|password|passwd|pin|token|cvv|cvc)(\\s*(je|:|=)?\\s*)\\S+"), "$1$2‹skryto›")
            .replace(Regex("[\\p{L}\\p{Nd}._%+-]+@[\\p{L}\\p{Nd}-]+\\.[\\p{L}\\p{Nd}.]+"), "‹e-mail›")
            .replace(Regex("https?://([^/\\s]+)\\S*")) { "‹url ${it.groupValues[1]}›" }
            .replace(Regex("[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}"), "‹IBAN›")
            .replace(Regex("\\d[\\d /.-]{4,}\\d"), "‹číslo›")
            .replace(Regex("\\d{3,}"), "‹číslo›")
            .take(160)
    }
}
