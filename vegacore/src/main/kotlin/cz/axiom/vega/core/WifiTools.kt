package cz.axiom.vega.core

/**
 * WiFi pomůcky bez sítě: QR kód pro připojení, lokální kontrola síly hesla, generátor přístupové fráze.
 * Čistý Kotlin, testovatelný. POZOR: heslo ani QR payload nesmí volající uložit do historie, logu, AI kontextu
 * ani exportu – jen zobrazit a zahodit. Modul se nikam nepřihlašuje a hesla nezkouší.
 */
object WifiTools {
    /** Escapování pro WIFI: QR dle de-facto standardu (\ ; , : " se uvozují zpětným lomítkem). */
    fun qrEscape(s: String): String = buildString { for (c in s) { if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\'); append(c) } }
    /** Hodnota jen z hex znaků („ABCD1234“) by čtečky (ZXing) četly jako bajty → uzavřít do uvozovek (dle formátu WIFI:). */
    private fun qrValue(s: String) = if (s.isNotEmpty() && s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) "\"$s\"" else qrEscape(s)

    /** `WIFI:T:WPA;S:<ssid>;P:<heslo>;H:<true|false>;;` – otevřená síť: T:nopass a bez P. */
    fun qrPayload(ssid: String, password: String, type: String = "WPA", hidden: Boolean = false): String {
        val t = if (password.isEmpty() || type.equals("nopass", true)) "nopass" else if (type.uppercase().contains("WEP")) "WEP" else "WPA"
        return buildString {
            append("WIFI:T:").append(t).append(";S:").append(qrValue(ssid)).append(';')
            if (t != "nopass") append("P:").append(qrValue(password)).append(';')
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
        val c = check0(pw, ssid)
        // mezera na začátku / konci je platný znak hesla – často ale omylem (zkopírováno s mezerou) → upozornit
        return if (pw.isNotEmpty() && pw != pw.trim() && pw.trim().isNotEmpty()) c.copy(reason = c.reason + " Pozor: na začátku nebo na konci je mezera – je opravdu součástí hesla?") else c
    }
    private val BASE_WORDS = setOf("password", "passwort", "heslo", "admin", "qwerty", "qwertz", "iloveyou", "welcome", "letmein", "internet", "wifi", "sunshine", "monkey",
        "dragon", "master", "football", "princess", "superman", "batman", "pokemon", "minecraft", "abc", "test", "router", "domov", "doma", "praha", "brno", "ahoj", "lasko", "miluju")
    /** „P@ssw0rd“ → „password“ (záměna znaků, kterou slovníky hesel zkoušejí automaticky). */
    private fun unleet(s: String) = s.lowercase().map { when (it) { '@', '4' -> 'a'; '0' -> 'o'; '1', '!' -> 'i'; '3' -> 'e'; '$', '5' -> 's'; '7' -> 't'; else -> it } }.joinToString("")
    private fun check0(pw: String, ssid: String?): Check {
        val p = pw.trim()
        val low = p.lowercase()
        fun r(level: String, reason: String, score: Int) = Check(score, level, reason)
        if (p.length < 8) return r("slabé", "WiFi (WPA2) vyžaduje aspoň 8 znaků – tohle má ${p.length}.", 0)
        // WPA2/WPA3: přístupová fráze 8–63 znaků, jen tisknutelné ASCII – delší nebo s diakritikou router nepřijme / zařízení zadá jinak
        if (pw.length > 63) return r("nepoužitelné", "WiFi heslo může mít nejvýš 63 znaků – tohle má ${pw.length}, router ho nepřijme. Zkrať ho.", 0)
        if (pw.any { it !in ' '..'~' }) return r("riskantní", "Heslo obsahuje znaky mimo základní sadu (diakritika jako „ž“, „ů“, emoji…) – řada routerů, televizí a tiskáren je nepřijme. Použij písmena bez diakritiky, číslice a symboly.", 1)
        if (ssid != null && ssid.isNotBlank() && low.contains(ssid.lowercase()) && ssid.length >= 3)
            return r("slabé", "Heslo obsahuje název sítě „$ssid“ – to útočník zkusí mezi prvními.", 0)
        if (low in COMMON || COMMON.any { low == it }) return r("slabé", "Je to jedno z nejčastějších hesel ze slovníků – prolomí se okamžitě.", 0)
        if (p.all { it == p[0] }) return r("slabé", "Celé heslo je jeden opakovaný znak.", 0)
        // běžné slovo (i „P@ssw0rd“) s číslem / znakem na konci: „Password123!“, „Heslo2024“ – slovníky zkoušejí jako první
        unleet(low.trimEnd { it.isDigit() || it in "!?.#*@\$_-" }.trimStart { it.isDigit() }).let { core -> if (core in BASE_WORDS || COMMON.contains(core))
            return r("slabé", "Je to běžné slovo ze slovníků hesel („$core“) s čísly nebo znakem navíc – útočník takové varianty zkouší mezi prvními.", 0) }
        if (Regex("^(.{2,6})\\1+$").matches(low)) return r("slabé", "Heslo je jeden krátký úsek několikrát za sebou – opakování skoro nepřidá sílu.", 1)
        // 27. 11.: „Password1Password1“, „Heslo-heslo-heslo-heslo“, „aaaaaaaaaaaaaaaaB1“ vycházely „silné“ jen díky délce
        repeatedUnit(low)?.let { u -> return r("slabé", "Heslo se skládá z opakovaného úseku „$u“ – délka je jen zdánlivá, útočník opakování zkouší. Použij různá slova.", 1) }
        if (longestRun(low) * 2 >= low.length) return r("slabé", "Většinu hesla tvoří jeden opakovaný znak – délka je jen zdánlivá.", 1)
        if (isSequential(low)) return r("slabé", "Je to souvislá řada znaků (např. „abcdefgh“ nebo „12345678“) – snadno uhodnutelné.", 0)
        if (isKeyboardWalk(low)) return r("slabé", "Je to tah po klávesnici (např. „qwertz“, „1qaz2wsx“) – takové řady jsou ve slovnících hesel mezi prvními.", 0)
        // jméno / slovo + rok („Monika1990“, „Praha2024!“) – jeden z prvních vzorů, které útočník zkouší
        if (p.length <= 14 && Regex("^\\p{L}{3,10}(19|20)\\d{2}[!.?#*]?$").matches(p))
            return r("slabé", "Slovo nebo jméno s rokem je jeden z prvních vzorů, které se zkoušejí – přidej další nesouvisející slova.", 1)
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

    /** Tah po klávesnici (US i česká QWERTZ, řádky i sloupce): aspoň 70 % hesla tvoří úseky ≥ 4 znaků z řad klávesnice. */
    private val KEYS = listOf("1234567890", "qwertyuiop", "qwertzuiop", "asdfghjkl", "zxcvbnm", "yxcvbnm", "1qaz", "2wsx", "3edc", "4rfv", "5tgb", "6yhn", "6zhn", "7ujm", "8ik", "9ol", "0p")
    private fun isKeyboardWalk(s: String): Boolean {
        if (s.length < 8) return false
        val covered = BooleanArray(s.length)
        for (row in KEYS) for (len in row.length downTo 3) for (st in 0..row.length - len) {
            val seg = row.substring(st, st + len)
            for (target in listOf(seg, seg.reversed())) { var i = s.indexOf(target); while (i >= 0) { for (k in i until i + len) covered[k] = true; i = s.indexOf(target, i + 1) } }
        }
        // sloupcové tahy („1qaz2wsx3edc“) se skládají z krátkých úseků; počítat jen úseky ≥ 3, jinak by běžné slovo vypadalo jako tah
        return covered.count { it } >= s.length * 0.7
    }

    /** Nejkratší úsek, jehož opakováním (po odstranění oddělovačů, s tolerancí 3 znaků navíc na konci) vznikne heslo; jinak null. */
    private fun repeatedUnit(low: String): String? {
        val t = low.filter { it.isLetterOrDigit() }
        if (t.length < 8) return null
        for (p in 2..t.length / 2) {
            var i = p
            while (i < t.length && t[i] == t[i - p]) i++
            if (i >= t.length - 3 && i >= 2 * p) return t.take(p)
        }
        return null
    }

    private fun longestRun(s: String): Int { var best = 0; var cur = 0; for (i in s.indices) { cur = if (i > 0 && s[i] == s[i - 1]) cur + 1 else 1; best = maxOf(best, cur) }; return best }

    private fun isSequential(s: String): Boolean {
        if (s.length < 6) return false
        var up = 0; var down = 0
        for (i in 1 until s.length) { val d = s[i].code - s[i - 1].code; if (d == 1) up++; if (d == -1) down++ }
        return up >= s.length - 2 || down >= s.length - 2
    }

    /**
     * Slova pro přístupovou frázi (česká, bez diakritiky, 3–9 písmen, každé jednou). Dřív 30 slov × 4 = asi 29 bitů:
     * zachycený WPA2 handshake se tím prolomí offline na grafické kartě za minuty – a kontrola hesla to přitom hodnotila „silné“.
     */
    internal val WORDS: List<String> = ("jablko,mesic,reka,hora,kniha,kolo,strom,kveten,kamen,vlak,more,pole,hvezda,lampa,klic,most,zahrada,oblak," +
        "prsten,duha,kotva,majak,sova,liska,bobr,delfin,orel,tygr,panda,jezek,auto,balon,banan,baterka,bota,brana," +
        "brambor,buben,cesta,citron,cukr,dalnice,datel,deka,deska,dort,drak,drevo,dub,dvere,fazole,fena,fialka,flasa," +
        "gauc,guma,hadr,hlava,hlina,hokej,holub,houba,hrad,hrnec,hruska,hudba,chleba,chata,chobot,ibis,indian,jahoda," +
        "jaro,jedle,jelen,jezero,jiskra,kabat,kakao,kanoe,kapr,karta,kaktus,kanec,kelimek,keks,klavir,klobouk,koberec," +
        "kocka,kohout,komin,konev,kopec,koruna,kos,kostel,koza,krab,kravata,kreslo,krokodyl,kruh,kun,kure,kytara,lahev," +
        "lano,lavice,led,lednice,lekar,les,lev,lilie,limonada,lod,lopata,los,louka,luk,lyze,mapa,marmelada,med,meloun," +
        "metla,mlyn,mlha,modrin,morce,motyl,mrak,mrkev,mys,nadrazi,nuzky,obraz,okno,opice,ostrov,ovce,papir,park,pavouk," +
        "pec,pero,pes,pisek,planeta,pohar,pokoj,polstar,postel,potok,prase,prkno,ptak,pustina,rak,raketa,rohlik,ruze," +
        "rybnik,sad,salam,sane,senik,sir,skala,skrin,slon,slunce,snih,sokol,stan,stul,sud,svicka,syr,tabule,talir," +
        "telefon,tesar,ticho,tram,trava,tuzka,ucho,ulice,upir,vana,vazka,vcela,vesta,vidlicka,vino,vitr,voda,volant," +
        "vrana,vrba,zajic,zamek,zebra,zed,zelva,zima,zlato,zub,antena,balkon,barva,bazen,bedna,beruska,blesk,brouk," +
        "bryle,bunda,cibule,cihla,cip,delo,dira,dlan,doupe,draha,dyne,elf,eso,fotka,fronta,garaz,gril,halda,harfa,hasic," +
        "hnizdo,hodiny,hrabe,hrom,hvozd,iglu,jachta,jaguar,jitro,kachna,kajak,kalich,kamion,kapsa,kaple,kasna,kladivo," +
        "klec,klenba,klubko,kompas,koralek,korab,kost,kufr,kuzel,labut,lampion,lebka,lekno,lesk,letadlo,lilek,lipa,lis," +
        "lustr,magnet,malina,mamut,maska,mast,medvid,melodie,mince,mlok,model,motor,mozaika,muflon,musle,naplast,noc," +
        "noha,obal,obelisk,okurka,opera,orloj,osel,palma,papousek,pavlac,perla,piano,pila,pirat,plamen,plast,plot,plyn," +
        "poklad,poleno,pomeranc,pouzdro,pramen,prapor,puma,pytel,radar,ret,rytir,robot,rozinka,ruka,ryba,salat,sardinka," +
        "sekera,semafor,sifon,sklep,slanina,sklo,smrk,sonda,spunt,strecha,struna,sumec,sykorka,tank,taska,tecka,terc," +
        "toulec,trubka,tulipan,udice,uhel,valec,vecer,vejce,vesmir,vez,vlna,vodnik,vozik,vydra,zavod,zebrik,zrcadlo," +
        "zvon").split(',')

    /** Počet slov ve frázi: 6 × log2(360) + symbol + číslo ≈ 60 bitů (WPA2 offline = tisíce let i na grafických kartách). */
    const val PASSPHRASE_WORDS = 6
    private const val SYMBOLS = "!?#%*+-="

    /** Náhodnost fráze v bitech (pro test a případné zobrazení): slova + symbol + dvojciferné číslo 10–99. */
    fun passphraseBits(): Double = PASSPHRASE_WORDS * Math.log(WORDS.size.toDouble()) / Math.log(2.0) + Math.log(SYMBOLS.length * 90.0) / Math.log(2.0)

    /** Náhodná přístupová fráze: 6 českých slov bez diakritiky + symbol + číslo (dobře se opisuje, přitom silná). */
    fun passphrase(rnd: java.util.Random = java.security.SecureRandom()): String {
        val picked = (1..PASSPHRASE_WORDS).map { WORDS[rnd.nextInt(WORDS.size)] }.mapIndexed { i, s -> if (i == 0) s.replaceFirstChar { it.uppercase() } else s }
        return picked.joinToString("-") + SYMBOLS[rnd.nextInt(SYMBOLS.length)] + (10 + rnd.nextInt(90))
    }
}
