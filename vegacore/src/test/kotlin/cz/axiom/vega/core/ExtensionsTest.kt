package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionsTest {
    private val code1 = "module.exports.run = (input) => input.trim().split(/\\n/).length + ' řádků';"
    private val code2 = "module.exports.run = (input) => String(input.split(/\\n/).filter(l => l.trim()).length) + ' řádků';"
    private val tests = mapOf("tool.test.js" to "const {run} = require('./tool'); test('počítá', () => expect(run('a\\nb')).toBe('2 řádků'));")

    @Test fun onlyTestedExactCodeCanBeSavedEnabledAndRolledBack() {
        val x = Extensions(MemKv())
        // neprošlé testy / jiný otisk než testovaný = nic se neuloží
        assertFailsWith<IllegalArgumentException> { x.saveVersion("Počet řádků", "d", code1, tests, 1, "✗", Extensions.shaOf(code1, tests)) }
        assertFailsWith<IllegalArgumentException> { x.saveVersion("Počet řádků", "d", code1, tests, 0, "ok", Extensions.shaOf(code2, tests)) }
        assertFailsWith<IllegalArgumentException> { x.saveVersion("Počet řádků", "d", code1, emptyMap(), 0, "ok", Extensions.shaOf(code1, emptyMap())) }
        assertTrue(x.list().isEmpty())

        val e1 = x.saveVersion("Počet řádků", "spočítá neprázdné řádky", code1, tests, 0, "1 prošlo", Extensions.shaOf(code1, tests))
        assertEquals("pocet-radku", e1.id); assertFalse(e1.enabled, "nové rozšíření je vypnuté")
        assertTrue(x.setEnabled(e1.id, true)!!.enabled)
        val e2 = x.saveVersion("Počet řádků", "verze 2", code2, tests, 0, "1 prošlo", Extensions.shaOf(code2, tests))
        assertEquals(2, e2.current); assertTrue(e2.enabled)
        val back = x.rollback(e1.id)!!
        assertEquals(1, back.current); assertEquals(code1, back.cur().code)
        assertEquals(listOf("uloženo v1", "zapnuto", "nová verze v2", "vráceno na v1"), back.history.map { it.action })
    }

    @Test fun askingForExtensionStartsProject() {
        val i = Router.route("napiš rozšíření, které z výpisu vytáhne všechny IBANy", RouterTuning.NONE)
        assertTrue(i is Router.Intent.NewProject, i.toString())
    }
}
