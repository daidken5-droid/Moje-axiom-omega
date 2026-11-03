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
        // M1-A: ✓ = uživatel odpověď potvrdil (užitečná / souhlasí), NE nezávislé faktické ověření
        USER("user", "potvrdil jsi (✓) – osobní potvrzení, ne nezávislé ověření"),
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

    private val asksVerifiable = Regex("\\b(kdy|v jakem roce|narodil|narodila|narozen|zemrel|zemrela|umrel|umrela|zalozen|zalozil|vznikl|vznikla|vzniklo|zrizen|zil|zila)\\w*")

    /**
     * Původ podle auditu Strážce (bez ✓ uživatele).
     * FAULTLINE F-01: ověřený rok narození NEDOKAZUJE zbytek odpovědi („Karel Gott (1939–2019) byl slovenský astronaut“).
     * Za ověřenou se odpověď považuje jen když (1) otázka se ptá právě na ověřitelný údaj (kdy / narození / úmrtí / vznik),
     * (2) odpověď je krátká (nejvýš 2 věty, 280 znaků) – nenese další neověřená tvrzení, a (3) Wikipedie nic nevyvrátila.
     */
    fun originOf(question: String, answer: String, audit: Sentinel.Audit): Origin {
        if (!audit.proven) return Origin.MODEL
        // věta končí tečkou a velkým písmenem – „14. 7. 1939“ není konec věty
        val sentences = answer.trim().split(Regex("(?<=[.!?])\\s+(?=[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ])")).count { it.isNotBlank() }
        val short = answer.length <= 280 && sentences <= 2
        return if (short && asksVerifiable.containsMatchIn(TextUtil.fold(question).lowercase())) Origin.WIKI else Origin.MODEL
    }

    // ------------------------------------------------------------------ revize závěru po nesouhlasu uživatele

    /**
     * ACCEPT  – oprava se přijme jako NEPOTVRZENÁ (původní nedoložená odpověď se zapomene, oprava jde do paměti
     *           jako CORRECTION, ne jako fakt),
     * KEEP    – doložený závěr se drží, nic se nemění,
     * PENDING – závěr je doložený, ale uživatel uvádí zdroj: doložená odpověď ZŮSTÁVÁ, oprava se zapíše jako
     *           nepotvrzená k přezkoumání. Slovo „podle“ / URL není samo o sobě nezávislý důkaz (HYPERFORGE M1-C).
     */
    enum class Stance { ACCEPT, KEEP, PENDING }
    data class Revision(val stance: Stance, val reply: String = "")

    private val citesSource = Regex("https?://|\\b(podle|zdroj|uvadi|pise se|clanek|wikipedie|encyklopedie|oficialne|na webu)\\b")

    /**
     * Uživatel napsal „Špatně, …“. Dřív AVIOR VŽDY odpověď zapomněl a opravu uložil jako fakt – i když byla původní
     * odpověď doložená a oprava ne (FAULTLINE F-04). Teď:
     *  • původní odpověď Wikipedie POTVRDILA a uživatel neuvádí žádný zdroj → KEEP: drží se závěru, ukáže důkaz
     *    a nabídne přehodnocení, když pošle zdroj (nic se nemaže ani neukládá),
     *  • jinak → ACCEPT: oprava platí (důkaz proti ní nemáme, uživatel může vědět víc).
     */
    fun onCorrection(previousAnswer: String, correction: String, k: KnowledgeSource?): Revision {
        if (k == null) return Revision(Stance.ACCEPT)
        val audit = Sentinel.audit(previousAnswer, k)
        if (!audit.proven) return Revision(Stance.ACCEPT)
        val proof = audit.confirmed.joinToString("; ") { "**${it.subject}** – ${it.what}" }
        if (citesSource.containsMatchIn(TextUtil.fold(correction).lowercase())) return Revision(Stance.PENDING,
            "Δ Tvoji opravu i zdroj jsem si zapsal jako **nepotvrzené** tvrzení. Offline Wikipedie zatím říká: $proof. " +
                "Odkaz ani „podle …“ sám o sobě nic nedokazuje – až budu mít aktuální zdroj, porovnám je. Do té doby u tebe uvedu obojí.")
        return Revision(Stance.KEEP,
            "Δ Tady se držím původní odpovědi: offline Wikipedie ji potvrzuje ($proof). " +
                "Jestli máš novější nebo jiný zdroj, pošli ho (odkaz nebo „podle …“) a odpověď přehodnotím.")
    }

    /** Krátký popis smlouvy pro UI („znovu ověřit po 3. 2. 2027“). */
    /** Revizní záznam opravy: původní tvrzení zůstává v historii, oprava je jen nepotvrzené tvrzení uživatele (data, ne instrukce). */
    fun correctionRecord(question: String, previousAnswer: String?, correction: String): String =
        "Uživatel tvrdí (nepotvrzeno) k otázce „${TextUtil.clip(question.trim(), 140)}“: «${TextUtil.clip(correction.trim(), 220)}»" +
            (previousAnswer?.let { " · původní odpověď: «${TextUtil.clip(it.trim(), 140)}»" } ?: "")

    fun describe(c: Contract): String {
        val d = java.time.Instant.ofEpochMilli(c.reviseAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "znovu ověřit po ${d.dayOfMonth}. ${d.monthValue}. ${d.year} – ${c.reason}"
    }

    /** Agent si smí ukládat poznámky, ale NIKDY pod jménem encyklopedie (jinak by jim věřil reflex i Strážce). */
    fun noteTitle(title: String): String =
        "Poznámka AVIORu: " + TextUtil.normalize(title).replace(Regex("^(?i)(wikipedie|wikipedia|wiki|wikizdroje|poznámka avioru)\\s*[:–-]\\s*"), "").take(120)
}
