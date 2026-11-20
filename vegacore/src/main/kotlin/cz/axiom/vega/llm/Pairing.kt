package cz.axiom.vega.llm

import cz.axiom.vega.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Párovací kód vlastního serveru (tools/vega-server): `VEGA-PAIR:<base64url {"v":1,"u":adresa,"m":model}>`.
 * Klíč v kódu záměrně NENÍ (kód bývá v logu Space / terminálu) – zadává se zvlášť.
 */
object Pairing {
    data class Pair(val url: String, val model: String)

    /** Srozumitelná hláška, proč vlastní server neodpověděl (usnutý Space, klíč, adresa, síť). */
    fun hint(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val msg = chain.joinToString(" ") { it.message.orEmpty() }
        val code = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code == 401 || code == 403 -> "Server odmítl klíč – v poli Klíč serveru musí být přesně tvůj VEGA_KEY."
            code == 404 -> "Na adrese není OpenAI API – zkontroluj, že adresa končí na /v1."
            code in setOf(502, 503, 504) || msg.contains("<html", true) || chain.any { it is java.net.SocketTimeoutException } ->
                "Server se probouzí nebo startuje (Hugging Face Space po nečinnosti 1–3 min, první stažení modelu i déle) – zkus to za chvíli."
            chain.any { it is java.net.UnknownHostException } -> "Adresu serveru nejde najít – zkontroluj ji (u Hugging Face: https://jmeno-space.hf.space/v1)."
            chain.any { it is java.net.ConnectException } -> "Server neběží nebo není v dosahu (doma: stejná Wi-Fi, správná IP a port 7860)."
            else -> "Server neodpovídá: ${e.message?.substringBefore(" [{")?.take(140)}"
        }
    }

    fun parse(code: String): Pair {
        val raw = code.trim().replace(Regex("\\s+"), "")
        require(raw.startsWith("VEGA-PAIR:")) { "Tohle není párovací kód VEGA (začíná „VEGA-PAIR:“)." }
        val json = try { String(java.util.Base64.getUrlDecoder().decode(raw.removePrefix("VEGA-PAIR:").trimEnd('=')), Charsets.UTF_8) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException("Párovací kód je poškozený – zkopíruj ho celý znovu.") }
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: throw IllegalArgumentException("Párovací kód je poškozený – zkopíruj ho celý znovu.")
        require(o["v"]?.jsonPrimitive?.intOrNull == 1) { "Párovací kód je z novější verze serveru – aktualizuj VEGA." }
        val url = o["u"]?.jsonPrimitive?.content?.trim()?.trimEnd('/').orEmpty()
        val model = o["m"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(url.isNotEmpty() && url.length <= 300) { "V párovacím kódu chybí adresa serveru." }
        require(!url.contains("IP-TVEHO-PC")) { "Server nezná svou adresu v síti – nastav VEGA_PUBLIC_URL (např. http://192.168.1.20:7860) a spusť ho znovu." }
        require(model.length <= 120 && model.all { it.isLetterOrDigit() || it in "-_.:/@" }) { "Neplatný název modelu v párovacím kódu." }
        Http.requireAllowed(url) // https, nebo http jen v lokální síti
        return Pair(url, model)
    }
}
