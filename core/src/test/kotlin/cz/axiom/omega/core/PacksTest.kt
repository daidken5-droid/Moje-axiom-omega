package cz.axiom.omega.core

import cz.axiom.omega.core.packs.Packs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PacksTest {
    private val dir = listOf(File("../packs"), File("packs")).first { it.isDirectory }

    @Test fun catalogMatchesFilesAndHashes() {
        val packs = Packs.parseCatalog(File(dir, "catalog.json").readText())
        assertTrue(packs.size >= 5)
        for (p in packs) {
            val bytes = File(dir, p.file).readBytes()
            assertTrue("SHA-256 nesouhlasí u ${p.file} – spusť tools/update_pack_catalog.py", Packs.verify(p, bytes))
            assertEquals(p.file, p.bytes, bytes.size.toLong())
            assertEquals(p.file, p.sections, Packs.sections(String(bytes)).size)
        }
    }

    @Test fun sectionSplit() {
        val s = Packs.sections("# Balík\n\núvod se ignoruje\n## A\nobsah a\n\n## B\nobsah b\n## Prázdná\n")
        assertEquals(listOf("Balík – A", "Balík – B"), s.map { it.title })
        assertEquals("obsah b", s[1].body)
    }
}
