package cz.axiom.vega.core

/**
 * Protokol virtuální karty VEGA přes ISO-DEP / APDU (NFC Forum, ISO/IEC 7816-4). Čistý Kotlin, testovatelný bez telefonu.
 *
 * Rozsah: VÝHRADNĚ vlastní profily vybavení VEGA (např. „Vrtačka Bosch, inv. č. 00123“). Žádné platební, přístupové
 * ani jiné autentizační karty – ty tento protokol neumí číst ani vydávat. Identita profilu je JEN v jeho aplikačních
 * datech (co uživatel označil ke sdílení); UID telefonu se nepoužívá.
 *
 * Telefon A (karta) = HostApduService, který volá [process]. Telefon B (čtečka) = IsoDep, který pošle [SELECT] a [READ].
 *
 * APDU:
 *  - SELECT by AID: `00 A4 04 00 Lc <AID> 00` → `9000` (+ 1 bajt verze protokolu) pokud je karta aktivní, jinak `6A82`.
 *  - READ:          `80 B0 00 00 00`         → `<data> 9000`, nebo `6A82` (není aktivní karta).
 *  - cokoli jiného  → `6D00` (neznámý příkaz). Vadná/krátká zpráva → `6700`.
 */
object CardApdu {
    /** Proprietární AID: 0xF0 + „VEGA“ (56 45 47 41) + verze 01. Kategorie „other“, mimo registrované RID → nekoliduje. */
    val AID = byteArrayOf(0xF0.toByte(), 0x56, 0x45, 0x47, 0x41, 0x01)
    const val VERSION = 0x01
    const val MAX_PAYLOAD = 480 // bezpečně pod typickým ISO-DEP rámcem

    // stavová slova (SW1 SW2)
    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NO_CARD = byteArrayOf(0x6A, 0x82.toByte())       // není aktivní karta / nenalezeno
    val SW_UNKNOWN = byteArrayOf(0x6D, 0x00)                // neznámá instrukce
    val SW_WRONG_LEN = byteArrayOf(0x67, 0x00)              // špatná délka / vadná zpráva
    val SW_ERR = byteArrayOf(0x6F, 0x00)                    // obecná chyba

    fun selectApdu(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
    fun readApdu(): ByteArray = byteArrayOf(0x80.toByte(), 0xB0.toByte(), 0x00, 0x00, 0x00)

    private fun isSelectCmd(a: ByteArray) = a.size >= 5 && a[0].toInt() and 0xFF == 0x00 && a[1].toInt() and 0xFF == 0xA4 && a[2].toInt() and 0xFF == 0x04
    private fun selectsOurAid(a: ByteArray) = isSelectCmd(a) && a.size >= 5 + (a[4].toInt() and 0xFF) && a.copyOfRange(5, 5 + (a[4].toInt() and 0xFF)).contentEquals(AID)
    private fun isRead(a: ByteArray) = a.size >= 4 && a[0].toInt() and 0xFF == 0x80 && a[1].toInt() and 0xFF == 0xB0

    /**
     * Reakce karty na příkaz. [activePayload] = data aktivní karty (null = žádná aktivní karta, nebo deaktivovaná).
     * Vždy vrátí korektní APDU odpověď zakončenou stavovým slovem – i na nesmysl (žádné vyhození výjimky do NFC vrstvy).
     */
    fun process(apdu: ByteArray?, activePayload: ByteArray?): ByteArray = when {
        apdu == null || apdu.size < 4 -> SW_WRONG_LEN
        selectsOurAid(apdu) -> if (activePayload != null) byteArrayOf(VERSION.toByte()) + SW_OK else SW_NO_CARD
        isSelectCmd(apdu) -> SW_NO_CARD // SELECT jiné aplikace – není to naše karta
        isRead(apdu) -> activePayload?.let { (if (it.size > MAX_PAYLOAD) it.copyOf(MAX_PAYLOAD) else it) + SW_OK } ?: SW_NO_CARD
        else -> SW_UNKNOWN
    }

    /** Rozdělí odpověď čtečky na data a stavové slovo. */
    fun splitSw(resp: ByteArray): Pair<ByteArray, Int> =
        if (resp.size < 2) ByteArray(0) to 0 else resp.copyOf(resp.size - 2) to (((resp[resp.size - 2].toInt() and 0xFF) shl 8) or (resp[resp.size - 1].toInt() and 0xFF))

    const val SW_OK_INT = 0x9000
    const val SW_NO_CARD_INT = 0x6A82

    // --- obsah karty: verze + UTF-8 „název \u001F veřejnýId \u001F k=v \u001F …“ (jen sdílená pole) ---
    data class Card(val name: String, val publicId: String, val fields: Map<String, String>)

    fun encode(card: Card): ByteArray {
        val sb = StringBuilder().append(card.name.replace('\u001F', ' ')).append('\u001F').append(card.publicId.replace('\u001F', ' '))
        card.fields.forEach { (k, v) -> sb.append('\u001F').append(k.replace('\u001F', ' ').replace('=', ' ')).append('=').append(v.replace('\u001F', ' ')) }
        val bytes = byteArrayOf(VERSION.toByte()) + sb.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD) { "Karta je moc velká (${bytes.size} B, max $MAX_PAYLOAD) – zkrať údaje." }
        return bytes
    }

    fun decode(payload: ByteArray): Card? {
        if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != VERSION) return null
        val parts = String(payload, 1, payload.size - 1, Charsets.UTF_8).split('\u001F')
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val fields = LinkedHashMap<String, String>()
        parts.drop(2).forEach { p -> val i = p.indexOf('='); if (i > 0) fields[p.substring(0, i)] = p.substring(i + 1) }
        return Card(parts[0], parts.getOrElse(1) { "" }, fields)
    }
}
