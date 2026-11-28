package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
/** Kolik běžných jednoduchých otázek zvládnou reflexy bez mozku (zbytek opravdu potřebuje mozek). */
class CoverageTest {
    @Test fun coverage() {
        val qs = listOf(
            "ahoj", "čau, jak se máš?", "jak se máš", "dobrý den", "díky", "děkuju moc", "ok", "super", "co umíš?", "co děláš?", "kdo tě vytvořil?", "jak se jmenuješ?",
            "dobrou noc", "nashle", "pomoc", "help",
            "kolik je hodin", "jaký je dnes den", "kolikátého je", "jaký je zítra den", "jaký bude zítra den", "kolik je 15 procent z 200", "kolik je 25 % z 80",
            "kolik je 7 krát 8", "kolik je 100 děleno 4", "2 na 10", "odmocnina z 144", "kolik je 3 plus 5", "12 * 12", "1/3 + 1/6",
            "kolik je 5 km v metrech", "převeď 100 eur na koruny", "kolik je 30 stupňů celsia ve fahrenheitech", "kolik je palec v cm", "1 míle na km",
            "kolik dní do vánoc", "kdy je velikonoce", "kdo má dnes svátek", "kdo má zítra svátek", "kdy má svátek petr",
            "hlavní město francie", "hlavní město slovenska", "jaká je měna v anglii", "značka železa", "kolik je planet",
            "nejvyšší hora světa", "kdo byl první prezident", "kdo je prezident", "kdo je premiér", "co je dph",
            "kolik má rok dní", "kolik má týden dní", "kolik sekund má hodina", "kolik minut má den", "kolik dní má únor",
            "jaký je rok", "jaký je měsíc", "co je dnes za den v týdnu", "kolik je mi let když jsem se narodil 1990", "kolik let je od roku 1989",
            "kolik baterie", "stav baterie", "zapni svítilnu", "kolik je 10 eur", "kurz eura", "jak se řekne pes anglicky",
            "co je to fotosyntéza", "co je láska", "proč je obloha modrá", "vtip", "řekni mi vtip", "hoď si mincí", "hoď kostkou", "náhodné číslo od 1 do 100",
            "kolik je 2+2", "1 + 1", "kolik je 0,5 * 8", "sin 30", "kolik je 1000 / 7",
        )
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        var hit = 0
        for (q in qs) {
            val a = Reflex.answer(q, c) ?: cz.axiom.omega.core.phone.PhoneCommands.parse(q)?.let { Instant.Answer("[povel] " + it.label, "povel") }
            if (a != null) hit++ else println("MISS  $q")
        }
        println("COVERAGE $hit/${qs.size}")
        org.junit.Assert.assertTrue("pokrytí $hit/${qs.size}", hit >= 74)
    }
}
