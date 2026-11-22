package cz.axiom.vega

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import cz.axiom.vega.web.Snippet
import cz.axiom.vega.files.Extract
import java.io.File
import java.util.concurrent.TimeUnit

/** Text ze souboru (převzato z AVIORu): PDF (PdfBox), naskenované PDF a obrázky přes OCR v telefonu, Office/ODF/HTML v jádru. */
class FileText(private val context: Context) {
    data class Read(val text: String, val notes: List<String>, val ocr: Boolean)

    fun read(name: String, mime: String?, bytes: ByteArray): Read {
        val notes = ArrayList<String>()
        return when (Extract.kindOf(name, mime, bytes.copyOf(minOf(bytes.size, 16)))) {
            Extract.Kind.PDF -> pdf(bytes, notes)
            Extract.Kind.IMAGE -> Read(image(bytes), listOf("Text z obrázku (OCR v telefonu, bez sítě)."), true)
            else -> { val r = Extract.extract(name, mime, bytes); r?.note?.let { notes += it }; Read(r?.text ?: cz.axiom.vega.core.Encoding.decode(bytes), notes, false) }
        }
    }

    fun pdfText(bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(bytes).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) }
    }

    private fun pdf(bytes: ByteArray, notes: MutableList<String>): Read {
        val (text, pages) = runCatching { PDFBoxResourceLoader.init(context.applicationContext); PDDocument.load(bytes).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) to it.numberOfPages } }.getOrElse { "" to 0 }
        if (pages > 0 && text.trim().length / pages >= 80) return Read(text, notes, false)
        notes += "PDF je naskenované – text čtu OCR v telefonu."
        val tmp = File.createTempFile("vega", ".pdf", context.cacheDir).apply { writeBytes(bytes) }
        return try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val n = minOf(r.pageCount, 20)
                    if (n < r.pageCount) notes += "Přečteno prvních $n z ${r.pageCount} stran."
                    Read((0 until n).joinToString("\n\n") { i -> r.openPage(i).use { p ->
                        val s = 1800f / maxOf(p.width, p.height)
                        val bmp = Bitmap.createBitmap((p.width * s).toInt().coerceAtLeast(1), (p.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        try {
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            "— Strana ${i + 1} —\n" + ocr(bmp)
                        } finally { bmp.recycle() }
                    } }, notes, true)
                }
            }
        } finally { tmp.delete() }
    }

    private fun image(bytes: ByteArray): String {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1; while (maxOf(o.outWidth, o.outHeight) / sample > 2600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ""
        return try { ocr(bmp) } finally { bmp.recycle() }
    }

    private fun ocr(bmp: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS).text } finally { rec.close() }
    }
}

/** LOKÁLNÍ INDEX (SQLite FTS4) nad soubory, které uživatel do VEGA dal – hledání bez sítě a bez modelu. */
/**
 * Lokální fulltext index (SQLite FTS4). Dokument má STABILNÍ ID (docid) oddělené od zobrazovaného jména, takže
 * dva soubory „faktura.pdf“ se nepřepíšou a každý jde samostatně odebrat. Starý index (bez docid) se migruje:
 * docid = jméno. Je to cache – i kdyby migrace selhala, znovu se naplní při práci se soubory.
 */
class LocalIndex(context: Context) : SQLiteOpenHelper(context.applicationContext, "vega-index.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE docs USING fts4(uid, name, body, added, notindexed=uid, notindexed=added, prefix=\"4\", tokenize=unicode61)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            // přenes staré řádky (name, body, added) do nového schématu s docid = name
            val old = runCatching { db.rawQuery("SELECT name, body, added FROM docs", null).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } } }.getOrDefault(emptyList())
            runCatching { db.execSQL("DROP TABLE IF EXISTS docs") }
            onCreate(db)
            db.beginTransaction()
            try { old.forEach { (nm, body, at) -> db.insert("docs", null, ContentValues().apply { put("uid", nm); put("name", nm); put("body", body); put("added", at) }) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
    }

    /** Přidá/nahradí dokument pod stabilním [docId]; [name] je jen pro zobrazení. */
    fun add(docId: String, name: String, text: String) {
        if (text.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("docs", "uid = ?", arrayOf(docId))
            db.insertOrThrow("docs", null, ContentValues().apply {
                put("uid", docId); put("name", name); put("body", text.take(2_000_000)); put("added", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** Zpětně kompatibilní: dokument bez vlastního ID se klíčuje podle jména. */
    fun add(name: String, text: String) = add(name, name, text)

    fun remove(docId: String) = writableDatabase.delete("docs", "uid = ?", arrayOf(docId))

    /** Dokumenty pro správu: (docid, jméno), nejnovější první. */
    fun entries(): List<Pair<String, String>> = readableDatabase.rawQuery("SELECT uid, name FROM docs ORDER BY added DESC", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    fun list(): List<String> = entries().map { it.second }

    /** Celá těla nejrelevantnějších dokumentů pro offline RAG (BM25 nad pasážemi dělá Rag v jádře). */
    fun bodies(query: String, limit: Int): Map<String, String> {
        val words = cz.axiom.vega.core.Rag.queryWords(query) // bez „do/kdy/je…“ – jinak by záloha „kterékoli slovo“ našla všechno
        if (words.isEmpty()) return emptyMap()
        val terms = words.map { if (it.length >= 4) "${it.take(6)}*" else it }
        fun run(q: String) = runCatching {
            readableDatabase.rawQuery("SELECT name, body FROM docs WHERE docs MATCH ? LIMIT ?", arrayOf(q, limit.toString())).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
        }.getOrDefault(emptyMap())
        return run(terms.joinToString(" ")).ifEmpty { if (terms.size > 1) run(terms.joinToString(" OR ")) else emptyMap() }
    }

    fun search(query: String, limit: Int): List<Snippet> {
        val words = cz.axiom.vega.core.Rag.queryWords(query) // bez „do/kdy/je…“ – jinak by záloha „kterékoli slovo“ našla všechno
        if (words.isEmpty()) return emptyList()
        val terms = words.map { if (it.length >= 4) "${it.take(6)}*" else it }
        fun run(q: String) = runCatching {
            // snippet nad sloupcem body (index 2: docid,name,body,…)
            readableDatabase.rawQuery("SELECT rowid, name, snippet(docs, '«', '»', '…', 2, 24) FROM docs WHERE docs MATCH ? LIMIT ?", arrayOf(q, limit.toString())).use { c ->
                buildList { while (c.moveToNext()) add(Snippet(c.getLong(0), c.getString(1), c.getString(2), "lokální index")) }
            }
        }.getOrDefault(emptyList())
        // nejdřív všechna slova, když nic – kterékoli
        return run(terms.joinToString(" ")).ifEmpty { if (terms.size > 1) run(terms.joinToString(" OR ")) else emptyList() }
    }
}
