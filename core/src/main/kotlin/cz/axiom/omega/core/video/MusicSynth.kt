package cz.axiom.omega.core.video

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * TICHÁ HUDBA KÓDEM (žádné vzorky, žádné stahování): pomalá smyčka 4 akordů (Am–F–C–G) v měkkých
 * trojúhelníkových tónech, sinusový bas, jemný puls na dobu, 1 s náběh a 2 s doznění, špička na čtvrtině
 * plného rozsahu – titulky zůstávají v popředí. Deterministické (stejná délka → stejný zvuk).
 */
object MusicSynth {
    const val RATE = 44_100
    const val PEAK = 0.25

    private val CHORDS = listOf(
        doubleArrayOf(220.00, 261.63, 329.63), // Am
        doubleArrayOf(174.61, 220.00, 261.63), // F
        doubleArrayOf(261.63, 329.63, 392.00), // C
        doubleArrayOf(196.00, 246.94, 293.66), // G
    )

    private fun tri(phase: Double): Double { val p = phase - Math.floor(phase); return 4 * abs(p - 0.5) - 1 }

    /** 16bit mono PCM přesně [seconds] dlouhé (počet vzorků = seconds × RATE). */
    fun render(seconds: Double, bpm: Int = 80): ShortArray {
        require(bpm in 70..90) { "tempo 70–90 BPM" }
        val n = (seconds * RATE).toInt()
        val buf = DoubleArray(n)
        val beat = 60.0 / bpm
        val bar = beat * 4
        val edge = 0.35 // prolnutí akordů (bez lupnutí)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val barIdx = (t / bar).toInt()
            val inBar = t - barIdx * bar
            val chord = CHORDS[barIdx % CHORDS.size]
            val next = CHORDS[(barIdx + 1) % CHORDS.size]
            // měkké přechody: konec taktu se prolne do dalšího akordu
            val mix = if (inBar > bar - edge) ((inBar - (bar - edge)) / edge) else 0.0
            val w = 0.5 - 0.5 * Math.cos(PI * mix)
            var pad = 0.0
            for (f in chord) pad += (1 - w) * tri(f * t)
            for (f in next) pad += w * tri(f * t)
            pad /= 3
            val bassF = (if (w > 0.5) next[0] else chord[0]) / 2
            val bass = sin(2 * PI * bassF * t)
            // puls na dobu: krátký měkký úder (sinus, exponenciální doznění)
            val inBeat = t % beat
            val pulse = sin(2 * PI * bassF * 2 * t) * exp(-inBeat / 0.12)
            buf[i] = 0.55 * pad + 0.30 * bass + 0.15 * pulse
        }
        val max = buf.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        val fadeIn = RATE * 1.0
        val fadeOut = RATE * 2.0
        val out = ShortArray(n)
        for (i in 0 until n) {
            var g = PEAK / max
            if (i < fadeIn) g *= i / fadeIn
            val left = n - 1 - i
            if (left < fadeOut) g *= left / fadeOut
            out[i] = (buf[i] * g * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** PCM little-endian (vstup AAC kodéru). */
    fun bytes(pcm: ShortArray): ByteArray {
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { val v = pcm[i].toInt(); b[2 * i] = (v and 255).toByte(); b[2 * i + 1] = (v shr 8 and 255).toByte() }
        return b
    }

    /** WAV soubor (pro test / export samotné hudby). */
    fun wav(pcm: ShortArray): ByteArray {
        val data = bytes(pcm)
        val o = java.io.ByteArrayOutputStream(44 + data.size)
        fun i32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
        fun i16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
        o.write("RIFF".toByteArray()); i32(36 + data.size); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(data.size); o.write(data)
        return o.toByteArray()
    }
}
