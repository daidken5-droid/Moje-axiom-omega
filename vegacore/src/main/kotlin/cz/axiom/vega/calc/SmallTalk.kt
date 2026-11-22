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
            m("ok", "okej", "oki", "jo", "jj", "ano", "dobre", "fajn", "super", "parada", "jasne", "rozumim", "ne", "nene", "nic", "\\p{So}+") || Regex("^[\\p{So}\\p{Sk}\\u200d\\ufe0f\\s]+$").matches(raw.trim()) ->
                "Dobře. Kdybys něco potřeboval/a, stačí napsat."
            m("co umis", "co umis delat", "co dokazes", "co vsechno umis", "pomoc", "help", "napoveda", "jak to funguje", "jak te pouzivat", "nevim co delat", "nevim co napsat", "s cim mi pomuzes") ->
                "$mode. I tak umím: $CAN. Rozhovor, psaní textů a překlady potřebují model – ${if (airgap) "vypni Bez sítě klepnutím na „VEGA ▾“ nahoře" else "zapneš ho zdarma v Nastavení"}."
            m("kdo jsi", "co jsi", "kdo jsi ty", "jak se jmenujes") -> "Jsem VEGA – asistent v telefonu. Počítám přesně bez modelu, pracuju s tvými soubory a s modelem v cloudu i píšu a odpovídám na otázky."
            m("jak se mas", "jak je", "jak se vede", "jak to jde") -> "Díky, běžím. S čím pomůžu?"
            m("nastaveni", "otevri nastaveni", "kde je nastaveni", "jak otevru nastaveni") -> "Nastavení je v menu ☰ vlevo nahoře → Nastavení."
            m("jak zapnu internet", "jak zapnu sit", "zapni internet", "zapni sit", "vypni bez site", "jak vypnu bez site") ->
                if (airgap) "Režim Bez sítě vypneš klepnutím na „VEGA ▾“ nahoře → Bez sítě. Internet v telefonu musí být zapnutý." else "Síť VEGA používá, když je v telefonu internet. Model připojíš zdarma v Nastavení."
            m("smaz (tuhle |tuto |celou )?konverzaci", "smaz (tenhle |tento )?chat", "jak smazu (tuhle |tuto )?konverzaci", "jak smazu chat") ->
                "Konverzaci smažeš v menu ☰: u jejího názvu klepni na ⋯ → Smazat."
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
