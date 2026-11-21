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
        val bottom = (h - m).toFloat()
        for ((lay, isHead) in blocks) {
            val extra = if (isHead) 14f else 2f
            if (isHead && y > m) y += 8f
            // odstavec delší než zbytek (i celá) stránky se rozdělí po řádcích přes stránky – nic se neořízne
            var line = 0
            while (line < lay.lineCount) {
                if (y >= bottom - 1f && y > m) newPage()
                val top = lay.getLineTop(line)
                var end = line
                while (end < lay.lineCount && y + (lay.getLineBottom(end) - top) <= bottom) end++
                if (end == line) { if (y > m) { newPage(); continue } else end = line + 1 } // aspoň jeden řádek, i když je vyšší než stránka
                val sliceTop = lay.getLineTop(line); val sliceBottom = lay.getLineBottom(end - 1)
                val c = page!!.canvas
                c.save(); c.clipRect(m.toFloat(), y, (w - m).toFloat(), y + (sliceBottom - sliceTop)); c.translate(m.toFloat(), y - sliceTop); lay.draw(c); c.restore()
                y += (sliceBottom - sliceTop); line = end
            }
            y += extra
        }
        doc.finishPage(page!!)
        val o = java.io.ByteArrayOutputStream(); doc.writeTo(o); doc.close()
        return o.toByteArray()
    }
}
