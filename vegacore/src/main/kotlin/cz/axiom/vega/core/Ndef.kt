package cz.axiom.vega.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Jeden NDEF záznam: TNF, typ, obsah a lidsky čitelná podoba (text / URL / MIME). */
data class NdefRecord(val tnf: Int, val type: String, val id: String, val payload: ByteArray, val display: String, val typeLabel: String, val lang: String? = null) {
    /** Hodnota pro předání dalšímu nástroji: URL → Url, text → Text, jinak bajty. */
    fun asValue(): Value = when (typeLabel) {
        // jen http(s) je adresa; „javascript:“, „intent:“, „file:“… ze štítku je jen TEXT (cizí štítek nesmí spustit kód)
        // skryté znaky (U+202E obrací směr textu…) by na displeji ukázaly jinou adresu, než kam vede → jen text s varováním
        "URL" -> if (Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE).matches(display) && !cz.axiom.vega.base.Hidden.has(display) && !display.contains("⟦U+")) Value.Url(display) else Value.Text(display, "NFC (adresa jiného typu)")
        "Text", "MIME text", "Kontakt" -> Value.Text(display, "NFC")
        "WiFi" -> Value.Text(display, "NFC (WiFi štítek – bez hesla)")  // surová data nesou heslo – dál jen popis
        else -> Value.Bytes("ndef-$type.bin", payload)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("tnf", tnf); put("type", type); put("id", id); put("display", display); put("label", typeLabel); lang?.let { put("lang", it) }
        // WiFi štítek nese heslo – do uložených výsledků / historie ho nepouštět (jen zobrazit a zahodit)
        put("b64", if (typeLabel == "WiFi") "" else java.util.Base64.getEncoder().encodeToString(payload))
    }

    companion object {
        fun fromJson(o: JsonObject) = NdefRecord(o["tnf"]!!.jsonPrimitive.content.toInt(), o["type"]!!.jsonPrimitive.content, o["id"]?.jsonPrimitive?.content ?: "",
            java.util.Base64.getDecoder().decode(o["b64"]?.jsonPrimitive?.content ?: ""), o["display"]!!.jsonPrimitive.content, o["label"]!!.jsonPrimitive.content, o["lang"]?.jsonPrimitive?.content)
    }
}

/**
 * Parser NDEF zprávy (NFC Forum NDEF 1.0, RTD Text 1.0, RTD URI 1.0, Smart Poster) – čistý Kotlin, testovatelný
 * na fixture bez telefonu. Telefon předá surové bajty zprávy (Ndef.getCachedNdefMessage().toByteArray()).
 */
object NdefParser {
    class NdefException(msg: String) : Exception(msg)

    private val URI_PREFIX = listOf("", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
        "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:",
        "urn:epc:", "urn:nfc:")

    private val TNF = listOf("Prázdný", "Well-known", "MIME", "Absolutní URI", "Externí", "Neznámý", "Nezměněno", "Rezervováno")

    /** Skryté znaky viditelně („⟦U+202E⟧“) + varování – laik uvidí, že adresa není, jak vypadá. */
    private fun revealUri(u: String) = if (!cz.axiom.vega.base.Hidden.has(u)) u else cz.axiom.vega.base.Hidden.reveal(u) + " (adresa obsahuje skryté znaky – neotvírej ji)"

    fun parse(bytes: ByteArray): List<NdefRecord> = parse0(bytes, 0)

    private fun parse0(bytes: ByteArray, depth: Int): List<NdefRecord> {
        if (bytes.isEmpty()) throw NdefException("Prázdná NDEF zpráva")
        val out = ArrayList<NdefRecord>()
        var i = 0
        var guard = 0
        while (i < bytes.size) {
            if (++guard > 256) throw NdefException("Příliš mnoho záznamů")
            val h = bytes[i].toInt() and 0xFF; i++
            val me = h and 0x40 != 0; val cf = h and 0x20 != 0; val sr = h and 0x10 != 0; val il = h and 0x08 != 0; val tnf = h and 0x07
            if (cf) throw NdefException("Dělené (chunked) záznamy nejsou podporované")
            fun need(n: Int) { if (i + n > bytes.size) throw NdefException("Zpráva je useknutá (bajt $i)") }
            need(1); val typeLen = bytes[i].toInt() and 0xFF; i++
            val payloadLen: Int = if (sr) { need(1); (bytes[i].toInt() and 0xFF).also { i++ } } else {
                need(4); val v = ((bytes[i].toLong() and 0xFF) shl 24) or ((bytes[i + 1].toLong() and 0xFF) shl 16) or ((bytes[i + 2].toLong() and 0xFF) shl 8) or (bytes[i + 3].toLong() and 0xFF)
                i += 4; if (v > 10_000_000) throw NdefException("Nesmyslná délka obsahu ($v B)"); v.toInt()
            }
            val idLen = if (il) { need(1); (bytes[i].toInt() and 0xFF).also { i++ } } else 0
            need(typeLen); val type = String(bytes, i, typeLen, Charsets.US_ASCII); i += typeLen
            need(idLen); val id = String(bytes, i, idLen, Charsets.US_ASCII); i += idLen
            need(payloadLen); val payload = bytes.copyOfRange(i, i + payloadLen); i += payloadLen
            out += record(tnf, type, id, payload, depth)
            if (me) break
        }
        return out
    }

    private fun record(tnf: Int, type: String, id: String, p: ByteArray, depth: Int): NdefRecord = when {
        tnf == 1 && type == "T" -> {
            if (p.isEmpty()) throw NdefException("Prázdný textový záznam")
            val status = p[0].toInt() and 0xFF
            val langLen = status and 0x3F
            val utf16 = status and 0x80 != 0
            if (1 + langLen > p.size) throw NdefException("Poškozený textový záznam")
            val lang = String(p, 1, langLen, Charsets.US_ASCII)
            val text = String(p, 1 + langLen, p.size - 1 - langLen, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
            NdefRecord(tnf, type, id, p, text, "Text", lang)
        }
        tnf == 1 && type == "U" -> {
            if (p.isEmpty()) throw NdefException("Prázdný URI záznam")
            val code = p[0].toInt() and 0xFF
            val uri = revealUri(URI_PREFIX.getOrElse(code) { "" } + String(p, 1, p.size - 1, Charsets.UTF_8))
            NdefRecord(tnf, type, id, p, uri, "URL")
        }
        // Smart Poster uvnitř Smart Posteru norma nepovoluje – nerozbalovat (dřív rekurze až do přetečení zásobníku)
        tnf == 1 && type == "Sp" && depth > 0 -> NdefRecord(tnf, type, id, p, "(vnořený Smart Poster, ${p.size} B – nerozbaluji)", "Smart Poster")
        tnf == 1 && type == "Sp" -> {
            // Smart Poster: vnořená zpráva (URI + titulek)
            val inner = runCatching { parse0(p, depth + 1) }.getOrDefault(emptyList())
            val uri = inner.firstOrNull { it.typeLabel == "URL" }?.display ?: ""
            val title = inner.firstOrNull { it.typeLabel == "Text" }?.display
            NdefRecord(tnf, type, id, p, if (title != null) "$uri ($title)" else uri.ifEmpty { inner.firstOrNull()?.display ?: "(prázdný Smart Poster)" }, if (uri.isNotEmpty() && title == null) "URL" else "Smart Poster")
        }
        // WiFi štítek „klepni a připoj se“ (Wi-Fi Simple Config): název sítě a zabezpečení; HESLO se nezobrazí ani neuloží
        tnf == 2 && type.equals("application/vnd.wfa.wsc", true) -> wsc(p)?.let { (ssid, auth) ->
            NdefRecord(tnf, type, id, p, "WiFi síť „$ssid“ ($auth)${if (auth != "otevřená") " – heslo je uložené na štítku (nezobrazuji ho)" else ""}", "WiFi") }
            ?: NdefRecord(tnf, type, id, p, "${p.size} B dat ($type)", "MIME")
        // vizitka: jméno · telefon · e-mail · firma místo syrového „BEGIN:VCARD…“
        tnf == 2 && (type.equals("text/vcard", true) || type.equals("text/x-vcard", true)) -> {
            val raw = String(p, Charsets.UTF_8)
            fun f(key: String) = Regex("(?im)^$key(?:;[^:\\r\\n]*)?:(.+)$").findAll(raw).map { it.groupValues[1].trim().replace("\\,", ",").replace("\\;", ";") }.filter { it.isNotBlank() }.toList()
            val name = f("FN").firstOrNull() ?: f("N").firstOrNull()?.split(';')?.filter { it.isNotBlank() }?.reversed()?.joinToString(" ")
            val parts = listOfNotNull(name, f("ORG").firstOrNull()?.replace(';', ' ')?.trim()) + f("TEL") + f("EMAIL")
            NdefRecord(tnf, type, id, p, if (parts.isEmpty()) raw else "Kontakt: " + parts.joinToString(" · "), if (parts.isEmpty()) "MIME text" else "Kontakt")
        }
        tnf == 2 -> {
            val textual = type.startsWith("text/") || type == "application/json" || type.endsWith("+xml") || type == "text/vcard"
            NdefRecord(tnf, type, id, p, if (textual) String(p, Charsets.UTF_8) else "${p.size} B dat ($type)", if (textual) "MIME text" else "MIME")
        }
        tnf == 3 -> NdefRecord(tnf, type, id, p, type, "URL")
        tnf == 4 -> NdefRecord(tnf, type, id, p, if (p.all { it in 0x20..0x7E }) String(p, Charsets.US_ASCII) else "${p.size} B", "Externí ($type)")
        tnf == 0 -> NdefRecord(tnf, type, id, p, "(prázdný záznam)", "Prázdný")
        else -> NdefRecord(tnf, type, id, p, "${p.size} B", TNF.getOrElse(tnf) { "?" })
    }

    /** Wi-Fi Simple Config (TLV: 2 B typ, 2 B délka): Credential 0x100E → SSID 0x1045, Auth 0x1003. Vrací (SSID, zabezpečení) bez hesla. */
    private fun wsc(p: ByteArray): Pair<String, String>? {
        fun tlv(b: ByteArray, from: Int, to: Int): Map<Int, ByteArray> { val m = HashMap<Int, ByteArray>(); var i = from
            while (i + 4 <= to) { val t = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); val l = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                if (i + 4 + l > to) break; m.putIfAbsent(t, b.copyOfRange(i + 4, i + 4 + l)); i += 4 + l }
            return m }
        val cred = tlv(p, 0, p.size)[0x100E] ?: return null
        val inner = tlv(cred, 0, cred.size)
        val ssid = inner[0x1045]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() } ?: return null
        val auth = inner[0x1003]?.takeIf { it.size == 2 }?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        val a = when { auth == null -> "zabezpečená"; auth == 0x0001 -> "otevřená"; auth and 0x0020 != 0 -> "WPA2"; auth and 0x0002 != 0 -> "WPA"; else -> "zabezpečená" }
        return ssid to a
    }

    /** Sestavení zprávy (pro testy a fixture): krátké záznamy, MB/ME správně. */
    fun build(records: List<Triple<Int, String, ByteArray>>): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        records.forEachIndexed { k, (tnf, type, payload) ->
            var h = tnf
            if (k == 0) h = h or 0x80
            if (k == records.lastIndex) h = h or 0x40
            val short = payload.size < 256
            if (short) h = h or 0x10
            o.write(h); o.write(type.length)
            if (short) o.write(payload.size) else { o.write(payload.size ushr 24); o.write(payload.size ushr 16 and 255); o.write(payload.size ushr 8 and 255); o.write(payload.size and 255) }
            o.write(type.toByteArray(Charsets.US_ASCII)); o.write(payload)
        }
        return o.toByteArray()
    }

    /** Lidský souhrn štítku pro chat: rodina, UID, norma, technologie, poznámka + případné NDEF záznamy. */
    fun summary(info: NfcTag.Info, records: List<NdefRecord>): String = buildString {
        append("**${info.family}**\n")
        append("- UID: `${info.uid}` (${info.uidLen} B)\n")
        append("- Norma: ${info.standard}\n")
        append("- Technologie: ${info.techs.joinToString(", ")}\n")
        if (records.isEmpty()) append("- NDEF: žádný čitelný záznam\n")
        else { append("- NDEF (${records.size}):\n"); records.take(10).forEach { append("    • ${it.typeLabel}: ${it.display.take(200)}\n") } }
        append("\n").append(info.note)
    }

    fun textPayload(text: String, lang: String = "cs"): ByteArray = byteArrayOf(lang.length.toByte()) + lang.toByteArray() + text.toByteArray(Charsets.UTF_8)
    fun uriPayload(uri: String): ByteArray {
        val (code, rest) = URI_PREFIX.withIndex().drop(1).filter { uri.startsWith(it.value) }.maxByOrNull { it.value.length }?.let { it.index to uri.removePrefix(it.value) } ?: (0 to uri)
        return byteArrayOf(code.toByte()) + rest.toByteArray(Charsets.UTF_8)
    }
}
