package cz.axiom.vega.core

/**
 * Rozbor NFC štítku z údajů, které telefon umí přečíst (UID, seznam technologií, u NFC-A hodnoty SAK a ATQA).
 * Jen ČTENÍ a pojmenování – žádné klonování ani emulace. Čistý Kotlin, testovatelný bez telefonu.
 *
 * Záměrně nerozlišuje konkrétní zabezpečené karty kvůli zneužití; určuje rodinu čipu a co s ním VEGA umí
 * (přečíst NDEF, přečíst veřejné stránky paměti), aby uživatel věděl, co drží v ruce.
 */
object NfcTag {
    data class Info(
        val uid: String,            // hex, velká písmena, oddělené mezerou po bajtech
        val uidLen: Int,
        val family: String,         // lidské jméno rodiny čipu
        val standard: String,       // ISO/IEC norma
        val techs: List<String>,    // krátké názvy technologií (NfcA, IsoDep, …)
        val note: String,           // co s tím VEGA umí / na co pozor
    )

    /** Krátký název technologie z plného názvu třídy (android.nfc.tech.NfcA → NfcA). */
    fun shortTech(fqcn: String) = fqcn.substringAfterLast('.')

    fun hexUid(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    /**
     * [techs] = krátké názvy (NfcA, MifareClassic, IsoDep, Ndef, …). [sak]/[atqa] jen u NFC-A (jinak null).
     * [atqa] jako přečtené 2 bajty (little-endian tak, jak je vrací Android: atqa[0] | atqa[1]<<8).
     */
    fun classify(uid: ByteArray, techs: List<String>, sak: Int? = null, atqa: Int? = null): Info {
        val t = techs.toSet()
        val iso14443a = "NfcA" in t
        val iso15693 = "NfcV" in t
        val felica = "NfcF" in t
        val isoDep = "IsoDep" in t

        val (family, standard, note) = when {
            "MifareClassic" in t ->
                Triple("MIFARE Classic", "ISO/IEC 14443-3A", "Starší karta se sektory chráněnými klíči. VEGA přečte jen UID a veřejné NDEF; obsah sektorů nečte ani nekopíruje.")
            "MifareUltralight" in t || (iso14443a && sak == 0x00 && !isoDep) ->
                Triple("MIFARE Ultralight / NTAG", "ISO/IEC 14443-3A", "Běžný přelepovací štítek (NTAG213/215/216). Veřejné stránky paměti a NDEF jdou přečíst.")
            isoDep && iso14443a && sak == 0x20 ->
                Triple("Čipová karta (ISO-DEP)", "ISO/IEC 14443-4 (Type A)", "Platební karta, občanka, DESFire a podobné. VEGA sem nezasahuje – komunikace je šifrovaná a chráněná.")
            isoDep && "NfcB" in t ->
                Triple("Čipová karta (ISO-DEP)", "ISO/IEC 14443-4 (Type B)", "Doklad nebo bezkontaktní karta. VEGA čte jen UID; vnitřek je chráněný.")
            felica ->
                Triple("FeliCa", "JIS X 6319-4", "Karta rozšířená hlavně v Japonsku (doprava, platby). VEGA čte jen identifikátor.")
            iso15693 ->
                Triple("NFC-V (Vicinity)", "ISO/IEC 15693", "Štítek na větší vzdálenost (knihovny, sklad, průmysl). Podle typu jde přečíst veřejná paměť.")
            iso14443a ->
                Triple("NFC-A štítek", "ISO/IEC 14443-3A", "Čip typu A. VEGA přečte UID a případné NDEF.")
            else ->
                Triple("Neznámý NFC štítek", t.joinToString(", ").ifEmpty { "neurčeno" }, "Rozpoznal jsem jen UID a seznam technologií.")
        }
        return Info(hexUid(uid), uid.size, family, standard, techs.map { it }, note +
            (sak?.let { "\nSAK 0x%02X".format(it) } ?: "") + (atqa?.let { ", ATQA 0x%04X".format(it) } ?: ""))
    }
}
