package cz.axiom.omega.core.agent

import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.util.TextUtil
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

    data class Entry(val question: String, val answer: String, val confidence: Int, val good: Boolean, val at: Long)

    // ------------------------------------------------------------------ 2) kalendář

    private val months = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")
    private val days = listOf("pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle")
    private fun fmt(d: LocalDate) = "${d.dayOfMonth}. ${months[d.monthValue - 1]} ${d.year}"
    private fun dayWord(n: Long) = when { n == 1L -> "den"; n in 2..4 -> "dny"; else -> "dní" }

    private val dateRx = Regex("""(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})?""")
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
        Regex("^(?:kolik (?:dni|dnu|dnu zbyva|dni zbyva) (?:zbyva )?)?(?:kolik )?(?:dni|dnu)?\\s*(?:zbyva )?do (.+)$").find(f)?.let { m ->
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
        if (Regex("^(jaky|kterej?y?|co) (je|bude|byl|to bude|to byl)?\\s*(den|den v tydnu)").containsMatchIn(f) || f.startsWith("na jaky den")) {
            dateRx.find(f)?.let { dm ->
                val d = runCatching { LocalDate.of(dm.groupValues[3].ifBlank { "${today.year}" }.toInt(), dm.groupValues[2].toInt(), dm.groupValues[1].toInt()) }.getOrNull() ?: return null
                return Instant.Answer("${fmt(d)} ${if (d.isBefore(today)) "byl" else if (d == today) "je" else "bude"} **${days[d.dayOfWeek.value - 1]}**.", "kalendář", confidence = 99)
            }
        }
        // jaké datum bude za 30 dní / co bude za 2 týdny
        Regex("za (\\d{1,4}) (dni|dnu|den|dny|tyden|tydny|tydnu)").find(f)?.let { m ->
            if (!Regex("^(jake|jaky|co|kdy|ktery)").containsMatchIn(f)) return@let
            val n = m.groupValues[1].toLong() * if (m.groupValues[2].startsWith("tyd")) 7 else 1
            val d = today.plusDays(n)
            return Instant.Answer("Za $n ${dayWord(n)} bude **${days[d.dayOfWeek.value - 1]} ${fmt(d)}**.", "kalendář", confidence = 99)
        }
        // kolikátý týden / den v roce
        if (Regex("^kolikat[yi] (je )?(dnes )?tyden").containsMatchIn(f)) {
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
            .replace(Regex("^Uživatelův "), "Tvůj ").replace(Regex("^Uživatelova "), "Tvoje ").replace(Regex("^Uživatelovo "), "Tvoje ")
            .replace(Regex("^Uživatelovi "), "Tví ").replaceFirstChar { it.uppercase() }
        return Instant.Answer("Podle toho, co si pamatuji: **$said.**", "z paměti o tobě", confidence = conf)
    }

    // ------------------------------------------------------------------ 4) paměť ověřených odpovědí

    private val volatileWords = listOf("dnes", "ted", "aktualni", "nejnovejsi", "letos", "zitra", "vcera", "pocasi", "kurz", "cena", "zpravy", "novinky", "momentalne", "prave")

    /** Otázka, jejíž odpověď se mění v čase → nikdy z paměti odpovědí. */
    fun timeSensitive(q: String): Boolean {
        val f = " " + TextUtil.fold(q) + " "
        return volatileWords.any { " $it" in f } || Regex("\\b20\\d\\d\\b").containsMatchIn(f)
    }

    fun key(q: String): Set<String> = TextUtil.words(q, 3).filter { it !in stop }.toSet()

    fun similarity(a: String, b: String): Double {
        val x = key(a); val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    fun cached(prompt: String, entries: List<Entry>, now: Long = System.currentTimeMillis()): Instant.Answer? {
        if (timeSensitive(prompt) || key(prompt).size < 2) return null
        val best = entries.asSequence()
            .filter { now - it.at < 60L * 86_400_000 && (it.good || it.confidence >= 90) }
            .map { it to similarity(prompt, it.question) }
            .filter { it.second >= 0.8 }
            .maxByOrNull { it.second } ?: return null
        val (e, sim) = best
        val conf = ((if (e.good) 96 else e.confidence) * sim).toInt().coerceAtMost(98)
        return Instant.Answer(e.answer, if (e.good) "ověřená odpověď (✓)" else "dřívější jistá odpověď", confidence = conf)
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
    )

    fun answer(prompt: String, c: Ctx): Instant.Answer? {
        if (prompt.trimStart().startsWith("/")) return null // výslovný příkaz (/chat, /agent…) = mozek
        val candidates = sequence {
            Instant.answer(prompt, c.instant.copy(knowledge = null))?.let { yield(it) }
            Facts.answer(prompt, c.today)?.let { yield(it) }
            calendar(prompt, c.today)?.let { yield(it) }
            recall(prompt, c.memories)?.let { yield(it) }
            cached(prompt, c.cache)?.let { yield(it) }
            if (c.definitions && c.instant.knowledge != null) Instant.answer(prompt, c.instant)?.let { yield(it) }
        }
        return candidates.firstOrNull { it.confidence >= c.threshold }
    }
}
