package cz.axiom.vega

import cz.axiom.vega.core.Evidence
import cz.axiom.vega.web.Snippet
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sestavení výsledku rešerše a hodnocení doložení pro UI (doporučení T1: z Ops.kt vyjmout nejdřív získání podkladů a sestavení
 * výsledku). Čisté funkce bez stavu – Ops jen volá; chování hlídají OpsBridgeEvidenceTest, OpsSourceIndependenceTest, OpsDecideTest.
 */
object EvidenceCards {
    const val CRITERIA = "Kritéria: původ a nezávislost zdroje, ověřitelnost, datace – stejná pro oficiální i alternativní výklad; pověst autora do hodnocení nevstupuje. Číselnou pravděpodobnost VEGA neuvádí."

    /** Položky karty zdrojů (rešerše i posouzení hypotézy – dřív dvě kopie): pořadí [n], text do 2 500 znaků, datum vydání × načtení. */
    fun sourceItems(src: List<Snippet>, today: String = java.time.LocalDate.now().toString()): JSONArray = JSONArray().apply {
        src.forEachIndexed { i, s -> put(JSONObject().put("n", i + 1).put("title", s.title).put("url", s.source).put("text", s.text.take(2500)).put("fetched", today).put("date", s.date)) }
    }

    /** Doklad do uloženého rozpracovaného hodnocení a zpět (adresa, datum vydání a načtení u uložených webových stránek se neztratí). */
    fun docJson(d: Evidence.Doc): JSONObject = JSONObject().put("name", d.name).put("text", d.text).putOpt("url", d.url).putOpt("published", d.published).putOpt("fetched", d.fetched)
    fun docOf(j: JSONObject): Evidence.Doc = Evidence.Doc(j.getString("name"), j.getString("text"), j.optString("url").ifEmpty { null }, j.optString("published").ifEmpty { null }, j.optString("fetched").ifEmpty { null })
    fun docsOf(a: JSONArray?): List<Evidence.Doc> = a?.let { (0 until it.length()).map { i -> docOf(it.getJSONObject(i)) } } ?: emptyList()
    fun docsJson(docs: List<Evidence.Doc>): JSONArray = JSONArray().apply { docs.forEach { put(docJson(it)) } }

    /** Části, jejichž stav se po novém dokladu změnil („datum …: jediný zdroj → potvrzeno …“). */
    fun changes(before: Evidence.Assessment?, after: Evidence.Assessment): List<String> = before?.let { b ->
        after.parts.mapNotNull { p -> b.parts.firstOrNull { it.label.substringBefore(" (") == p.label.substringBefore(" (") }?.takeIf { it.status != p.status }?.let { "${p.label}: ${it.status} → ${p.status}" } }
    }.orEmpty()

    /** Karta „Síla doložení“: části s pasážemi + podrobný audit (přímo / odvozeno / otevřené / co by to změnilo / kritéria). */
    fun evidenceCard(a: Evidence.Assessment): JSONObject = JSONObject().put("type", "evidence")
        .put("audit", JSONObject().put("direct", JSONArray(a.direct)).put("inferred", JSONArray(a.inferred)).put("open", JSONArray(a.open)).putOpt("missing", a.missing).put("criteria", CRITERIA))
        .put("parts", JSONArray().apply { a.parts.forEach { p ->
            put(JSONObject().put("label", p.label).put("status", p.status).put("lines", p.lines).put("support", JSONArray(p.support))
                .put("passages", JSONArray().apply { p.passages.forEach { ps -> put(JSONObject().put("doc", ps.doc).put("text", ps.text).putOpt("url", ps.url).putOpt("published", ps.published).putOpt("fetched", ps.fetched)) } }))
        } })
}
