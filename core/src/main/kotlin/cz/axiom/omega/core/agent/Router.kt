package cz.axiom.omega.core.agent

import cz.axiom.omega.core.randy.Randy
import cz.axiom.omega.core.randy.RandyLevel
import cz.axiom.omega.core.util.TextUtil

/**
 * AUTONOMNÍ VÝBĚR MODULŮ. Pokud uživatel výslovně neurčí, AXIOM Ω sám rozhodne:
 *  • JAK pracovat – odpověď (chat), aktivní agent s nástroji, nebo Konzilium,
 *  • KDE myslet – lokální mozek / AXIOM Node / Kimi K3 / vlastní server / Claude,
 *  • JAK HLUBOKO – úroveň RANDY.
 * Rozhodnutí je deterministické a vysvětlené (ukáže se u odpovědi).
 */
object Router {

    enum class Mode(val label: String) { CHAT("Odpověď"), AGENT("Aktivní agent"), COUNCIL("Konzilium") }

    data class Context(
        val online: Boolean,
        /** Dostupné mozky v pořadí preference, např. ["kimi","node","local","server","claude"]. */
        val engines: List<String>,
        /** Uživatel zvolil konkrétní mozek (ne "auto") → respektovat. */
        val userEngine: String? = null,
        /** Uživatel zvolil konkrétní režim (chat/agent/council) → respektovat. */
        val userMode: Mode? = null,
    )

    data class Route(val mode: Mode, val engine: String, val level: RandyLevel, val reason: String, val prompt: String)

    private val agentCmd = Regex("""^\s*/(?:agent|úkol|ukol)\b[:\s]*""", RegexOption.IGNORE_CASE)
    private val chatCmd = Regex("""^\s*/(?:chat|odpověď|odpoved)\b[:\s]*""", RegexOption.IGNORE_CASE)
    private val councilCmd = Regex("""^\s*/(?:konzilium|council)\b[:\s]*""", RegexOption.IGNORE_CASE)

    /** Slova, která znamenají „jdi a něco zjisti/udělej“ – agent s nástroji. */
    private val agentVerbs = listOf(
        "najdi", "vyhledej", "dohledej", "zjisti", "prozkoumej", "projdi", "zmapuj", "porovnej ceny", "rešerš", "resers",
        "stáhni", "stahni", "naplánuj a", "krok za krokem", "sepiš report", "připrav report", "monitoruj", "ověř na webu",
        "search", "research", "find out", "look up",
    )
    private val freshWords = listOf("dnes", "aktuální", "aktualni", "nejnovější", "nejnovejsi", "letos", "teď", "momentálně", "cena", "kurz dne", "zprávy", "novinky", "2026", "2027")

    fun route(text: String, ctx: Context): Route {
        var prompt = text.trim()
        var mode: Mode? = ctx.userMode
        val why = mutableListOf<String>()
        when {
            agentCmd.containsMatchIn(prompt) -> { prompt = agentCmd.replace(prompt, ""); mode = Mode.AGENT; why += "příkaz /agent" }
            councilCmd.containsMatchIn(prompt) -> { prompt = councilCmd.replace(prompt, ""); mode = Mode.COUNCIL; why += "příkaz /konzilium" }
            chatCmd.containsMatchIn(prompt) -> { prompt = chatCmd.replace(prompt, ""); mode = Mode.CHAT; why += "příkaz /chat" }
        }
        val det = Randy.detect(prompt)
        val level = det.level
        val folded = " " + TextUtil.fold(prompt) + " "

        if (mode == null) {
            val wantsAction = agentVerbs.any { " ${TextUtil.fold(it)}" in folded }
            val wantsFresh = freshWords.any { " ${TextUtil.fold(it)}" in folded }
            val hasUrl = Regex("https?://").containsMatchIn(prompt)
            mode = when {
                hasUrl && ctx.online -> { why += "obsahuje odkaz"; Mode.AGENT }
                wantsAction && (ctx.online || level.ordinal >= RandyLevel.L2_DEEP.ordinal) -> { why += "úkol vyžaduje kroky a nástroje"; Mode.AGENT }
                wantsFresh && ctx.online -> { why += "potřebuje aktuální informace"; Mode.AGENT }
                else -> Mode.CHAT
            }
        }

        // Výslovná volba uživatele platí vždy (když není dostupná, uživatel dostane jasnou hlášku, ne tichou náhradu).
        val engine = ctx.userEngine ?: pickEngine(level, mode!!, prompt, ctx, why)
        if (ctx.userEngine != null) why += "zvolený mozek"
        if (mode == Mode.COUNCIL && ctx.engines.size < 2) {
            mode = Mode.CHAT
            why += "Konzilium potřebuje 2 mozky"
        }
        if (why.isEmpty()) why += "RANDY ${level.code}"
        return Route(mode!!, engine, level, why.joinToString(" · "), prompt)
    }

    private fun pickEngine(level: RandyLevel, mode: Router.Mode, prompt: String, ctx: Context, why: MutableList<String>): String {
        val avail = ctx.engines
        if (avail.isEmpty()) return "none"
        // AXIOM je nezávislý: primárně myslí výkonný mozek v telefonu, cloud jen když v telefonu žádný není.
        if ("local" in avail) { why += "mozek v telefonu"; return "local" }
        if (!ctx.online) { why += "offline"; return avail.firstOrNull { it == "server" } ?: avail.first() }
        why += "v telefonu není mozek → ${avail.first()}"
        return avail.first()
    }
}
