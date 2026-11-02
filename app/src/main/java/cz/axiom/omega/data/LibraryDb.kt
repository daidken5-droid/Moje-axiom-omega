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

    private val app = context.applicationContext
    private val prebuiltOpen = HashMap<String, SQLiteDatabase>()

    /** Předpřipravená databáze knihovny (stažená z GitHub Release), uložená jako samostatný soubor. */
    fun prebuiltFile(lib: String): java.io.File = app.getDatabasePath("lib-$lib.db")

    fun hasPrebuilt(lib: String) = prebuiltFile(lib).length() > 0

    // ---------------------------------------------------------------- RAG index (vektory významu)

    /** Předpočítaný RAG index knihovny (AVRAG1, stažený z GitHub Release) – patří k předpřipravené databázi. */
    fun ragFile(lib: String): java.io.File = app.getDatabasePath("lib-$lib.rag")
    fun hasRag(lib: String) = ragFile(lib).length() > 16 && hasPrebuilt(lib)
    fun anyRag() = Libraries.ALL.any { hasRag(it.id) }
    private val ragOpen = HashMap<String, cz.axiom.omega.core.rag.RagIndex>()

    @Synchronized
    private fun rag(lib: String): cz.axiom.omega.core.rag.RagIndex? {
        ragOpen[lib]?.let { return it }
        if (!hasRag(lib)) return null
        return runCatching { cz.axiom.omega.core.rag.RagIndex.open(ragFile(lib)) }.getOrNull()?.also { ragOpen[lib] = it }
    }

    @Synchronized
    fun closeRag(lib: String) { ragOpen.remove(lib) }

    fun removeRag(lib: String) { closeRag(lib); ragFile(lib).delete() }

    /** Sémantické hledání: články nejbližší významem dotazu ([qvec] = vektor dotazu z OMEGA Sense). */
    fun semantic(qvec: FloatArray, limit: Int, minScore: Float = 0.42f): List<Snippet> =
        Libraries.ALL.withIndex().filter { hasRag(it.value.id) }.flatMap { (i, spec) ->
            val idx = rag(spec.id) ?: return@flatMap emptyList()
            val db = prebuilt(spec.id) ?: return@flatMap emptyList()
            val hits = idx.search(qvec, limit, minScore)
            if (hits.isEmpty()) return@flatMap emptyList()
            val score = hits.toMap()
            val rows = HashMap<Int, Snippet>()
            runCatching {
                db.rawQuery("SELECT rowid, title, url, substr(body, 1, 900) FROM articles WHERE rowid IN (${hits.joinToString(",") { it.first.toString() }})", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getInt(0)
                        rows[id] = Snippet(((i + 1).toLong() shl 40) or id.toLong(), "${spec.source}: ${c.getString(1)}", c.getString(3), c.getString(2))
                    }
                }
            }
            hits.mapNotNull { (id, _) -> rows[id]?.let { it to (score[id] ?: 0f) } }
        }.sortedByDescending { it.second }.take(limit).map { it.first }

    @Synchronized
    private fun prebuilt(lib: String): SQLiteDatabase? {
        prebuiltOpen[lib]?.let { if (it.isOpen) return it }
        val f = prebuiltFile(lib)
        if (!f.exists()) return null
        return runCatching {
            // NO_LOCALIZED_COLLATORS: soubor je jen ke čtení, Android by jinak zkoušel zapsat android_metadata
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        }.getOrNull()?.also { prebuiltOpen[lib] = it; cz.axiom.omega.core.agent.Sentinel.reset() }
    }

    @Synchronized
    fun closePrebuilt(lib: String) { prebuiltOpen.remove(lib)?.close(); cz.axiom.omega.core.agent.Sentinel.reset() }

    private fun prebuiltArticles(lib: String): Int = runCatching {
        prebuilt(lib)?.rawQuery("SELECT offset FROM progress LIMIT 1", null)?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
    }.getOrDefault(0)

    /** Po instalaci předpřipravené knihovny smaže případné dříve postupně stažené články téže knihovny (duplicity). */
    fun dropStreamed(lib: String) {
        if (progress(lib).first == 0) return
        remove(lib, keepPrebuilt = true)
    }

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

    fun remove(lib: String, keepPrebuilt: Boolean = false) {
        if (!keepPrebuilt) {
            removeRag(lib)
            closePrebuilt(lib)
            prebuiltFile(lib).delete()
            java.io.File(prebuiltFile(lib).path + "-journal").delete()
        }
        val db = writableDatabase
        db.execSQL("DELETE FROM articles WHERE lib = ?", arrayOf(lib))
        db.delete("progress", "lib=?", arrayOf(lib))
        runCatching { db.execSQL("INSERT INTO articles(articles) VALUES('optimize')") }
    }

    fun count(): Int = runCatching {
        readableDatabase.rawQuery("SELECT sum(offset) FROM progress", null).use { it.moveToFirst(); it.getInt(0) }
    }.getOrDefault(0) + Libraries.ALL.filter { hasPrebuilt(it.id) }.sumOf { prebuiltArticles(it.id) }

    fun sizeBytes(context: Context): Long {
        val f = context.getDatabasePath("library.db")
        return f.length() + java.io.File(f.path + "-wal").length() + Libraries.ALL.sumOf { prebuiltFile(it.id).length() + ragFile(it.id).length() }
    }

    /** Hledání: nejdřív všechna slova (AND), při málo výsledcích kterékoli (OR); řazení podle shody v názvu. */
    /**
     * @param minScore minimální relevance (shoda v názvu váží 4, v textu 1) – do odpovědi se nedostanou
     * články, které s otázkou souvisí jen jedním slovem v textu (šetří tokeny mozku v telefonu).
     */
    fun search(query: String, limit: Int, minScore: Int = 0, qvec: FloatArray? = null): List<Snippet> {
        val dbs = listOf(readableDatabase) + Libraries.ALL.filter { hasPrebuilt(it.id) }.mapNotNull { prebuilt(it.id) }
        val words = dbs.flatMapIndexed { i, db -> search(db, i, query, limit) }.filter { it.second >= minScore }
            .sortedByDescending { it.second }.take(limit).map { it.first }
        if (qvec == null || !anyRag()) return words
        // HYBRIDNÍ RAG: slova (FTS) + význam (vektory) → Reciprocal Rank Fusion
        val meaning = runCatching { semantic(qvec, limit) }.getOrDefault(emptyList())
        if (meaning.isEmpty()) return words
        return cz.axiom.omega.core.rag.Fusion.rrf(listOf(words, meaning), limit)
    }

    private fun search(db: SQLiteDatabase, dbIndex: Int, query: String, limit: Int): List<Pair<Snippet, Int>> {
        val out = LinkedHashMap<Long, Pair<Snippet, Int>>()
        for (match in Libraries.ftsPlan(query)) {
            runCatching {
                db.rawQuery(
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
                        // id: horní bity rozliší databázi, aby se výsledky z různých souborů nepletly
                        out[id] = Snippet((dbIndex.toLong() shl 40) or id, "${Libraries.label(c.getString(3))}: $title", text, c.getString(2)) to Libraries.score(query, title, snip)
                    }
                }
            }
            if (out.size >= limit * 2) break
        }
        return out.values.toList()
    }

    fun status(context: Context): JSONObject {
        val arr = JSONArray()
        for (l in Libraries.ALL) {
            val pre = hasPrebuilt(l.id)
            val (streamed, _, streamedDone) = progress(l.id)
            val offset = if (pre) prebuiltArticles(l.id) else streamed
            val done = pre || streamedDone
            arr.put(
                JSONObject().put("id", l.id).put("name", l.name).put("description", l.description).put("rows", l.rows)
                    .put("approxGb", l.approxGb).put("downloadGb", l.downloadGb).put("source", l.source).put("license", l.license).put("offset", offset).put("done", done)
                    .put("percent", if (done) 100 else if (l.rows > 0) (offset * 100L / l.rows).toInt() else 0).put("started", offset > 0)
                    .put("prebuilt", pre).put("bytes", if (pre) prebuiltFile(l.id).length() else 0L)
                    .put("rag", hasRag(l.id)).put("ragBytes", if (hasRag(l.id)) ragFile(l.id).length() else 0L),
            )
        }
        return JSONObject().put("libraries", arr).put("articles", count()).put("bytes", sizeBytes(context))
    }

    companion object {
        @Volatile private var instance: LibraryDb? = null
        fun get(context: Context): LibraryDb = instance ?: synchronized(this) { instance ?: LibraryDb(context).also { instance = it } }
    }
}
