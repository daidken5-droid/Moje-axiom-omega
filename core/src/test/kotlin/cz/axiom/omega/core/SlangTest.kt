package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
/** Slang, obecná čeština, nářečí, slovenština, chatové zkratky, angličtina a překlepy – vše bez mozku. */
class SlangTest {
    val qs = listOf(
        // obecná čeština a slang
        "kolik je hoďek", "kolik je hodin vole", "čus, kolik je hodin", "zdar, jakej je dneska den", "kolikátýho je dneska", "jakej je dneska datum",
        "kolik je teďka hodin", "vo kolik je to 15 procent z 200", "kolik dělá sedum krát vosum", "kolik je vosum plus pět", "jaký je dneska den kámo",
        "dyť mi řekni kolik je hodin", "hele, kolik je hodin jo", "kolik je hodin pls", "kolik je hodin plz", "kolik je hodin thx",
        "kdo má dneska svátek jo", "kdo má zejtra svátek", "jakej bude zejtra den", "kolik je hodin brácho", "kolik máme hoďin",
        "kolik je stovka v eurech", "kolik je litr v mililitrech", "kolik je kilák v metrech",
        "hlavní město francie jo", "jaký je hlavák slovenska", "kdo je prezoš", "kdo je premiér jo", "díky moc kámo",
        // nářečí (Morava, Haná, Slezsko)
        "kolék je hodin", "keré je hlavní město polska", "jaké je dnes datum šak", "kolik je hodin esi víš", "co je dnes za den, vé",
        "kdo má dnes svátek, gde", "kolik je to korun za euro", "kolik je hodin, ne", "jaký je dnes deň",
        // slovenština
        "koľko je hodín", "aký je dnes deň", "aký je dnešný dátum", "koľko je 7 krát 8", "hlavné mesto francúzska", "čo je hlavné mesto nemecka",
        "kto je prezident", "kto má dnes meniny", "koľko dní je do vianoc", "aký bude zajtra deň", "ďakujem", "ahoj, ako sa máš",
        "koľko je 20 percent z 300", "kto napísal babičku", "kedy začala druhá svetová vojna", "aká je mena v poľsku",
        // chatové zkratky a bez diakritiky
        "kolik je h", "kolik je hod", "kolik je hodin?!", "KOLIK JE HODIN", "kolik je hodin???", "kolik je hod?", "jak se mas", "dk", "thx", "dekuju",
        "hl mesto italie", "kolik je 2x3", "kolik je 10x10", "15% z 200", "200/4", "kolik je 5*5?", "sqrt 81", "kolik je 3^2",
        // angličtina
        "what time is it", "what's the date today", "what day is it", "how are you", "who are you", "thanks", "thank you",
        "what is the capital of france", "capital of germany", "how much is 15 percent of 200", "what is 7 times 8", "flip a coin", "tell me a joke",
        // překlepy
        "kolik je hodim", "kolik je hidin", "kolok je hodin", "jaky je dnes dem", "hlavni mesto francue", "hlavni msto nemecka",
        "kdo ma dnes svatel", "kolik je patnact procent z dvesta", "kolik dni do vanco", "kdo napsla babicku", "jaky je dnes datun",
        "kolik je hodin vlastne", "tak kolik je hodin", "no a kolik je hodin", "kolik je zrovna hodin", "kolik je presne hodin", "presny cas",
    )

    @Test fun recognition() {
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false))
        val miss = qs.filter { Reflex.answer(it, c) == null }
        miss.forEach { println("MISS  $it") }
        val hit = qs.size - miss.size
        println("SLANG $hit/${qs.size}")
        org.junit.Assert.assertTrue("rozpoznáno $hit/${qs.size}", hit >= MIN)
    }
    companion object { const val MIN = 98 }
}
