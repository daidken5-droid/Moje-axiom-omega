package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Doporučení T2: router měnit podle naměřených chyb. Oddělená sada českých parafrází – informační otázka („Jak se zapíná …?“) nesmí
 * spustit akci, příkaz („Zapni …“) má spustit svou akci. Vykazuje se počet akcí chybně spuštěných informační otázkou (cíl 0).
 */
class RouterParaphraseMetricTest {
    private val ctx = Router.Ctx(false, null, false, true, null)
    private fun kind(q: String) = Router.route(q, ctx).let { it::class.simpleName + (if (it is Router.Intent.Tool) ":" + it.id else "") }
    /** Záměry, které něco dělají (mění stav, spouští nástroj, mažou) – na informační otázku nesmí. */
    private val ACTIONS = setOf("Remember", "Forget", "Undo", "MakeButton", "BuildStatementTool", "FixColumn", "Continue", "Tool", "RunProcedure", "Code", "Demand", "Nfc")

    private val informational = listOf(
        "Jak se zapíná rozšíření?", "Jak se dělá tlačítko?", "Jak funguje zapomínání?", "Co znamená zapamatuj si?", "Jak se vrací změna?",
        "Co dělá tlačítko zpět?", "Jak smažu chat?", "Jak se exportuje do Excelu?", "Jak udělám zálohu?", "Jak nastavím budík?",
        "Proč se ti zapnul web?", "Co je to rešerše?", "Jak pokračovat v projektu?", "Jak se dělá nástroj na výpis?", "Kde najdu paměť?",
        "Jak se zapomíná heslo?", "Jak vypnout ranní přehled?", "Jak opravit sloupec částka?", "K čemu je nástroj na výpis?",
        "Co umí čtení NFC?", "Jak se počítá kontrolní součet?",
        // rozšíření 25. 11. večer
        "Jak se mažou vzpomínky?", "Co se stane, když napíšu vrať to?", "Kde je tlačítko pro pokračování?", "Proč mi nefunguje zapomínání?",
        "Jak se přidává pravidlo do paměti?", "Co dělá předžalobní výzva?", "Jak se čte NFC štítek?", "K čemu slouží kontrola CSV?",
        "Jak se opravuje sloupec ve výpisu?", "Jak se vytváří tlačítko z výsledku?", "Co je to kontrolní součet SHA-256?", "Proč se mi nezapamatovalo jméno?",
        "A jak se to vrací?", "Kde nastavím, co si pamatuješ?",
        // rozšíření 27. 11. (hardware, dnešní změny, tvary „Dá se / Je bezpečné…?“)
        "Je bezpečné otevřít adresu z NFC štítku?", "Jak se čte virtuální karta?", "Jak se generuje heslo na WiFi?", "Jak zapíšu text na NFC štítek?",
        "Proč se mi nepřečetla karta?", "Co přesně dělá pokračuj?", "Jak udělat z výsledku tlačítko?", "Dá se vrátit samoúprava aplikace?",
        "Dá se nějak zapnout rozšíření?", "Šlo by nějak udělat tlačítko?", "Jde zapomenout jen jednu věc?", "Je možné přečíst NFC kartu z banky?")

    private val commands = mapOf(
        "Udělej z toho tlačítko" to "MakeButton", "Zapomeň, kde bydlím" to "Forget", "Pamatuj si, že piju kafe bez cukru" to "Remember",
        "Vrať to" to "Undo", "Pokračuj v projektu" to "Continue", "Udělej z toho nástroj na výpis" to "BuildStatementTool",
        "Ukaž paměť" to "WhatDoYouRemember", "Částka je ve sloupci C" to "FixColumn",
        "zapamatuj si, že bydlím v Brně" to "Remember", "prosím zapomeň na psa" to "Forget", "vrať zpět" to "Undo", "co si pamatuješ?" to "WhatDoYouRemember",
        // 27. 11.: zdvořilý příkaz ve tvaru otázky zůstává příkazem
        "Můžeš si zapamatovat, že bydlím v Brně?" to "Remember", "Zapomeň všechno o mé práci" to "Forget", "Vrať poslední změnu" to "Undo", "Přečti NFC štítek" to "Nfc")

    @Test fun informationalQuestionsNeverTriggerActions() {
        val misfires = informational.mapNotNull { q -> kind(q).takeIf { k -> k.substringBefore(':') in ACTIONS }?.let { "„$q“ → $it" } }
        println("METRIKA router: ${misfires.size} akcí spuštěných informační otázkou z ${informational.size}")
        assertEquals(emptyList(), misfires)
    }

    @Test fun commandsStillTriggerTheirAction() {
        val wrong = commands.mapNotNull { (q, want) -> kind(q).takeIf { it.substringBefore(':') != want }?.let { "„$q“ → $it (čekáno $want)" } }
        println("METRIKA router: ${commands.size - wrong.size} z ${commands.size} příkazů rozpoznáno")
        assertEquals(emptyList(), wrong)
    }

    /** 27. 11.: otázka na VEGA samotnou („Umíš…?“, „Dá se…?“) není holý název tématu – nesmí odejít jako rešerše na web. */
    @Test fun questionsAboutVegaAreNotWebResearch() {
        val qs = listOf("Dá se nějak zapnout rozšíření?", "Šlo by nějak udělat tlačítko?", "Jakým způsobem se zapomíná?", "Jde zapomenout jen jednu věc?",
            "Umíš si pamatovat jména?", "Pamatuješ si i hesla?", "Dá se vrátit samoúprava aplikace?", "Zvládneš přečíst NFC štítek?", "Podporuješ Bluetooth?")
        val toWeb = qs.filter { kind(it) == "Research" }
        println("METRIKA router: ${toWeb.size} otázek na VEGA odešlo jako rešerše z ${qs.size}")
        assertEquals(emptyList(), toWeb)
        // skutečné názvy témat dál jdou na rešerši
        for (t in listOf("Operation Prato", "Projekt Manhattan", "kauza Dozimetr", "Apollo 11")) assertEquals("Research", kind(t), t)
    }
}
