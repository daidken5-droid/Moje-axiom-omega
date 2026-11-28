package cz.axiom.omega.core.agent

/**
 * DLOUHÉ ZADÁNÍ (REBIRTH P0 „žádné tiché odstranění části dotazu“):
 *  • [subQuestions] – deterministicky vytáhne podotázky / požadavky (věty s „?“, číslované a odrážkové body,
 *    rozkazy „porovnej / vysvětli / zjisti…“) → kontrolní seznam, na který musí odpověď navázat,
 *  • [chunks] – rozdělení po odstavcích/větách s pevným stropem, bez ztráty textu (spojení = původní text).
 */
object LongInput {
    private val numbered = Regex("""(?m)^\s*(?:\d{1,3}[.)]|[-•*]|[a-z][.)])\s+(.{6,400})$""")
    private val imperative = Regex("""(?i)^(porovnej|srovnej|vysvětli|vysvetli|zjisti|popiš|popis|uveď|uved|analyzuj|zhodnoť|zhodnot|shrň|shrn|najdi|dolož|dolo[zž]|ověř|over|compare|explain|describe|find|list|analyze|summarize)\b""")

    fun subQuestions(text: String, max: Int = 60): List<String> {
        val out = LinkedHashSet<String>()
        numbered.findAll(text).forEach { out += it.groupValues[1].trim() }
        // věty: otázky a pokyny
        text.replace('\n', ' ').split(Regex("(?<=[.?!])\\s+")).map { it.trim() }.forEach { s ->
            if (s.length in 6..400 && (s.endsWith("?") || imperative.containsMatchIn(s))) out += s
        }
        // body, které jsou zároveň větou, nezdvojovat
        return out.map { it.trimEnd() }.distinctBy { it.lowercase().trimEnd('.', '?', '!') }.take(max)
    }

    /** Části po odstavcích (nebo větách) do [max] znaků; spojení částí = původní text (nic se neztratí). */
    fun chunks(text: String, max: Int): List<String> {
        require(max >= 200)
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>(); val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        // odstavce včetně oddělovačů → přesná rekonstrukce
        val pieces = Regex("(?s).+?(?:\\n\\s*\\n|$)").findAll(text).map { it.value }.filter { it.isNotEmpty() }.toList()
        for (p in pieces) {
            if (p.length > max) {
                flush()
                // dlouhý odstavec po větách, nouzově natvrdo
                var rest = p
                while (rest.length > max) {
                    val cut = rest.lastIndexOfAny(charArrayOf('.', '?', '!', '\n'), max - 1).takeIf { it >= max / 2 }?.plus(1) ?: max
                    out += rest.substring(0, cut); rest = rest.substring(cut)
                }
                cur.append(rest)
            } else {
                if (cur.length + p.length > max) flush()
                cur.append(p)
            }
        }
        flush()
        return out
    }
}
