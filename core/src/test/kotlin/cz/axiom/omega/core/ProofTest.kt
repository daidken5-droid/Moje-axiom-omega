package cz.axiom.omega.core

import cz.axiom.omega.core.agent.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PROOFCORE: jistota z důkazu, revizní smlouvy, žádné vydávání vlastního textu za zdroj. */
class ProofTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val ctx85 = Reflex.Ctx(Instant.Ctx(), threshold = 85)

    private fun e(q: String, a: String, origin: Proof.Origin, at: Long = now - day, revise: Long = 0) =
        Reflex.Entry(q, a, 90, origin == Proof.Origin.USER, at, origin, revise)

    @Test fun confidenceComesFromEvidence() {
        val q = "jak dlouho se vaří vajíčko natvrdo"
        // jen model → tutéž otázku vrátí pod prahem Auto (80 < 85), přeformulovanou vůbec
        val m = Reflex.cached(q, listOf(e(q, "Asi 10 minut.", Proof.Origin.MODEL)), now)
        assertNotNull(m); assertEquals(80, m.confidence); assertEquals(Proof.Origin.MODEL.label, m.kind)
        assertNull(Reflex.answer(q, ctx85.copy(cache = listOf(e(q, "Asi 10 minut.", Proof.Origin.MODEL)))))
        assertNotNull(Reflex.answer(q, Reflex.Ctx(Instant.Ctx(), threshold = 70, cache = listOf(e(q, "Asi 10 minut.", Proof.Origin.MODEL)))))
        // ověřené uživatelem / Wikipedií → i pro přeformulovanou otázku, nad prahem
        val u = Reflex.cached("prosím tě, jak dlouho se vaří vajíčko natvrdo", listOf(e(q, "10 minut.", Proof.Origin.USER)), now)
        assertEquals(Proof.Origin.USER.label, u!!.kind)
        val w = Reflex.cached(q, listOf(e(q, "10 minut.", Proof.Origin.WIKI)), now)
        assertEquals(Proof.Origin.WIKI.label, w!!.kind); assertTrue(w.confidence >= 85)
        // ověřené má přednost před neověřeným
        val both = Reflex.cached(q, listOf(e(q, "model", Proof.Origin.MODEL), e(q, "uživatel", Proof.Origin.USER)), now)
        assertEquals("uživatel", both!!.text)
    }

    @Test fun revisionContract() {
        val vol = Proof.contract("kdo je ministr financí", "Ministrem financí je X.", Proof.Origin.USER, now)
        assertEquals(now + 90 * day, vol.reviseAt); assertTrue(vol.reason.contains("mění"))
        assertEquals(now + 180 * day, Proof.contract("proč kočky předou", "Když jsou spokojené.", Proof.Origin.MODEL, now).reviseAt)
        assertEquals(now + 3 * 365 * day, Proof.contract("kdo napsal babičku", "Božena Němcová.", Proof.Origin.WIKI, now).reviseAt)
        // po termínu revize se ani ✓ odpověď nepoužije
        val q = "kdo je ministr financí"
        assertNull(Reflex.cached(q, listOf(e(q, "X.", Proof.Origin.USER, at = now - 100 * day, revise = now - day)), now))
        assertNotNull(Reflex.cached(q, listOf(e(q, "X.", Proof.Origin.USER, at = now - 100 * day, revise = now + day)), now))
        // staré záznamy bez smlouvy: dosavadních 60 dní
        assertTrue(Proof.alive(now - 59 * day, 0, now)); assertTrue(!Proof.alive(now - 61 * day, 0, now))
    }

    @Test fun sentinelAuditGivesPositiveProof() {
        val k = object : KnowledgeSource {
            override fun search(query: String, limit: Int) = if ("gott" in cz.axiom.omega.core.util.TextUtil.fold(query).lowercase())
                listOf(Snippet(1, "Wikipedie: Karel Gott", "Karel Gott (14. července 1939 Plzeň – 1. října 2019 Praha) byl zpěvák.", "wiki")) else emptyList()
        }
        Sentinel.reset()
        val ok = Sentinel.audit("Karel Gott (14. 7. 1939 – 1. 10. 2019) byl zpěvák.", k)
        assertTrue(ok.proven); assertEquals(2, ok.confirmed.size)
        assertEquals(Proof.Origin.WIKI, Proof.originOf("kdy žil karel gott", "Karel Gott (14. 7. 1939 – 1. 10. 2019) byl zpěvák.", ok))
        val bad = Sentinel.audit("Karel Gott se narodil v roce 1938.", k)
        assertTrue(!bad.proven && bad.flags.size == 1); assertEquals(Proof.Origin.MODEL, Proof.originOf("kdy se narodil karel gott", "Karel Gott se narodil v roce 1938.", bad))
        assertEquals(Proof.Origin.MODEL, Proof.originOf("jak dlouho vařit vajíčko", "Vajíčko se vaří 10 minut.", Sentinel.audit("Vajíčko se vaří 10 minut.", k)))
    }

    @Test fun agentNotesNeverPoseAsEncyclopedia() {
        assertEquals("Poznámka AVIORu: Karel Gott", Proof.noteTitle("Wikipedie: Karel Gott"))
        assertEquals("Poznámka AVIORu: Ceny energií", Proof.noteTitle("Ceny energií"))
        assertTrue(!WikiReflex.titleMatches(Proof.noteTitle("Wikipedie: Karel Gott"), "karel gott"))
    }
}
