package cz.axiom.vega.calc

import cz.axiom.vega.base.Text

/**
 * HOLÝ NÁZEV TÉMATU („operation Prato“, „Projekt Manhattan“, „kauza Dozimetr“) = chce vědět, co to je → rešerše.
 * Konzervativně: 1–6 slov, bez otázkového slova, slovesa pokynu, pozdravu, čísel a povelů.
 */
object Topic {
    private val notTopic = Regex("\\b(co|kdo|kdy|kde|jak|proc|kolik|ktery|ktera|ktere|jaky|jaka|jake|je|jsou|byl|byla|bylo|ma|mam|mas|muze|muzu|lze|bude|chci|potrebuju|" +
        "napis|vytvor|udelej|navrhni|spocitej|vypocitej|preved|preloz|shrn|vysvetli|porovnej|zapni|vypni|nastav|otevri|posli|pust|najdi|hledej|" +
        "ahoj|cau|dik|diky|dekuji|dekuju|ok|okej|super|jasne|dobre|dobry|dobra|ano|ne|test|pomoc|help|hi|hello|thanks|nevim|aha|hmm|jo|" +
        // navazující povely a pozdravy (bez historie by z nich vznikla rešerše o slově „pokračuj“)
        "pokracuj|pokracovat|dal|dale|rozved|rozvin|zopakuj|znovu|zkrat|prodluz|anglicky|cesky|nemecky|slovensky|prelozit|souhlasim|nesouhlasim|presne|spravne|" +
        "zdravim|nashle|nashledanou|dobrou|noc|rano|vecer|cus|cauky|hotovo|stop|konec|dalsi|jeste|vic|mene|kratsi|delsi|priklad|vysvetleni|" +
        // pokyny a zájmena (laik píše „Přepiš to formálněji“, „Změň barvu…“, „Pomoz mi s výpisem“ – to není název tématu)
        "uprav|zmen|prepis|pomoz|pomozte|vylepsi|oprav|zkontroluj|priprav|dej|rekni|ukaz|zjisti|pridej|odeber|smaz|uloz|zapamatuj|zapomen|vrat|zrus|" +
        // oslovení a smalltalk („měj se“, „jsi chytrá?“, „seš tu?“) – není to název tématu
        "jsi|jste|ses|sis|mej|mejte|chytra|chytry|chytre|hloupy|vtipny|zivy|nazivu|tady|tu|funguje|slysis|vidis|" +
        "zformuluj|preformuluj|naformatuj|seradi|serad|vyber|doplni|dopln|napoveda|poradi|porad|to|tohle|toto|tenhle|tahle|mi|me|mne|muj|moje|meho|tvuj|nas|vas|" +
        // slovenská otázková slova, hovorové díky, slovní matematika a spojky celé věty („myslím, že…“ není název tématu)
        "kolko|preco|kto|kedy|ako|aky|aka|ake|dakujem|dik\\w*|dekuj\\w*|thx|krat|deleno|plus|minus|odmocnin\\w*|procent\\w*|ze|aby|protoze|kdyz|kdyby|jestli|zda|myslim|myslis|" +
        // povely aplikaci („spusť testy“, „exportuj do excelu“) – bez výsledku z nich nesmí vzniknout rešerše na webu
        "ulozit|exportuj|exportovat|export|spust|spustit|testy|stahni|stahnout|vytiskni|tiskni|sdilej|posli)\\b")
    private val bad = Regex("[0-9+*/=<>@#{}\\[\\]|\\\\]")

    fun bare(prompt: String): String? {
        val t = prompt.trim().trimEnd('?', '!', '.', '…', ' ').trim('"', '„', '“', '\'', ' ')
        // „Apollo 11“, „Windows 11“, „F-16“: číslo na konci názvu je povolené
        val named = Regex("^[\\p{L}][\\p{L} .-]{0,40}[ -]\\d{1,4}$").matches(t)
        if (t.length !in 3..60 || t.startsWith("/") || (bad.containsMatchIn(t) && !named) || '\n' in t) return null
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size !in 1..6 || (words.size == 1 && words[0].length < 3)) return null
        if (notTopic.containsMatchIn(Text.fold(t).lowercase())) return null
        return t
    }
}
