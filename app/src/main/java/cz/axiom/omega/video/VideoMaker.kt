package cz.axiom.omega.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.video.MusicSynth
import cz.axiom.omega.core.video.Scene
import cz.axiom.omega.core.video.SceneKind
import cz.axiom.omega.core.video.SceneQa
import cz.axiom.omega.core.video.Storyboard
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Výroba videa V TELEFONU: scény se kreslí na Canvas (jednotný styl AVIOR – černá + titan), převádějí do YUV
 * a kódují hardwarovým H.264; hudba z [MusicSynth] jde do AAC; obojí spojí MediaMuxer do MP4.
 * Žádné ffmpeg, žádný server, nic neodchází z telefonu.
 */
class VideoMaker(private val context: Context) {

    data class Result(
        val mp4: File,
        val stills: ByteArray?,
        /** Nálezy kontroly snímků (text mimo plochu, kontrast); prázdné = vše prošlo. */
        val qa: List<String>,
        val silent: Boolean,
        val millis: Long,
        val frames: Int,
    )

    companion object {
        const val W = 720
        const val H = 1280
        const val FPS = 30
        private const val MARGIN = 72f

        // paleta z UI (app.css): pozadí, text, titan, tlumený
        private const val BG = 0x000000
        private const val TEXT = 0xF1F1F2
        private const val TI = 0xCFD1D5
        private const val METAL = 0xE4E5E8
        private const val MUTED = 0xA3A5AA
        private const val LINE = 0x2C2C30
        private val SIZES = listOf(62f, 56f, 50f, 45f, 40f, 36f, 32f)
        private const val CAPTION_TOP = 760f
        private const val CAPTION_LAST = H - 190f
    }

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansBold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; color = 0xFF000000.toInt() or TEXT }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 24f; color = 0xFF000000.toInt() or MUTED }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; textSize = 24f; letterSpacing = 0.32f; color = 0xFF000000.toInt() or TI }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 30f; color = 0xFF000000.toInt() or TI }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; color = 0xFF000000.toInt() or METAL; textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; color = 0xFF000000.toInt() or METAL }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint().apply { shader = RadialGradient(W / 2f, 470f, 520f, 0xFF18181B.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP) }

    private class Layout(val size: Float, val lines: List<String>)

    /** KONTROLA SNÍMKŮ: titulek musí být celý v ploše (zalomení + zmenšení písma) a čitelný (kontrast ≥ 4,5). */
    private fun layoutAll(b: Storyboard, qa: MutableList<String>): List<Layout> {
        if (SceneQa.contrast(TEXT, BG) < 4.5) qa += "nízký kontrast titulků"
        if (SceneQa.contrast(MUTED, BG) < 4.5) qa += "nízký kontrast popisků"
        val maxW = W - 2 * MARGIN
        return b.scenes.map { s ->
            // poslední účaří titulku musí zůstat nad zápatím (zdroj na H−140)
            val fit = SceneQa.fitBox(s.caption, maxW, CAPTION_LAST - CAPTION_TOP, SIZES) { t, size -> capPaint.textSize = size; capPaint.measureText(t) }
            if (fit == null) {
                qa += "scéna ${s.index}: titulek se nevešel ani nejmenším písmem – zalomen po znacích"
                capPaint.textSize = SIZES.last()
                Layout(SIZES.last(), hardWrap(s.caption, maxW))
            } else Layout(fit.first, fit.second)
        }
    }

    private fun hardWrap(text: String, maxW: Float): List<String> {
        val out = ArrayList<String>(); var cur = StringBuilder()
        for (ch in text) { cur.append(ch); if (capPaint.measureText(cur.toString()) > maxW) { out += cur.dropLast(1).toString(); cur = StringBuilder().append(ch) } }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun ease(x: Float) = (1 - (1 - x.coerceIn(0f, 1f)).let { it * it * it })

    /** Jeden snímek: [t] = čas ve scéně (s), [g] = podíl celého videa 0–1. */
    private fun draw(c: Canvas, b: Storyboard, s: Scene, lay: Layout, t: Float, g: Float) {
        c.drawColor(Color.BLACK)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), glow)
        // záhlaví: značka, téma, číslo scény
        c.drawText("AVIOR", MARGIN, 112f, brandPaint)
        smallPaint.textAlign = Paint.Align.RIGHT
        c.drawText("${s.index} / ${b.scenes.size}", W - MARGIN, 112f, smallPaint)
        smallPaint.textAlign = Paint.Align.LEFT
        val title = ellipsize(b.topic.replaceFirstChar { it.uppercaseChar() }, titlePaint, W - 2 * MARGIN)
        c.drawText(title, MARGIN, 170f, titlePaint)

        val a = ease(t / 0.8f)
        val cx = W / 2f; val cy = 470f
        when (s.kind) {
            SceneKind.QUESTION -> {
                stroke.alpha = 255
                c.drawArc(RectF(cx - 130, cy - 130, cx + 130, cy + 130), -90f, 360f * a, false, stroke)
                bigPaint.textSize = 170f; bigPaint.alpha = (255 * a).toInt()
                c.drawText(s.big ?: "?", cx, cy + 60f, bigPaint)
            }
            SceneKind.NUMBER -> {
                val txt = s.big ?: ""
                var size = 150f; bigPaint.textSize = size
                while (bigPaint.measureText(txt) > W - 2 * MARGIN && size > 50) { size -= 6; bigPaint.textSize = size }
                bigPaint.alpha = (255 * a).toInt()
                c.save(); val sc = 0.86f + 0.14f * a; c.scale(sc, sc, cx, cy)
                c.drawText(txt, cx, cy + size * 0.35f, bigPaint); c.restore()
                fill.color = 0xFF000000.toInt() or LINE
                c.drawRect(cx - 160, cy + 120, cx + 160, cy + 124, fill)
            }
            SceneKind.FACT -> {
                fill.color = 0xFF000000.toInt() or METAL; fill.alpha = (255 * a).toInt()
                val r = 16f + 6f * a
                for (k in -1..1) c.drawCircle(cx + k * 90f, cy, if (k == 0) r * 1.6f else r, fill)
                fill.alpha = 255
                fill.color = 0xFF000000.toInt() or LINE
                c.drawRect(cx - 240 * a, cy + 110, cx + 240 * a, cy + 114, fill)
            }
            SceneKind.ANSWER -> {
                fill.color = 0xFF000000.toInt() or METAL; fill.alpha = (255 * min(1f, a * 1.4f)).toInt()
                c.drawCircle(cx, cy, 120f * (0.7f + 0.3f * a), fill)
                fill.alpha = 255
                val p = Path(); p.moveTo(cx - 52, cy + 4); p.lineTo(cx - 14, cy + 44); p.lineTo(cx + 58, cy - 40)
                val ck = Paint(stroke).apply { color = 0xFF050506.toInt(); strokeWidth = 14f }
                val m = android.graphics.PathMeasure(p, false); val seg = Path(); m.getSegment(0f, m.length * ease((t - 0.3f) / 0.6f), seg, true)
                c.drawPath(seg, ck)
            }
        }
        // titulek: vyjede zespodu a rozsvítí se
        val ca = ease((t - 0.15f) / 0.7f)
        capPaint.textSize = lay.size; capPaint.alpha = (255 * ca).toInt()
        val lh = lay.size * 1.28f
        var y = CAPTION_TOP + (1 - ca) * 28f
        for (line in lay.lines) { c.drawText(line, MARGIN, y, capPaint); y += lh }
        capPaint.alpha = 255
        // zdroj + průběh celého videa
        c.drawText(ellipsize("Zdroj: ${b.source}", smallPaint, W - 2 * MARGIN), MARGIN, H - 140f, smallPaint)
        fill.color = 0xFF000000.toInt() or LINE
        c.drawRoundRect(MARGIN, H - 96f, W - MARGIN, H - 90f, 3f, 3f, fill)
        fill.color = 0xFF000000.toInt() or METAL
        c.drawRoundRect(MARGIN, H - 96f, MARGIN + (W - 2 * MARGIN) * g, H - 90f, 3f, 3f, fill)
        // přechod mezi scénami: krátké zatmění na konci scény
        val left = s.seconds - t
        if (left < 0.35f) { fill.color = Color.BLACK; fill.alpha = (255 * (1 - left / 0.35f)).toInt().coerceIn(0, 255); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), fill); fill.alpha = 255 }
    }

    private fun ellipsize(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    /** Jeden snímek scény [scene] v čase [t] s (náhled, test vzhledu). Vrací i nálezy kontroly. */
    fun still(b: Storyboard, scene: Int, t: Float): Pair<Bitmap, List<String>> {
        val qa = ArrayList<String>()
        val lay = layoutAll(b, qa)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val before = b.scenes.take(scene).sumOf { it.seconds }
        draw(Canvas(bmp), b, b.scenes[scene], lay[scene], t, (before + t) / b.seconds)
        return bmp to qa
    }

    // ------------------------------------------------------------------ výroba

    fun make(b: Storyboard, cancel: CancelToken, progress: (String, Int) -> Unit): Result {
        val started = System.currentTimeMillis()
        val qa = ArrayList<String>()
        val layouts = layoutAll(b, qa)
        val total = b.seconds
        val frames = total * FPS
        val dir = File(context.cacheDir, "video").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val out = File(dir, "explainer-${System.currentTimeMillis()}.mp4")

        // 1) hudba → AAC (selže-li, video bude bez zvuku – a řekneme to)
        progress("Skládám hudbu…", 2)
        val audio = runCatching { encodeAudio(MusicSynth.render(total.toDouble()), cancel) }.getOrElse { if (it is cz.axiom.omega.core.llm.CancelledException) throw it; null }

        // 2) scény → H.264 + spojení
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val argb = IntArray(W * H)
        val yuv = Yuv(W, H)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val stills = ArrayList<Bitmap>()
        var muxStarted = false
        var vTrack = -1; var aTrack = -1
        var audioNext = 0
        val info = MediaCodec.BufferInfo()
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            fun writeAudioUpTo(ptsUs: Long) {
                val a = audio ?: return
                while (audioNext < a.samples.size && a.samples[audioNext].second.presentationTimeUs <= ptsUs) {
                    val (bytes, i) = a.samples[audioNext++]
                    muxer.writeSampleData(aTrack, ByteBuffer.wrap(bytes), i)
                }
            }

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            vTrack = muxer.addTrack(enc.outputFormat)
                            if (audio != null) aTrack = muxer.addTrack(audio.format)
                            muxer.start(); muxStarted = true
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxStarted) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                writeAudioUpTo(info.presentationTimeUs)
                                muxer.writeSampleData(vTrack, buf, info)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            var sceneStart = 0
            var lastKey = ""
            for ((si, s) in b.scenes.withIndex()) {
                val n = s.seconds * FPS
                for (f in 0 until n) {
                    cancel.check()
                    val t = f.toFloat() / FPS
                    val frame = sceneStart + f
                    val g = frame.toFloat() / (frames - 1)
                    // stejný obraz jako minulý snímek (animace doběhla, pruh průběhu stojí) → bez kreslení a převodu
                    val animating = t < 1.6f || s.seconds - t < 0.4f
                    val key = if (animating) "$si/$f" else "$si/${((W - 2 * MARGIN) * g).toInt()}"
                    if (key != lastKey) {
                        draw(canvas, b, s, layouts[si], t, g)
                        bmp.getPixels(argb, 0, W, 0, 0, W, H)
                        yuv.convert(argb)
                        lastKey = key
                    }
                    if (f == min(n - 1, (1.8f * FPS).toInt())) stills += Bitmap.createScaledBitmap(bmp, W / 4, H / 4, true)
                    var idx: Int
                    while (true) { idx = enc.dequeueInputBuffer(10_000); if (idx >= 0) break; drain(false) }
                    val img = enc.getInputImage(idx) ?: error("Kodér nepodporuje vstup obrazu")
                    yuv.writeTo(img)
                    enc.queueInputBuffer(idx, 0, W * H * 3 / 2, frame * 1_000_000L / FPS, 0)
                    drain(false)
                    if (frame % 15 == 0) progress("Vykresluji scénu ${s.index}/${b.scenes.size}…", 5 + (90L * frame / frames).toInt())
                }
                sceneStart += n
            }
            var idx: Int
            while (true) { idx = enc.dequeueInputBuffer(10_000); if (idx >= 0) break; drain(false) }
            enc.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            writeAudioUpTo(Long.MAX_VALUE)
        } finally {
            runCatching { enc.stop() }; runCatching { enc.release() }
            runCatching { if (muxStarted) muxer.stop() }; runCatching { muxer.release() }
            bmp.recycle()
        }
        if (!muxStarted || out.length() < 1000) error("Kodér videa nevrátil žádná data.")
        progress("Ukládám…", 97)
        return Result(out, sheet(stills), qa, audio == null, System.currentTimeMillis() - started, frames)
    }

    /** Přehled snímků (jeden z každé scény) do PNG. */
    private fun sheet(stills: List<Bitmap>): ByteArray? = runCatching {
        if (stills.isEmpty()) return null
        val cols = min(4, stills.size); val rows = (stills.size + cols - 1) / cols
        val w = W / 4; val h = H / 4; val gap = 8
        val out = Bitmap.createBitmap(cols * w + (cols + 1) * gap, rows * h + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(0xFF0C0C0D.toInt())
        stills.forEachIndexed { i, b -> c.drawBitmap(b, (gap + (i % cols) * (w + gap)).toFloat(), (gap + (i / cols) * (h + gap)).toFloat(), null); b.recycle() }
        val o = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.PNG, 100, o); out.recycle()
        o.toByteArray()
    }.getOrNull()

    private class Audio(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    private fun encodeAudio(pcm: ShortArray, cancel: CancelToken): Audio {
        val bytes = MusicSynth.bytes(pcm)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MusicSynth.RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var format: MediaFormat? = null
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
            var pos = 0; var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                cancel.check()
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!; buf.clear()
                        val n = min(buf.remaining() and 1.inv(), bytes.size - pos)
                        val pts = (pos / 2) * 1_000_000L / MusicSynth.RATE
                        if (n <= 0) { enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { buf.put(bytes, pos, n); enc.queueInputBuffer(i, 0, n, pts, 0); pos += n }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = enc.outputFormat
                else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val b = enc.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size)
                        val arr = ByteArray(info.size); b.get(arr)
                        out += arr to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()) }
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { runCatching { enc.stop() }; runCatching { enc.release() } }
        return Audio(format ?: error("AAC bez formátu"), out)
    }

    /** ARGB → YUV 4:2:0 (BT.601, omezený rozsah) a zápis do vstupního Image kodéru (respektuje rozteče rovin). */
    private class Yuv(val w: Int, val h: Int) {
        val y = ByteArray(w * h)
        val u = ByteArray(w * h / 4)
        val v = ByteArray(w * h / 4)

        fun convert(argb: IntArray) {
            var i = 0
            for (r in 0 until h) for (c in 0 until w) {
                val p = argb[i]; val R = p shr 16 and 255; val G = p shr 8 and 255; val B = p and 255
                y[i++] = (((66 * R + 129 * G + 25 * B + 128) shr 8) + 16).toByte()
            }
            var k = 0
            for (r in 0 until h step 2) for (c in 0 until w step 2) {
                val p = argb[r * w + c]; val R = p shr 16 and 255; val G = p shr 8 and 255; val B = p and 255
                u[k] = (((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128).toByte()
                v[k++] = (((112 * R - 94 * G - 18 * B + 128) shr 8) + 128).toByte()
            }
        }

        fun writeTo(img: Image) {
            val pl = img.planes
            plane(pl[0], y, w, h)
            plane(pl[1], u, w / 2, h / 2)
            plane(pl[2], v, w / 2, h / 2)
        }

        private fun plane(p: Image.Plane, src: ByteArray, pw: Int, ph: Int) {
            val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
            if (ps == 1) {
                if (rs == pw) { buf.position(0); buf.put(src, 0, pw * ph) }
                else for (r in 0 until ph) { buf.position(r * rs); buf.put(src, r * pw, pw) }
            } else {
                val lim = buf.limit()
                for (r in 0 until ph) {
                    val base = r * rs; val off = r * pw
                    for (c in 0 until pw) { val at = base + c * ps; if (at < lim) buf.put(at, src[off + c]) }
                }
            }
        }
    }
}
