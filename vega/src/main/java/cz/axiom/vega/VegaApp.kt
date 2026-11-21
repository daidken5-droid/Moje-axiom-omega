package cz.axiom.vega

import android.app.Application
import org.json.JSONObject

/** Aplikace drží jednu instanci [Ops] – přežije otočení obrazovky; události jdou vždy do aktuální aktivity. */
class VegaApp : Application() {
    @Volatile private var sink: ((String, JSONObject) -> Unit)? = null
    private var instance: Ops? = null

    /** Zničená aktivita už události nedostává (a nedrží se v paměti přes odkaz v sink). */
    @Synchronized fun detach(s: (String, JSONObject) -> Unit) { if (sink === s) sink = null }

    @Synchronized
    fun ops(sink: (String, JSONObject) -> Unit): Ops {
        this.sink = sink
        return instance ?: Ops(this) { t, d -> this.sink?.invoke(t, d) }.also { instance = it }
    }
}
