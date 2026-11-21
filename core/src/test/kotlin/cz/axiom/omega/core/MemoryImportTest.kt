package cz.axiom.omega.core

import cz.axiom.omega.core.self.MemoryImport
import cz.axiom.omega.core.self.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryImportTest {

    @Test fun structuredGeminiAnswer() {
        val r = MemoryImport.parseText(
            """
            Zde je vše, co o tobě vím:
            [Profil] Jmenuješ se Daniel a bydlíš v Brně.
            [Preference] Chceš stručné odpovědi s tabulkou.
            [Fakt] Pracuješ s mikrocementem Topciment.
            [Fakt] Pracuješ s mikrocementem Topciment.
            """.trimIndent(),
        )
        assertEquals(3, r.items.size)
        assertEquals(listOf(MemoryKind.PROFILE, MemoryKind.PREFERENCE, MemoryKind.FACT), r.items.map { it.kind })
        assertTrue(r.items[0].text.startsWith("Jmenuješ se Daniel a bydlíš v Brně."))
        assertTrue(r.items[0].text.endsWith("(import: Gemini)"))
        assertEquals(2, r.skipped) // úvodní věta + duplicita
    }

    @Test fun freeListIsClassified() {
        val r = MemoryImport.parseText("Saved info\n• Lives in Prague\n• Prefers concise answers\n- Owns a 3D printer\n1. x")
        assertEquals(listOf(MemoryKind.PROFILE, MemoryKind.PREFERENCE, MemoryKind.FACT), r.items.map { it.kind })
    }

    @Test fun takeoutJson() {
        val json = """
            [
              {"header":"Gemini Apps","title":"Prompted Jmenuji se Daniel, pomoz mi s kalkulací","time":"2026-05-01T10:00:00Z","products":["Gemini Apps"]},
              {"header":"Gemini Apps","title":"Prompted pamatuj si, že preferuju Half-Kelly","time":"2026-05-02T10:00:00Z"},
              {"header":"Gemini Apps","title":"Used an Assistant feature","time":"2026-05-02T11:00:00Z"},
              {"header":"YouTube","title":"Watched video","time":"2026-05-03T10:00:00Z"}
            ]
        """.trimIndent()
        val r = MemoryImport.parseAny(json)
        assertEquals(2, r.prompts)
        assertTrue(r.items.any { it.kind == MemoryKind.PROFILE && it.text.contains("Daniel") })
        assertTrue(r.items.any { it.text.contains("Half-Kelly") })
        assertNotNull(r.transcript)
        assertTrue(r.transcript!!.contains("[2026-05-01] Jmenuji se Daniel"))
    }

    @Test fun takeoutHtml() {
        val html = "<div class=\"content-cell\">Prompted&nbsp;Jmenuji se Eva<br>1. 5. 2026</div><div>Prompted&nbsp;Kolik je 2+2?</div>"
        val r = MemoryImport.parseAny(html, "MyActivity.html")
        assertEquals(2, r.prompts)
        assertTrue(r.items.single().text.contains("Eva"))
    }
}
