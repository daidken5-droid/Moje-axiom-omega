package cz.axiom.vega.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Bod 48: velké obsahy v deníku změn jako bloby, velké vstupy běhů jako odkaz – a zpětná čitelnost starého formátu. */
class StorageTest {
    @Test fun bigChangesGoToBlobsAndUndoStillWorks() {
        val root = Files.createTempDirectory("st").toFile().resolve("p")
        val w = Workspace.create(root, "p", mapOf("a.txt" to "malý".toByteArray()))
        val big1 = "x".repeat(300_000); val big2 = "y".repeat(300_000)
        w.write("a.txt", big1, w.hash("a.txt"), "1"); w.write("a.txt", big2, w.hash("a.txt"), "2")
        val log = File(root, "meta/changes.json")
        assertTrue(log.length() < 10_000, "deník má jen odkazy, ne obsah (${log.length()} B)")
        assertEquals(big1, w.changes().last().before); assertEquals(big2, w.changes().last().after)
        w.undo(); assertEquals(big1, w.read("a.txt"))
        w.undo(); assertEquals("malý", w.read("a.txt"))
        assertTrue(File(root, "meta/blob").listFiles()!!.isNotEmpty())
    }

    @Test fun oldFormatJournalIsReadAndExtended() {
        val root = Files.createTempDirectory("st").toFile().resolve("p")
        val w = Workspace.create(root, "p", mapOf("a.txt" to "v1".toByteArray()))
        // starý formát: obsah přímo v deníku
        File(root, "meta/changes.json").writeText("""[{"id":7,"path":"a.txt","op":"write","before":"v0","after":"v1","at":1}]""")
        assertEquals(7, w.changes().single().id)
        val c = w.write("a.txt", "v2", w.hash("a.txt"))
        assertEquals(8, c.id); assertEquals(listOf(7, 8), w.changes().map { it.id })
        w.undo(); assertEquals("v1", w.read("a.txt"))
    }

    @Test fun bigRunInputIsStoredOnceAsReference() {
        val kv = MemKv()
        val bytes = ByteArray(600_000) { (it % 251).toByte() }
        val r = Run("run-x", "t", listOf(Step("a"), Step("b")), Value.Bytes("v.csv", bytes, "text/csv"), emptyList())
        saveRun(kv, r); saveRun(kv, r.copy(results = listOf(StepResult(0, "a", Value.Text("ok"), null, 1))))
        assertTrue(kv.read("run/run-x")!!.length < 2_000, "běh drží odkaz, ne base64")
        assertEquals(1, kv.keys("runblob/").size)
        val back = assertNotNull(loadRun(kv, "run-x"))
        assertTrue((back.input as Value.Bytes).bytes.contentEquals(bytes)); assertEquals(1, back.results.size)
        // starý formát (base64 přímo v běhu) jde načíst dál
        kv.write("run/old", r.copy(id = "old").toJson().toString())
        assertTrue((loadRun(kv, "old")!!.input as Value.Bytes).bytes.contentEquals(bytes))
        kv.delete("run/run-x"); kv.delete("run/old"); pruneRunBlobs(kv); assertEquals(0, kv.keys("runblob/").size)
    }

    @Test fun forgedBlobRefCannotReadOtherFilesAndMissingBlobIsConflict() {
        val root = Files.createTempDirectory("st").toFile().resolve("p")
        File(root.parentFile, "tajne.txt").writeText("TAJNÉ")
        val w = Workspace.create(root, "p", mapOf("a.txt" to "\u0000blob:../../tajne.txt".toByteArray()))
        w.write("a.txt", "nový", w.hash("a.txt"))
        assertEquals("\u0000blob:../../tajne.txt", w.changes().last().before, "podvržený odkaz se nesmí rozbalit")
        w.undo(); assertEquals("\u0000blob:../../tajne.txt", w.read("a.txt"))
        // chybějící blob: vrácení = konflikt, ne smazání souboru
        val big = "z".repeat(200_000); w.write("a.txt", big, w.hash("a.txt"))
        w.write("a.txt", "malý", w.hash("a.txt"))
        File(root, "meta/blob").listFiles()!!.forEach { it.delete() }
        kotlin.test.assertFailsWith<Workspace.Conflict> { w.undo() }
        assertEquals("malý", w.read("a.txt"))
    }
}
