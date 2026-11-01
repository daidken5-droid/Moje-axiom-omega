package cz.axiom.omega.core.agent

import cz.axiom.omega.core.hub.HfHub
import cz.axiom.omega.core.hub.Web
import cz.axiom.omega.core.llm.CancelToken
import cz.axiom.omega.core.self.MemoryItem
import cz.axiom.omega.core.self.MemoryKind
import cz.axiom.omega.core.self.MemoryRanker
import cz.axiom.omega.core.step0.Calc
import cz.axiom.omega.core.step0.Rates
import cz.axiom.omega.core.step0.Step0
import cz.axiom.omega.core.util.TextUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vestavěné nástroje aktivního agenta. Síťové se přidají jen online. */
object Tools {

    private fun JsonObject.arg(vararg keys: String): String =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            ?: throw IllegalArgumentException("Chybí argument ${keys.first()}.")

    private fun tool(name: String, args: String, description: String, run: (JsonObject, CancelToken) -> String) = object : AgentTool {
        override val name = name
        override val args = args
        override val description = description
        override fun run(args: JsonObject, cancel: CancelToken) = run(args, cancel)
    }

    fun offline(knowledge: KnowledgeSource, memory: MemoryStore, rates: () -> Rates, notes: (String, String) -> Unit): List<AgentTool> = listOf(
        tool("calc", """{"expr": "2*(3+4)^2"}""", "přesný výpočet výrazu (+ - * / ^ %, sqrt, ln, log, sin, cos, pi)") { a, _ ->
            val e = a.arg("expr", "expression", "q")
            Calc.eval(e).fold({ "$e = ${TextUtil.num(it, 8)}" }, { "Chyba výpočtu: ${it.message}" })
        },
        tool("step0", """{"text": "kurz 2,1 a odhad 55 %"}""", "deterministická analýza: jednotky, měny, +EV/Kelly, stavba, import, forenzní stopy") { a, _ ->
            Step0.analyze(a.arg("text", "q"), rates()).joinToString("\n").ifBlank { "Nic počitatelného nenalezeno." }
        },
        tool("kb_search", """{"q": "dotaz"}""", "hledání v offline znalostní databázi v telefonu") { a, _ ->
            knowledge.search(a.arg("q", "query"), 5).joinToString("\n\n") { "[${it.id}] ${it.title}: ${TextUtil.clip(TextUtil.normalize(it.text), 900)}" }
                .ifBlank { "V offline databázi nic nenalezeno." }
        },
        tool("memory_search", """{"q": "dotaz"}""", "co si pamatuju o uživateli a z minulých úloh") { a, _ ->
            MemoryRanker.rank(a.arg("q", "query"), memory.all(), 8).joinToString("\n") { "- [${it.kind.label}] ${it.text}" }.ifBlank { "Nic relevantního v paměti." }
        },
        tool("memory_save", """{"text": "fakt k zapamatování"}""", "uloží důležitý fakt do dlouhodobé paměti") { a, _ ->
            val t = a.arg("text", "fact")
            memory.remember(MemoryItem(kind = MemoryKind.FACT, text = TextUtil.clip(t, 400), weight = 1.0))
            "Uloženo do paměti."
        },
        tool("note", """{"title": "název", "text": "obsah"}""", "uloží výsledek práce jako dokument do offline databáze") { a, _ ->
            notes(a.arg("title", "name").take(120), a.arg("text", "content"))
            "Poznámka uložena do znalostí."
        },
        tool("time", "{}", "aktuální datum a čas v telefonu") { _, _ ->
            SimpleDateFormat("EEEE d. M. yyyy HH:mm", Locale("cs", "CZ")).format(Date())
        },
    )

    fun online(hub: HfHub = HfHub()): List<AgentTool> = listOf(
        tool("web_search", """{"q": "dotaz"}""", "vyhledá na webu (DuckDuckGo) a vrátí odkazy s úryvky") { a, _ ->
            Web.search(a.arg("q", "query")).joinToString("\n") { "- ${it.title} — ${it.url}\n  ${TextUtil.clip(it.snippet, 240)}" }
                .ifBlank { "Žádné výsledky (zkus wiki_search nebo jiná slova)." }
        },
        tool("fetch_url", """{"url": "https://…"}""", "otevře veřejnou https stránku a vrátí její text") { a, _ ->
            Web.fetchText(a.arg("url"))
        },
        tool("wiki_search", """{"q": "dotaz", "lang": "cs"}""", "najde článek na Wikipedii a vrátí jeho začátek") { a, _ ->
            val lang = (a["lang"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "cs"
            val q = a.arg("q", "query")
            val titles = Web.wikiSearch(q, lang, 3)
            if (titles.isEmpty()) return@tool "Na Wikipedii ($lang) nic nenalezeno."
            val page = Web.wikiPage(titles.first(), lang)
            buildString {
                append("Nalezeno: ${titles.joinToString()}\n\n")
                if (page != null) append("${page.title} (${page.url}):\n${TextUtil.clip(page.text, 5_000)}")
            }
        },
        tool("hf_search", """{"q": "gemma 4"}""", "najde GGUF modely na Hugging Face") { a, _ ->
            hub.searchModels(a.arg("q", "query"), 8).joinToString("\n") { "- ${it.id} · ${it.downloads} stažení · ${it.updated}" }.ifBlank { "Nic nenalezeno." }
        },
    )
}
