package cz.axiom.omega.core.step0

/**
 * Forenzní sken textu (RANDY L5). Vrací stopy (artefakty) v pořadí výskytu.
 * Oproti Titanium: IBAN ověřen kontrolním součtem (mod 97), IPv4 validována
 * po oktetech, přidány hashe MD5/SHA-1, data a časy pro časovou osu.
 */
object Forensic {

    data class Trace(val kind: String, val value: String, val index: Int)

    private val patterns: List<Pair<String, Regex>> = listOf(
        "URL" to Regex("""https?://[\w\-./?%&=+#:~@!$,;]+"""),
        "EMAIL" to Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+"""),
        "IPv4" to Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""),
        "SHA256" to Regex("""\b[a-fA-F0-9]{64}\b"""),
        "SHA1" to Regex("""\b[a-fA-F0-9]{40}\b"""),
        "MD5" to Regex("""\b[a-fA-F0-9]{32}\b"""),
        "IBAN" to Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){2,7}(?:\s?[A-Z0-9]{1,4})?\b"""),
        "ČÁSTKA" to Regex("""(\d[\d\s.,]*\d|\d)\s*(?:Kč|CZK|EUR|€|USD|\$)""", RegexOption.IGNORE_CASE),
        "TELEFON" to Regex("""(?<![\w.])\+?\d{3}[\s-]?\d{3}[\s-]?\d{3}(?:[\s-]?\d{3})?(?![\w.])"""),
        "DATUM" to Regex("""\b(?:\d{1,2}\.\s?\d{1,2}\.\s?\d{4}|\d{4}-\d{2}-\d{2})\b"""),
        "ČAS" to Regex("""\b(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?\b"""),
    )

    fun scan(input: String): List<Trace> {
        val out = mutableListOf<Trace>()
        val taken = mutableListOf<IntRange>()
        for ((kind, rx) in patterns) {
            for (m in rx.findAll(input)) {
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                val v = m.value.trim()
                val ok = when (kind) {
                    "IPv4" -> v.split('.').all { (it.toIntOrNull() ?: 999) in 0..255 }
                    "IBAN" -> ibanValid(v)
                    else -> true
                }
                if (!ok) continue
                taken += m.range
                out += Trace(kind, v.take(120), m.range.first)
            }
        }
        return out.sortedBy { it.index }
    }

    /** ISO 13616 kontrola IBAN (mod 97 == 1). */
    fun ibanValid(raw: String): Boolean {
        val s = raw.replace(" ", "").uppercase()
        if (s.length !in 15..34 || !s.all { it.isLetterOrDigit() }) return false
        val rearranged = s.drop(4) + s.take(4)
        var mod = 0
        for (ch in rearranged) {
            val digits = if (ch.isDigit()) ch.toString() else (ch - 'A' + 10).toString()
            for (d in digits) mod = (mod * 10 + (d - '0')) % 97
        }
        return mod == 1
    }
}
