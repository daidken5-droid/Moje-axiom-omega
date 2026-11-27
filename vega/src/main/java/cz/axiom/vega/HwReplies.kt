package cz.axiom.vega

import cz.axiom.vega.base.Hidden
import org.json.JSONArray
import org.json.JSONObject

/**
 * Texty odpovědí sekce Hardware (refaktoring 8, noc 27./28. 11. – vyjmuto z Ops.kt; doporučení T1).
 * Čisté funkce bez stavu: Ops jen předá data a odešle. Cizí názvy (BLE, WiFi, karta) jdou přes [Hidden].
 * Strážný snímek přesného textu: OpsHwSnapshotTest.
 */
object HwReplies {
    const val BLE_NONE = "Žádné BLE zařízení v dosahu. Zapni hledání na periferii (párovací režim) a zkus to znovu."
    const val WIFI_NONE = "Nenašel jsem žádné WiFi sítě. Zapni WiFi a polohu (sken WiFi ji na Androidu vyžaduje) a zkus to znovu."

    /** Aktuální WiFi připojení; [at] = čas měření (už naformátovaný). */
    fun wifiLink(o: JSONObject, at: String): String {
        // SSID i ostatní pole jsou text zvenku (název sítě si nastaví kdokoli) – jeden řádek, bez skrytých znaků
        fun v(k: String, unit: String = "") = o.opt(k)?.takeIf { it != JSONObject.NULL && it.toString().isNotBlank() }?.let { Hidden.oneLine(it.toString(), 64) + unit } ?: "—"
        return buildString {
            append("**Aktuální WiFi připojení** · změřeno $at\n")
            append("- Síť (SSID): ${v("ssid")}\n- Zabezpečení: ${v("security")}\n- Signál: ${v("rssi", " dBm")} (${v("quality")})\n")
            append("- Frekvence: ${v("freq", " MHz")} (${v("band")})\n- Linková rychlost: ${v("link", " Mb/s")}\n- IP: ${v("ip")}\n")
            if (o.optBoolean("stale")) append("\n⚠ Údaje jsou z posledního skenu, ne živé.")
            append("\n_(měřeno lokálně; funguje i bez internetu)_")
        }
    }

    /** BLE sken: tabulka nejvýš 25 zařízení (název, adresa, odhad vzdálenosti podle RSSI). */
    fun ble(arr: JSONArray): String = buildString {
        append("**Bluetooth zařízení v okolí: ${arr.length()}**\n\n| Název | Adresa | Signál |\n|---|---|---|\n")
        (0 until arr.length()).map { arr.getJSONObject(it) }.take(25).forEach { o ->
            val r = o.optInt("rssi"); val q = when { r >= -60 -> "blízko"; r >= -75 -> "střed"; else -> "daleko" }
            append("| ${Hidden.cell(o.optString("name"))} | `${Hidden.cell(o.optString("addr"), 24)}` | $q ($r dBm) |\n")
        }
        append("\n_(jen hledání a síla signálu; připojení a čtení GATT služeb je další krok)_")
    }

    /** WiFi v okolí: rady a tabulka nejvýš 20 sítí. */
    fun wifi(rep: cz.axiom.vega.core.WifiScan.Report): String = buildString {
        append("**WiFi v okolí: ${rep.nets.size} ${cz.axiom.vega.base.Text.pluralWord(rep.nets.size, "síť", "sítě", "sítí")}**\n\n")
        rep.advice.forEach { append("- $it\n") }
        append("\n| Síť | Pásmo | Kanál | Signál | Zabezpečení |\n|---|---|---|---|---|\n")
        rep.nets.take(20).forEach { n ->
            val sig = when { n.rssi >= -55 -> "výborný"; n.rssi >= -67 -> "dobrý"; n.rssi >= -75 -> "slabší"; else -> "slabý" }
            append("| ${Hidden.cell(n.ssid).let { s -> if (n.mine) "**$s** 📍" else s }} | ${n.band} | ${n.channel} | $sig (${n.rssi} dBm) | ${n.security}${if (n.weak != null) " ⚠" else ""} |\n")
        }
    }

    /** Virtuální karta z druhého telefonu: každé pole na jeden řádek bez skrytých znaků (cizí data). */
    fun card(card: cz.axiom.vega.core.CardApdu.Card): String = buildString {
        fun one(s: String) = Hidden.oneLine(s)
        append("**Virtuální karta VEGA přečtena**\n- Název: ${one(card.name)}\n- Veřejný identifikátor: ${one(card.publicId)}\n")
        card.fields.forEach { (k, v) -> append("- ${one(k)}: ${one(v)}\n") }
        append("\n_(data pocházejí z aktivního profilu druhého telefonu, ne z UID)_")
    }
}
