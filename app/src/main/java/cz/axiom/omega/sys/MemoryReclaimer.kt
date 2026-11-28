package cz.axiom.omega.sys

import android.app.ActivityManager
import android.content.Context
import cz.axiom.omega.engine.NativeLlama

/**
 * UVOLNĚNÍ PAMĚTI PRO AVIOR – zavření aplikací na pozadí, které berou RAM.
 *
 * Od Androidu 14 smí běžná aplikace ukončit jen své vlastní procesy (killBackgroundProcesses na cizí
 * aplikace nic neudělá). Funkční cesta je přes systém: AVIOR (v popředí, nejvyšší priorita) si na okamžik
 * zabere paměť, Android při jejím nedostatku sám zavře aplikace na pozadí (nižší priorita) a AVIOR paměť
 * hned vrátí. Výsledek = víc volné RAM pro mozek. Pojistky: strop zabrané paměti, nikdy pod hranici
 * systému pro kritický nedostatek, konec, když už není co zavřít.
 */
object MemoryReclaimer {

    data class Result(val beforeMb: Long, val afterMb: Long, val pressedMb: Int, val note: String) {
        val gainedMb get() = (afterMb - beforeMb).coerceAtLeast(0)
    }

    @Volatile var running = false
        private set

    private fun info(c: Context) = ActivityManager.MemoryInfo().also { (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }

    /**
     * @param targetFreeMb kolik volné RAM chceme (Pixel 9a + E4B ≈ 3 500 MB)
     */
    fun reclaim(context: Context, targetFreeMb: Long = 3_500, onStep: (String) -> Unit = {}): Result {
        val start = info(context)
        val before = start.availMem / 1_048_576
        if (before >= targetFreeMb) return Result(before, before, 0, "Volné paměti je dost (${before} MB).")
        if (!NativeLlama.available(context)) return Result(before, before, 0, "Uvolnění paměti potřebuje nativní jádro.")
        if (running) return Result(before, before, 0, "Uvolňování už běží.")
        running = true
        val total = start.totalMem / 1_048_576
        val cap = minOf(2_500L, total * 35 / 100).toInt()
        val floor = start.threshold / 1_048_576          // pod tímto Android začíná kritický režim
        val chunks = ArrayList<Long>()
        var pressed = 0
        var stale = 0
        var lastAvail = before
        try {
            while (pressed < cap) {
                val mi = info(context)
                val avail = mi.availMem / 1_048_576
                if (avail + pressed >= targetFreeMb) break
                if (avail <= floor * 12 / 10) {
                    // tlak je na hranici → počkat, až systém zavře aplikace na pozadí
                    Thread.sleep(600)
                    val after = info(context).availMem / 1_048_576
                    if (after <= lastAvail + 32) { if (++stale >= 3) break } else stale = 0
                    lastAvail = after
                    continue
                }
                val step = if (avail - floor > 1_024) 256 else 128
                val p = NativeLlama.nativeTouchAlloc(step)
                if (p == 0L) break
                chunks += p; pressed += step
                lastAvail = avail
                onStep("Uvolňuji paměť… ${avail} MB volno")
                Thread.sleep(120)
            }
        } finally {
            chunks.forEach { runCatching { NativeLlama.nativeTouchFree(it) } }
            running = false
        }
        Thread.sleep(400)
        val after = info(context).availMem / 1_048_576
        val gained = after - before
        return Result(before, after, pressed,
            if (gained > 50) "Uvolněno ~${gained} MB – Android zavřel aplikace na pozadí. Volno ${after} MB."
            else "Aplikace na pozadí už paměť neblokují (volno ${after} MB).")
    }
}
