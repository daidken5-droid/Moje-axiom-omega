package cz.axiom.vega

import android.app.Application
import org.json.JSONObject

/** Aplikace drží jednu instanci [Ops] – přežije otočení obrazovky; události jdou vždy do aktuální aktivity. */
class VegaApp : Application() {
    @Volatile private var sink: ((String, JSONObject) -> Unit)? = null
    private var instance: Ops? = null

    @Synchronized
    fun ops(sink: (String, JSONObject) -> Unit): Ops {
        this.sink = sink
        return instance ?: Ops(this) { t, d -> this.sink?.invoke(t, d) }.also { instance = it }
    }
}
