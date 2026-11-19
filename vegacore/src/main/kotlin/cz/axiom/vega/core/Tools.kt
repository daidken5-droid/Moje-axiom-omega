package cz.axiom.vega.core

import cz.axiom.vega.web.Snippet
import cz.axiom.vega.llm.CancelToken
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Oprávnění nástroje (zobrazí se u nástroje i u uloženého postupu). */
enum class Perm(val label: String) { NETWORK("síť"), FILES("zvolené soubory"), NFC("NFC"), INDEX("lokální index") }

data class Param(val key: String, val label: String, val default: String = "", val hint: String = "")

/** Prostředí běhu nástroje: zrušení, síť a lokální index dodává aplikace (v testech náhrady). */
interface ToolCtx {
    val cancel: CancelToken
    /** Text veřejné stránky (SSRF ochrana, limit). Bez sítě vyhodí výjimku. */
    fun fetch(url: String): String = throw ToolError("Síť není k dispozici", recoverable = true)
    fun index(query: String, limit: Int): List<Snippet> = emptyList()
}

class ToolError(msg: String, val recoverable: Boolean = false) : Exception(msg)

/**
 * JEDNA REGISTRACE NÁSTROJE: vstup, výsledný typ, dostupnost offline, oprávnění, chybové stavy, zrušitelnost.
 * Chat i ruční nabídka volají stejné [run] – dvě rovnocenné cesty, jedna implementace.
 */
class ToolDef(
    val id: String,
    val title: String,
    val description: String,
    val input: Set<Kind>,
    val output: Kind,
    val offline: Boolean = true,
    val perms: Set<Perm> = emptySet(),
    val cancellable: Boolean = true,
    val errors: List<String> = emptyList(),
    val params: List<Param> = emptyList(),
    val group: String = "Data",
    private val impl: (Value, Map<String, String>, ToolCtx) -> Value,
) {
    fun accepts(v: Value) = Kind.ANY in input || v.kind in input || (v.kind == Kind.BYTES && Kind.TEXT in input) || (v.kind == Kind.TABLE && Kind.TEXT in input)

    fun run(v: Value, params: Map<String, String>, ctx: ToolCtx): Value {
        ctx.cancel.check()
        if (!accepts(v)) throw ToolError("$title čeká ${input.joinToString(" / ") { it.label }}, dostal ${v.kind.label}")
        val p = this.params.associate { it.key to it.default } + params.filterValues { it.isNotEmpty() }
        return impl(v, p, ctx)
    }

    fun describe() = buildJsonObject {
        put("id", id); put("title", title); put("description", description); put("group", group)
        put("input", buildJsonArray { input.forEach { add(JsonPrimitive(it.name)) } }); put("output", output.name)
        put("offline", offline); put("cancellable", cancellable)
        put("perms", buildJsonArray { perms.forEach { add(JsonPrimitive(it.label)) } })
        put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
        put("params", buildJsonArray { params.forEach { add(buildJsonObject { put("key", it.key); put("label", it.label); put("default", it.default); put("hint", it.hint) }) } })
    }
}

object Tools {
    private fun text(v: Value) = v.asText()
    private fun bytes(v: Value) = if (v is Value.Bytes) v.bytes else v.asText().toByteArray(Charsets.UTF_8)

    val BUILTIN: List<ToolDef> = listOf(
        ToolDef("statement.process", "Zpracovat výpis", "Bankovní / účetní export (CSV) → jednotná tabulka Datum · Částka · Měna · Popis + souhrn. Pozná české desetinné čárky.",
            setOf(Kind.TEXT, Kind.BYTES), Kind.TABLE, errors = listOf("nerozpoznané sloupce", "neplatné řádky (vypíšou se s číslem řádku)"),
            params = listOf(Param("spec", "Parser (JSON, prázdné = rozpoznat)", hint = "uloží se do postupu")), group = "Data") { v, p, _ ->
            val t = text(v)
            val spec = p["spec"]?.takeIf { it.isNotBlank() }?.let { Statement.ParserSpec.parse(it) } ?: Statement.detect(t).let { d ->
                d.spec ?: throw ToolError("Formát výpisu nepoznám: ${d.warnings.joinToString(" ")}")
            }
            if (spec.dateCol < 0 || spec.amountCol < 0) throw ToolError("Parser nemá sloupec data nebo částky – rozpoznej formát znovu.")
            val tb = Statement.process(t, spec).table
            Statement.reconcileText(t, spec)?.let { tb.copy(notes = tb.notes + it) } ?: tb
        },
        ToolDef("statement.detect", "Rozpoznat formát výpisu", "Zjistí oddělovač, sloupce, desetinnou čárku a ověří parser na vzorku.",
            setOf(Kind.TEXT, Kind.BYTES), Kind.JSON, group = "Data") { v, _, _ ->
            val t = text(v); val d = Statement.detect(t)
            val ver = d.spec?.let { Statement.verify(t, it) }
            Value.Json(buildJsonObject {
                put("headers", buildJsonArray { d.headers.forEach { add(JsonPrimitive(it)) } })
                put("warnings", buildJsonArray { d.warnings.forEach { add(JsonPrimitive(it)) } })
                d.spec?.let { put("spec", it.json()) }
                ver?.let { put("verified", it.first); put("verification", it.second) }
            })
        },
        ToolDef("statement.months", "Součty po měsících", "Tabulka výpisu → příjmy, výdaje a saldo po měsících.", setOf(Kind.TABLE), Kind.TABLE, group = "Data") { v, _, _ ->
            Statement.byMonth(v as? Value.Table ?: throw ToolError("Čekám tabulku výpisu"))
        },
        ToolDef("csv.inspect", "Inspektor CSV", "Načte CSV (; , tab |), ukáže problémové řádky (uvozovky, počet polí) a umí filtrovat.",
            setOf(Kind.TEXT, Kind.BYTES), Kind.TABLE, errors = listOf("neuzavřené uvozovky", "nekonzistentní počet polí"),
            params = listOf(Param("filter", "Filtr", hint = "Částka < 0, Popis ~ nájem"), Param("delimiter", "Oddělovač", hint = "prázdné = rozpoznat")), group = "Data") { v, p, _ ->
            val parsed = Csv.read(text(v), p["delimiter"]?.firstOrNull())
            val notes = buildList {
                add("Oddělovač „${if (parsed.delimiter == '\t') "tab" else parsed.delimiter}“ · ${parsed.headers.size} sloupců · ${parsed.rows.size} řádků")
                if (parsed.issues.isEmpty()) add("Bez problémových řádků.")
                parsed.issues.take(10).forEach { add("Řádek ${it.line}: ${it.message} → ${it.raw.take(120)}") }
            }
            TableFilter.apply(Value.Table(parsed.headers, parsed.rows, notes, (v as? Value.Bytes)?.name), p["filter"] ?: "").let { it.copy(notes = notes + it.notes.filter { n -> n.startsWith("Filtr") }) }
        },
        ToolDef("csv.export", "Export CSV", "Tabulka → CSV (výchozí středník a UTF-8 s BOM pro český Excel).", setOf(Kind.TABLE), Kind.BYTES,
            params = listOf(Param("delimiter", "Oddělovač", ";"), Param("name", "Název souboru", "export.csv")), group = "Data") { v, p, _ ->
            val t = v as? Value.Table ?: throw ToolError("Čekám tabulku")
            Value.Bytes(p["name"] ?: "export.csv", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + Csv.write(t, p["delimiter"]?.firstOrNull() ?: ';').toByteArray(), "text/csv")
        },
        ToolDef("table.filter", "Filtr tabulky", "Řádky podle podmínky (sloupec op hodnota).", setOf(Kind.TABLE), Kind.TABLE,
            params = listOf(Param("expr", "Podmínka", hint = "Částka < -1000")), group = "Data") { v, p, _ ->
            TableFilter.apply(v as? Value.Table ?: throw ToolError("Čekám tabulku"), p["expr"] ?: "")
        },
        ToolDef("table.sum", "Součet sloupce", "Součet (i podle skupin).", setOf(Kind.TABLE), Kind.TABLE,
            params = listOf(Param("col", "Sloupec", "Částka"), Param("by", "Seskupit podle", "")), group = "Data") { v, p, _ ->
            TableFilter.sum(v as? Value.Table ?: throw ToolError("Čekám tabulku"), p["col"] ?: "Částka", p["by"]?.takeIf { it.isNotBlank() })
        },
        ToolDef("json.inspect", "Inspektor JSON", "Ověří JSON (s místem chyby), vybere cestu ($.data[0].name, items.*.id).", setOf(Kind.TEXT, Kind.BYTES, Kind.JSON), Kind.JSON,
            errors = listOf("neplatný JSON (řádek/sloupec)"), params = listOf(Param("path", "Cesta", hint = "$.items[*].name")), group = "Data") { v, p, _ ->
            val root = (v as? Value.Json)?.json ?: JsonTool.parse(text(v)).let { (e, err) -> e ?: throw ToolError(err ?: "Neplatný JSON") }
            val path = p["path"].orEmpty()
            if (path.isBlank()) Value.Json(root) else Value.Json(kotlinx.serialization.json.JsonArray(JsonTool.select(root, path)))
        },
        ToolDef("json.table", "JSON → tabulka", "Pole objektů jako tabulka (pak filtr / export CSV).", setOf(Kind.JSON, Kind.TEXT, Kind.BYTES), Kind.TABLE, group = "Data") { v, _, _ ->
            JsonTool.toTable((v as? Value.Json)?.json ?: JsonTool.parse(text(v)).first ?: throw ToolError("Neplatný JSON"))
        },
        ToolDef("text.diff", "Rozdíl textů a kódu", "Řádkový rozdíl (Myers) dvou textů / verzí souboru.", setOf(Kind.TEXT, Kind.BYTES), Kind.DIFF,
            params = listOf(Param("against", "Porovnat s (text)")), group = "Kód") { v, p, _ ->
            Diff.lines(text(v), p["against"].orEmpty())
        },
        ToolDef("hash", "Kontrolní součty", "SHA-256, SHA-1, SHA-512, MD5, CRC32; ověří očekávaný otisk.", setOf(Kind.TEXT, Kind.BYTES), Kind.TABLE,
            params = listOf(Param("expect", "Očekávaný otisk")), group = "Soubory") { v, p, _ ->
            Checksum.table(bytes(v), p["expect"])
        },
        ToolDef("log.search", "Hledání v logu", "Text nebo regulární výraz, kontext, úroveň (ERROR/WARN).", setOf(Kind.TEXT, Kind.BYTES), Kind.TABLE,
            params = listOf(Param("q", "Hledat"), Param("regex", "Regex (ano/ne)", "ne"), Param("context", "Kontext řádků", "1"), Param("level", "Úroveň", hint = "ERROR")), group = "Kód") { v, p, _ ->
            LogTool.search(text(v), p["q"].orEmpty(), p["regex"] == "ano", p["context"]?.toIntOrNull()?.coerceIn(0, 20) ?: 1, p["level"])
        },
        ToolDef("log.crash", "Rozbor pádu", "Najde výjimku, příčinu a místo ve vlastním kódu (Java/Kotlin, Python, JS).", setOf(Kind.TEXT, Kind.BYTES), Kind.TABLE,
            errors = listOf("v logu není výjimka"), params = listOf(Param("pkg", "Vlastní balíček", hint = "cz.firma.app")), group = "Kód") { v, p, _ ->
            LogTool.crashTable(LogTool.crash(text(v), p["pkg"]?.takeIf { it.isNotBlank() }) ?: throw ToolError("V logu jsem nenašel výjimku ani traceback."))
        },
        ToolDef("url.read", "Číst veřejnou URL", "Hlavní text veřejné stránky (jen https, ne vnitřní síť).", setOf(Kind.URL, Kind.TEXT), Kind.TEXT, offline = false,
            perms = setOf(Perm.NETWORK), errors = listOf("bez sítě", "neveřejná adresa", "stránka nevrátila text"), group = "Web") { v, _, ctx ->
            val url = Regex("https?://\\S+").find(v.asText())?.value?.trimEnd('.', ',', ')') ?: throw ToolError("Ve vstupu není adresa https://…")
            Value.Text(ctx.fetch(url), url)
        },
        ToolDef("ndef.parse", "Čtení NDEF", "NFC záznam (text, URL, MIME, Smart Poster) z bajtů zprávy.", setOf(Kind.BYTES, Kind.NDEF), Kind.NDEF,
            perms = setOf(Perm.NFC), errors = listOf("useknutá / poškozená zpráva"), group = "Zařízení") { v, _, _ ->
            if (v is Value.Ndef) v else try { Value.Ndef(NdefParser.parse(bytes(v))) } catch (e: NdefParser.NdefException) { throw ToolError(e.message ?: "Poškozená zpráva") }
        },
        ToolDef("index.search", "Hledat v mých souborech", "Lokální fulltext (i text z OCR) bez sítě a bez modelu.", setOf(Kind.TEXT), Kind.TABLE,
            perms = setOf(Perm.INDEX), group = "Soubory") { v, _, ctx ->
            val hits = ctx.index(v.asText(), 20)
            Value.Table(listOf("Soubor", "Úryvek"), hits.map { listOf(it.title, it.text.replace(Regex("\\s+"), " ").take(220)) }, listOf("${hits.size} výsledků v lokálním indexu (bez sítě)"))
        },
    )

    private val extra = LinkedHashMap<String, ToolDef>()
    /** Aplikace přidá nástroje závislé na Androidu (OCR, PDF) – se stejnou registrací. */
    fun register(t: ToolDef) { extra[t.id] = t }
    fun all(): List<ToolDef> = BUILTIN + extra.values
    fun get(id: String) = all().firstOrNull { it.id == id }
    /** Nástroje, které umí navázat na [v] (nabídka „Předat dál“). */
    fun next(v: Value) = all().filter { it.accepts(v) }
}
