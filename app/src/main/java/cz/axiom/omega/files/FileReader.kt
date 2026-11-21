package cz.axiom.omega.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import cz.axiom.omega.core.files.Extract
import cz.axiom.omega.core.hub.HfServices
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AVIOR čte soubory „přirozeně“:
 *  • PDF – text (PdfBox, správná čeština); naskenované PDF → OCR stránek přímo v telefonu (ML Kit),
 *  • obrázky – OCR v telefonu (offline) + volitelně popis obsahu přes Hugging Face (vize),
 *  • zvuk – přepis řeči přes Hugging Face (Whisper),
 *  • Word, Excel, PowerPoint, OpenDocument, EPUB, HTML, RTF, ZIP, text a kód – v jádru (Extract).
 */
class FileReader(private val context: Context, private val hfToken: () -> String?, private val visionModel: () -> String,
                 private val asrModel: () -> String, private val visionOn: () -> Boolean) {

    data class Read(val name: String, val kind: Extract.Kind, val text: String, val notes: List<String>)

    fun read(uri: Uri, progress: (String) -> Unit = {}): Read {
        val cr = context.contentResolver
        val name = runCatching { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "soubor"
        val mime = cr.getType(uri)
        val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
        require(bytes.size <= 150_000_000) { "Soubor je příliš velký (max. 150 MB)." }
        return read(name, mime, bytes, progress)
    }

    fun read(name: String, mime: String?, bytes: Bytes, progress: (String) -> Unit = {}): Read {
        val kind = Extract.kindOf(name, mime, bytes.copyOf(minOf(bytes.size, 16)))
        val notes = mutableListOf<String>()
        val text = when (kind) {
            Extract.Kind.PDF -> pdf(bytes, notes, progress)
            Extract.Kind.IMAGE -> image(bytes, mime ?: "image/jpeg", notes, progress)
            Extract.Kind.AUDIO -> audio(bytes, mime ?: "audio/mpeg", notes, progress)
            else -> Extract.extract(name, mime, bytes)?.also { r -> r.note?.let { notes += it } }?.text ?: ""
        }
        return Read(name, kind, text.trim(), notes)
    }

    // ------------------------------------------------------------------ PDF

    private fun pdf(bytes: ByteArray, notes: MutableList<String>, progress: (String) -> Unit): String {
        progress("Čtu PDF…")
        PDFBoxResourceLoader.init(context.applicationContext)
        val (text, pages) = runCatching {
            PDDocument.load(bytes).use { doc ->
                if (doc.isEncrypted) notes += "PDF je chráněné – čtu, co jde."
                PDFTextStripper().apply { sortByPosition = true }.getText(doc) to doc.numberOfPages
            }
        }.getOrElse { "" to 0 }
        // Naskenované PDF (skoro žádný text) → OCR stránek v telefonu
        val perPage = if (pages > 0) text.trim().length / pages else 0
        if (perPage >= 80) return text
        notes += "PDF je naskenované – text čtu z obrázků stránek (OCR v telefonu)."
        val tmp = File.createTempFile("avior", ".pdf", context.cacheDir).apply { writeBytes(bytes) }
        return try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val n = minOf(r.pageCount, 30)
                    if (r.pageCount > n) notes += "OCR jen prvních $n stran z ${r.pageCount}."
                    (0 until n).joinToString("\n\n") { i ->
                        progress("OCR strana ${i + 1}/$n…")
                        r.openPage(i).use { p ->
                            val scale = 2000f / maxOf(p.width, p.height)
                            val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            "— Strana ${i + 1} —\n" + ocr(bmp).also { bmp.recycle() }
                        }
                    }
                }
            }
        } finally { tmp.delete() }
    }

    // ------------------------------------------------------------------ obrázky

    private fun image(bytes: ByteArray, mime: String, notes: MutableList<String>, progress: (String) -> Unit): String {
        progress("Čtu text z obrázku…")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return "".also { notes += "Obrázek se nepodařilo otevřít." }
        val text = runCatching { ocr(bmp) }.getOrDefault("")
        val sb = StringBuilder()
        if (text.isNotBlank()) sb.append("TEXT NA OBRÁZKU (OCR v telefonu):\n").append(text)
        val token = hfToken()
        if (token != null && visionOn()) {
            progress("Hugging Face popisuje obrázek…")
            runCatching {
                val jpeg = java.io.ByteArrayOutputStream().also { o ->
                    val s = 1280f / maxOf(bmp.width, bmp.height)
                    val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
                    small.compress(Bitmap.CompressFormat.JPEG, 85, o)
                }.toByteArray()
                HfServices.describeImage(token, jpeg, "image/jpeg",
                    "Popiš česky a věcně, co je na obrázku. Pokud jde o dokument, účtenku, tabulku nebo graf, vypiš klíčové údaje a čísla.", visionModel())
            }.onSuccess { sb.append(if (sb.isNotEmpty()) "\n\n" else "").append("POPIS OBRÁZKU (Hugging Face vize):\n").append(it) }
                .onFailure { notes += "Popis přes Hugging Face se nepodařil: ${it.message}" }
        } else if (text.isBlank()) {
            notes += "Na obrázku není text. Pro popis obsahu zadej token Hugging Face (Moduly → Hugging Face služby)."
        }
        bmp.recycle()
        return sb.toString()
    }

    private fun ocr(bmp: Bitmap): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS).text } finally { rec.close() }
    }

    // ------------------------------------------------------------------ zvuk

    private fun audio(bytes: ByteArray, mime: String, notes: MutableList<String>, progress: (String) -> Unit): String {
        val token = hfToken() ?: return "".also { notes += "Přepis řeči potřebuje token Hugging Face (Moduly → Hugging Face služby)." }
        require(bytes.size <= 25_000_000) { "Nahrávka je příliš dlouhá (max. 25 MB)." }
        progress("Přepisuji řeč (Hugging Face Whisper)…")
        return "PŘEPIS NAHRÁVKY:\n" + HfServices.transcribe(token, bytes, mime, asrModel())
    }
}

private typealias Bytes = ByteArray
