package cz.axiom.omega.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.library.Libraries
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline knihovna – samostatná databáze (může mít několik GB), oddělená od hlavní,
 * aby záloha vláken a paměti zůstala malá. FTS4 s prefixovým indexem pro češtinu.
 */
class LibraryDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "library.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE progress(lib TEXT PRIMARY KEY, offset INTEGER NOT NULL, total INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE articles USING fts4(title, body, lib, url, notindexed=lib, notindexed=url, prefix=\"5\", tokenize=unicode61)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun progress(lib: String): Triple<Int, Int, Boolean> =
        readableDatabase.rawQuery("SELECT offset, total, done FROM progress WHERE lib=?", arrayOf(lib)).use { c ->
            if (c.moveToFirst()) Triple(c.getInt(0), c.getInt(1), c.getInt(2) == 1) else Triple(0, 0, false)
        }

    fun setProgress(lib: String, offset: Int, total: Int, done: Boolean) {
        writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
            put("lib", lib); put("offset", offset); put("total", total); put("done", if (done) 1 else 0); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Vloží stránku článků v jedné transakci a posune ukazatel navazování. */
    fun insertPage(lib: String, items: List<Triple<String, String, String>>, newOffset: Int, total: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT INTO articles(title, body, lib, url) VALUES (?,?,?,?)")
            for ((title, body, url) in items) {
                st.clearBindings()
                st.bindString(1, title); st.bindString(2, body); st.bindString(3, lib); st.bindString(4, url)
                st.executeInsert()
            }
            db.insertWithOnConflict("progress", null, ContentValues().apply {
                put("lib", lib); put("offset", newOffset); put("total", total); put("done", 0); put("updated", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(lib: String) {
        val db = writableDatabase
        db.execSQL("DELETE FROM articles WHERE lib = ?", arrayOf(lib))
        db.delete("progress", "lib=?", arrayOf(lib))
        runCatching { db.execSQL("INSERT INTO articles(articles) VALUES('optimize')") }
    }

    fun count(): Int = runCatching {
        readableDatabase.rawQuery("SELECT sum(offset) FROM progress", null).use { it.moveToFirst(); it.getInt(0) }
    }.getOrDefault(0)

    fun sizeBytes(context: Context): Long {
        val f = context.getDatabasePath("library.db")
        return f.length() + java.io.File(f.path + "-wal").length()
    }

    /** Hledání: nejdřív všechna slova (AND), při málo výsledcích kterékoli (OR); řazení podle shody v názvu. */
    fun search(query: String, limit: Int): List<Snippet> {
        val out = LinkedHashMap<Long, Pair<Snippet, Int>>()
        for (and in listOf(true, false)) {
            val match = Libraries.ftsQuery(query, and) ?: return emptyList()
            runCatching {
                readableDatabase.rawQuery(
                    "SELECT rowid, title, url, lib, substr(body, 1, 900), snippet(articles, '', '', '…', 1, 48) FROM articles WHERE articles MATCH ? LIMIT 60",
                    arrayOf(match),
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (out.containsKey(id)) continue
                        val title = c.getString(1)
                        val intro = c.getString(4)
                        val snip = c.getString(5)
                        val text = if (intro.contains(snip.trim('…').take(40))) intro else "$intro\n…\n$snip"
                        out[id] = Snippet(id, "Wikipedie: $title", text, c.getString(2)) to Libraries.score(query, title, snip)
                    }
                }
            }
            if (out.size >= limit * 2) break
        }
        return out.values.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun status(context: Context): JSONObject {
        val arr = JSONArray()
        for (l in Libraries.ALL) {
            val (offset, total, done) = progress(l.id)
            arr.put(
                JSONObject().put("id", l.id).put("name", l.name).put("description", l.description).put("rows", l.rows)
                    .put("approxGb", l.approxGb).put("license", l.license).put("offset", offset).put("done", done)
                    .put("percent", if (l.rows > 0) (offset * 100L / l.rows).toInt() else 0).put("started", offset > 0),
            )
        }
        return JSONObject().put("libraries", arr).put("articles", count()).put("bytes", sizeBytes(context))
    }

    companion object {
        @Volatile private var instance: LibraryDb? = null
        fun get(context: Context): LibraryDb = instance ?: synchronized(this) { instance ?: LibraryDb(context).also { instance = it } }
    }
}
