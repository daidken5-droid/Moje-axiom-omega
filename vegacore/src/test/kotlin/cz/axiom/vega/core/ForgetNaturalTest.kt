package cz.axiom.vega.core
import kotlin.test.Test
import kotlin.test.assertEquals

/** „Zapomeň …“ přirozeně česky: údaj (bydliště, jméno, věk, práce, partner) a české koncovky (Brno/Brně) – a nic navíc. */
class ForgetNaturalTest {
    private val facts = listOf("Bydlím v Brně", "Jmenuju se Petr", "Pracuju jako učitel", "Je mi 42 let", "Manželka se jmenuje Jana", "Mám rád stručné odpovědi", "Mám psa Rexe")
    private fun forget(q: String): List<String> {
        val kv = MemKv(); var t = 1L; val m = Memory(kv) { t++ }
        facts.forEach { m.remember(it) }
        val query = (Router.route(q, Router.Ctx(false, null, false, false, null)) as? Router.Intent.Forget)?.query ?: return listOf("<není Forget>")
        return m.forget(query).map { it.text }
    }

    @Test fun naturalPhrasings() {
        val cases = mapOf(
            "zapomeň, kde bydlím" to listOf("Bydlím v Brně"),
            "zapomeň kde bydlím" to listOf("Bydlím v Brně"),
            "zapomeň bydliště" to listOf("Bydlím v Brně"),
            "zapomeň na Brno" to listOf("Bydlím v Brně"),
            "zapomeň mé jméno" to listOf("Jmenuju se Petr"),
            "zapomeň jak se jmenuju" to listOf("Jmenuju se Petr"),
            "zapomeň jméno manželky" to listOf("Manželka se jmenuje Jana"),
            "zapomeň můj věk" to listOf("Je mi 42 let"),
            "zapomeň kolik mi je" to listOf("Je mi 42 let"),
            "zapomeň kde pracuju" to listOf("Pracuju jako učitel"),
            "zapomeň mou práci" to listOf("Pracuju jako učitel"),
            "zapomeň na psa" to listOf("Mám psa Rexe"),
            "zapomeň, že mám psa" to listOf("Mám psa Rexe"),
            "zapomeň na kočku" to emptyList(),
            "zapomeň to" to emptyList(),
        )
        val bad = cases.mapNotNull { (q, want) -> forget(q).takeIf { it != want }?.let { "$q → $it (čekáno $want)" } }
        assertEquals(emptyList(), bad)
    }

    @Test fun stemMatchIsTight() {
        assertEquals(true, Mem.stemMatch("brno", "brne"))
        assertEquals(false, Mem.stemMatch("brno", "bruna"))
    }
}
