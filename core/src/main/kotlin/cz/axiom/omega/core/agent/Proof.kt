package cz.axiom.omega.core.agent

import cz.axiom.omega.core.util.TextUtil

/**
 * PROOFCORE – původ a platnost znalostí, které si AVIOR ukládá.
 *
 * Problém (změřený Δ Labem na Delta III): paměť odpovědí vracela NEOVĚŘENOU dřívější odpověď mozku jako
 * odpověď „bez mozku“ s jistotou nad prahem Auto – model tak používal vlastní text jako zdroj pravdy (6 z 8 případů).
 *
 * Řešení:
 *  1) PŮVOD každé uložené odpovědi: USER (potvrdil ji uživatel ✓), WIKI (Strážce ji kladně ověřil proti offline
 *     Wikipedii a nic nevyvrátil), MODEL (jen text modelu). Jistota se odvozuje z důkazu, ne z přesvědčivosti textu.
 *  2) REVIZNÍ SMLOUVA: každá uložená odpověď nese datum, kdy se má znovu vyhodnotit, a důvod
 *     („úřady, ceny a rekordy se mění“). Po něm se nepoužije a otázku znovu zodpoví mozek / zdroj.
 */
object Proof {

    enum class Origin(val code: String, val label: String) {
        USER("user", "ověřená odpověď (✓)"),
        WIKI("wiki", "odpověď ověřená Wikipedií (Δ)"),
        MODEL("model", "dřívější odpověď mozku (neověřeno)");

        companion object { fun of(code: String?) = values().firstOrNull { it.code == code } ?: MODEL }
    }

    data class Contract(val reviseAt: Long, val reason: String)

    private const val DAY = 86_400_000L

    /** Fakta, která zastarávají: kdo je ve funkci, ceny, kurzy, rekordy, počty, „letos / aktuálně“. */
    private val volatile = Regex(
        "\\b(prezident\\w*|premi[eé]r\\w*|ministr\\w*|předsed\\w*|ředitel\\w*|starost\\w*|hejtman\\w*|kancléř\\w*|papež\\w*|guvernér\\w*|" +
            "trenér\\w*|šéf\\w*|ceo|vítěz\\w*|mistr\\w* světa|rekord\\w*|nejnověj\\w*|aktuáln\\w*|současn\\w*|letos|nyní|momentáln\\w*|" +
            "stojí|cen[auy]|kurz\\w*|verz[eí]|obyvatel\\w*|inflac\\w*|sazb\\w*|úrok\\w*)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Revizní smlouva pro uloženou odpověď. */
    fun contract(question: String, answer: String, origin: Origin, now: Long = System.currentTimeMillis()): Contract {
        val text = "$question\n$answer"
        return when {
            volatile.containsMatchIn(text) -> Contract(now + 90 * DAY, "obsahuje fakta, která se mění (úřady, ceny, rekordy, počty)")
            origin == Origin.MODEL -> Contract(now + 180 * DAY, "neověřená odpověď mozku – po půl roce znovu")
            else -> Contract(now + 3 * 365 * DAY, "ověřené nadčasové fakty – kontrola po 3 letech")
        }
    }

    /** Je odpověď ještě platná? (staré záznamy bez smlouvy: dosavadních 60 dní) */
    fun alive(at: Long, reviseAt: Long, now: Long): Boolean = if (reviseAt > 0) now < reviseAt else now - at < 60 * DAY

    /** Původ podle auditu Strážce (bez ✓ uživatele). */
    fun originOf(audit: Sentinel.Audit) = if (audit.proven) Origin.WIKI else Origin.MODEL

    /** Krátký popis smlouvy pro UI („znovu ověřit po 3. 2. 2027“). */
    fun describe(c: Contract): String {
        val d = java.time.Instant.ofEpochMilli(c.reviseAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "znovu ověřit po ${d.dayOfMonth}. ${d.monthValue}. ${d.year} – ${c.reason}"
    }

    /** Agent si smí ukládat poznámky, ale NIKDY pod jménem encyklopedie (jinak by jim věřil reflex i Strážce). */
    fun noteTitle(title: String): String =
        "Poznámka AVIORu: " + TextUtil.normalize(title).replace(Regex("^(?i)(wikipedie|wikipedia|wiki|wikizdroje|poznámka avioru)\\s*[:–-]\\s*"), "").take(120)
}
