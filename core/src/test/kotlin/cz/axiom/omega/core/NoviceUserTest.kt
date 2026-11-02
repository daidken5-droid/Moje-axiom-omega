package cz.axiom.omega.core
import cz.axiom.omega.core.agent.*
import org.junit.Test
/** Otázky tak, jak je píše člověk, který s AI skoro nepracoval: celé věty, vykání, pozdrav + otázka + poděkování. */
class NoviceUserTest {
    @Test fun probe() {
        val now = java.time.LocalDateTime.of(2026, 11, 2, 14, 30).atZone(java.time.ZoneId.of("Europe/Prague"))
        val c = Reflex.Ctx(Instant.Ctx(battery = 70, charging = false, now = now), today = now.toLocalDate())
        val qs = listOf(
            "Dobrý den, chtěla bych se zeptat, kolik je hodin?", "Prosím Vás, jaké je dnes datum?", "Můžete mi prosím říct, kolik je hodin?",
            "Mohl byste mi říct, jaký je dnes den?", "Řekněte mi prosím, kolik je hodin.", "Víte, kolik je hodin?", "Nevíte náhodou, kolik je hodin?",
            "Potřebovala bych vědět, kolik dní zbývá do Vánoc.", "Dobrý den, prosím Vás, kdy jsou letos Velikonoce? Děkuji.",
            "Ahoj. Kolik je hodin?", "Dobrý den. Jaké je hlavní město Francie?", "Zdravím, kolik je 15 procent z 2000 korun?",
            "Dobrý den Avioře, kdo má dnes svátek?", "Prosím vás pěkně kolik je 7 krát 8", "dobry den kolik je hodin dekuji",
            "Chtěl bych vědět, jaké je hlavní město Německa, děkuji.", "Kolik je hodin? Děkuji moc!", "Jaký je dneska den? Díky.",
            "Můžete mi spočítat, kolik je 1500 korun bez DPH?", "Kolik je to v eurech 1000 korun?", "Kolik je 1000 Kč v eurech prosím",
            "Kolik stupňů Fahrenheita je 25 stupňů Celsia?", "Dobrý den, kolik je hodin v Londýně?", "Kdy je příští neděle?",
            "Kolik mi bude let, když jsem se narodila 15. 6. 1955?", "Narodil jsem se v roce 1960, kolik je mi let?", "Kolik je mi let, když jsem ročník 1948?",
            "Kdo je teď prezidentem?", "Kdo je u nás prezident?", "Jak se jmenuje náš prezident?", "Kdo je teď premiér?", "Kdo vládne v Česku?",
            "Jaké je číslo na záchranku?", "Na jaké číslo mám volat, když hoří?", "Jaké je číslo na policii?", "Potřebuji zavolat sanitku, jaké je číslo?",
            "Dobrý den, mohla bych poprosit o vtip?", "Řekněte mi nějaký vtip", "Je dnes státní svátek?", "Kdy je další státní svátek?",
            "Kolik je venku stupňů?", "Jak dlouho ještě do Silvestra?", "Kolik dní má tento měsíc?", "Kolik dní má listopad?",
            "Kolik je 100 eur v korunách?", "Za kolik je dneska euro?", "Jaký je kurz eura, prosím?", "Kolik stojí euro?",
            "Kolik kilometrů je jedna míle?", "Kolik je jedna libra v kilech?", "Kolik váží litr mléka?", "Kolik deka je půl kila?",
            "Kolik je čtvrt kila v gramech?", "Kolik je hodina v minutách?", "Kolik minut má den?", "Kolik je půl hodiny v minutách?",
            "Kdo napsal Babičku?", "Kdy byla bitva na Bílé hoře?", "Kdy vzniklo Československo?", "Kdy byla sametová revoluce?",
            "Jak se máte?", "Jak se vám daří?", "Kdo jste?", "Jak se jmenujete?", "Co umíte?", "Děkuji vám.", "Děkuji za pomoc.",
            "Na shledanou.", "Dobrou noc.", "Hezký den.", "Promiňte, kolik je hodin?",
            "kolik je hodin prosim", "kolik je hodin?????", "KOLIK JE HODIN", "kolik je hodim", "kolko je hodin", "jake je datum",
        )
        var miss = 0
        qs.forEach { q -> val a = Reflex.answer(q, c); if (a == null) { miss++; println("NOV ∅  $q") } else println("NOV ✓  %-60s → %s".format(q, a.text.replace("\n", " ").take(70))) }
        println("NOV HIT ${qs.size - miss}/${qs.size}")
        org.junit.Assert.assertTrue("rozpoznáno ${qs.size - miss}/${qs.size}", qs.size - miss >= 76) // zbývá počasí (internet)
        // kdo vyká, dostane vykání
        fun a(q: String) = Reflex.answer(q, c)!!.text
        org.junit.Assert.assertTrue(a("Jak se máte?"), a("Jak se máte?").contains("jak se máte vy"))
        org.junit.Assert.assertTrue(a("Dobrou noc, děkuji vám."), a("Dobrou noc, děkuji vám.").contains("vám"))
        org.junit.Assert.assertTrue(a("Kolik mi bude let, když jsem se narodila 15. 6. 1955?"), a("Kolik mi bude let, když jsem se narodila 15. 6. 1955?").contains("71 let"))
        org.junit.Assert.assertTrue(a("Na jaké číslo mám volat, když hoří?").startsWith("Hasiči: **150**"))
        org.junit.Assert.assertTrue(a("Dobrý den, prosím Vás, kdy jsou letos Velikonoce? Děkuji.").startsWith("Letošní Velikonoce už byly"))
        org.junit.Assert.assertTrue(a("Kolik je 1000 Kč v eurech prosím").contains("41,15 EUR"))
        // kdo tyká, dostane tykání
        org.junit.Assert.assertTrue(a("jak se máš").contains("jak se máš ty"))
    }
}
