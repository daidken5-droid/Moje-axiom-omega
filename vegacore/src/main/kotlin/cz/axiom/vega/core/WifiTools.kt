package cz.axiom.vega.core

/**
 * WiFi pomůcky bez sítě: QR kód pro připojení, lokální kontrola síly hesla, generátor přístupové fráze.
 * Čistý Kotlin, testovatelný. POZOR: heslo ani QR payload nesmí volající uložit do historie, logu, AI kontextu
 * ani exportu – jen zobrazit a zahodit. Modul se nikam nepřihlašuje a hesla nezkouší.
 */
object WifiTools {
    /** Escapování pro WIFI: QR dle de-facto standardu (\ ; , : " se uvozují zpětným lomítkem). */
    fun qrEscape(s: String): String = buildString { for (c in s) { if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\'); append(c) } }

    /** `WIFI:T:WPA;S:<ssid>;P:<heslo>;H:<true|false>;;` – otevřená síť: T:nopass a bez P. */
    fun qrPayload(ssid: String, password: String, type: String = "WPA", hidden: Boolean = false): String {
        val t = if (password.isEmpty() || type.equals("nopass", true)) "nopass" else if (type.uppercase().contains("WEP")) "WEP" else "WPA"
        return buildString {
            append("WIFI:T:").append(t).append(";S:").append(qrEscape(ssid)).append(';')
            if (t != "nopass") append("P:").append(qrEscape(password)).append(';')
            if (hidden) append("H:true;")
            append(';')
        }
    }

    data class Check(val score: Int, val level: String, val reason: String) // score 0..4

    private val COMMON = setOf("password", "heslo", "12345678", "123456789", "1234567890", "qwertyui", "qwertz", "admin123",
        "internet", "nintendo", "football", "superman", "iloveyou", "letmein", "welcome", "starwars", "dragon123", "wifiheslo",
        "hesloheslo", "password1", "aaaaaaaa", "11111111", "00000000", "asdfghjk", "zaq12wsx")

    /** Lokální hodnocení hesla s JEDNOU konkrétní větou proč. Nezkouší se přihlášení, jen rozbor textu. */
    fun checkPassword(pw: String, ssid: String? = null): Check {
        val p = pw.trim()
        val low = p.lowercase()
        fun r(level: String, reason: String, score: Int) = Check(score, level, reason)
        if (p.length < 8) return r("slabé", "WiFi (WPA2) vyžaduje aspoň 8 znaků – tohle má ${p.length}.", 0)
        if (ssid != null && ssid.isNotBlank() && low.contains(ssid.lowercase()) && ssid.length >= 3)
            return r("slabé", "Heslo obsahuje název sítě „$ssid“ – to útočník zkusí mezi prvními.", 0)
        if (low in COMMON || COMMON.any { low == it }) return r("slabé", "Je to jedno z nejčastějších hesel ze slovníků – prolomí se okamžitě.", 0)
        if (p.all { it == p[0] }) return r("slabé", "Celé heslo je jeden opakovaný znak.", 0)
        if (isSequential(low)) return r("slabé", "Je to souvislá řada znaků (např. „abcdefgh“ nebo „12345678“) – snadno uhodnutelné.", 0)
        val classes = listOf(Regex("[a-z]"), Regex("[A-Z]"), Regex("[0-9]"), Regex("[^a-zA-Z0-9]")).count { it.containsMatchIn(p) }
        val score = when {
            p.length >= 16 && classes >= 2 -> 4
            p.length >= 12 && classes >= 3 -> 4
            p.length >= 12 && classes >= 2 -> 3
            p.length >= 10 && classes >= 2 -> 2
            else -> 1
        }
        val level = listOf("slabé", "slabé", "ucházející", "dobré", "silné")[score]
        val reason = when (score) {
            4 -> "Dost dlouhé a pestré – takové heslo je v praxi neprolomitelné hrubou silou."
            3 -> "Slušná délka i pestrost; pro jistotu můžeš přidat pár znaků."
            2 -> "Projde, ale krátké nebo málo pestré – přidej délku a další druh znaku (číslice, symbol)."
            else -> "Krátké a málo pestré – prodluž ho a přidej velká písmena, číslice a symbol."
        }
        return Check(score, level, reason)
    }

    private fun isSequential(s: String): Boolean {
        if (s.length < 6) return false
        var up = 0; var down = 0
        for (i in 1 until s.length) { val d = s[i].code - s[i - 1].code; if (d == 1) up++; if (d == -1) down++ }
        return up >= s.length - 2 || down >= s.length - 2
    }

    private const val WORDS = "jablko,mesic,reka,hora,kniha,kolo,strom,kveten,kamen,vlak,more,pole,hvezda,lampa,klic,most,zahrada,oblak,prsten,duha,kotva,maják,sova,liska,bobr,delfin,orel,tygr,panda,jezek"

    /** Náhodná přístupová fráze: 4 česká slova bez diakritiky + číslo + symbol (dobře se opisuje, přitom silná). */
    fun passphrase(rnd: java.util.Random = java.security.SecureRandom()): String {
        val w = WORDS.split(',')
        val picked = (1..4).map { w[rnd.nextInt(w.size)] }.mapIndexed { i, s -> if (i == 0) s.replaceFirstChar { it.uppercase() } else s }
        val sym = "!?#%*+-=".let { it[rnd.nextInt(it.length)] }
        return picked.joinToString("-") + sym + (10 + rnd.nextInt(90))
    }
}
