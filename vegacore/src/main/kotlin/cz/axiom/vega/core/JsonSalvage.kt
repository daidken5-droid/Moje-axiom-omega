package cz.axiom.vega.core

/**
 * Záchrana useknutého JSON pole („[{…},{…},{…“ po vybité baterii / plném disku): vrátí texty CELÝCH prvků nejvyšší úrovně
 * až po místo poškození. Respektuje řetězce a escape (závorky v textu nepočítá). Neúplný poslední prvek se zahodí.
 */
object JsonSalvage {
    fun arrayElements(raw: String): List<String> {
        val s = raw.trimStart(); if (!s.startsWith("[")) return emptyList()
        val out = ArrayList<String>(); var depth = 0; var start = -1; var inStr = false; var esc = false
        for (i in 1 until s.length) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> { inStr = true; if (depth == 0 && start < 0) start = i }
                '{', '[' -> { if (depth == 0) start = i; depth++ }
                '}', ']' -> { if (depth == 0) return out; depth--; if (depth == 0 && start >= 0) { out += s.substring(start, i + 1); start = -1 } }
                ',' -> if (depth == 0 && start >= 0) { out += s.substring(start, i).trim(); start = -1 }
                else -> if (depth == 0 && start < 0 && !c.isWhitespace()) start = i
            }
        }
        return out
    }
}
