package cz.axiom.omega.core.agent

import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.util.TextUtil
import cz.axiom.omega.core.util.rx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * REFLEXY – podpůrné moduly, které odpoví BEZ mozku, když si jsou odpovědí jisté.
 *
 * Každý reflex vrací odpověď s mírou jistoty 0–100. Odpověď se použije jen nad prahem
 * (Ω Flash 70, Auto 85); jinak dotaz jde mozku. Pořadí = od nejjistějšího:
 *  1. deterministické nástroje (čas, datum, výpočty, převody, hash, CIDR…) – [Instant], jistota 99,
 *  2. kalendář: dny do data/svátku, den v týdnu, datum za N dní – jistota 99,
 *  3. vzpomínky o uživateli („jak se jmenuje můj pes“) – jistota podle shody,
 *  4. paměť ověřených odpovědí (co už mozek jednou dobře zodpověděl, nebo uživatel označil ✓),
 *  5. definice „co je / kdo byl X“ z offline Wikipedie při přesné shodě hesla.
 */
object Reflex {

    data class Entry(
        val question: String, val answer: String, val confidence: Int, val good: Boolean, val at: Long,
        /** PROOFCORE: kdo odpověď potvrdil (uživatel ✓ / Wikipedie / jen model). */
        val origin: Proof.Origin = if (good) Proof.Origin.USER else Proof.Origin.MODEL,
        /** Revizní smlouva: po tomto čase se odpověď nepoužije (0 = dosavadních 60 dní). */
        val reviseAt: Long = 0,
    ) {
        /** Klíčová slova otázky – spočítají se jednou (paměť odpovědí je v RAM, prochází se u každého dotazu). */
        val keys: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { key(question) }
    }

    // ------------------------------------------------------------------ 2) kalendář

    private val months = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")
    private val days = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private fun fmt(d: LocalDate) = "${d.dayOfMonth}. ${months[d.monthValue - 1]} ${d.year}"
    private fun dayWord(n: Long) = when { n == 1L -> "den"; n in 2..4 -> "dny"; else -> "dní" }

    private val dateRx = Regex("""(\d{1,2})\.\s*(\d{1,2})\.?\s*(\d{4})?""")
    private val namedDays: Map<String, (Int) -> LocalDate> = mapOf(
        "vanoc" to { y -> LocalDate.of(y, 12, 24) }, "stedreho dne" to { y -> LocalDate.of(y, 12, 24) },
        "silvestra" to { y -> LocalDate.of(y, 12, 31) }, "noveho roku" to { y -> LocalDate.of(y + 1, 1, 1) },
        "valentyna" to { y -> LocalDate.of(y, 2, 14) }, "halloweenu" to { y -> LocalDate.of(y, 10, 31) },
        "mikulase" to { y -> LocalDate.of(y, 12, 5) }, "prvniho maje" to { y -> LocalDate.of(y, 5, 1) },
        "konce roku" to { y -> LocalDate.of(y, 12, 31) },
    )

    fun calendar(prompt: String, today: LocalDate): Instant.Answer? {
        val f = TextUtil.fold(prompt).trim().trimEnd('?', '.', '!', ' ')
        if (f.length > 80) return null
        // kolik dní do Vánoc / do 24. 12. (2026)
        rx("^(?:kolik (?:dni|dnu|dnu zbyva|dni zbyva) (?:zbyva )?)?(?:kolik )?(?:dni|dnu)?\\s*(?:zbyva )?do (.+)$").find(f)?.let { m ->
            if (!f.startsWith("kolik")) return@let
            val target = m.groupValues[1].trim()
            var d = namedDays.entries.firstOrNull { target.startsWith(it.key) }?.value?.invoke(today.year)
                ?: dateRx.find(target)?.let { dm -> runCatching { LocalDate.of(dm.groupValues[3].ifBlank { "${today.year}" }.toInt(), dm.groupValues[2].toInt(), dm.groupValues[1].toInt()) }.getOrNull() }
                ?: return null
            if (d.isBefore(today) && dateRx.find(target)?.groupValues?.get(3).isNullOrBlank()) d = d.plusYears(1)
            val n = ChronoUnit.DAYS.between(today, d)
            return Instant.Answer(if (n == 0L) "Je to **dnes** (${fmt(d)})." else "Do ${fmt(d)} zbývá **$n ${dayWord(n)}** (${days[d.dayOfWeek.value - 1]}).", "kalendář", confidence = 99)
        }
        // jaký den (v týdnu) bude / byl 24. 12. 2026
        if (rx("^(jaky|kterej?y?|co) (je|bude|byl|to bude|to byl)?\\s*(den|den v tydnu)").containsMatchIn(f) || f.startsWith("na jaky den")) {
            dateRx.find(f)?.let { dm ->
                val d = runCatching { LocalDate.of(dm.groupValues[3].ifBlank { "${today.year}" }.toInt(), dm.groupValues[2].toInt(), dm.groupValues[1].toInt()) }.getOrNull() ?: return null
                return Instant.Answer("${fmt(d)} ${if (d.isBefore(today)) "byl" else if (d == today) "je" else "bude"} **${days[d.dayOfWeek.value - 1]}**.", "kalendář", confidence = 99)
            }
        }
        // jaké datum bude za 30 dní / co bude za 2 týdny
        rx("za (\\d{1,4}) (dni|dnu|den|dny|tyden|tydny|tydnu)").find(f)?.let { m ->
            if (!rx("^(jake|jaky|co|kdy|ktery)").containsMatchIn(f)) return@let
            val n = m.groupValues[1].toLong() * if (m.groupValues[2].startsWith("tyd")) 7 else 1
            val d = today.plusDays(n)
            return Instant.Answer("Za $n ${dayWord(n)} bude **${days[d.dayOfWeek.value - 1]} ${fmt(d)}**.", "kalendář", confidence = 99)
        }
        // kolikátý týden / den v roce
        if (rx("^kolikat[yi] (je )?(dnes )?tyden").containsMatchIn(f)) {
            val w = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            return Instant.Answer("Dnes je **$w. týden** roku ${today.year} (ISO).", "kalendář", confidence = 99)
        }
        if (f == "je dnes vikend" || f == "je vikend") {
            val we = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
            return Instant.Answer(if (we) "Ano, je víkend (${days[today.dayOfWeek.value - 1]})." else "Ne, je ${days[today.dayOfWeek.value - 1]}.", "kalendář", confidence = 99)
        }
        return null
    }

    // ------------------------------------------------------------------ 3) vzpomínky o uživateli

    private val personal = Regex("\\b(muj|moje|mojeho|mou|mym|moji|mi|me|mam|jsem|bydlim|pracuji|pracuju|jmenuji|jmenuju|narodil|narodila|oblibene|oblibeny|rad|rada)\\b")
    private val stop = setOf("jak", "jaky", "jaka", "jake", "kde", "kdy", "kdo", "co", "se", "je", "jsem", "muj", "moje", "mam", "mi", "me", "to", "ten", "ta", "vis", "pamatujes", "si", "jmenuje", "rikal", "rikala", "jsi", "byl", "byla")

    fun recall(prompt: String, memories: List<MemoryItem>): Instant.Answer? {
        val f = " " + TextUtil.fold(prompt) + " "
        if (prompt.length > 100 || !personal.containsMatchIn(f) || !prompt.trim().endsWith("?") && !f.startsWith(" vis ") && !f.startsWith(" pamatujes")) return null
        val q = TextUtil.words(prompt, 3).filter { it !in stop }.toSet()
        if (q.isEmpty()) return null
        val best = memories.filter { it.kind == MemoryKind.PROFILE || it.kind == MemoryKind.FACT || it.kind == MemoryKind.PREFERENCE }
            .map { m -> m to TextUtil.words(m.text, 3).count { w -> q.any { qq -> w.startsWith(qq.take(5)) || qq.startsWith(w.take(5)) } } }
            .filter { it.second > 0 }.sortedByDescending { it.second }
        val top = best.firstOrNull() ?: return null
        // jistota: všechna obsahová slova otázky v jedné vzpomínce a žádný soupeř se stejnou shodou
        val coverage = top.second * 100 / q.size
        val unique = best.getOrNull(1)?.second?.let { it < top.second } ?: true
        val conf = (if (coverage >= 100) 92 else if (coverage >= 50) 80 else 60) - if (unique) 0 else 15
        // vzpomínky jsou psané ve 3. osobě („Uživatelův pes…“) → oslovit přímo
        val said = top.first.text.trim().trimEnd('.')
            .replace(rx("^Uživatelův "), "Tvůj ").replace(rx("^Uživatelova "), "Tvoje ").replace(rx("^Uživatelovo "), "Tvoje ")
            .replace(rx("^Uživatelovi "), "Tví ").replaceFirstChar { it.uppercase() }
        return Instant.Answer("Podle toho, co si pamatuji: **$said.**", "z paměti o tobě", confidence = conf)
    }

    // ------------------------------------------------------------------ 4) paměť ověřených odpovědí

    private val volatileWords = listOf("dnes", "ted", "aktualni", "nejnovejsi", "letos", "zitra", "vcera", "pocasi", "kurz", "cena", "zpravy", "novinky", "momentalne", "prave")

    /** Otázka, jejíž odpověď se mění v čase → nikdy z paměti odpovědí. */
    fun timeSensitive(q: String): Boolean {
        val f = " " + TextUtil.fold(q) + " "
        return volatileWords.any { " $it" in f } || rx("\\b20\\d\\d\\b").containsMatchIn(f)
    }

    fun key(q: String): Set<String> = TextUtil.words(q, 3).filter { it !in stop }.toSet()

    fun similarity(a: String, b: String): Double = similarity(key(a), key(b))

    /** Jaccard nad hotovými množinami (bez alokace průniku i sjednocení). */
    fun similarity(x: Set<String>, y: Set<String>): Double {
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val inter = x.count { it in y }
        return inter.toDouble() / (x.size + y.size - inter)
    }

    private fun shares(x: Set<String>, y: Set<String>) = x.any { it in y }

    private val canonMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun canon(q: String): String = canonMemo[q] ?: (QuestionNorm.variants(q).lastOrNull() ?: q).also { if (canonMemo.size < 4000) canonMemo[q] = it }

    /** Pasuje uložená otázka na tuto natolik, že by ji paměť odpovědí vrátila? (stejná logika jako [cached]) */
    fun sameQuestion(a: String, b: String): Boolean {
        if (similarity(a, b) >= 0.8) return true
        if ((key(a) intersect key(b)).isEmpty()) return false
        return similarity(canon(a), canon(b)) >= 0.8
    }

    fun cached(prompt: String, entries: List<Entry>, now: Long = System.currentTimeMillis()): Instant.Answer? {
        val pk = key(prompt)
        if (pk.size < 2 || timeSensitive(prompt)) return null
        // PŘEFORMULOVANÁ OTÁZKA: když se slova přímo neshodují, porovnat kanonické tvary (slang, překlepy, obaly pryč).
        // Úsporně: jen u záznamů, které sdílí aspoň jedno klíčové slovo, a kanonický tvar se pamatuje.
        // VÝKON: klíče záznamů jsou předpočítané (Entry.keys), klíče dotazu a jeho kanonického tvaru jen jednou;
        // podobnost se počítá JEDNOU pro každý záznam (dřív líná sekvence procházená dvakrát → 2× všechno).
        val ck by lazy(LazyThreadSafetyMode.NONE) { key(canon(prompt)) }
        fun sim(e: Entry): Double {
            val direct = similarity(pk, e.keys)
            if (direct >= 0.8 || !shares(pk, e.keys)) return direct
            return maxOf(direct, similarity(ck, key(canon(e.question))))
        }
        // REVIZNÍ SMLOUVA: prošlé odpovědi se nepoužijí – otázku znovu vyhodnotí mozek nebo zdroj
        val scored = entries.filter { Proof.alive(it.at, it.reviseAt, now) }.map { it to sim(it) }
        // PROOFCORE: jistota z DŮKAZU. Ověřené (✓ uživatele, Wikipedie) platí i pro přeformulovanou otázku;
        // neověřená odpověď mozku jen pro tutéž otázku a POD prahem Auto (85) – v Auto ji mozek zodpoví znovu,
        // Flash (práh 70, uživatel chce rychlost) ji vrátí s viditelným štítkem „neověřeno“.
        val proven = scored.filter { (e, sim) -> e.origin != Proof.Origin.MODEL && sim >= 0.8 }.maxByOrNull { it.second }
        if (proven != null) {
            val (e, sim) = proven
            val base = if (e.origin == Proof.Origin.USER) 96 else 92
            return Instant.Answer(e.answer, e.origin.label, confidence = (base * sim).toInt().coerceAtMost(98))
        }
        val same = scored.filter { (e, sim) -> e.origin == Proof.Origin.MODEL && sim >= 0.95 && e.confidence >= 75 }.maxByOrNull { it.second } ?: return null
        return Instant.Answer(same.first.answer, Proof.Origin.MODEL.label, confidence = 80)
    }

    // ------------------------------------------------------------------ orchestrace

    data class Ctx(
        val instant: Instant.Ctx,
        val today: LocalDate = instant.now.toLocalDate(),
        val memories: List<MemoryItem> = emptyList(),
        val cache: List<Entry> = emptyList(),
        /** minimální jistota (Flash 70, Auto 85) */
        val threshold: Int = 85,
        /** definice z knihovny smí odpovídat (Flash vždy, Auto jen při velmi jisté shodě) */
        val definitions: Boolean = true,
        /**
         * Δ STRÁŽCE BEZ MOZKU: odpověď z paměti / tabulek, kterou offline Wikipedie vyvrací, se NEPOUŽIJE
         * (dotaz převezme další reflex nebo mozek) a aplikace ji může z paměti odpovědí smazat.
         */
        val onRejected: (Instant.Answer, List<Sentinel.Flag>) -> Unit = { _, _ -> },
    )

    // FAULTLINE F-02: dřív „\\S+\\s+\\S+“ bez ukotvení → kvadratický backtracking (20 000 znaků bez mezer = 3,4 s).
    // Slova jsou omezená na 40 znaků → lineární čas.
    private val compound = Regex("\\S{1,40}\\s+\\S{1,40}[\\s,]+(a|ale|i|plus|a taky|a jeste) (jak|proc|co|kdy|kolik|kde|kdo|ktery|ktera|jaky|jaka|jake|cim|porovnej|vysvetli|napis)\\b")

    fun isCompound(prompt: String): Boolean = compound.containsMatchIn(TextUtil.fold(prompt))

    /** Reflex je pro krátké otázky; vložený článek či dlouhý text patří mozku (a nesmí zdržet frontu). */
    const val MAX_PROMPT = 2_000

    fun answer(prompt: String, c: Ctx): Instant.Answer? {
        if (prompt.length > MAX_PROMPT || prompt.isBlank()) return null
        if (prompt.trimStart().startsWith("/")) return null // výslovný příkaz (/chat, /agent…) = mozek
        // SLOŽENÁ OTÁZKA („kolik obyvatel má Praha a jak se to změnilo…“) → reflex by odpověděl jen napůl → mozek
        val sig = Pavucina.of(prompt) // PAVUČINA: varianty a signály se sdílí s E-ARL, RANDY i nápovědou
        if (sig.compound) return null
        return answerExact(prompt, c)
            // ROZPOZNÁNÍ: stejná otázka jinými slovy („prosím tě, víš kolik je dneska hodin?“) → kanonické varianty
            ?: sig.variants.take(9).firstNotNullOfOrNull { answerExact(it, c) }
    }

    private fun answerExact(prompt: String, c: Ctx): Instant.Answer? {
        val candidates = sequence {
            // Praktické první: má nejužší vzory (sleva, DPH, světový čas) – obecná procenta a hodiny by je přebila
            Practical.answer(prompt, c.instant.now)?.let { yield(it) }
            Instant.answer(prompt, c.instant.copy(knowledge = null))?.let { yield(it) }
            Everyday.answer(prompt, c.today, c.instant.rates, c.instant.battery, c.instant.charging)?.let { yield(it) }
            Facts.answer(prompt, c.today)?.let { yield(it) }
            Politics.answer(prompt, c.today)?.let { yield(it) }
            calendar(prompt, c.today)?.let { yield(it) }
            recall(prompt, c.memories)?.let { yield(it) }
            cached(prompt, c.cache)?.let { yield(it) }
            // OFFLINE WIKIPEDIE (přes Pavučinu sdílený zdroj): narození/úmrtí, poloha, vznik, definice – jen přesná shoda hesla
            if (c.definitions && c.instant.knowledge != null) {
                // Δ SROVNÁVAČ: „co je větší, Praha nebo Brno?“ → dvě hesla, tabulka čísel a spočítaný verdikt
                Compare.answer(prompt, c.instant.knowledge)?.let { yield(it) }
                WikiReflex.answer(prompt, c.instant.knowledge)?.let { yield(it) }
            }
        }
        return candidates.firstOrNull { it.confidence >= c.threshold && verified(it, c) }
    }

    /**
     * Ověří tvrzení reflexní odpovědi proti offline Wikipedii. Odpovědi přímo z Wikipedie (WikiReflex, srovnávač)
     * se neověřují samy proti sobě; výpočty a čas neprojdou rychlým sítem (mikrosekundy, žádné hledání).
     */
    private fun verified(a: Instant.Answer, c: Ctx): Boolean {
        val k = c.instant.knowledge ?: return true
        if (a.kind.startsWith("Wikipedie") || a.kind.startsWith("Δ srovnání") || !Sentinel.mayClaim(a.text)) return true
        val flags = runCatching { Sentinel.check(a.text, k) }.getOrDefault(emptyList())
        if (flags.isEmpty()) return true
        runCatching { c.onRejected(a, flags) }
        return false
    }

    /** Pochází odpověď z paměti odpovědí (dá se smazat)? */
    fun fromCache(a: Instant.Answer) = Proof.Origin.values().any { it.label == a.kind }
}
