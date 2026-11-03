package cz.axiom.omega

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.axiom.omega.core.agent.Proof
import cz.axiom.omega.data.Db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Migrace paměti odpovědí v3 → v4 na SKUTEČNÉ Android SQLite (emulátor), v samostatném souboru DB. */
@RunWith(AndroidJUnit4::class)
class DbOnDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun reset() {
        val f = Db::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (f.get(null) as? Db)?.close(); f.set(null, null)
    }

    @Test fun upgradeV3ToV4OnRealSqlite() {
        reset(); ctx.deleteDatabase("omega.db")
        val path = ctx.getDatabasePath("omega.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE answers(id INTEGER PRIMARY KEY, question TEXT NOT NULL UNIQUE, answer TEXT NOT NULL, conf INTEGER NOT NULL, good INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO answers(question, answer, conf, good, at) VALUES ('kdo napsal babičku', 'Božena Němcová.', 96, 1, 1)")
            db.version = 3
        }
        val a = Db.get(ctx).answers()
        assertEquals(1, a.size)
        assertEquals(Proof.Origin.USER, a[0].origin)
        Db.get(ctx).putAnswer("kdy se narodil karel gott", "1939.", 90, good = false, origin = Proof.Origin.WIKI)
        assertTrue(Db.get(ctx).answers().any { it.origin == Proof.Origin.WIKI && it.reviseAt > 0 })
        reset(); ctx.deleteDatabase("omega.db") // úklid: další testy začnou s čistou instalací
    }
}
