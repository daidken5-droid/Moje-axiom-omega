package cz.axiom.vega.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Typ hodnoty, kterou si nástroje předávají (výstup jednoho = vstup dalšího). */
enum class Kind(val label: String) {
    TEXT("text"), TABLE("tabulka"), JSON("JSON"), BYTES("soubor"), URL("adresa"), DIFF("rozdíl"), NDEF("NFC záznam"), ANY("cokoli")
}

/** Hodnota v řetězci nástrojů. Každý mezivýsledek jde otevřít (preview) a uložit (serialize). */
sealed class Value {
    abstract val kind: Kind
    /** Krátký náhled pro kartu výsledku (první obrazovka). */
    abstract fun preview(max: Int = 600): String
    /** Text pro předání nástroji, který čte text (CSV export tabulky, URL, …). */
    abstract fun asText(): String

    data class Text(val text: String, val name: String? = null) : Value() {
        override val kind = Kind.TEXT
        override fun preview(max: Int) = text.take(max)
        override fun asText() = text
    }

    data class Table(val headers: List<String>, val rows: List<List<String>>, val notes: List<String> = emptyList(), val name: String? = null) : Value() {
        override val kind = Kind.TABLE
        override fun preview(max: Int) = (listOf(headers) + rows.take(8)).joinToString("\n") { it.joinToString(" | ") }.take(max) +
            if (rows.size > 8) "\n… (${rows.size} řádků)" else ""
        override fun asText() = Csv.write(this, ';')
        fun col(name: String) = headers.indexOfFirst { it.equals(name, true) }
    }

    data class Json(val json: JsonElement) : Value() {
        override val kind = Kind.JSON
        override fun preview(max: Int) = pretty.encodeToString(JsonElement.serializer(), json).take(max)
        override fun asText() = pretty.encodeToString(JsonElement.serializer(), json)
    }

    class Bytes(val name: String, val bytes: ByteArray, val mime: String? = null) : Value() {
        override val kind = Kind.BYTES
        override fun preview(max: Int) = "$name · ${bytes.size} B"
        /** Text souboru: UTF-8, nebo Windows-1250 (české exporty z bank a účetnictví). */
        override fun asText(): String = Encoding.decode(bytes)
    }

    data class Url(val url: String) : Value() {
        override val kind = Kind.URL
        override fun preview(max: Int) = url
        override fun asText() = url
    }

    data class DiffV(val lines: List<DiffLine>, val added: Int, val removed: Int) : Value() {
        override val kind = Kind.DIFF
        override fun preview(max: Int) = "+$added −$removed\n" + Diff.unified(lines, 2).take(max)
        override fun asText() = Diff.unified(lines, 3)
    }

    data class Ndef(val records: List<NdefRecord>) : Value() {
        override val kind = Kind.NDEF
        override fun preview(max: Int) = records.joinToString("\n") { "${it.typeLabel}: ${it.display}" }.take(max)
        override fun asText() = records.joinToString("\n") { it.display }
    }

    companion object {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class) // prettyPrintIndent: jen formát náhledu, ne uložená data
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /** Serializace pro uložení mezivýsledků (obnova po pádu, otevření kroku). Bajty base64. */
        fun toJson(v: Value): JsonObject = when (v) {
            is Text -> buildJsonObject { put("k", "text"); put("text", v.text); v.name?.let { put("name", it) } }
            is Table -> buildJsonObject {
                put("k", "table"); v.name?.let { put("name", it) }
                put("headers", buildJsonArray { v.headers.forEach { add(JsonPrimitive(it)) } })
                put("rows", buildJsonArray { v.rows.forEach { r -> add(buildJsonArray { r.forEach { add(JsonPrimitive(it)) } }) } })
                put("notes", buildJsonArray { v.notes.forEach { add(JsonPrimitive(it)) } })
            }
            is Json -> buildJsonObject { put("k", "json"); put("json", v.json) }
            is Bytes -> buildJsonObject { put("k", "bytes"); put("name", v.name); v.mime?.let { put("mime", it) }; put("b64", java.util.Base64.getEncoder().encodeToString(v.bytes)) }
            is Url -> buildJsonObject { put("k", "url"); put("url", v.url) }
            is DiffV -> buildJsonObject { put("k", "diff"); put("text", Diff.unified(v.lines, 1_000_000)); put("added", v.added); put("removed", v.removed) }
            is Ndef -> buildJsonObject { put("k", "ndef"); put("records", buildJsonArray { v.records.forEach { add(it.toJson()) } }) }
        }

        fun fromJson(o: JsonObject): Value {
            fun s(k: String) = o[k]?.jsonPrimitive?.content
            return when (s("k")) {
                "text" -> Text(s("text") ?: "", s("name"))
                "table" -> Table(o["headers"]!!.jsonArray.map { it.jsonPrimitive.content }, o["rows"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.content } },
                    o["notes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), s("name"))
                "json" -> Json(o["json"]!!)
                "bytes" -> Bytes(s("name") ?: "soubor", java.util.Base64.getDecoder().decode(s("b64") ?: ""), s("mime"))
                "url" -> Url(s("url") ?: "")
                "diff" -> Diff.parseUnified(s("text") ?: "")
                "ndef" -> Ndef(o["records"]!!.jsonArray.map { NdefRecord.fromJson(it.jsonObject) })
                else -> Text(o.toString())
            }
        }

        fun list(o: JsonArray) = o.map { fromJson(it.jsonObject) }
    }
}

/** Kódování textových souborů: UTF-8 (i s BOM), jinak Windows-1250 – typické u českých bankovních exportů. */
object Encoding {
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        // UTF-16 (Excel „Text v kódování Unicode“, některé exporty bank): BOM FF FE / FE FF, nebo bez BOM s nulami v každém druhém bajtu
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        if (b.size >= 8) { val n = minOf(b.size, 400); val oddZero = (1 until n step 2).count { b[it] == 0.toByte() }; val evenZero = (0 until n step 2).count { b[it] == 0.toByte() }
            if (oddZero > n / 4 && evenZero == 0) return String(b, Charsets.UTF_16LE)
            if (evenZero > n / 4 && oddZero == 0) return String(b, Charsets.UTF_16BE) }
        val dec = Charsets.UTF_8.newDecoder()
        return try { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() } catch (e: java.nio.charset.CharacterCodingException) { String(b, charset("windows-1250")) }
    }

    fun isUtf8(b: ByteArray) = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)); true }.getOrDefault(false)
}
