package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
/**
 * ROZPOZNÁVÁNÍ OTÁZEK: stejné otázky, jak je lidé opravdu píšou – zdvořilé obaly, hovorová čeština,
 * bez diakritiky, překlepy, jiný slovosled. Všechny mají odpověď offline (bez mozku).
 */
class ParaphraseTest {
    val qs = listOf(
        // obaly a zdvořilost
        "prosím tě, kolik je hodin?", "řekni mi kolik je hodin", "můžeš mi říct kolik je hodin?", "víš kolik je hodin?", "nevíš, kolik je hodin?",
        "AVIOR, kolik je hodin?", "hele kolik je hodin", "zajímalo by mě, kolik je hodin", "potřebuju vědět kolik je hodin", "kolik je teď hodin?",
        "kolik je hodin prosím", "čas?", "jaký je čas", "kolik máme hodin", "kolik je hoďin",
        "jaké je dnes datum", "jaké je dneska datum?", "kolikátého je dneska", "co je dneska za den", "jakej je dneska den",
        "řekni mi dnešní datum", "dnešní datum", "jaký máme dnes den", "jaký je dnes den v týdnu?", "kolikátýho je",
        // počty
        "kolik je sedm krát osm", "spočítej 15 % z 240", "kolik dělá 12 krát 13", "spočítej mi 45 + 37", "vypočítej 144 / 12",
        "kolik je dvacet procent z tisíce", "kolik je 15% z 200 prosím", "kolik je třetina z 90", "kolik je polovina z 250", "druhá odmocnina ze 169",
        "kolik je 2 na desátou", "kolik je 3 na druhou", "kolik je 9 na 2", "kolik je pi", "kolik je 7*6?",
        // převody
        "kolik metrů je 5 km", "kolik je 3 kila v gramech", "kolik gramů má kilo", "převeď 50 mil na kilometry", "kolik je 70 kg v librách",
        "kolik je 1 galon v litrech", "kolik centimetrů má metr", "kolik je 100 fahrenheita v celsiech", "20 °C na fahrenheity", "kolik je 1 stopa v cm",
        "kolik je 100 dolarů v korunách", "převeď 1000 kč na eura", "za kolik je euro", "jaký je kurz eura", "kolik stojí dolar",
        // kalendář a svátky
        "kolik dní zbývá do vánoc?", "kolik dnů do silvestra", "za jak dlouho jsou vánoce", "kdy jsou letos velikonoce", "kdy budou velikonoce",
        "kdo slaví dnes svátek", "kdo má dneska svátek?", "kdy slaví svátek jana", "kdy má jmeniny petr", "svátek dnes",
        "jaký den bude 24. 12.", "jaký den byl 17. 11. 1989", "jaké datum bude za 2 týdny", "kolikátý je týden", "je dnes víkend?",
        // fakta
        "jaké je hlavní město francie?", "co je hlavním městem německa", "hlavni mesto italie", "hlavní město japonska prosím", "jaká je metropole španělska",
        "kde je hlavní město polska", "hlavní město usa", "jaké je hlavní město velké británie", "hlavni mesto rakouska", "hl. město slovenska",
        "jakou měnu má polsko", "jaká měna je v maďarsku", "v čem se platí v chorvatsku", "jaká je měna japonska", "měna švýcarska",
        "kolik obyvatel má česko", "kolik lidí žije v česku", "jak velká je česká republika", "kolik má čr obyvatel", "počet obyvatel prahy",
        "jaká je nejvyšší hora světa?", "nejvyšší hora česka", "nejdelší řeka světa", "nejdelší řeka v česku", "největší oceán",
        "kolik planet má sluneční soustava", "která planeta je největší", "jak daleko je měsíc", "jak daleko je slunce od země", "kolik je stupňů v kruhu",
        "chemická značka zlata", "jakou značku má kyslík", "co je to H2O", "jaký je vzorec vody", "chemická značka sodíku",
        "kdo napsal babičku", "kdo napsal R.U.R.", "kdo složil mou vlast", "kdo namaloval mona lisu", "kdo vynalezl žárovku",
        "kdy začala druhá světová válka", "kdy skončila 2. světová válka", "kdy byla bitva na bílé hoře", "kdy vznikla čsr", "kdy byla sametová revoluce",
        "kdo byl prvním prezidentem čsr", "kdo je český prezident", "kdo je prezidentem usa", "kdo je premiérem česka", "kdo je současný premiér",
        "co znamená zkratka nato", "co znamená DPH", "co je to OSN", "co znamená EU", "význam zkratky HDP",
        "kolik má nato členů", "kolik států má eu", "kdy vstoupila čr do nato", "kdy vstoupila česká republika do eu", "kdy jsme vstoupili do eu",
        // všední
        "díky moc", "dík", "děkuji ti", "dobré ráno", "dobrý večer",
        "jak se jmenuješ", "kdo jsi", "co jsi zač", "kdo tě udělal", "jsi ai?",
        "hoď mincí", "hoď si kostkou", "vyber číslo od 1 do 10", "panna nebo orel?", "řekni vtip",
        "kolik dní má rok", "kolik hodin má týden", "kolik vteřin má minuta", "kolik týdnů má rok", "kolik dní má přestupný rok",
    )

    @Test fun recognition() {
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        var hit = 0
        val miss = mutableListOf<String>()
        for (q in qs) {
            val a = Reflex.answer(q, c)
            if (a != null) hit++ else miss += q
        }
        miss.forEach { println("MISS  $it") }
        // rychlost: i s variantami musí reflex rozhodnout v řádu milisekund
        val t0 = System.nanoTime(); repeat(5) { qs.forEach { Reflex.answer(it, c) } }
        println("SPEED %.2f ms/otázka".format((System.nanoTime() - t0) / 1e6 / (5 * qs.size)))
        println("PARAPHRASE $hit/${qs.size}")
        org.junit.Assert.assertTrue("rozpoznáno $hit/${qs.size}", hit >= MIN)
    }
    companion object { const val MIN = 145 }
}
