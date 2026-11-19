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
}
