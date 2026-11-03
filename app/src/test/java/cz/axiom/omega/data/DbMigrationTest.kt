package cz.axiom.omega.data

import android.database.sqlite.SQLiteDatabase
import cz.axiom.omega.core.agent.Proof
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CONVERGENCE R-01: migrace paměti odpovědí na v4 se skutečnou Android SQLite (Robolectric).
 * Ověřuje upgrade z v3 (data zachována, ✓ → user), samoopravu napůl migrované databáze a čistou instalaci.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbMigrationTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun resetSingleton() {
        val f = Db::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (f.get(null) as? Db)?.close(); f.set(null, null)
    }

    @Before fun clean() { resetSingleton(); ctx.deleteDatabase("omega.db") }
    @After fun after() = resetSingleton()

    private fun legacy(version: Int, withV4Columns: Boolean = false) {
        val path = ctx.getDatabasePath("omega.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE answers(id INTEGER PRIMARY KEY, question TEXT NOT NULL UNIQUE, answer TEXT NOT NULL, conf INTEGER NOT NULL, good INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL)")
            if (withV4Columns) db.execSQL("ALTER TABLE answers ADD COLUMN revise INTEGER NOT NULL DEFAULT 0") // napůl migrovaná
            db.execSQL("INSERT INTO answers(question, answer, conf, good, at) VALUES ('kdo napsal babičku', 'Božena Němcová.', 96, 1, 1)")
            db.execSQL("INSERT INTO answers(question, answer, conf, good, at) VALUES ('proč kočky předou', 'Když jsou spokojené.', 80, 0, 2)")
            db.version = version
        }
    }

    @Test fun upgradeFromV3KeepsDataAndMarksUserVerified() {
        legacy(3)
        val a = Db.get(ctx).answers().associateBy { it.question }
        assertEquals(2, a.size)
        assertEquals(Proof.Origin.USER, a.getValue("kdo napsal babičku").origin)
        assertEquals(Proof.Origin.MODEL, a.getValue("proč kočky předou").origin)
    }

    @Test fun halfMigratedDatabaseSelfHealsOnOpen() {
        legacy(4, withV4Columns = true) // verze 4, ale chybí sloupec origin (přerušená dřívější migrace)
        val a = Db.get(ctx).answers()
        assertEquals(2, a.size)
        assertTrue(a.all { it.origin == Proof.Origin.MODEL || it.origin == Proof.Origin.USER })
    }

    @Test fun freshInstallRoundTripsProvenanceAndContract() {
        val db = Db.get(ctx)
        db.putAnswer("kdy se narodil karel gott", "14. července 1939.", 90, good = false, origin = Proof.Origin.WIKI)
        db.putAnswer("kdo je ministr financí", "X.", 90, good = false)
        val a = db.answers().associateBy { it.question }
        assertEquals(Proof.Origin.WIKI, a.getValue("kdy se narodil karel gott").origin)
        assertTrue(a.values.all { it.reviseAt > System.currentTimeMillis() })
        // ✓ uživatele se neztratí, když se uloží TENTÝŽ text znovu (i s jinými mezerami/diakritikou)
        db.putAnswer("kdo je ministr financí", "X.", 90, good = true)
        db.putAnswer("kdo je ministr financí", "x. ", 70, good = false)
        db.answers().first { it.question == "kdo je ministr financí" }.let { assertEquals(Proof.Origin.USER, it.origin); assertTrue(it.good); assertEquals(90, it.confidence) }
    }

    /** HYPERFORGE M1-A: ověření A se NESMÍ přenést na jiný text B. */
    @Test fun replacedAnswerDoesNotInheritVerification() {
        val db = Db.get(ctx)
        db.putAnswer("kdo je ministr financí", "A je ministr.", 95, good = true)
        db.putAnswer("kdo je ministr financí", "B je ministr.", 60, good = false)
        val e = db.answers().single { it.question == "kdo je ministr financí" }
        assertEquals("B je ministr.", e.answer)
        assertEquals(false, e.good)
        assertEquals(Proof.Origin.MODEL, e.origin)
        assertEquals(60, e.confidence)
        // ani ověření Wikipedií se nepřenese na jiný text
        db.putAnswer("kdy vznikl most", "1357.", 90, good = false, origin = Proof.Origin.WIKI)
        db.putAnswer("kdy vznikl most", "1402.", 90, good = false)
        assertEquals(Proof.Origin.MODEL, db.answers().single { it.question == "kdy vznikl most" }.origin)
    }

    @Test fun concurrentWritesKeepVerificationBoundToText() {
        val db = Db.get(ctx)
        val threads = (0 until 8).map { t -> Thread { repeat(25) { i -> db.putAnswer("otázka $i", if (t == 0) "ověřená $i" else "jiná $t-$i", 50 + t, good = t == 0) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val a = db.answers()
        assertEquals(25, a.size)
        // každé ✓ musí patřit textu, který ✓ dostal
        assertTrue(a.filter { it.good }.all { it.answer.startsWith("ověřená") })
        assertTrue(a.filter { it.origin == Proof.Origin.USER }.all { it.answer.startsWith("ověřená") })
    }
}
