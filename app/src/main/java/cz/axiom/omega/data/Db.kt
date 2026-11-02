package cz.axiom.omega.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cz.axiom.omega.core.agent.KnowledgeSource
import cz.axiom.omega.core.agent.MemoryStore
import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.util.TextUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jediná SQLite databáze AVIOR: vlákna + zprávy, dlouhodobá paměť, znalostní báze (FTS4).
 *
 * AXIOM 2.1 ukládal celou historii jako JSON do SharedPreferences (až 6 MB, synchronní
 * commit při KAŽDÉM stisku klávesy v poznámkách) → zamrzání UI a ztráta dat při překročení.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "omega.db", null, 3), KnowledgeSource, MemoryStore {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE threads(id INTEGER PRIMARY KEY, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY, thread_id INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, meta TEXT, created INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX messages_thread ON messages(thread_id, id)")
        db.execSQL(
            "CREATE TABLE memory(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, text TEXT NOT NULL, weight REAL NOT NULL, " +
                "created INTEGER NOT NULL, last_used INTEGER NOT NULL, uses INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE TABLE docs(id INTEGER PRIMARY KEY, title TEXT, source TEXT, added INTEGER, chars INTEGER)")
        db.execSQL("CREATE VIRTUAL TABLE chunks USING fts4(document_id, title, body, notindexed=document_id, tokenize=unicode61)")
        db.execSQL("CREATE TABLE packs(id TEXT PRIMARY KEY, name TEXT NOT NULL, version INTEGER NOT NULL, docs INTEGER NOT NULL, installed INTEGER NOT NULL)")
        createV2(db)
        createV3(db)
        // Znalosti se plní z offline balíčků (PackManager.ensureAutoInstalled) – vestavěné v APK, bez internetu.
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
    }

    /** v3: paměť ověřených odpovědí (reflex – odpověď bez mozku, když je jistá). */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS answers(id INTEGER PRIMARY KEY, question TEXT NOT NULL UNIQUE, answer TEXT NOT NULL, conf INTEGER NOT NULL, good INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL)")
    }

    /** Uloží/aktualizuje odpověď do paměti odpovědí (✓ od uživatele = ověřená). */
    fun putAnswer(question: String, answer: String, conf: Int, good: Boolean) {
        val q = question.trim().take(500)
        if (q.isBlank() || answer.isBlank()) return
        // bez UPSERT (SQLite < 3.24 na Androidu 8–10): zachovat vyšší jistotu a příznak ověření
        val db = writableDatabase
        val prev = db.rawQuery("SELECT conf, good FROM answers WHERE question=?", arrayOf(q)).use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else null }
        db.insertWithOnConflict("answers", null, android.content.ContentValues().apply {
            put("question", q); put("answer", answer.take(4000))
            put("conf", maxOf(conf, prev?.first ?: 0)); put("good", if (good || prev?.second == 1) 1 else 0)
            put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // držet malé: nejvýš 2000 nejnovějších
        writableDatabase.execSQL("DELETE FROM answers WHERE id NOT IN (SELECT id FROM answers ORDER BY good DESC, at DESC LIMIT 2000)")
        answersCache = null
    }

    /** Smaže uloženou odpověď na otázku i všechny, které by na ni paměť odpovědí vrátila (podobné / přeformulované). */
    fun forgetMatching(question: String) {
        val doomed = answers().filter { cz.axiom.omega.core.agent.Reflex.sameQuestion(question, it.question) }.map { it.question }
        val db = writableDatabase
        db.delete("answers", "question=?", arrayOf(question.trim().take(500)))
        doomed.forEach { db.delete("answers", "question=?", arrayOf(it)) }
        answersCache = null
    }


    /** Paměť odpovědí v RAM – reflex ji čte u každého dotazu, mění se jen při ✓/✗ a nové jisté odpovědi. */
    @Volatile private var answersCache: List<cz.axiom.omega.core.agent.Reflex.Entry>? = null

    fun answers(): List<cz.axiom.omega.core.agent.Reflex.Entry> = answersCache ?:
        readableDatabase.rawQuery("SELECT question, answer, conf, good, at FROM answers ORDER BY at DESC LIMIT 2000", null).use { c ->
            buildList { while (c.moveToNext()) add(cz.axiom.omega.core.agent.Reflex.Entry(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3) == 1, c.getLong(4))) }
        }.also { answersCache = it }

    /** v2: vektory pro sémantické hledání + úkoly aktivních agentů. */
    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS vectors(chunk INTEGER PRIMARY KEY, dim INTEGER NOT NULL, vec BLOB NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY, thread_id INTEGER, goal TEXT NOT NULL, status TEXT NOT NULL, " +
                "engine TEXT, steps INTEGER NOT NULL DEFAULT 0, result TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL)",
        )
    }

    // ------------------------------------------------------------------ úkoly agentů

    fun addTask(threadId: Long, goal: String, engine: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insertOrThrow("tasks", null, ContentValues().apply {
            put("thread_id", threadId); put("goal", TextUtil.clip(goal, 500)); put("status", "running"); put("engine", engine)
            put("created", now); put("updated", now)
        })
    }

    fun updateTask(id: Long, status: String, steps: Int, result: String?) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("status", status); put("steps", steps); put("result", result?.let { TextUtil.clip(it, 400) }); put("updated", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun tasks(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id, thread_id, goal, status, engine, steps, result, created, updated FROM tasks ORDER BY id DESC LIMIT 200", null).use { c ->
            while (c.moveToNext()) out.put(
                JSONObject().put("id", c.getLong(0)).put("threadId", c.getLong(1)).put("goal", c.getString(2)).put("status", c.getString(3))
                    .put("engine", c.getString(4) ?: "").put("steps", c.getInt(5)).put("result", c.getString(6) ?: "")
                    .put("created", c.getLong(7)).put("updated", c.getLong(8)),
            )
        }
        return out
    }

    fun deleteTask(id: Long) { writableDatabase.delete("tasks", "id=?", arrayOf(id.toString())) }

    /** Po pádu/ukončení aplikace nesmí úkol viset jako „běží“. */
    fun failStaleTasks() {
        writableDatabase.execSQL("UPDATE tasks SET status='stopped' WHERE status='running'")
    }

    // ------------------------------------------------------------------ vektory (sémantická paměť)

    fun chunksWithoutVectors(limit: Int): List<Triple<Long, String, String>> {
        val out = mutableListOf<Triple<Long, String, String>>()
        readableDatabase.rawQuery(
            "SELECT docid, title, body FROM chunks WHERE docid NOT IN (SELECT chunk FROM vectors) LIMIT ?", arrayOf(limit.toString()),
        ).use { c -> while (c.moveToNext()) out += Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "") }
        return out
    }

    fun putVector(chunk: Long, v: FloatArray) {
        val buf = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        writableDatabase.insertWithOnConflict("vectors", null, ContentValues().apply {
            put("chunk", chunk); put("dim", v.size); put("vec", buf.array())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        vectorIndex = null
    }

    /** Vektory v paměti – načtou se jednou, ne při každém dotazu (dřív až desítky MB čtení na otázku). */
    @Volatile private var vectorIndex: cz.axiom.omega.core.util.VectorIndex? = null

    @Synchronized
    private fun vectors(dim: Int): cz.axiom.omega.core.util.VectorIndex {
        vectorIndex?.takeIf { it.dim == dim }?.let { return it }
        val rows = ArrayList<Pair<Long, FloatArray>>()
        readableDatabase.rawQuery("SELECT chunk, vec FROM vectors WHERE dim=?", arrayOf(dim.toString())).use { c ->
            while (c.moveToNext()) {
                val fb = java.nio.ByteBuffer.wrap(c.getBlob(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                rows += c.getLong(0) to FloatArray(dim).also { fb.get(it) }
            }
        }
        return cz.axiom.omega.core.util.VectorIndex.build(dim, rows).also { vectorIndex = it }
    }

    fun vectorCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM vectors", null).use { it.moveToFirst(); it.getInt(0) }

    fun pruneVectors() {
        writableDatabase.execSQL("DELETE FROM vectors WHERE chunk NOT IN (SELECT docid FROM chunks)")
        vectorIndex = null
    }

    /** Kosinová podobnost (vektory jsou normalizované) přes všechny úseky – pro desítky tisíc úseků stačí. */
    fun semantic(query: FloatArray, limit: Int): List<Pair<Snippet, Float>> {
        return vectors(query.size).top(query, limit).mapNotNull { (id, score) ->
            readableDatabase.rawQuery(
                "SELECT chunks.document_id, chunks.title, chunks.body, docs.source FROM chunks JOIN docs ON docs.id=chunks.document_id WHERE chunks.docid=?",
                arrayOf(id.toString()),
            ).use { c -> if (c.moveToFirst()) Snippet(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) to score else null }
        }
    }

    // ------------------------------------------------------------------ vlákna

    fun threads(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT t.id, t.title, t.updated, (SELECT count(*) FROM messages m WHERE m.thread_id=t.id), " +
                "(SELECT substr(content,1,140) FROM messages m WHERE m.thread_id=t.id ORDER BY id DESC LIMIT 1) " +
                "FROM threads t ORDER BY t.updated DESC LIMIT 500",
            null,
        ).use { c ->
            while (c.moveToNext()) out.put(
                JSONObject().put("id", c.getLong(0)).put("title", c.getString(1)).put("updated", c.getLong(2))
                    .put("count", c.getInt(3)).put("preview", c.getString(4) ?: ""),
            )
        }
        return out
    }

    /** Hledání ve všech konverzacích (název i obsah zpráv). */
    fun searchThreads(q: String, limit: Int = 30): JSONArray {
        val out = JSONArray()
        val like = "%" + q.trim().replace("%", "").replace("_", "") + "%"
        if (like.length < 4) return out
        readableDatabase.rawQuery(
            "SELECT t.id, t.title, t.updated, (SELECT substr(m.content, max(1, instr(lower(m.content), lower(?)) - 40), 160) FROM messages m " +
                "WHERE m.thread_id=t.id AND m.content LIKE ? ORDER BY m.id DESC LIMIT 1) AS hit " +
                "FROM threads t WHERE t.title LIKE ? OR EXISTS (SELECT 1 FROM messages m WHERE m.thread_id=t.id AND m.content LIKE ?) " +
                "ORDER BY t.updated DESC LIMIT ?",
            arrayOf(q.trim(), like, like, like, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("id", c.getLong(0)).put("title", c.getString(1)).put("updated", c.getLong(2)).put("preview", c.getString(3) ?: ""))
        }
        return out
    }

    fun newThread(title: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insertOrThrow("threads", null, ContentValues().apply {
            put("title", TextUtil.clip(title.ifBlank { "Nové vlákno" }, 80)); put("created", now); put("updated", now)
        })
    }

    fun renameThread(id: Long, title: String) {
        writableDatabase.update("threads", ContentValues().apply { put("title", TextUtil.clip(title, 80)) }, "id=?", arrayOf(id.toString()))
    }

    fun deleteThread(id: Long) {
        writableDatabase.delete("threads", "id=?", arrayOf(id.toString()))
    }

    fun threadCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM threads", null).use { it.moveToFirst(); it.getInt(0) }

    fun messages(threadId: Long, limit: Int = 400): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, role, content, meta, created FROM (SELECT * FROM messages WHERE thread_id=? ORDER BY id DESC LIMIT ?) ORDER BY id",
            arrayOf(threadId.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.put(
                JSONObject().put("id", c.getLong(0)).put("role", c.getString(1)).put("content", c.getString(2))
                    .put("meta", c.getString(3)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())
                    .put("created", c.getLong(4)),
            )
        }
        return out
    }

    fun addMessage(threadId: Long, role: String, content: String, meta: JSONObject? = null): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("messages", null, ContentValues().apply {
                put("thread_id", threadId); put("role", role); put("content", content); put("meta", meta?.toString()); put("created", now)
            })
            db.update("threads", ContentValues().apply { put("updated", now) }, "id=?", arrayOf(threadId.toString()))
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------------ paměť

    override fun all(): List<MemoryItem> {
        val out = mutableListOf<MemoryItem>()
        readableDatabase.rawQuery("SELECT id, kind, text, weight, created, last_used, uses FROM memory ORDER BY id DESC LIMIT 2000", null).use { c ->
            while (c.moveToNext()) out += MemoryItem(
                c.getLong(0), runCatching { MemoryKind.valueOf(c.getString(1)) }.getOrDefault(MemoryKind.FACT),
                c.getString(2), c.getDouble(3), c.getLong(4), c.getLong(5), c.getInt(6),
            )
        }
        return out
    }

    override fun remember(item: MemoryItem): Long {
        val db = writableDatabase
        // duplicitní text jen posílí váhu
        db.rawQuery("SELECT id FROM memory WHERE lower(text)=lower(?)", arrayOf(item.text)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.execSQL("UPDATE memory SET weight=min(weight+0.3, 3.0), last_used=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id))
                return id
            }
        }
        return db.insertOrThrow("memory", null, ContentValues().apply {
            put("kind", item.kind.name); put("text", TextUtil.clip(item.text, 600)); put("weight", item.weight)
            put("created", item.createdAt); put("last_used", item.lastUsedAt); put("uses", item.uses)
        })
    }

    override fun forget(query: String): Int {
        val words = TextUtil.words(query, 3)
        if (words.isEmpty()) return 0
        val victims = all().filter { m -> val w = TextUtil.fold(m.text); words.all { it in w } }
        victims.forEach { writableDatabase.delete("memory", "id=?", arrayOf(it.id.toString())) }
        return victims.size
    }

    override fun touch(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        ids.forEach { writableDatabase.execSQL("UPDATE memory SET uses=uses+1, last_used=? WHERE id=?", arrayOf<Any>(now, it)) }
    }

    fun deleteMemory(id: Long) {
        writableDatabase.delete("memory", "id=?", arrayOf(id.toString()))
    }

    /** NOUZOVÉ SMAZÁNÍ: konverzace, paměť, úlohy i vlastní dokumenty (offline balíčky se příště doinstalují). */
    fun wipeAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("messages", "threads", "memory", "tasks", "vectors", "docs", "packs", "answers")) runCatching { db.delete(t, null, null) }
            runCatching { db.execSQL("DELETE FROM chunks") }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        vectorIndex = null
        answersCache = null
        runCatching { db.execSQL("VACUUM") } // smazaná data fyzicky přepsat, ne jen označit jako volná
    }

    fun clearMemory() {
        writableDatabase.delete("memory", null, null)
    }

    fun boostMemory(text: String, delta: Double) {
        writableDatabase.execSQL("UPDATE memory SET weight=max(0.1, min(3.0, weight+?)) WHERE text=?", arrayOf<Any>(delta, text))
    }

    fun memoryCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM memory", null).use { it.moveToFirst(); it.getInt(0) }

    // ------------------------------------------------------------------ znalosti

    private fun addDoc(db: SQLiteDatabase, title: String, body: String, source: String): Long {
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("docs", null, ContentValues().apply {
                put("title", title); put("source", source); put("added", System.currentTimeMillis()); put("chars", body.length)
            })
            for (chunk in chunk(body)) {
                db.insertOrThrow("chunks", null, ContentValues().apply { put("document_id", id); put("title", title); put("body", chunk) })
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun addDocument(title: String, body: String, source: String): Long {
        require(body.isNotBlank()) { "Prázdný dokument." }
        require(body.length <= 10_000_000) { "Limit jednoho dokumentu je 10 MB textu." }
        return addDoc(writableDatabase, title.ifBlank { "Bez názvu" }, body, source)
    }

    fun deleteDocument(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // vektory smazaných úseků pryč (jinak by zabíraly místa mezi nejlepšími výsledky)
            db.execSQL("DELETE FROM vectors WHERE chunk IN (SELECT docid FROM chunks WHERE document_id=?)", arrayOf(id))
            db.delete("chunks", "document_id=?", arrayOf(id.toString()))
            db.delete("docs", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            vectorIndex = null
        } finally {
            db.endTransaction()
        }
    }

    fun documents(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id, title, source, chars, added FROM docs ORDER BY id DESC LIMIT 1000", null).use { c ->
            while (c.moveToNext()) out.put(
                JSONObject().put("id", c.getLong(0)).put("title", c.getString(1)).put("source", c.getString(2))
                    .put("chars", c.getInt(3)).put("added", c.getLong(4)),
            )
        }
        return out
    }

    fun docCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM docs", null).use { it.moveToFirst(); it.getInt(0) }

    /** Fulltext s jednoduchým řazením podle počtu shodných slov (AXIOM 2.1 vracel první nalezené bez řazení). */
    override fun search(query: String, limit: Int): List<Snippet> {
        val terms = TextUtil.words(query, 3).distinct().take(10)
        if (terms.isEmpty()) return emptyList()
        // FTS4: předpona jen bez uvozovek (abc*); slova jsou očištěná na písmena/číslice
        val match = terms.joinToString(" OR ") { (if (it.length > 6) it.take(6) else it) + "*" }
        val rows = mutableListOf<Pair<Snippet, Int>>()
        readableDatabase.rawQuery(
            "SELECT chunks.document_id, chunks.title, chunks.body, docs.source FROM chunks JOIN docs ON docs.id=chunks.document_id WHERE chunks MATCH ? LIMIT 60",
            arrayOf(match),
        ).use { c ->
            while (c.moveToNext()) {
                val s = Snippet(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
                val folded = TextUtil.fold(s.title + " " + s.text)
                rows += s to terms.count { folded.contains(it.take(6)) }
            }
        }
        return rows.sortedByDescending { it.second }.distinctBy { it.first.id to it.first.text.take(40) }.take(limit).map { it.first }
    }

    // ------------------------------------------------------------------ offline balíčky

    fun installPack(id: String, name: String, version: Int, sections: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            removePackDocs(db, id)
            for ((title, body) in sections) addDoc(db, title, body, "pack:$id")
            db.insertWithOnConflict("packs", null, ContentValues().apply {
                put("id", id); put("name", name); put("version", version); put("docs", sections.size); put("installed", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removePack(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            removePackDocs(db, id)
            db.delete("packs", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun removePackDocs(db: SQLiteDatabase, id: String) {
        db.execSQL("DELETE FROM chunks WHERE document_id IN (SELECT id FROM docs WHERE source=?)", arrayOf("pack:$id"))
        db.delete("docs", "source=?", arrayOf("pack:$id"))
    }

    fun installedPacks(): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT id, version FROM packs", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) }
        return out
    }

    // ------------------------------------------------------------------ záloha

    fun file(): java.io.File = java.io.File(readableDatabase.path)

    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
    }

    fun stats(): JSONObject = JSONObject()
        .put("vectors", vectorCount())
        .put("docs", docCount()).put("memory", memoryCount()).put("threads", threadCount())
        .put("chunks", readableDatabase.rawQuery("SELECT count(*) FROM chunks", null).use { it.moveToFirst(); it.getInt(0) })
        .put("messages", readableDatabase.rawQuery("SELECT count(*) FROM messages", null).use { it.moveToFirst(); it.getInt(0) })
        .put("bytes", file().length() + java.io.File(file().path + "-wal").length())

    fun searchJson(query: String, limit: Int): JSONArray = JSONArray().apply {
        search(query, limit).forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text).put("source", it.source)) }
    }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db = instance ?: synchronized(this) { instance ?: Db(context).also { instance = it } }

        /** Zavře databázi (před obnovou ze zálohy); další get() ji otevře znovu. */
        fun reset() = synchronized(this) { instance?.close(); instance = null }

        fun path(context: Context): java.io.File = context.applicationContext.getDatabasePath("omega.db")

        /** Úseky ~1100 znaků s překryvem, přednostně dělené na koncích odstavců/vět. */
        fun chunk(text: String, size: Int = 1100, overlap: Int = 200): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                var end = minOf(text.length, i + size)
                if (end < text.length) {
                    val window = text.substring(i, end)
                    val cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "))
                    if (cut > size / 2) end = i + cut + 1
                }
                out += text.substring(i, end).trim()
                if (end >= text.length) break
                i = maxOf(end - overlap, i + 1)
            }
            return out.filter { it.isNotBlank() }
        }

    }
}
