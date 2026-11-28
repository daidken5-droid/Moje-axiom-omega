package cz.axiom.vega

import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Společná testovací pomůcka pro celou cestu zprávy (refaktoring 25. 11.): dřív 34 ručních kopií čekací smyčky
 * v 19 souborech, každá s jiným limitem a většina při vypršení času tiše pokračovala (test pak padal jinde nebo
 * dostal „(žádná odpověď)“ jako by to byla odpověď). Tady: jedno čekání, které při vypršení času SELŽE s popisem.
 */
class OpsHarness(
    airgap: Boolean = true,
    /** Souhlas s odesláním do cloudu / schválení akce se odklepne automaticky (cesta s modelem). */
    autoConsent: Boolean = false,
    make: (onEvent: (String, JSONObject) -> Unit) -> Ops = { cb -> Ops(RuntimeEnvironment.getApplication(), emit = cb) },
) {
    val ev = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    val o: Ops

    init {
        lateinit var ops: Ops
        ops = make { t, d -> ev += t to d; if (autoConsent && (t == "consent" || t == "approve")) ops.answer(d.getString("req"), JSONObject().put("ok", true)) }
        ops.settings.airgap = airgap; ops.settings.webAllowed = false
        o = ops
    }

    /** Odpověď jedné zprávy: poslední zpráva VEGA a případná chyba (událost „error“). */
    class Reply(val msg: JSONObject?, val error: String?) {
        val text: String get() = msg?.optString("text") ?: ""
        val card: JSONObject? get() = msg?.optJSONObject("card")
        override fun toString() = error?.let { "CHYBA $it" } ?: text
    }

    fun mark() = ev.size
    fun since(n: Int) = ev.drop(n)

    /** Počká na událost [type] po značce [n]; když nepřijde do [ms], test selže (ne tiché pokračování). */
    fun await(n: Int, type: String = "idle", ms: Long = 20_000): List<Pair<String, JSONObject>> = awaitAny(n, setOf(type), ms)
    /** Počká na kteroukoli z událostí [types] (např. „done“ nebo „idle“ po zastavení). */
    fun awaitAny(n: Int, types: Set<String>, ms: Long = 20_000): List<Pair<String, JSONObject>> {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (since(n).any { it.first in types }) return since(n); Thread.sleep(20) }
        throw AssertionError("událost ${types.joinToString("/") { "„$it“" }} nepřišla do ${ms / 1000} s; přišlo: ${since(n).map { it.first }}")
    }

    fun replyAfter(n: Int, type: String = "idle", ms: Long = 20_000): Reply {
        val evs = await(n, type, ms)
        return Reply(evs.lastOrNull { it.first == "message" }?.second?.optJSONObject("msg"), evs.firstOrNull { it.first == "error" }?.second?.optString("text"))
    }

    /** Pošle zprávu do chatu a počká na konec zpracování. */
    fun say(chat: String, text: String, ids: List<String> = emptyList(), ms: Long = 20_000): Reply { val n = mark(); o.chat(chat, text, ids); return replyAfter(n, ms = ms) }

    companion object {
        /** NDEF zpráva s jedním textovým záznamem (jako cizí NFC štítek). */
        fun ndefText(text: String, lang: String = "cs"): ByteArray {
            val payload = byteArrayOf(lang.length.toByte()) + lang.toByteArray() + text.toByteArray()
            require(payload.size < 256) { "krátký záznam (SR)" }
            return byteArrayOf(0xD1.toByte(), 1, payload.size.toByte(), 'T'.code.toByte()) + payload
        }
    }
}
