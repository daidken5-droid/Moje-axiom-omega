package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import cz.axiom.omega.core.library.Libraries
import cz.axiom.omega.core.util.TextUtil
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Incident 1018: „Co je porno“ skončilo v mozku, přestože offline Wikipedie má heslo „Pornografie“. */
class ColloquialHeadwordTest {
    private val porno = Snippet(1, "Wikipedie: Pornografie", "Pornografie (zkráceně porno) je zobrazování lidské sexuality s cílem sexuálně vzrušit. Za pornografii se nepovažuje samotná nahota.", "wiki")
    private val autor = Snippet(2, "Wikipedie: Autor", "Autor je původce díla, například spisovatel nebo skladatel.", "wiki")
    private val auto = Snippet(3, "Wikipedie: Automobil", "Automobil je silniční motorové vozidlo určené k přepravě osob nebo nákladu.", "wiki")
    private val kinem = Snippet(4, "Wikipedie: Kinematografie", "Kinematografie je umělecký obor zabývající se filmem.", "wiki")

    /** Chová se jako FTS knihovna: hledá předponou v názvu (title:porno*). */
    private val lib = object : KnowledgeSource {
        override fun search(query: String, limit: Int): List<Snippet> {
            val q = TextUtil.fold(query).lowercase().trim()
            return listOf(porno, autor, auto, kinem).filter { TextUtil.fold(it.title.removePrefix("Wikipedie: ")).lowercase().startsWith(q.take(5)) }
        }
    }
    private val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false, knowledge = lib))
    private fun a(q: String) = Reflex.answer(q, c)?.text ?: "∅"

    @Test fun pornoAnsweredWithoutBrain() {
        assertTrue(a("Co je porno").contains("zobrazování lidské sexuality"), a("Co je porno"))
        assertTrue(a("porno").contains("Pornografie") || a("porno").contains("zobrazování"), a("porno"))
        assertEquals(Earl.Path.RETRIEVE, Earl.decide(Pavucina.of("co je porno"), Earl.Ctx(knowledge = { lib.search(it, 5) })).path)
    }

    @Test fun aliasAndNoFalsePrefix() {
        assertTrue(a("co je auto").contains("silniční motorové vozidlo"), a("co je auto"))
        // „auto“ nesmí skončit u hesla „Autor“, „kino“ nesmí skončit u „Kinematografie“ bez zmínky v úvodu
        assertTrue(!a("co je auto").contains("původce díla"))
        assertEquals(null, WikiReflex.match("kino", listOf(kinem)))
        assertEquals(null, WikiReflex.match("auto", listOf(autor)))
        assertEquals(porno, WikiReflex.match("porno", listOf(autor, porno)))
    }

    @Test fun ftsTitleUsesPrefixFromFourLetters() {
        assertEquals("title:porno*", Libraries.ftsQuery("co je porno", and = true, titleOnly = true))
        assertEquals("porno", Libraries.ftsQuery("co je porno", and = true))
        assertEquals("title:cerpa*", Libraries.ftsQuery("čerpadlo", and = true, titleOnly = true))
    }
}
