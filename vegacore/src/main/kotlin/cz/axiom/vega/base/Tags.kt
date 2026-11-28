package cz.axiom.vega.base

/**
 * Lineární práce se značkami XML/HTML z cizích souborů (RSS, DOCX, XLSX, ODT, PPTX).
 * Regex „<x…>(.*?)</x>“ nebo „<[^>]*>“ u otevřených značek bez konce zkouší od každého začátku až na konec vstupu –
 * kvadraticky (změřeno 25. 11.: 150 kB RSS 18–47 s, XLSX/DOCX stejně; strop souboru 2–20 MB = desítky minut až hodiny
 * a jediné pracovní vlákno VEGA stojí). Tady vždy jeden průchod dopředu a délka značky shora omezená.
 */
object Tags {
    /** Nejdelší značka (s atributy), kterou hledáme v [blocks] / [cells]; delší se přeskočí jako nečitelná. */
    const val TAG_MAX = 500
    /** Prvek: atributy z otevírací značky a obsah (u samouzavírací „<x … />“ prázdný). */
    data class El(val attrs: String, val body: String)

    /**
     * Prvky <name …>…</name> (i „<name … />“, když [selfClosing]) jedním průchodem. Otevřený prvek bez konce = dál už nic
     * celého není → konec (ne zkoušení každého dalšího začátku).
     */
    fun elements(xml: String, name: String, max: Int = Int.MAX_VALUE, selfClosing: Boolean = false): List<El> {
        val openTag = "<$name"; val closeTag = "</$name"
        val out = ArrayList<El>(); var from = 0
        var gtSeen = -1  // poslední nalezené „>“ – „<row<row<row…>“ nehledá znovu od každého začátku
        while (out.size < max) {
            val lt = xml.indexOf(openTag, from, ignoreCase = true); if (lt < 0) break
            val after = lt + openTag.length
            // hranice jména: „<c“ nesmí chytit „<cols>“ ani „<c:x>“
            if (after < xml.length && (xml[after].isLetterOrDigit() || xml[after] == '_' || xml[after] == ':' || xml[after] == '-')) { from = after; continue }
            val gt = (if (gtSeen >= after) gtSeen else xml.indexOf('>', after).also { gtSeen = it }); if (gt < 0) break  // bez „>“ až do konce už žádná celá značka není
            if (gt - lt > TAG_MAX) { from = after; continue }
            val innerLt = xml.indexOf('<', after); if (innerLt in after until gt) { from = innerLt; continue }  // „<row<row a>“: platná je až ta druhá
            val self = xml[gt - 1] == '/'
            val attrs = xml.substring(after, if (self) gt - 1 else gt)
            if (self) { if (selfClosing) out += El(attrs, ""); from = gt + 1; continue }
            var c = xml.indexOf(closeTag, gt + 1, ignoreCase = true)
            while (c >= 0) { val e = c + closeTag.length; if (e < xml.length && (xml[e] == '>' || xml[e].isWhitespace())) break; c = xml.indexOf(closeTag, e, ignoreCase = true) }
            if (c < 0) break  // otevřený prvek bez konce = dál už nic celého není
            val cgt = xml.indexOf('>', c + closeTag.length); if (cgt < 0) break
            out += El(attrs, xml.substring(gt + 1, c)); from = cgt + 1
        }
        return out
    }

    /** Jen obsahy prvků <name>…</name> (samouzavírací se přeskočí). */
    fun blocks(xml: String, name: String, max: Int = Int.MAX_VALUE): List<String> = elements(xml, name, max).map { it.body }

    /** Obsah prvního prvku <name>…</name>, nebo null. */
    fun first(xml: String, name: String): String? = blocks(xml, name, max = 1).firstOrNull()

    /**
     * Značky „<x…>“, „</x>“, „<!…>“, „<?…>“ pryč (nahradí [with]). „<“ bez písmene za ním je text („cena < 100“), ne značka.
     * Značka bez „>“ až do konce se zahodí. Lineárně, bez stropu délky značky (kořen ODT má kB deklarací).
     */
    fun strip(s: String, with: String = ""): String {
        val sb = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            var lt = s.indexOf('<', i)
            while (lt >= 0 && !(lt + 1 < s.length && s[lt + 1].let { it.isLetter() || it == '/' || it == '!' || it == '?' })) lt = s.indexOf('<', lt + 1)
            if (lt < 0) { sb.append(s, i, s.length); break }
            sb.append(s, i, lt)
            val gt = s.indexOf('>', lt + 1)
            if (gt < 0) break
            sb.append(with); i = gt + 1
        }
        return sb.toString()
    }
}
