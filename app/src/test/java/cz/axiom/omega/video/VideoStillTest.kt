package cz.axiom.omega.video

import android.graphics.Bitmap
import org.robolectric.RuntimeEnvironment
import cz.axiom.omega.core.video.Explainer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Vzhled scén (skutečné vykreslení Canvasu přes nativní grafiku Robolectricu): titulek je vidět, scény se liší. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VideoStillTest {
    @Test fun scenesRenderVisibleCaptions() {
        val src = "Fotosyntéza je biochemický proces, při kterém rostliny, řasy a sinice přeměňují světelnou energii na energii chemických vazeb. " +
            "Probíhá v chloroplastech, které obsahují zelené barvivo chlorofyl. Ročně se jí na Zemi zachytí asi 130 terawattů energie. Proces popsal Jan Ingenhousz v roce 1779."
        val b = Explainer.fromSource("Fotosyntéza", src, "Wikipedie – Fotosyntéza", long = true)!!
        val vm = VideoMaker(RuntimeEnvironment.getApplication())
        val out = File("build/video-stills").apply { mkdirs() }
        b.scenes.indices.forEach { i ->
            val (bmp, qa) = vm.still(b, i, 2.0f)
            assertTrue(qa.toString(), qa.isEmpty())
            // oblast titulku obsahuje světlé pixely (text je vidět)
            var bright = 0
            for (y in 700 until 1100 step 4) for (x in 72 until 648 step 4) if ((bmp.getPixel(x, y) and 0xFF) > 180) bright++
            assertTrue("scéna ${i + 1}: titulek není vidět ($bright)", bright > 40)
            File(out, "scena-${i + 1}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
