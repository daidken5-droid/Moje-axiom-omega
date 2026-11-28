package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
/**
 * SPRÁVNOST odpovědí bez mozku – ne jen „něco odpověděl“, ale „odpověděl to, co by uživatel čekal“.
 * Pevné datum (pondělí 2. 11. 2026, 14:30), aby výsledky nezávisely na dni běhu.
 */
class CorrectnessTest {
    private val now = LocalDateTime.of(2026, 11, 2, 14, 30).atZone(java.time.ZoneId.of("Europe/Prague"))
    private val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false, now = now), today = now.toLocalDate())
    private fun ask(q: String) = Reflex.answer(q, c)?.text?.replace("**", "")?.replace(" ", " ") ?: "∅"

    private val cases = listOf(
        // výpočty
        "kolik je 15 % z 200" to "30", "kolik je 7 krát 8" to "56", "kolik je sedm krát osm" to "56", "100 děleno 4" to "25", "2 na 10" to "1 024",
        "odmocnina ze 144" to "12", "kolik je 3 plus 5" to "8", "12 * 12" to "144", "kolik je 0,5 * 8" to "4", "kolik je 1000 / 8" to "125",
        "kolik je třetina z 90" to "30", "kolik je polovina z 250" to "125", "kolik je dvacet procent z tisíce" to "200", "2x3" to "6", "kolik je 3^2" to "9",
        "kolik je 10 - 15" to "-5", "kolik je 2 + 2 * 2" to "6", "kolik je (2 + 2) * 2" to "8",
        // převody
        "kolik je 5 km v metrech" to "5 000", "kolik metrů je 5 km" to "5 000", "kolik gramů má kilo" to "1 000", "kolik je litr v mililitrech" to "1 000",
        "kolik je 30 stupňů celsia ve fahrenheitech" to "86", "kolik je 100 fahrenheita v celsiech" to "37", "1 míle na km" to "1,6",
        "kolik je palec v cm" to "2,54", "kolik je 70 kg v librách" to "154",
        // čas a kalendář (2. 11. 2026, pondělí)
        "jaký je dnes den" to "pondělí", "kolikátého je dneska" to "2.", "jaký bude zítra den" to "úterý", "jaký je rok" to "2026",
        "kolik dní do vánoc" to "52", "jaký den bude 24. 12." to "čtvrtek", "jaký den byl 17. 11. 1989" to "pátek",
        "jaké datum bude za 2 týdny" to "16.", "kolik dní má únor" to "28", "kolik dní má rok" to "365", "kolik sekund má hodina" to "3 600",
        "kolik let je od roku 1989" to "37", "je dnes víkend" to "Ne",
        // svátky
        "kdy má svátek petr" to "29.", "kdy jsou velikonoce" to "2027",
        // fakta
        "hlavní město francie" to "Paříž", "hlavní město slovenska" to "Bratislava", "hlavní město austrálie" to "Canberra", "hl. město kanady" to "Ottawa",
        "jaká je měna v polsku" to "zlot", "jaká je měna v japonsku" to "jen", "značka železa" to "Fe", "chemická značka zlata" to "Au",
        "nejvyšší hora světa" to "Everest", "nejvyšší hora česka" to "Sněžka", "nejdelší řeka světa" to "Nil", "kolik je planet" to "8",
        "největší planeta" to "Jupiter", "kdo napsal babičku" to "Němcová", "kdo složil mou vlast" to "Smetana", "kdo vynalezl žárovku" to "Edison",
        "kdy začala druhá světová válka" to "1939", "kdy byla sametová revoluce" to "1989", "kdy vznikla česká republika" to "1993",
        "kolik má nato členů" to "32", "kolik států má eu" to "27", "co znamená dph" to "přidané hodnoty", "kolik má člověk kostí" to "206",
        "jak daleko je měsíc" to "384", "kdo byl prvním prezidentem čsr" to "Masaryk", "kdo je prezident" to "Pavel",
        // slang, SK, EN
        "koľko je 7 krát 8" to "56", "aký je dnes deň" to "pondělí", "what time is it" to "14:30", "capital of germany" to "Berlín",
        "kolik je hoďek" to "14:30", "kolék je hodin" to "14:30", "hlavni mesto francue" to "Paříž",
        // praktické (v8.7)
        "kolik je hodin v new yorku" to "8:30", "kolik je hodin v tokiu" to "22:30", "jaký je čas v londýně" to "13:30",
        "21 % dph z 1000" to "1 210", "kolik je 1000 bez dph" to "826,45", "1000 s dph" to "1 210", "kolik je 100 + 20 %" to "120",
        "o kolik procent je 120 víc než 100" to "20 %", "kolik procent je 30 z 120" to "25 %", "sleva 30 % z 1200" to "840",
        "kolikátý je dnes den v roce" to "306", "kdy je příští pondělí" to "9. listopadu", "kolik dní je mezi 1. 1. 2026 a 1. 3. 2026" to "59 dní",
        "kolik je mi let narodil jsem se 1. 5. 1990" to "36 let", "kolik je 5 / 0" to "nelze", "dobré ráno" to "Dobré ráno",
        "kolik je to korun 50 eur" to "Kč", "kolik je 1 000 000 děleno 3" to "333 333,33", "kolik je 1,5 l v ml" to "1500 ml",
    )

    @Test fun answersAreCorrect() {
        val wrong = cases.mapNotNull { (q, want) -> ask(q).let { a -> if (!a.contains(want)) "$q  ⇒ chci „$want“, dostal: ${a.take(110)}" else null } }
        wrong.forEach { println("WRONG $it") }
        println("CORRECT ${cases.size - wrong.size}/${cases.size}")
        org.junit.Assert.assertTrue(wrong.joinToString("\n"), wrong.size <= MAX_WRONG)
    }
    companion object { const val MAX_WRONG = 0 }
}
