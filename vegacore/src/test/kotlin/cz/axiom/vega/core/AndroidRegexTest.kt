package cz.axiom.vega.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android (ICU) NEZNÁ regexové příznaky (?U) a (?x)-varianty, které JVM v testech přijme → v telefonu výjimka
 * u každé zprávy (stalo se v e7248a5). Kontrola zdrojů aplikace i jádra, ať se to nevrátí.
 */
class AndroidRegexTest {
    @Test fun noUnsupportedInlineFlags() {
        val roots = listOf("src/main", "../vega/src/main", "../vegacore/src/main").map { File(it) }.filter { it.isDirectory }
        val bad = roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (Regex("\\(\\?[a-zA-Z]*U[a-zA-Z]*[):]").containsMatchIn(l)) "${f.path}:${i + 1}: ${l.trim().take(120)}" else null } } }
        assertTrue("Nepodporované příznaky (?U) pro Android:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * `\b` vedle českého písmene: ICU (telefon) bere hranici podle Unicode, JVM 17+ jen podle ASCII \w → v testu
     * a v telefonu by se výraz choval RŮZNĚ. Pro češtinu (?<!\p{L}) / (?!\p{L}). Hlídá i skupiny „(…|souborů)\b“.
     */
    @Test fun noWordBoundaryNextToNonAscii() {
        val roots = listOf("../vega/src/main", "../vegacore/src/main").map { File(it) }.filter { it.isDirectory }
        val lit = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val bad = roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().flatMapIndexed { i, l -> lit.findAll(l).mapNotNull { m -> m.groupValues[1].takeIf { boundaryNextToNonAscii(it) }?.let { "${f.path}:${i + 1}: ${it.take(120)}" } }.toList() } } }
        assertTrue("\\b vedle ne-ASCII písmene (na Androidu jiné chování než v testu):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun nonAscii(c: Char?) = c != null && c.code > 127 && c.isLetter()
    /** [p] je obsah řetězcového literálu (zpětná lomítka zdvojená). */
    internal fun boundaryNextToNonAscii(p: String): Boolean {
        var i = p.indexOf("\\\\b")
        while (i >= 0) {
            val before = p.getOrNull(i - 1); val after = p.getOrNull(i + 3)
            if (nonAscii(before) || nonAscii(after)) return true
            if (before == ')') groupAlternatives(p, i - 1, backwards = true)?.let { alts -> if (alts.any { nonAscii(it.lastOrNull()) }) return true }
            if (after == '(') groupAlternatives(p, i + 3, backwards = false)?.let { alts -> if (alts.any { nonAscii(it.removePrefix("?:").firstOrNull()) }) return true }
            i = p.indexOf("\\\\b", i + 3)
        }
        return false
    }
    private fun groupAlternatives(p: String, at: Int, backwards: Boolean): List<String>? {
        var depth = 0; var j = at
        while (j in p.indices) {
            when (p[j]) { ')' -> depth += if (backwards) 1 else -1; '(' -> depth += if (backwards) -1 else 1 }
            if (depth == 0) { val body = if (backwards) p.substring(j + 1, at) else p.substring(at + 1, j); return body.split('|') }
            j += if (backwards) -1 else 1
        }
        return null
    }

    @Test fun detectorWorks() {
        assertTrue(boundaryNextToNonAscii("(?i)\\\\b(souborech|souborů)\\\\b"))
        assertTrue(boundaryNextToNonAscii("ulož\\\\b"))
        assertTrue(!boundaryNextToNonAscii("(?i)\\\\b(heslo|token)\\\\b"))
        assertTrue(!boundaryNextToNonAscii("(?<!\\\\p{L})(souborů)(?!\\\\p{L})"))
    }
}
