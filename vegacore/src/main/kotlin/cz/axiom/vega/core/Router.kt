package cz.axiom.vega.core

import cz.axiom.omega.core.util.TextUtil

/**
 * ROUTER CHATU: rozliší akci od přemýšlení nahlas a pošle zprávu na STEJNOU implementaci, kterou má ruční
 * nabídka (nástroje, postupy, paměť, projekt). Model se volá jen tam, kde je potřeba úsudek nebo text.
 */
object Router {
    sealed class Intent {
        /** [scope]: "project" (tady / v tomhle projektu), "global" (vždy / všude), null = podle kontextu. */
        data class Remember(val text: String, val scope: String? = null) : Intent()
        /** „pokračuj tam, kde jsme skončili“ → obnovit poslední projekt a jeho stav. */
        object Continue : Intent()
        /** „udělej mi z toho tlačítko“ → znovupoužitelný nástroj z posledního úspěšného výsledku. */
        object MakeButton : Intent()
        /** „částka je ve sloupci Pohyb“ → oprava parseru posledního výpisu. */
        data class FixColumn(val role: String, val column: String) : Intent()
        /** Neobvyklá hypotéza → nejsilnější opora PRO i alternativní vysvětlení, závěr úměrný podkladům. */
        data class Hypothesis(val text: String) : Intent()
        data class Forget(val query: String) : Intent()
        object WhatDoYouRemember : Intent()
        data class Tool(val id: String, val params: Map<String, String> = emptyMap(), val why: String) : Intent()
        /** „udělej z toho nástroj na výpis“ – rozpoznat formát, ověřit, nabídnout uložení postupu. */
        object BuildStatementTool : Intent()
        data class RunProcedure(val procedure: Procedure) : Intent()
        data class Code(val task: String, val kind: CodeKind) : Intent()
        data class NewProject(val spec: String) : Intent()
        object Nfc : Intent()
        object Demand : Intent()
        data class Research(val topic: String, val variants: List<String>) : Intent()
        /** „tohle je divné“ u právě zobrazeného výsledku → navázat na konkrétní řádek / změnu. */
        data class FollowUp(val text: String) : Intent()
        data class Talk(val thinkingAloud: Boolean, val creative: Boolean) : Intent()
        object Undo : Intent()
    }

    enum class CodeKind { CHANGE, EXPLAIN, FIX_FROM_LOG, TEST }

    data class Ctx(val hasAttachment: Boolean, val attachmentName: String?, val activeProject: Boolean, val lastResult: Boolean, val procedures: ProcedureStore?)

    private fun f(s: String) = TextUtil.fold(s).lowercase().trim()
    private fun has(t: String, vararg rx: String) = rx.any { Regex(it).containsMatchIn(t) }

    fun route(raw: String, c: Ctx): Intent {
        val text = raw.trim()
        val t = f(text)
        // --- navázání na práci (dřív než „pokračuj“ jako pouhé opakování)
        if (has(t, "kde jsme (skoncili|prestali|minule skoncili)|navaz(at)? na (to,? co jsme delali|minulou praci|minule)|pokracuj(me)? (v (tom )?projektu|v praci|kde jsme)|kde jsme byli")) return Intent.Continue
        // --- tlačítko z hotové práce
        if (has(t, "(udelej|vytvor|uloz)( mi)?( z toho| to)? (jako )?(tlacitko|zkratk|nastroj na (jedno|1) klepnuti)|z toho (udelej )?tlacitko")) return Intent.MakeButton
        // --- oprava sloupce výpisu
        Regex("^(?:ne,?\\s+)?(castka|datum|popis|mena|zprava|protistrana)\\s+je\\s+(?:ve|v)\\s+sloupci\\s+[„\"']?(.+?)[“\"']?\\s*\\.?$").find(t)?.let { m ->
            val role = when (m.groupValues[1]) { "castka" -> "amount"; "datum" -> "date"; "mena" -> "currency"; else -> "desc" }
            val start = text.length - m.groupValues[2].length - (t.length - m.range.last - 1)
            return Intent.FixColumn(role, text.substring(start.coerceIn(0, text.length), (start + m.groupValues[2].length).coerceAtMost(text.length)).trim().trim('„', '“', '"', '\'', '.'))
        }
        // --- pravidlo projektu × globální preference (bez „pamatuj si“: „tady používej středník“)
        Regex("^(?:a\\s+)?(tady|zde|v tomhle projektu|v tomto projektu|u tohohle projektu|u tohoto projektu|vzdy|vsude|obecne|ve vsech projektech)\\s*,?\\s+(pouzivej|nepouzivej|pis|nepis|davej|nedavej|delej|nedelej|chci|preferuj|formatuj)\\b").find(t)?.let { m ->
            val scope = if (m.groupValues[1] in setOf("vzdy", "vsude", "obecne", "ve vsech projektech")) "global" else "project"
            return Intent.Remember(text, scope)
        }
        // --- paměť (výslovné pokyny mají přednost)
        Regex("^(?:prosim\\s+)?(?:zapamatuj|pamatuj)\\s+si\\s*(?:,|:)?\\s*(?:ze\\s+)?(.+)$").find(t)?.let {
            val body = text.substring(text.length - it.groupValues[1].length)
            val scope = when { has(t, "\\b(tady|v tomhle projektu|v tomto projektu|u tohoto projektu|v projektu)\\b") -> "project"; has(t, "\\b(vzdy|vsude|obecne|ve vsech)\\b") -> "global"; else -> null }
            return Intent.Remember(body, scope)
        }
        Regex("^(?:prosim\\s+)?(?:zapomen|zapomenout|smaz z pameti)\\s+(?:na\\s+)?(?:to,?\\s*ze\\s+)?(.+)$").find(t)?.let { return Intent.Forget(it.groupValues[1]) }
        if (has(t, "^co si (o mne )?pamatujes", "^co o mne vis", "co si pamatujes a proc")) return Intent.WhatDoYouRemember
        if (has(t, "^(vrat|vrat zpet|zpet|undo|vrat posledni zmenu|vrat to)\\b")) return Intent.Undo
        // --- uložený postup podle jména („zpracuj výpis“)
        c.procedures?.find(text)?.let { p -> if (c.hasAttachment || p.inputKind == "TEXT") return Intent.RunProcedure(p) }
        // --- výpis → nástroj
        if (has(t, "vypis") && has(t, "nastroj|kazdy tyden|pokazde|zpracuj|zpracovat|parser|z toho")) return Intent.BuildStatementTool
        // --- NFC
        if (has(t, "\\bnfc\\b|stitek|ndef|\\btag\\b")) return Intent.Nfc
        // --- předžalobní výzva
        if (has(t, "predzalobni|vyzv\\w* k (za)?placeni|upomink|dluzi mi|nezaplacen\\w* faktur")) return Intent.Demand
        // --- nástroje s přílohou nebo vstupem v textu
        val url = Regex("https?://\\S+").find(text)?.value
        if (url != null && has(t, "precti|otevri|shrn|co je na|stahni|nacti") ) return Intent.Tool("url.read", emptyMap(), "adresa v textu")
        if (has(t, "sha-?256|sha-?1|md5|crc|kontrolni soucet|otisk souboru|checksum|hash")) return Intent.Tool("hash", Regex("\\b[0-9a-f]{32,128}\\b").find(t)?.let { mapOf("expect" to it.value) } ?: emptyMap(), "kontrolní součet")
        if (has(t, "porovnej|rozdil mezi|diff\\b|co se zmenilo")) return Intent.Tool("text.diff", emptyMap(), "porovnání")
        if (c.activeProject && has(t, "oprav|fix") && has(t, "\\blog\\w*|pad\\w*|crash|vyjimk|exception|stack ?trace|traceback")) return Intent.Code(text, CodeKind.FIX_FROM_LOG)
        if (has(t, "\\bpad\\w*|crash|vyjimk|exception|stack ?trace|traceback") && (c.hasAttachment || text.contains("\n\tat ") || text.contains("Traceback"))) return Intent.Tool("log.crash", emptyMap(), "rozbor pádu")
        Regex("(?:najdi|hledej|vyhledej)\\s+(?:v logu\\s+)?[„\"']?([^„\"'”“]+?)[“\"'”]?\\s+v logu|v logu\\s+(?:najdi|hledej)\\s+[„\"']?([^„\"'”“]+)").find(text.lowercase())?.let { m ->
            return Intent.Tool("log.search", mapOf("q" to (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()), "hledání v logu")
        }
        if (has(t, "\\bcsv\\b|import") && has(t, "rozbil|nefunguje|proc|chyba|spatn|divn|nesedi|zkontroluj|nacti|prohlédni|prohledni")) return Intent.Tool("csv.inspect", emptyMap(), "diagnostika CSV")
        if (has(t, "\\bjson\\b") && (c.hasAttachment || text.trimStart().startsWith("{") || text.trimStart().startsWith("["))) return Intent.Tool("json.inspect", emptyMap(), "JSON")
        if (has(t, "najdi v (mych )?souborech|v mych souborech|v dokumentech najdi")) return Intent.Tool("index.search", emptyMap(), "lokální index")
        // --- programování
        if (has(t, "^(zaloz|vytvor|udelej|napis)\\b.*\\b(projekt|program|skript|aplikaci|nastroj|parser|kalkulack)") && !c.activeProject) return Intent.NewProject(text)
        if (c.activeProject) {
            if (has(t, "^(vysvetli|co dela|jak funguje|popis)\\b")) return Intent.Code(text, CodeKind.EXPLAIN)
            if (has(t, "^(spust|pust|otestuj)\\b.*\\btest|^testy$|^spust testy")) return Intent.Code(text, CodeKind.TEST)
            if (has(t, "^(pridej|oprav|uprav|zmen|prepis|refaktor|implementuj|dopln|odstran|smaz|prejmenuj|napis test|vytvor funkci|udelej)\\b")) return Intent.Code(text, CodeKind.CHANGE)
        }
        if (has(t, "^(zaloz|vytvor) (novy )?projekt")) return Intent.NewProject(text)
        // --- navázání na zobrazený výsledek
        if (c.lastResult && has(t, "divn|nesedi|to je spatne|tohle ne|proc (tam|je tam)|chyba v tom|zvlastni")) return Intent.FollowUp(text)
        // --- neobvyklá hypotéza (spiknutí, „ve skutečnosti“, „je možné, že…“)
        if (has(t, "^(je mozne|je pravda|myslim|co kdyz|co kdyby|mam teorii|teorie|hypotez)|ve skutecnosti|zatajuj|spiknut|konspirac|nikdo (ti|vam) nerekne|oficialni verze|je (to )?mozne,? ze") &&
            !has(t, "^(co kdyby(ch)? (jsem|jsme)|co kdyz (to|ten|tu) (udelam|zkusim))")) return Intent.Hypothesis(text)
        // --- přemýšlení nahlas a kreativní zadání = rozhovor (ne akce, ne rešerše)
        val aloud = has(t, "^(premyslim|napada me|co kdyby|mozna bych|hmm|asi bych|zvazuju|nevim jestli)")
        val creative = has(t, "napad|brainstorm|vymysli|navrhni (nazev|jmeno|logo|motto|varianty)|basen|basnick|pribeh|povidk|slogan|kreativ|rymovan|vtip|prani k|pozvank|nazev pro|jak (bych )?(to )?pojmenoval")
        if (aloud || creative) return Intent.Talk(aloud, creative)
        // --- holý název tématu → rešerše
        cz.axiom.omega.core.agent.Topic.bare(text)?.let { topic -> return Intent.Research(topic, Research.variants(topic)) }
        return Intent.Talk(false, false)
    }
}

/** Rešerše z holého názvu: pravopisné varianty, nejistá identifikace = víc možností, závěr úměrný podkladům. */
object Research {
    /** „operation Prato“ → „Operation Prato“, „operace Prato“, „Operazione Prato“, bez diakritiky… */
    fun variants(topic: String): List<String> {
        val t = topic.trim()
        val out = LinkedHashSet<String>()
        out += t
        out += t.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        val words = t.split(' ')
        val first = TextUtil.fold(words.first()).lowercase()
        val swaps = mapOf("operation" to listOf("operace", "operazione", "operación"), "operace" to listOf("operation"), "projekt" to listOf("project"), "project" to listOf("projekt"),
            "battle" to listOf("bitva"), "bitva" to listOf("battle"), "case" to listOf("kauza", "případ"), "kauza" to listOf("case", "affair"))
        swaps[first]?.forEach { s -> out += (listOf(s.replaceFirstChar { it.uppercaseChar() }) + words.drop(1)).joinToString(" ") }
        out += TextUtil.fold(t)
        return out.toList().take(6)
    }

    /** Různé identity v nalezených titulcích → ukázat možnosti místo tichého výběru jedné. */
    fun candidates(titles: List<String>): List<String> = titles.map { it.substringAfter(": ").substringBefore(" – ").trim() }.filter { it.isNotBlank() }.distinctBy { TextUtil.fold(it).lowercase() }.take(5)

    const val SYSTEM = """Jsi VEGA, český rešeršní parťák. Tykej. Pracuj jen s podklady [n] (zdroj, datum). Každé tvrzení označ [n].
Když identifikace tématu není jistá, napiš možnosti a co je odlišuje. U neobvyklé hypotézy dej nejlepší důkaz PRO a nejlepší důkaz PROTI a závěr úměrný podkladům.
Nevymýšlej zdroje ani data. Když podklady nestačí, řekni to."""

    /** Kontrola úplnosti dlouhého zadání: každá dílčí otázka musí mít v odpovědi vlastní část. */
    fun coverage(subQuestions: List<String>, answer: String): List<Pair<String, Boolean>> {
        val a = TextUtil.fold(answer).lowercase()
        return subQuestions.map { q ->
            val keys = TextUtil.words(TextUtil.fold(q).lowercase(), 4).filter { it !in setOf("jake", "ktere", "proc", "kolik", "jaky", "jaka", "muze", "bude", "jsou") }.map { it.take(6) }.distinct()
            val hit = keys.isEmpty() || keys.count { a.contains(it) } * 2 >= keys.size
            q to hit
        }
    }
}
