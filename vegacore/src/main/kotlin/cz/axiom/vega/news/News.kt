package cz.axiom.vega.news

import cz.axiom.vega.base.Text
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RANNÍ PŘEHLED ZPRÁV – čisté jádro (testovatelné bez Androidu): zdroje, čtení RSS/Atom, časové okno 05:20 → 05:20
 * (Europe/Prague, i přes přechod letního/zimního času), sloučení stejné události z různých webů, výběr max 10 témat,
 * kontrola souhrnu z modelu (věta bez zdroje se vyřadí) a záloha „titulky + odkazy“. Nikdy se nekopírují celé články:
 * z RSS se bere jen titulek, odkaz, čas a perex (úvod od vydavatele) zkrácený na [PEREX_MAX] znaků.
 */
object News {
    data class Source(val id: String, val name: String, val url: String, val defaultOn: Boolean)

    /** Adresy ověřené živě 22. 11. 2026 (HTTP 200, platné RSS s položkami a pubDate). Neověřené se sem nepřidávají. */
    val SOURCES = listOf(
        Source("novinky", "Novinky.cz", "https://www.novinky.cz/rss", true),
        // http://servis.idnes.cz/rss.asp?c=zpravodaj přesměrovává sem; http mimo LAN VEGA nepovolí
        Source("idnes", "iDNES.cz", "https://servis.idnes.cz/rss.aspx?c=zpravodaj", true),
        Source("ct24", "ČT24", "https://ct24.ceskatelevize.cz/rss", false),
        Source("irozhlas", "iROZHLAS", "https://www.irozhlas.cz/rss/irozhlas", false),
        Source("seznamzpravy", "Seznam Zprávy", "https://www.seznamzpravy.cz/rss", false),
        Source("aktualne", "Aktuálně.cz", "https://www.aktualne.cz/rss/", false),
        Source("denikn", "Deník N", "https://denikn.cz/feed/", false),
    )
    fun source(id: String) = SOURCES.firstOrNull { it.id == id }

    val ZONE: ZoneId = ZoneId.of("Europe/Prague")
    val CUTOFF: LocalTime = LocalTime.of(5, 20)
    /** Perex z RSS (úvod, který web sám dává do kanálu) – nikdy celý článek. */
    const val PEREX_MAX = 600
    /** Kolik vět z perexů na jedno téma (z různých webů, bez opakování). */
    const val SENTENCES_PER_TOPIC = 4
    const val MAX_TOPICS = 10

    data class Item(val source: String, val title: String, val link: String, val published: Instant?, val perex: String)

    // ------------------------------------------------------------------ RSS / Atom

    /** Vadný, prázdný nebo HTML místo RSS → prázdný seznam (nikdy výjimka). Položka bez titulku nebo odkazu se vynechá. */
    fun parse(xml: String, source: String): List<Item> = runCatching {
        val blocks = Regex("(?is)<item\\b[^>]*>(.*?)</item>").findAll(xml).map { it.groupValues[1] }.toList()
            .ifEmpty { Regex("(?is)<entry\\b[^>]*>(.*?)</entry>").findAll(xml).map { it.groupValues[1] }.toList() }
        blocks.mapNotNull { b ->
            val title = clean(tag(b, "title") ?: return@mapNotNull null).take(300)
            val link = (tag(b, "link")?.let { clean(it) }?.takeIf { it.isNotBlank() }
                ?: Regex("(?is)<link\\b[^>]*href=[\"']([^\"']+)[\"']").find(b)?.groupValues?.get(1)?.let { clean(it) }
                // RSS bez <link>, jen <guid isPermaLink="true">https://…</guid> (výchozí isPermaLink je true)
                ?: Regex("(?is)<guid\\b(?![^>]*isPermaLink=[\"']false)[^>]*>(.*?)</guid>").find(b)?.groupValues?.get(1)?.let { clean(it) })
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { untrack(it) } ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val date = (tag(b, "pubDate") ?: tag(b, "dc:date") ?: tag(b, "published") ?: tag(b, "updated"))?.let { date(clean(it)) }
            val perex = clean(tag(b, "description") ?: tag(b, "summary") ?: "").let { if (it.length > PEREX_MAX) it.take(PEREX_MAX).substringBeforeLast(' ') + "…" else it }
            Item(source, title, link, date, perex)
        }
    }.getOrDefault(emptyList())

    /** Odkaz bez sledovacích parametrů (utm_…, #utm…) – kratší a nic navíc o uživateli. */
    fun untrack(u: String): String {
        val noFrag = if (Regex("#.*utm_", RegexOption.IGNORE_CASE).containsMatchIn(u)) u.substringBefore('#') else u
        val q = noFrag.substringAfter('?', ""); if (q.isEmpty()) return noFrag
        val keep = q.split('&').filter { !it.lowercase().startsWith("utm_") }
        return noFrag.substringBefore('?') + if (keep.isEmpty()) "" else "?" + keep.joinToString("&")
    }

    private fun tag(b: String, name: String): String? = Regex("(?is)<$name\\b[^>]*>(.*?)</$name>").find(b)?.groupValues?.get(1)

    /** CDATA, HTML značky a entity pryč; jeden řádek. */
    fun clean(s: String): String {
        var t = s.replace(Regex("(?s)<!\\[CDATA\\[(.*?)]]>"), "$1")
        // dva průchody: HTML zakódované v <description> („&lt;p&gt;…“) i dvojí kódování v Atomu type="html" („&amp;ndash;“)
        repeat(2) { t = decode(t.replace(Regex("(?s)</?[A-Za-z!][^>]*>"), " ")) }
        // text z cizího kanálu nesmí v přehledu vytvořit odkaz ani formátování (markdown): [ ] ` * | < > pryč, # a _ na začátku slov taky
        return t.replace('\u00a0', ' ').replace("\u00ad", "").replace(Regex("[\\[\\]`*|<>]"), " ").replace(Regex("(^|\\s)[#_]+"), "$1").replace(Regex("\\s+"), " ").trim()
    }
    private val NAMED = mapOf("nbsp" to " ", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "lsquo" to "‘", "rsquo" to "’", "laquo" to "«", "raquo" to "»", "euro" to "€",
        "copy" to "©", "reg" to "®", "deg" to "°", "times" to "×", "shy" to "", "middot" to "·", "bull" to "•", "minus" to "−",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "yacute" to "ý", "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í",
        "Oacute" to "Ó", "Uacute" to "Ú", "Yacute" to "Ý", "scaron" to "š", "Scaron" to "Š", "ccaron" to "č", "Ccaron" to "Č", "ecaron" to "ě", "Ecaron" to "Ě",
        "rcaron" to "ř", "Rcaron" to "Ř", "zcaron" to "ž", "Zcaron" to "Ž", "ncaron" to "ň", "Ncaron" to "Ň", "dcaron" to "ď", "Dcaron" to "Ď",
        "tcaron" to "ť", "Tcaron" to "Ť", "uring" to "ů", "Uring" to "Ů", "ouml" to "ö", "uuml" to "ü", "auml" to "ä")
    private fun decode(s: String): String {
        val t = s.replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: " " }
            .replace(Regex("&#x([0-9a-fA-F]{1,6});")) { m -> m.groupValues[1].toIntOrNull(16)?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: " " }
            .replace(Regex("&([A-Za-z]{2,8});")) { m -> if (m.groupValues[1] == "amp") m.value else NAMED[m.groupValues[1]] ?: m.value }
        return t.replace("&amp;", "&")
    }
    private val RFC = DateTimeFormatter.RFC_1123_DATE_TIME
    private val RFC_NAMED = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
    /** RFC 822/1123 („Sun, 22 Nov 2026 15:41:32 +0100“, „… GMT“, „… CET“) i ISO 8601 (Atom). Nečitelné → null. */
    fun date(s: String): Instant? {
        // normalizace tvarů z reálných CMS: víc mezer, „Monday,“ / „November“, ISO s mezerou místo „T“ („2026-11-23 05:20:00 +0100“)
        val v = s.trim().replace(Regex("\\s+"), " ").replace(Regex("^([A-Za-z]{3})[A-Za-z]*,"), "$1,")
            .replace(Regex("(?<=\\d )([A-Za-z]{3})[A-Za-z]+(?= \\d{4})"), "$1")
            .replace(Regex("^(\\d{4}-\\d\\d-\\d\\d) (\\d\\d:\\d\\d(?::\\d\\d(?:\\.\\d+)?)?) ?(?:([+-]\\d\\d):?(\\d\\d)|(Z))$")) { m ->
                "${m.groupValues[1]}T${m.groupValues[2]}${if (m.groupValues[5].isNotEmpty()) "Z" else "${m.groupValues[3]}:${m.groupValues[4]}"}" }
        return runCatching { ZonedDateTime.parse(v, RFC).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(v, RFC_NAMED).toInstant() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(v).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(v) }.getOrNull()
    }

    // ------------------------------------------------------------------ okno 05:20 → 05:20

    /** Okno přehledu pro okamžik [now]: od včerejších 05:20 (včetně) do dnešních 05:20 (bez). Před 05:20 = předchozí okno. */
    fun window(now: ZonedDateTime): Pair<Instant, Instant> {
        val local = now.withZoneSameInstant(ZONE)
        val endDay = if (local.toLocalTime() >= CUTOFF) local.toLocalDate() else local.toLocalDate().minusDays(1)
        return startOf(endDay.minusDays(1)) to startOf(endDay)
    }
    private fun startOf(d: LocalDate): Instant = d.atTime(CUTOFF).atZone(ZONE).toInstant()
    fun inWindow(t: Instant?, w: Pair<Instant, Instant>) = t != null && !t.isBefore(w.first) && t.isBefore(w.second)

    // ------------------------------------------------------------------ témata (deduplikace)

    data class Topic(val items: List<Item>) {
        val sources get() = items.map { it.source }.distinct()
        val title get() = items.first().title
        val newest get() = items.mapNotNull { it.published }.maxOrNull()
    }

    private val STOP = setOf("a", "i", "o", "u", "v", "ve", "na", "do", "z", "ze", "s", "se", "k", "ke", "po", "pro", "pri", "za", "od", "je", "jsou", "byl", "byla",
        "bylo", "by", "ze", "ale", "jak", "co", "kdo", "to", "ten", "ta", "tak", "uz", "jeste", "rika", "podle", "kvuli", "mezi", "nad", "pod", "pred", "jeho", "jeji", "sve")
    /** Kmeny slov titulku (bez diakritiky, bez spojek, první 4 znaky) – „konferenci“ ~ „konference“, „útok“ ~ „útoku“. */
    fun stems(title: String): Set<String> = Text.words(synonyms(Text.fold(title).lowercase()), 2).filter { it !in STOP }.map { it.take(4) }.toSet()
    /** Různá slova pro totéž napříč redakcemi („paliva“ × „pohonné hmoty“ × „benzin a nafta“, „atomovka“ × „jaderná zbraň“) → jeden tvar. */
    private val SYN = listOf(
        Regex("pohonn\\w* hmot\\w*|benzin\\w*|naft\\w*|pohonk\\w*") to "paliva",
        Regex("atomov\\w*|jadern\\w* zbran\\w*|nuklearn\\w* zbran\\w*") to "jaderna zbran",
        Regex("exsef\\w*|byval\\w* sef\\w*|byval\\w* reditel\\w*") to "sef",
        Regex("spojen\\w* stat\\w*|\\busa\\b|\\bspojenych statu\\b") to "usa",
        Regex("premier\\w*|predsed\\w* vlady") to "premier",
        Regex("rozvedk\\w*|tajn\\w* sluzb\\w*|zpravodajsk\\w* sluzb\\w*") to "rozvedka")
    private fun synonyms(t: String) = SYN.fold(t) { acc, (r, w) -> acc.replace(r, w) }
    /** Podobnost titulků: aspoň 3 společné kmeny (4 písmena – „útok/útoku“, „ruskou/ruská“) a polovina menšího titulku. */
    fun similar(a: String, b: String): Boolean = similarStems(stems(a), stems(b))
    private fun similarStems(x: Set<String>, y: Set<String>): Boolean {
        val common = x.filter { it in y }
        val ratio = common.size.toDouble() / minOf(x.size, y.size).coerceAtLeast(1)
        // aspoň jeden společný kmen musí být konkrétní – „Policie zadržela muže“ v Praze a v Brně jsou dvě různé události
        return common.size >= 3 && ratio >= 0.5 && common.any { it !in GENERIC }
    }
    /** Obecná zpravodajská slova (kmeny po 4 písmenech): sama o sobě neříkají, že jde o stejnou událost. */
    private val GENERIC = setOf("poli", "zadr", "muze", "muzi", "zena", "zeny", "vlad", "schv", "zvys", "sniz", "ceny", "cena", "cen",
        "ledn", "unor", "brez", "dube", "kvet", "cerv", "srpn", "zari", "rijn", "list", "pros", "proc", "koru", "mili", "mld",
        "soud", "lide", "lidi", "stat", "mest", "kraj", "nove", "nova", "novy", "prvn", "dals", "rok", "roku", "let", "dnes", "vcer",
        "zitr", "tyde", "chce", "zemr", "jedn", "pora", "sraz", "auta", "auto", "nehod", "dite", "deti", "hasi", "zach", "obce", "obec")

    /** Položky v okně → témata: stejný odkaz = jedna položka, podobné titulky = jedno téma; řazeno podle počtu zdrojů, pak času. */
    fun topics(items: List<Item>, w: Pair<Instant, Instant>, max: Int = MAX_TOPICS): List<Topic> {
        val inWin = items.filter { inWindow(it.published, w) }.distinctBy { it.link.substringBefore('#').substringBefore("?utm") }
        val groups = ArrayList<MutableList<Item>>()
        val st = inWin.associateWith { stems(it.title) }  // kmeny jednou na položku (dřív v každém porovnání znovu)
        for (x in inWin.sortedByDescending { it.published }) {
            // položka podobná víc skupinám je spojí (dřív jen do první → stejná událost rozdělená do dvou témat)
            val hit = groups.filter { g -> g.any { y -> similarStems(st.getValue(x), st.getValue(y)) } }
            if (hit.isEmpty()) groups += mutableListOf(x)
            else { val g = hit.first(); hit.drop(1).forEach { g += it; groups.remove(it) }; g += x }
        }
        return groups.map { Topic(it) }.sortedWith(compareByDescending<Topic> { it.sources.size }.thenByDescending { it.newest }).take(max)
    }

    // ------------------------------------------------------------------ souhrn

    /** Číslování zdrojů pro model: S1… napříč tématy (každá položka jedno číslo). */
    fun refs(topics: List<Topic>): List<Pair<Int, Item>> { var n = 0; return topics.flatMap { t -> t.items.map { (++n) to it } } }

    fun prompt(topics: List<Topic>): String {
        val r = refs(topics); val byItem = r.associate { it.second to it.first }
        val body = topics.mapIndexed { i, t -> "T${i + 1}:\n" + t.items.joinToString("\n") { "[S${byItem[it]}] (${source(it.source)?.name ?: it.source}) ${it.title} — ${it.perex}" } }.joinToString("\n\n")
        return "Napiš ke KAŽDÉMU tématu 1–2 vlastní krátké věty česky (ne kopii perexu). Každou větu zakonči odkazem na zdroj ve tvaru [S číslo], " +
            "jen ze zdrojů daného tématu. Nic nepřidávej, co v podkladech není. Formát řádků přesně: „T1: věta [S1]. Druhá věta [S2].“\n\n" +
            cz.axiom.vega.core.Untrusted.wrap("titulky a perexy z RSS", body)
    }

    data class Sentence(val text: String, val refs: List<Int>)
    data class Summary(val topic: Topic, val sentences: List<Sentence>)

    /**
     * Kontrola výstupu modelu: věta bez [S#] nebo s odkazem mimo své téma se vyřadí; nejvýš 2 věty a 300 znaků; věta, která
     * přebírá 12+ slov za sebou z perexu, se vyřadí (žádné kopírování). Téma bez platné věty dostane jen titulek s odkazy.
     */
    fun validate(output: String, topics: List<Topic>): List<Summary> {
        val r = refs(topics)
        return topics.mapIndexed { i, t ->
            val own = r.filter { it.second in t.items }.map { it.first }.toSet()
            val line = output.lines().firstOrNull { Regex("^\\s*\\**T${i + 1}\\**\\s*[:.)]").containsMatchIn(it) }?.substringAfter(':')?.trim().orEmpty()
            val sentences = Regex("[^.!?]+?[.!?]?\\s*(?:\\[S\\s?\\d+](?:\\s*,?\\s*\\[S\\s?\\d+])*)[.!?]?").findAll(line).map { it.value.trim() }.toList()
                .mapNotNull { s ->
                    val ids = Regex("\\[S\\s?(\\d+)]").findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                    val text = s.replace(Regex("\\s*\\[S\\s?\\d+]"), "").trim().trimEnd(',').let { if (it.endsWith('.') || it.endsWith('!') || it.endsWith('?')) it else "$it." }
                    if (ids.isEmpty() || ids.any { it !in own } || text.length < 8 || text.length > 300 || copies(text, t)) null else Sentence(text, ids.distinct())
                }.take(2)
            Summary(t, sentences)
        }
    }
    private fun copies(s: String, t: Topic): Boolean {
        val w = Text.words(Text.fold(s).lowercase(), 1)
        if (w.size < 12) return false
        return t.items.any { p -> val pw = Text.words(Text.fold(p.perex + " " + p.title).lowercase(), 1).joinToString(" ")
            (0..w.size - 12).any { k -> pw.contains(w.subList(k, k + 12).joinToString(" ")) } }
    }

    /**
     * Obsáhlejší souhrn tématu BEZ modelu: věty z perexů všech webů, které o tématu psaly, střídavě po zdrojích (víc úhlů
     * pohledu), podobné věty jen jednou, max [SENTENCES_PER_TOPIC] vět a ~700 znaků. Každá věta s odkazem na svůj článek.
     */
    fun extract(t: Topic): List<Pair<String, Item>> {
        val perSource = t.items.distinctBy { it.source }.map { item ->
            item to sentences(item.perex)
                .filter { it.length in 25..400 && !it.endsWith("…") && !similarSentence(it, t.title) }.toList()
        }
        val out = ArrayList<Pair<String, Item>>(); var chars = 0; var round = 0
        while (out.size < SENTENCES_PER_TOPIC && chars < 700 && perSource.any { it.second.size > round }) {
            for ((item, ss) in perSource) {
                val sn = ss.getOrNull(round) ?: continue
                if (out.size >= SENTENCES_PER_TOPIC || chars >= 700) break
                if (out.none { similarSentence(it.first, sn) }) { out += sn to item; chars += sn.length }
            }
            round++
        }
        return out
    }
    /** Věty perexu; úlomek začínající malým písmenem patří k předchozí větě („10. července“, „tzv. vklady“, „a.s. uložila“). */
    internal fun sentences(perex: String): List<String> {
        val out = ArrayList<String>()
        Regex("[^.!?…]+[.!?…]+[\"“”»)]*|[^.!?…]+$").findAll(perex).map { it.value.trim() }.filter { it.isNotEmpty() }.forEach { p ->
            if (out.isNotEmpty() && p.first().isLowerCase()) out[out.size - 1] = out.last() + " " + p else out += p
        }
        return out
    }
    private fun similarSentence(a: String, b: String): Boolean {
        val x = stems(a); val y = stems(b); val c = x.intersect(y).size
        return c >= 3 && c.toDouble() / minOf(x.size, y.size).coerceAtLeast(1) >= 0.6
    }

    private fun name(id: String) = source(id)?.name ?: id
    private fun mdLink(text: String, url: String) = "[${text.replace("]", ")").replace("[", "(")}](${url.replace(")", "%29")})"

    /** Výsledný text (markdown s odkazy). Bez modelu / bez platných vět: titulky + odkazy na všechny zdroje tématu. */
    fun render(date: LocalDate, sums: List<Summary>, withModel: Boolean): String {
        if (sums.isEmpty()) return "**Ranní přehled ${date.dayOfMonth}. ${date.monthValue}.** – za posledních 24 hodin (do 05:20) jsem v zapnutých zdrojích nic nenašel."
        val r = refs(sums.map { it.topic }).associate { it.first to it.second }
        val body = sums.mapIndexed { i, s ->
            val t = s.topic
            val links = t.items.distinctBy { it.source }.joinToString(" · ") { mdLink(name(it.source), it.link) }
            val head = "**${i + 1}. ${t.title}**" + if (t.sources.size > 1) " _(${t.sources.size} ${if (t.sources.size in 2..4) "zdroje" else "zdrojů"})_" else ""
            if (s.sentences.isEmpty()) "$head\n" + extract(t).joinToString(" ") { (sn, it) -> "$sn ${mdLink("(" + name(it.source) + ")", it.link)}" }.let { if (it.isBlank()) "" else "$it\n" } +
                "Číst: " + t.items.distinctBy { it.source }.joinToString(" · ") { mdLink(name(it.source), it.link) }
            else "$head\n" + s.sentences.joinToString(" ") { sn -> sn.text + " " + sn.refs.mapNotNull { r[it] }.joinToString(" ") { mdLink(name(it.source), it.link) } }
        }.joinToString("\n\n")
        return "**Ranní přehled ${date.dayOfMonth}. ${date.monthValue}.** (včera 05:20 → dnes 05:20)\n\n$body" +
            if (!withModel) "\n\n_Sestaveno přímo z RSS (bez jazykového modelu)._" else ""
    }
}
