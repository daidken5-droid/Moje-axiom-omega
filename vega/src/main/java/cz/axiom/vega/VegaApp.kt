package cz.axiom.vega

import android.app.Application
import org.json.JSONObject

/** Aplikace drží jednu instanci [Ops] – přežije otočení obrazovky; události jdou vždy do aktuální aktivity. */
class VegaApp : Application() {
    @Volatile private var sink: ((String, JSONObject) -> Unit)? = null
    private var instance: Ops? = null

    /** Zničená aktivita už události nedostává (a nedrží se v paměti přes odkaz v sink). */
    @Synchronized fun detach(s: (String, JSONObject) -> Unit) { if (sink === s) sink = null }

    /** Bez aktivity (alarm ranního přehledu): stávající instance, nebo nová; události jdou do aktivity, až nějaká bude. */
    @Synchronized fun opsHeadless(): Ops = instance ?: create()

    @Synchronized
    fun ops(sink: (String, JSONObject) -> Unit): Ops {
        this.sink = sink
        return instance ?: create()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun create(): Ops = Ops(this) { t, d -> this.sink?.invoke(t, d) }.also { o ->
        instance = o
        WorkService.isWorking = { o.busy }
        // práce delší než chvilka → služba v popředí (výpočet za 50 ms žádné oznámení neukáže)
        o.onWork = { working -> if (working) main.postDelayed({ if (o.busy) WorkService.start(this) }, 700) }
    }
}
