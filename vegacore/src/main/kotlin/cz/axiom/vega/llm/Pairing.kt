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
