package cz.axiom.vega.calc

/** Dlouhé zadání: dílčí požadavky (otázky, číslované body, pokyny) a dělení na pasáže po odstavcích. */
object LongText {
    private val numbered = Regex("(?m)^\\s*(?:\\d{1,2}[.)]|[a-z][.)]|[-•*])\\s+(.{6,400})$")
    private val imperative = Regex("(?i)^(uveď|uved|vysvětli|vysvetli|popiš|popis|shrň|shrn|porovnej|spočítej|spocitej|navrhni|zhodnoť|zhodnot|najdi|vypiš|vypis|urči|urci|doplň|dopln|odpověz|odpovez|napiš|napis)(?!\\p{L})")

    fun subQuestions(text: String, max: Int = 60): List<String> {
        val out = LinkedHashSet<String>()
        // nejdřív otázky a pokyny uživatele, pak číslované body (ty mohou být dlouhý seznam a vytlačit otázky)
        text.replace('\n', ' ').split(Regex("(?<=[.?!])\\s+")).map { it.trim() }.forEach { s ->
            if (s.length in 6..400 && (s.endsWith("?") || imperative.containsMatchIn(s))) out += s
        }
        numbered.findAll(text).forEach { out += it.groupValues[1].trim() }
        return out.toList().take(max)
    }

    /** Pasáže do [max] znaků po odstavcích (dlouhý odstavec po větách); spojením vznikne původní text. */
    fun chunks(text: String, max: Int): List<String> {
        require(max >= 200)
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>(); val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        Regex("(?s).+?(?:\\n\\s*\\n|$)").findAll(text).map { it.value }.filter { it.isNotEmpty() }.forEach { p ->
            if (p.length > max) { flush(); Regex("(?s).+?(?:[.!?]\\s+|$)").findAll(p).map { it.value }.forEach { s ->
                if (cur.length + s.length > max) flush(); if (s.length > max) s.chunked(max).forEach { out += it } else cur.append(s) } }
            else { if (cur.length + p.length > max) flush(); cur.append(p) }
        }
        flush(); return out
    }
}
