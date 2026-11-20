package cz.axiom.vega.core

/**
 * WiFi analyzátor: z nasbíraných sítí (telefon je přečte systémovým skenem) spočítá kanály, sílu signálu,
 * typ zabezpečení a zahlcení – a hlavně UPOZORNÍ na slabé zabezpečení VLASTNÍ sítě. Jen čtení okolí
 * (pasivní sken, který dělá i systém), žádné připojování, rušení ani útoky. Čistý Kotlin, testovatelný.
 */
object WifiScan {
    data class Net(val ssid: String, val bssid: String, val rssi: Int, val freqMhz: Int, val caps: String)

    data class Seen(val ssid: String, val rssi: Int, val band: String, val channel: Int, val security: String, val weak: String?, val mine: Boolean)
    data class Report(val nets: List<Seen>, val channels24: Map<Int, Int>, val channels5: Map<Int, Int>, val advice: List<String>)

    /** Kanál z frekvence v MHz (2,4 a 5 GHz; 6 GHz orientačně). */
    fun channel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }

    fun band(freq: Int): String = when {
        freq < 2500 -> "2,4 GHz"
        freq < 5955 -> "5 GHz"
        else -> "6 GHz"
    }

    /** Typ zabezpečení z řetězce schopností (capabilities) a varování, pokud je slabé. */
    fun security(caps: String): Pair<String, String?> {
        val c = caps.uppercase()
        return when {
            "SAE" in c && ("WPA2" in c || "RSN" in c && "PSK" in c) -> "WPA2/WPA3" to null
            "SAE" in c -> "WPA3" to null
            "WPA2" in c || ("RSN" in c && ("PSK" in c || "EAP" in c)) -> "WPA2" to null
            "WPA" in c -> "WPA" to "Starší WPA (TKIP) jde prolomit – přepni router na WPA2/WPA3."
            "WEP" in c -> "WEP" to "WEP se dá prolomit za pár minut – okamžitě přepni na WPA2/WPA3."
            "OWE" in c -> "Otevřená (šifrovaná OWE)" to null
            else -> "Otevřená" to "Síť bez hesla – kdokoli se připojí a může odposlouchávat. Nastav heslo (WPA2/WPA3)."
        }.let { (s, w0) ->
            val w = w0 ?: if ("WPS" in c) "Zapnuté WPS usnadňuje prolomení PINu – v routeru ho vypni." else null
            s to w
        }
    }

    fun analyze(nets: List<Net>, mySsid: String? = null, myBssid: String? = null): Report {
        val norm = { s: String? -> s?.trim()?.trim('"')?.ifBlank { null } }
        val mine = norm(mySsid)
        val seen = nets.filter { it.freqMhz > 0 }.map { n ->
            val (sec, weak) = security(n.caps)
            Seen(norm(n.ssid) ?: "(skrytá síť)", n.rssi, band(n.freqMhz), channel(n.freqMhz), sec, weak,
                mine = (myBssid != null && n.bssid.equals(myBssid, true)) || (mine != null && norm(n.ssid) == mine))
        }.sortedByDescending { it.rssi }
        fun congestion(b: String) = seen.filter { it.band == b && it.channel > 0 }.groupingBy { it.channel }.eachCount()
        val ch24 = congestion("2,4 GHz"); val ch5 = congestion("5 GHz")

        val advice = ArrayList<String>()
        val myNets = seen.filter { it.mine }
        myNets.firstOrNull()?.let { m ->
            m.weak?.let { advice += "⚠ Tvoje síť „${m.ssid}“: $it" }
            if (m.band == "2,4 GHz" && seen.any { it.mine && it.band == "5 GHz" }.not() && seen.any { it.band == "5 GHz" })
                advice += "Tvoje síť je jen na 2,4 GHz. Pokud router umí 5 GHz, bude tam rychlejší a méně rušená (kratší dosah)."
            if (m.band == "2,4 GHz") {
                val best = listOf(1, 6, 11).minByOrNull { ch -> (ch24[ch] ?: 0) + (ch24[ch - 1] ?: 0) + (ch24[ch + 1] ?: 0) }
                val cur = (ch24[m.channel] ?: 1)
                if (best != null && best != m.channel && cur > 1) advice += "Na 2,4 GHz je tvůj kanál ${m.channel} sdílený s dalšími sítěmi – zkus v routeru kanál $best (méně zahlcený)."
            }
        }
        if (myNets.isEmpty() && mine != null) advice += "Síť „$mine“ jsem v okolí nenašel – zkontroluj, že je zapnutá a jsi v dosahu."
        seen.count { it.weak != null }.let { if (it > 0) advice += "V okolí je $it ${if (it == 1) "síť se slabým" else "sítí se slabým"} zabezpečením. U cizích s tím nic nedělej – řeš jen svoji." }
        if (advice.isEmpty()) advice += "Nic kritického – zabezpečení i kanál vypadají v pořádku."
        advice += "Pozn.: VEGA jen pasivně čte, co sítě samy vysílají. Nepřipojuje se a do žádné nezasahuje."
        return Report(seen, ch24, ch5, advice)
    }
}
