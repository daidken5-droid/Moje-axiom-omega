package cz.axiom.vega.core

/** Řádek rozdílu: ' ' stejný, '+' přidaný, '-' odebraný. Čísla řádků v A a B (1…), 0 = není. */
data class DiffLine(val op: Char, val text: String, val a: Int, val b: Int)

/** Rozdíl textů po řádcích (Myersův algoritmus, O((N+M)·D)) – rychlý i na tisíce řádků kódu. */
object Diff {
    fun lines(a: String, b: String): Value.DiffV = compute(split(a), split(b))

    private fun split(s: String) = if (s.isEmpty()) emptyList() else s.replace("\r\n", "\n").removeSuffix("\n").split('\n')

    fun compute(a: List<String>, b: List<String>): Value.DiffV {
        // společný začátek a konec zvlášť (typická úprava kódu mění pár řádků uprostřed)
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf); val bm = b.subList(pre, b.size - suf)
        val mid = myers(am, bm)
        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until pre) out += DiffLine(' ', a[i], i + 1, i + 1)
        var ai = pre; var bi = pre
        for (op in mid) when (op) {
            ' ' -> { out += DiffLine(' ', a[ai], ai + 1, bi + 1); ai++; bi++ }
            '-' -> { out += DiffLine('-', a[ai], ai + 1, 0); ai++ }
            '+' -> { out += DiffLine('+', b[bi], 0, bi + 1); bi++ }
        }
        for (i in 0 until suf) { out += DiffLine(' ', a[ai], ai + 1, bi + 1); ai++; bi++ }
        return Value.DiffV(out, out.count { it.op == '+' }, out.count { it.op == '-' })
    }

    private fun myers(a: List<String>, b: List<String>): List<Char> {
        val n = a.size; val m = b.size
        if (n == 0) return List(m) { '+' }
        if (m == 0) return List(n) { '-' }
        val max = n + m
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[max + k - 1] < v[max + k + 1])) v[max + k + 1] else v[max + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[max + k] = x
                if (x >= n && y >= m) { found = true; break }
                k += 2
            }
            if (found) break
        }
        // zpětný průchod
        val ops = ArrayList<Char>()
        var x = n; var y = m
        for (d in trace.size - 1 downTo 0) {
            val vv = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv[max + k - 1] < vv[max + k + 1])) k + 1 else k - 1
            val prevX = vv[max + prevK]; val prevY = prevX - prevK
            while (x > prevX && y > prevY) { ops += ' '; x--; y-- }
            if (d > 0) { if (x == prevX) ops += '+' else ops += '-' }
            x = prevX; y = prevY
        }
        return ops.reversed()
    }

    /** Unifikovaný výpis s [ctx] řádky kontextu. */
    fun unified(lines: List<DiffLine>, ctx: Int): String {
        if (lines.none { it.op != ' ' }) return "(beze změn)"
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l -> if (l.op != ' ') for (j in maxOf(0, i - ctx)..minOf(lines.size - 1, i + ctx)) keep[j] = true }
        val sb = StringBuilder()
        var i = 0
        while (i < lines.size) {
            if (!keep[i]) { i++; continue }
            var j = i
            while (j < lines.size && keep[j]) j++
            val h = lines.subList(i, j)
            val a0 = h.firstOrNull { it.a > 0 }?.a ?: 0; val b0 = h.firstOrNull { it.b > 0 }?.b ?: 0
            sb.append("@@ -$a0,${h.count { it.op != '+' }} +$b0,${h.count { it.op != '-' }} @@\n")
            h.forEach { sb.append(it.op).append(it.text).append('\n') }
            i = j
        }
        return sb.toString()
    }

    fun parseUnified(text: String): Value.DiffV {
        val out = text.lines().filter { it.isNotEmpty() && !it.startsWith("@@") }.map { DiffLine(it[0], it.substring(1), 0, 0) }
        return Value.DiffV(out, out.count { it.op == '+' }, out.count { it.op == '-' })
    }
}
