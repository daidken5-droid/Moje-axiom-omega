package cz.axiom.vega

import com.topjohnwu.superuser.Shell

/**
 * Volitelný root přes libsu (topjohnwu). Běžný chod VEGA na rootu NEZÁVISÍ – root se žádá jen na výslovné klepnutí.
 *
 * Privilegované operace jsou JEN pojmenované metody s pevným příkazem. Dotazy uživatele, text z NFC ani výstup
 * modelu se do shellu nikdy nedostanou. Do AI kontextu jde nanejvýš strukturovaný výsledek ověření (ok/ne).
 */
object RootAccess {
    @Volatile private var allowed = false   // „vypnuto“ zabrání obnovení relace do dalšího výslovného ověření

    init {
        // bez automatického spouštění su při startu; shell se vytvoří až na vyžádání
        runCatching { Shell.enableVerboseLogging = false; Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(15)) }
    }

    data class Result(val ok: Boolean, val message: String)

    /** Asynchronní ověření pevným příkazem „id -u“. [cb] se zavolá na libsu vlákně – volající si přepne na UI. */
    fun verify(cb: (Result) -> Unit) {
        allowed = true
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(r: Result) { if (done.compareAndSet(false, true)) cb(r) }
        // tvrdý časový strop: když uživatel v Magisku nepotvrdí
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish(Result(false, "Ověření vypršelo – v Magisku nepřišlo potvrzení. Zkus to znovu.")) }, 20_000)
        try {
            Shell.cmd("id -u").submit { out ->
                val line = out.out.joinToString("").trim()
                finish(when {
                    out.code == 0 && line == "0" -> Result(true, "Root ověřen (id -u = 0).")
                    out.code != 0 -> Result(false, "Root zamítnut nebo nedostupný (Magisk odmítl, nebo telefon není rootnutý).")
                    else -> Result(false, "Root se nepodařilo ověřit.")
                })
            }
        } catch (e: Throwable) { finish(Result(false, "Root se nepodařilo ověřit (${e.message ?: e.javaClass.simpleName}).")) }
    }

    /** Ukončí privilegovanou relaci a zabrání jejímu obnovení do dalšího výslovného ověření. */
    fun disable() {
        allowed = false
        runCatching { Shell.getCachedShell()?.close() }
    }

    fun isAllowed() = allowed
}
