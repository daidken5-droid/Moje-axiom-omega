package cz.axiom.omega.core
import cz.axiom.omega.core.video.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoExplainerTest {
    private val foto = "Fotosyntéza je biochemický proces, při kterém rostliny, řasy a sinice přeměňují světelnou energii na energii chemických vazeb. " +
        "Probíhá v chloroplastech, které obsahují zelené barvivo chlorofyl. Při fotosyntéze vzniká kyslík a glukóza z oxidu uhličitého a vody. " +
        "Ročně se jí na Zemi zachytí asi 130 terawattů energie. Proces popsal Jan Ingenhousz v roce 1779."

    @Test fun intent() {
        assertEquals("fotosyntéza", VideoIntent.parse("/video fotosyntéza"))
        assertEquals("fotosyntéze", VideoIntent.parse("Udělej mi video o fotosyntéze"))
        assertEquals("černé díry", VideoIntent.parse("vytvoř krátké video na téma černé díry"))
        assertEquals("Operation Prato", VideoIntent.parse("video o Operation Prato"))
        assertNull(VideoIntent.parse("co je video"))
        assertNull(VideoIntent.parse("jak funguje videokamera"))
        assertNull(VideoIntent.parse("pusť video"))
    }

    @Test fun storyboardFromSourceWithoutBrain() {
        val b = assertNotNull(Explainer.fromSource("fotosyntéza", foto, "Wikipedie: Fotosyntéza"))
        assertEquals(4, b.scenes.size, Explainer.describe(b))
        assertEquals(SceneKind.QUESTION, b.scenes.first().kind)
        assertEquals("Co je Fotosyntéza?", b.scenes.first().caption)
        assertEquals(SceneKind.ANSWER, b.scenes.last().kind)
        assertTrue(b.scenes.last().caption.startsWith("Fotosyntéza je biochemický proces"))
        assertTrue(b.seconds in 30..60, "${b.seconds}")
        assertTrue(b.scenes.all { it.seconds in 7..10 })
        // každý titulek kromě otázky je doslova věta ze zdroje (žádný vymyšlený fakt)
        b.scenes.drop(1).forEach { s -> assertTrue(foto.contains(s.caption.trimEnd('.')), s.caption) }
        // dlouhá verze: číslo / rok jako velký prvek
        val l = assertNotNull(Explainer.fromSource("fotosyntéza", foto, "x", long = true))
        assertTrue(l.scenes.size in 5..8)
        assertTrue(l.scenes.any { it.kind == SceneKind.NUMBER && (it.big == "1779" || it.big!!.startsWith("130")) }, Explainer.describe(l))
    }

    @Test fun articleForDeclinedTopic() {
        val f = cz.axiom.omega.core.agent.Snippet(1, "Wikipedie: Fotosyntéza", foto, "u")
        val e = cz.axiom.omega.core.agent.Snippet(2, "Wikipedia EN: Photosynthesis", "Photosynthesis is…", "u")
        val c = cz.axiom.omega.core.agent.Snippet(3, "Wikipedie: Černá díra", "Černá díra je oblast…", "u")
        assertEquals(f, Explainer.pickArticle("fotosyntéze", listOf(e, f)))
        assertEquals(c, Explainer.pickArticle("černé díře", listOf(f, c)))
        assertNull(Explainer.pickArticle("fotografie", listOf(f)))
        assertEquals("Brno", Explainer.displayTitle("Wikipedie: Brno (město)"))
    }

    @Test fun personQuestionAndThinSource() {
        val gott = "Karel Gott (14. července 1939 Plzeň – 1. října 2019 Praha) byl český zpěvák populární hudby. Proslul jako Zlatý slavík. Nazpíval téměř tři tisíce písní."
        assertEquals("Kdo byl Karel Gott?", Explainer.fromSource("Karel Gott", gott, "w")!!.scenes[0].caption)
        assertNull(Explainer.fromSource("x", "Jediná věta o něčem, co nic dalšího neříká.", "w"))
    }

    @Test fun brainJsonIsValidated() {
        val raw = "Tady je: {\"scenes\":[{\"caption\":\"Proč je nebe modré?\",\"kind\":\"question\"},{\"caption\":\"Sluneční světlo se rozptyluje na molekulách vzduchu.\",\"kind\":\"fact\"}," +
            "{\"caption\":\"Modré světlo má kratší vlnovou délku, proto se rozptyluje víc.\",\"kind\":\"number\"},{\"caption\":\"Nebe je modré kvůli Rayleighovu rozptylu.\",\"kind\":\"fact\"}]}"
        val b = assertNotNull(Explainer.parse("modré nebe", raw))
        assertTrue(b.byBrain)
        assertEquals(SceneKind.FACT, b.scenes[2].kind) // number bez čísla → fact
        assertEquals(SceneKind.ANSWER, b.scenes.last().kind)
        assertNull(Explainer.parse("x", "nevím"))
    }

    @Test fun qaFitAndContrast() {
        val measure = { s: String, size: Float -> s.length * size * 0.5f }
        val (size, lines) = assertNotNull(SceneQa.fit("Fotosyntéza je biochemický proces, při kterém rostliny přeměňují světlo", 600f, 4, listOf(64f, 56f, 48f, 40f), measure))
        assertTrue(lines.all { measure(it, size) <= 600f } && lines.size <= 4)
        assertNull(SceneQa.fit("Supersuperdlouhéslovokteréseneveszanicdojednéhořádkuanipřinejmenšímpísmu", 300f, 4, listOf(40f), measure))
        assertTrue(SceneQa.contrast(0xF1F1F2, 0x000000) > 15)
        assertTrue(SceneQa.contrast(0x6C6F75, 0x000000) >= 3.0)
    }

    @Test fun musicSpec() {
        val pcm = MusicSynth.render(32.0)
        assertEquals(32 * MusicSynth.RATE, pcm.size)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak <= (Short.MAX_VALUE * 0.25).toInt() + 1 && peak > Short.MAX_VALUE * 0.18, "peak $peak")
        assertTrue(kotlin.math.abs(pcm[0].toInt()) < 50 && kotlin.math.abs(pcm.last().toInt()) < 50)
        // náběh: první půlsekunda tišší než pátá sekunda
        fun rms(a: Int, b: Int) = Math.sqrt((a until b).sumOf { pcm[it].toDouble() * pcm[it] } / (b - a))
        assertTrue(rms(0, 22050) < rms(5 * 44100, 6 * 44100))
        val wav = MusicSynth.wav(pcm)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals(44 + pcm.size * 2, wav.size)
        assertTrue(MusicSynth.render(5.0).contentEquals(MusicSynth.render(5.0)))
    }
}

class VideoExplainerFixTest {
    @Test fun numberNotGluedToWord() {
        assertEquals("130 terawattů", Explainer.bigNumber("Ročně se jí na Zemi zachytí asi 130 terawattů energie."))
        assertEquals("1779", Explainer.bigNumber("Proces popsal Jan Ingenhousz v roce 1779."))
        assertEquals("25 km", Explainer.bigNumber("Leží asi 25 km jihozápadně od Jihlavy."))
    }

    @Test fun fitBoxRespectsHeight() {
        val measure = { s: String, size: Float -> s.length * size * 0.5f }
        val text = "Fotosyntéza je biochemický proces, při kterém rostliny, řasy a sinice přeměňují světelnou energii na energii chemických vazeb."
        val (size, lines) = assertNotNull(SceneQa.fitBox(text, 576f, 330f, listOf(62f, 56f, 50f, 45f, 40f, 36f, 32f), measure = measure))
        assertTrue((lines.size - 1) * size * 1.28f <= 330f, "$size ${lines.size}")
    }
}
