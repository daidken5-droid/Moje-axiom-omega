package cz.axiom.omega

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.video.Explainer
import cz.axiom.omega.video.VideoMaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** VIDEO VYSVĚTLENÍ na skutečném Androidu (MediaCodec H.264 + AAC + MediaMuxer): soubor má obraz, zvuk a správnou délku. */
@RunWith(AndroidJUnit4::class)
class VideoOnDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rendersPlayableMp4WithMusic() {
        val src = "Fotosyntéza je biochemický proces, při kterém rostliny, řasy a sinice přeměňují světelnou energii na energii chemických vazeb. " +
            "Probíhá v chloroplastech, které obsahují zelené barvivo chlorofyl. Při fotosyntéze vzniká kyslík a glukóza z oxidu uhličitého a vody."
        val b = Explainer.fromSource("Fotosyntéza", src, "Wikipedie – Fotosyntéza")!!
        val r = VideoMaker(ctx).make(b, CancelToken()) { _, _ -> }
        try {
            val ex = MediaExtractor(); ex.setDataSource(r.mp4.path)
            val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            ex.release()
            assertTrue("stopy: $mimes", mimes.contains(MediaFormat.MIMETYPE_VIDEO_AVC))
            assertTrue("stopy: $mimes", mimes.contains(MediaFormat.MIMETYPE_AUDIO_AAC))
            val mmr = MediaMetadataRetriever(); mmr.setDataSource(r.mp4.path)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val frame = mmr.getFrameAtTime(3_000_000)
            mmr.release()
            assertEquals(720, w)
            assertTrue("délka $ms ms, storyboard ${b.seconds} s", kotlin.math.abs(ms - b.seconds * 1000L) < 600)
            assertNotNull("snímek ve 3. s", frame)
            assertTrue(r.qa.isEmpty()); assertTrue(!r.silent); assertNotNull(r.stills)
            println("CRUCIBLE video: ${b.seconds} s · ${r.frames} snímků vyrobeno za ${r.millis} ms · ${r.mp4.length() / 1024} kB · stopy $mimes (emulátor, softwarový kodér)")
        } finally { r.mp4.delete() }
    }
}
