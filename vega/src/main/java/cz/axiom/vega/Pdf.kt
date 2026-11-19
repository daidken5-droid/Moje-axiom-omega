package cz.axiom.vega

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** PDF z textu dokumentu (A4, české znaky, „# “ = nadpis, „**…**“ tučně odstraněno, „- “ = odrážka). */
object Pdf {
    fun build(@Suppress("UNUSED_PARAMETER") context: Context, text: String): ByteArray {
        val doc = PdfDocument()
        val w = 595; val h = 842; val m = 56
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Color.BLACK; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val blocks = text.replace("**", "").lines().map { l ->
            val isHead = l.startsWith("# ")
            val t = if (isHead) l.removePrefix("# ") else if (l.startsWith("- ")) "•  " + l.removePrefix("- ") else l
            StaticLayout.Builder.obtain(t.ifEmpty { " " }, 0, t.ifEmpty { " " }.length, if (isHead) head else body, w - 2 * m)
                .setAlignment(if (isHead) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1.12f).build() to isHead
        }
        var page: PdfDocument.Page? = null; var y = 0f; var n = 0
        fun newPage() { page?.let { doc.finishPage(it) }; n++; page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n).create()); y = m.toFloat() }
        newPage()
        for ((lay, isHead) in blocks) {
            val extra = if (isHead) 14f else 2f
            if (y + lay.height + extra > h - m) newPage()
            val c = page!!.canvas
            if (isHead) y += 8f
            c.save(); c.translate(m.toFloat(), y); lay.draw(c); c.restore()
            y += lay.height + extra
        }
        doc.finishPage(page!!)
        val o = java.io.ByteArrayOutputStream(); doc.writeTo(o); doc.close()
        return o.toByteArray()
    }
}
