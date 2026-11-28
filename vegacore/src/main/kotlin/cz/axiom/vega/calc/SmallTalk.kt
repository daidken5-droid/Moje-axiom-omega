package cz.axiom.vega.calc

import cz.axiom.vega.base.Text

/**
 * Běžná konverzační věta, když NENÍ model (Bez sítě / bez klíče): místo „potřebuji model v cloudu“ na „ahoj“ krátká lidská
 * odpověď a co jde i teď. S modelem se nepoužije (odpoví model). null = není to drobná konverzace.
 */
object SmallTalk {
    private const val CAN = "výpočty a procenta („15 % z 2400“, splátka hypotéky), data a dny („kolik dní do Vánoc“), převody jednotek, " +
        "výpisy z banky (přilož CSV), hledání v tvých souborech („najdi v mých souborech …“) a paměť („pamatuj si …“)"

    fun answer(raw: String, airgap: Boolean): String? {
        val t = Text.fold(raw).lowercase().trim().trimEnd('!', '.', '?', ' ', ',').replace(Regex("\\s+"), " ")
        val mode = if (airgap) "Teď běžím v režimu Bez sítě" else "Teď nemám připojený model"
        fun m(vararg p: String) = p.any { Regex("^(?:$it)$").matches(t) }
        return when {
            t.isEmpty() || Regex("^[?.!…\\s]+$").matches(raw.trim()) -> "Napiš, s čím pomoct – třeba „kolik je 15 % z 2400“ nebo přilož výpis z banky."
            m("ahoj\\w*", "cau\\w*", "nazdar", "zdravim", "dobry den", "dobre rano", "dobry vecer", "hej", "hello", "hi", "zdar", "ahoj vego", "ahoj vega", "cus") ->
                "${if (t.startsWith("dobr")) "Dobrý den" else "Ahoj"}! $mode, ale i tak umím: $CAN."
            m("dek\\w*", "dik\\w*", "dikec", "diky moc", "dekuju moc", "dekuji pekne", "super diky", "thx", "thanks") -> "Rádo se stalo."
            m("ok", "okej", "oki", "jo", "jj", "ano", "dobre", "fajn", "super", "parada", "jasne", "rozumim", "ne", "nene", "nic",
                "no nic", "nevadi", "to nevadi", "to nic", "vyborne", "skvele", "bezva", "dobra", "dobra prace", "perfektni", "prima", "tak jo", "tak dobre", "aha", "aha jasne", "chapu", "v pohode", "pohoda", "neva", "to je jedno", "jedno", "zadny problem", "nic dalsiho", "to je vse", "to je vsechno", "uz nic", "zatim nic",
                "\\p{So}+") || Regex("^[\\p{So}\\p{Sk}\\u200d\\ufe0f\\s]+$").matches(raw.trim()) ->
                "Dobře. Kdybys něco potřeboval/a, stačí napsat."
            m("co umis", "co umis delat", "co dokazes", "co vsechno umis", "pomoc", "help", "napoveda", "jak to funguje", "jak te pouzivat", "nevim co delat", "nevim co napsat", "s cim mi pomuzes") ->
                "$mode. I tak umím: $CAN. Rozhovor, psaní textů a překlady potřebují model – ${if (airgap) "vypni Bez sítě klepnutím na „VEGA ▾“ nahoře" else "zapneš ho zdarma v Nastavení"}."
            m("kdo jsi", "co jsi", "kdo jsi ty", "jak se jmenujes") -> "Jsem VEGA – asistent v telefonu. Počítám přesně bez modelu, pracuju s tvými soubory a s modelem v cloudu i píšu a odpovídám na otázky."
            // sobota 28. 11.: otázky uživatele zvyklého na ChatGPT na VEGA samotnou (dřív „potřebuji model v cloudu“, dvě i rešerše na web)
            m("(jsi|si|seš|ses) (lepsi|horsi|chytrejsi|hloupejsi) nez (chatgpt|gpt|claude|gemini|copilot)\\w*", "(cim|v cem) se lisis od (chatgpt|gpt|claude|gemini|copilot)\\w*",
                "(jsi|si) (jako |to )?(chatgpt|gpt|claude|gemini)\\w*", "jaky je rozdil mezi tebou a (chatgpt|gpt|claude|gemini)\\w*") ->
                "Nejsem náhrada ChatGPT – jsem asistent přímo v telefonu. Přesné výpočty, výpisy z banky, tvoje soubory, paměť a ranní přehled zpráv dělám sám, i bez internetu. Volné psaní, překlady a rozhovor dělá jazykový model, který si vybereš (v cloudu nebo vlastní server). $mode."
            m("kdo te (vytvoril|udelal|naprogramoval|stvoril|vyrobil)", "kdo je tvuj (autor|tvurce|vyrobce)", "odkud jsi") ->
                "Jsem VEGA, aplikace v tvém telefonu. Výpočty a práci se soubory dělám vlastním kódem bez modelu; texty a odpovědi na volné otázky píše jazykový model, který si vybereš v Nastavení."
            m("jsi (umela inteligence|ai|robot|clovek|chatbot|zivy|ziva|skutecny clovek)", "jsi realny clovek") ->
                "Nejsem člověk. Část věcí dělám přesnými výpočty přímo v telefonu (bez modelu), na volný text používám jazykový model."
            m("(fungujes|funguje to|jde to|umis pracovat|pracujes|umis neco) bez (internetu|site|signalu|pripojeni)", "potrebujes internet", "potrebujes (pripojeni|sit)") ->
                "Ano, bez internetu umím: $CAN. Volné psaní, překlady a rozhovor potřebují model – v cloudu přes internet, nebo vlastní server v domácí síti."
            m("(posilas|odesilas|sdilis|prodavas|nahravas) (moje |me )?(data|udaje|zpravy|soubory|fotky)( nekam| nekomu| na internet)?", "kam (posilas|odchazi|jdou) (moje )?(data|zpravy)",
                "vidi nekdo moje (zpravy|data|soubory)", "kdo vidi moje (zpravy|data|soubory)") ->
                "V režimu Bez sítě neodchází nic. S modelem v cloudu mu odejde jen tvoje otázka a přílohy, které k ní přidáš; placený model použiju až po tvém schválení ceny. Hesla a čísla karet z dřívějších zpráv do další otázky neposílám (nahradím je „[skryto]“). Chaty, paměť a soubory zůstávají v telefonu."
            m("kolik (to )?(stoji|stojis|stojite)", "(je to|jsi) zdarma", "platim (za )?(to|tebe)", "musim (za to |za tebe )?platit", "je to placene") ->
                "Výpočty, výpisy, soubory, paměť, ranní přehled i Hardware jsou zdarma a běží v telefonu. Model v cloudu může být zdarma (např. bezplatná verze Gemini); placený použiju jen po tvém schválení ceny."
            m("jaky (model|mozek|jazykovy model|ai) (pouzivas|mas|jsi|bezi)", "na jakem modelu (bezis|jedes)", "jaky model") ->
                if (airgap) "Teď žádný – je zapnutý režim Bez sítě, odpovídám jen místními nástroji v telefonu. Model vybereš po vypnutí Bez sítě (nahoře „VEGA ▾“)." else "Teď nemám připojený žádný model – zapneš ho zdarma v Nastavení."
            m("pamatujes si (nase |me |moje )?(minule|predchozi|starsi|stare)? ?(konverzace|rozhovory|chaty|zpravy)", "pamatujes si me", "co si o mne pamatujes", "pamatujes si veci") ->
                "Pamatuju si, co mi výslovně řekneš („pamatuj si, že …“) – přehled a mazání v menu ☰ › Paměť. Chaty zůstávají v telefonu, dokud je nesmažeš."
            m("umis (cesky|anglicky|slovensky|nemecky)", "mluvis (cesky|anglicky|slovensky)", "rozumis (cesky|slovensky)") ->
                "Ano – čeština je můj hlavní jazyk, rozumím i slovensky. Odpovědi v jiných jazycích a překlady píše model."
            m("proc (mi )?(neodpovidas|neumis odpovedet|neodpovis) (na )?(vsechno|vse|tohle|to)", "proc mi neodpovidas", "proc to neumis") ->
                "$mode – bez modelu odpovídám jen na to, co umím spočítat nebo najít přesně (abych nic nevymýšlel). Na volné otázky potřebuji model: ${if (airgap) "vypni Bez sítě klepnutím na „VEGA ▾“ nahoře" else "zapneš ho zdarma v Nastavení"}."
            m("jak te (mam )?pouzivat", "jak s tebou (mam )?(pracovat|mluvit|komunikovat)", "jak zacit", "co ti mam napsat") ->
                "Napiš, co potřebuješ, jako bys psal/a člověku – třeba „kolik je 15 % z 2400“, „kolik dní do Vánoc“, nebo přilož výpis z banky či PDF a napiš „shrň“. $mode."
            // povely, jak je píše uživatel ChatGPT (lomítka, angličtina) – dřív rešerše na web nebo „potřebuji model“
            m("/?(novy|nova|nove) (chat|konverzace|rozhovor|vlakno)", "/new", "new chat", "zacni znovu", "zacit znovu", "zacneme znovu", "reset", "/reset", "od zacatku") ->
                "Nový chat založíš v menu ☰ vlevo nahoře → „＋ Nová konverzace“. Tenhle zůstane uložený v seznamu."
            m("/help", "/\\?", "\\?", "what can you do", "what can you do for me", "help me", "how does it work", "how do i use (it|you|this)") ->
                "$mode. I tak umím: $CAN. Rozhovor, psaní textů a překlady potřebují model – ${if (airgap) "vypni Bez sítě klepnutím na „VEGA ▾“ nahoře" else "zapneš ho zdarma v Nastavení"}."
            m("/clear", "clear", "clear chat", "/delete") -> "Konverzaci smažeš v menu ☰: u jejího názvu klepni na ⋯ → Smazat."
            m("stop", "zastav", "zastav to", "prestan", "dost", "/stop") -> "Teď nic nezpracovávám. Když odpovídám, tlačítko vpravo od pole se změní na „Zastavit“ – jedním klepnutím odpověď ukončíš."
            m("jak se mas", "jak se mas dnes", "jak se dnes mas", "jak je", "jak se vede", "jak to jde", "jak se mate") -> "Díky, běžím. S čím pomůžu?"
            m("co je noveho", "co je noveho ve svete", "zpravy", "novinky", "dnesni zpravy", "zpravy ze sveta", "co se deje", "co se deje ve svete",
                "novinky dnes", "dnesni novinky", "zpravy dnes", "ranni prehled", "ranni prehled zprav", "prehled zprav", "co pisou noviny", "co pisou media",
                "co se stalo dnes", "co se dnes stalo", "zapni ranni prehled", "zapni zpravy", "jak zapnu zpravy", "jak zapnu zpravy rano", "jak zapnu ranni prehled",
                "vypni ranni prehled", "jak vypnu ranni prehled") ->
                "Zprávy ti můžu posílat každé ráno v 05:30 – Ranní přehled (Nastavení › Ranní přehled zpráv) stáhne RSS z českých zpravodajských webů a funguje i bez modelu. Hned ho zkusíš tlačítkem „Vyzkoušet teď“."
            m("nastaveni", "otevri nastaveni", "kde je nastaveni", "jak otevru nastaveni") -> "Nastavení je v menu ☰ vlevo nahoře → Nastavení."
            m("jak zapnu internet", "jak zapnu sit", "zapni internet", "zapni sit", "vypni bez site", "jak vypnu bez site") ->
                if (airgap) "Režim Bez sítě vypneš klepnutím na „VEGA ▾“ nahoře → Bez sítě. Internet v telefonu musí být zapnutý." else "Síť VEGA používá, když je v telefonu internet. Model připojíš zdarma v Nastavení."
            m("smaz (historii|historii chatu|vsechny konverzace|vsechno)", "smaz (tuhle |tuto |celou )?konverzaci", "smaz (tenhle |tento )?chat", "jak smazu (tuhle |tuto )?konverzaci", "jak smazu chat") ->
                "Konverzaci smažeš v menu ☰: u jejího názvu klepni na ⋯ → Smazat."
            else -> null
        }
    }

    /**
     * Povely pro telefon (volání, budík, připomínka, hudba, svítilna, SMS odeslat): VEGA je neprovede – poctivě to říct,
     * i když je model připojený (model by mohl odpovědět „budík nastaven“, aniž by se cokoli stalo). Psaní textu SMS nechá modelu.
     */
    fun phoneAction(raw: String): String? {
        val t = Text.fold(raw).lowercase().trim().trimEnd('!', '.', '?', ' ').removePrefix("prosim ").removePrefix("vego ").removePrefix("vega ")
        return when {
            Regex("^(zavolej|vytoc|volej|prozvon)( mi)? \\p{L}+").containsMatchIn(t) -> "Volat neumím – VEGA nemá přístup k telefonu. Otevři aplikaci Telefon."
            Regex("^(nastav|natoc|zapni|dej mi)( mi)? budik|^budik na|^vzbud me").containsMatchIn(t) -> "Budík nastavit neumím – otevři aplikaci Hodiny. (Nic jsem nenastavil.)"
            (Regex("^(pripomen|upozorni)( mi| me)? ").containsMatchIn(t) && Regex("\\b(zitra|dnes|pozitri|vecer|rano|odpoledne|v poledne|v \\d|za \\d|o pul|v (pondeli|utery|stredu|ctvrtek|patek|sobotu|nedeli)|kazd\\w+ (den|tyden|rano))\\b").containsMatchIn(t) || Regex("^(nastav|udelej|vytvor)( mi)? (pripominku|upominku|minutku|odpocet)|^minutka na").containsMatchIn(t)) -> "Připomínky a minutky zatím neumím – použij aplikaci Hodiny nebo Kalendář. (Nic jsem nenastavil.)"
            Regex("^(pust|zahraj|prehraj)( mi)? (hudbu|pisn|pisnick|radio|playlist|spotify|youtube)").containsMatchIn(t) -> "Hudbu pustit neumím – otevři svůj přehrávač."
            Regex("^(zapni|rozsvit|vypni)( mi)? (svitiln|baterk|blesk)").containsMatchIn(t) -> "Svítilnu ovládat neumím – je v rychlém nastavení telefonu (stáhni lištu shora)."
            Regex("^(posli|odesli)( mi)? (sms|zpravu|esemesku)").containsMatchIn(t) -> "Sám zprávy neodesílám. Můžu ti text napsat – pak ho zkopíruješ tlačítkem Kopírovat."
            else -> null
        }
    }

    /** „zapomeň všechno“, „vymaž paměť“, „smaž celou paměť“ – celá paměť (potvrzuje se tlačítkem, ne hned). */
    fun isForgetAll(raw: String): Boolean {
        val t = Text.fold(raw).lowercase().trim().trimEnd('!', '.', '?', ' ')
        return Regex("^(?:prosim )?(?:zapomen|zapomenout)(?: na)? (?:vse|vsechno|uplne vsechno|vsechno co vis|vsechno o mne|celou pamet)$").matches(t) ||
            Regex("^(?:prosim )?(?:vymaz|smaz|vycisti|vyprazdni|resetuj)(?: celou| moji| mou| svoji| svou)? pamet$").matches(t)
    }
}
