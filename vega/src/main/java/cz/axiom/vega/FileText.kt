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
                    Read((0 until n).joinToString("\n\n") { i -> r.openPage(i).use { p ->
                        val s = 1800f / maxOf(p.width, p.height)
                        val bmp = Bitmap.createBitmap((p.width * s).toInt(), (p.height * s).toInt(), Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        "— Strana ${i + 1} —\n" + ocr(bmp).also { bmp.recycle() }
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
class LocalIndex(context: Context) : SQLiteOpenHelper(context.applicationContext, "vega-index.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE docs USING fts4(name, body, added, notindexed=added, prefix=\"4\", tokenize=unicode61)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit

    fun add(name: String, text: String) {
        if (text.isBlank()) return
        writableDatabase.delete("docs", "name = ?", arrayOf(name))
        writableDatabase.insert("docs", null, ContentValues().apply { put("name", name); put("body", text.take(2_000_000)); put("added", System.currentTimeMillis()) })
    }

    fun list(): List<String> = readableDatabase.rawQuery("SELECT name FROM docs ORDER BY added DESC", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Celá těla nejrelevantnějších dokumentů pro offline RAG (BM25 nad pasážemi dělá Rag v jádře). */
    fun bodies(query: String, limit: Int): Map<String, String> {
        val words = cz.axiom.vega.base.Text.words(query, 2).distinct().take(8)
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
        val words = cz.axiom.vega.base.Text.words(query, 2).distinct().take(8)
        if (words.isEmpty()) return emptyList()
        val terms = words.map { if (it.length >= 4) "${it.take(6)}*" else it }
        fun run(q: String) = runCatching {
            readableDatabase.rawQuery("SELECT rowid, name, snippet(docs, '«', '»', '…', 1, 24) FROM docs WHERE docs MATCH ? LIMIT ?", arrayOf(q, limit.toString())).use { c ->
                buildList { while (c.moveToNext()) add(Snippet(c.getLong(0), c.getString(1), c.getString(2), "lokální index")) }
            }
        }.getOrDefault(emptyList())
        // nejdřív všechna slova, když nic – kterékoli
        return run(terms.joinToString(" ")).ifEmpty { if (terms.size > 1) run(terms.joinToString(" OR ")) else emptyList() }
    }
}
