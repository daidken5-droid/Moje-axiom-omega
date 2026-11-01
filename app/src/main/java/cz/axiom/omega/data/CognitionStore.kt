package cz.axiom.omega.data

import android.content.Context
import cz.axiom.omega.core.self.Cognition
import cz.axiom.omega.core.self.Cognition.Competence
import cz.axiom.omega.core.self.Cognition.Domain
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trvalá paměť učení AVIORu: kompetence po oblastech, otevřené otázky, deník vývoje
 * a odkazy na vlastní poznatky v znalostní databázi.
 */
class CognitionStore(context: Context, private val db: Db) {
    private val p = context.applicationContext.getSharedPreferences("cognition", Context.MODE_PRIVATE)

    @Synchronized
    private fun root(): JSONObject = runCatching { JSONObject(p.getString("root", "{}")!!) }.getOrDefault(JSONObject())

    @Synchronized
    private fun save(o: JSONObject) = p.edit().putString("root", o.toString()).apply()

    private fun comp(j: JSONObject?): Competence? = j?.let {
        Competence(it.optInt("q"), if (it.has("conf") && !it.isNull("conf")) it.getInt("conf") else null, it.optInt("good"), it.optInt("bad"),
            it.optInt("rcs"), it.optInt("ins"), it.optLong("first"))
    }

    private fun compJson(c: Competence) = JSONObject().put("q", c.questions).put("conf", c.avgConfidence ?: JSONObject.NULL).put("good", c.good)
        .put("bad", c.bad).put("rcs", c.ratedConfidenceSum).put("ins", c.insights).put("first", c.firstSeen)

    fun competence(d: Domain): Competence? = comp(root().optJSONObject("domains")?.optJSONObject(d.name))
    fun totalQuestions(): Int = root().optInt("total")

    /** Metakognice před odpovědí. */
    fun brief(question: String): Pair<Domain, String> {
        val d = Cognition.domainOf(question)
        return d to Cognition.metacognition(d, competence(d), totalQuestions())
    }

    /** Učební smyčka po odpovědi. Vrací nové milníky (pro oznámení). */
    @Synchronized
    fun learn(question: String, answer: String, confidence: Int?, ok: Boolean): List<String> {
        if (!ok) return emptyList()
        val o = root()
        val domains = o.optJSONObject("domains") ?: JSONObject()
        val d = Cognition.domainOf(question)
        val ins = Cognition.distill(question, answer, confidence, d)
        if (ins != null) {
            val keys = o.optJSONObject("insights") ?: JSONObject()
            if (keys.has(ins.key)) runCatching { db.deleteDocument(keys.getLong(ins.key)) }
            keys.put(ins.key, db.addDocument(ins.title, ins.text, "self:insight"))
            o.put("insights", keys)
            o.put("insightCount", o.optInt("insightCount") + if (keys.length() > o.optInt("insightCount")) 1 else 0)
        }
        val c = Cognition.learn(comp(domains.optJSONObject(d.name)), confidence, ins != null)
        domains.put(d.name, compJson(c))
        o.put("domains", domains)
        val total = o.optInt("total") + 1
        o.put("total", total)
        if (Cognition.isOpen(answer, confidence)) {
            val open = o.optJSONArray("open") ?: JSONArray()
            val k = Cognition.key(question)
            if ((0 until open.length()).none { open.getJSONObject(it).optString("key") == k }) {
                open.put(JSONObject().put("q", question.take(300)).put("key", k).put("domain", d.name).put("time", System.currentTimeMillis()))
                while (open.length() > 30) open.remove(0)
            }
            o.put("open", open)
        }
        val ms = Cognition.milestones(total, d, c, (o.optJSONObject("insights")?.length() ?: 0))
        if (ms.isNotEmpty()) journal(o, ms)
        save(o)
        return ms
    }

    @Synchronized
    fun rate(question: String, good: Boolean, stated: Int?) {
        val o = root()
        val domains = o.optJSONObject("domains") ?: JSONObject()
        val d = Cognition.domainOf(question)
        val c = Cognition.rate(comp(domains.optJSONObject(d.name)), good, stated)
        domains.put(d.name, compJson(c)); o.put("domains", domains)
        Cognition.milestones(o.optInt("total"), d, c, o.optJSONObject("insights")?.length() ?: 0)
            .filter { it.contains("zkalibroval") }.takeIf { it.isNotEmpty() }?.let { journal(o, it) }
        save(o)
    }

    fun openQuestions(): List<JSONObject> = root().optJSONArray("open")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    @Synchronized
    fun resolve(key: String, note: String) {
        val o = root()
        val open = o.optJSONArray("open") ?: return
        val keep = JSONArray()
        for (i in 0 until open.length()) open.getJSONObject(i).let { if (it.optString("key") != key) keep.put(it) }
        o.put("open", keep)
        o.put("studied", o.optInt("studied") + 1)
        o.put("lastStudy", System.currentTimeMillis())
        journal(o, listOf(note))
        save(o)
    }

    fun lastStudy(): Long = root().optLong("lastStudy")

    private fun journal(o: JSONObject, entries: List<String>) {
        val j = o.optJSONArray("journal") ?: JSONArray()
        val out = JSONArray()
        entries.forEach { out.put(JSONObject().put("text", it).put("time", System.currentTimeMillis())) }
        for (i in 0 until minOf(j.length(), 100 - entries.size)) out.put(j.getJSONObject(i))
        o.put("journal", out)
    }

    fun toJson(): JSONObject {
        val o = root()
        val domains = JSONArray()
        Domain.entries.forEach { d ->
            val c = comp(o.optJSONObject("domains")?.optJSONObject(d.name)) ?: return@forEach
            domains.put(JSONObject().put("id", d.name).put("label", d.label).put("icon", d.icon).put("questions", c.questions)
                .put("mastery", c.mastery).put("approval", c.approval ?: JSONObject.NULL).put("conf", c.avgConfidence ?: JSONObject.NULL)
                .put("calibration", c.calibration ?: JSONObject.NULL).put("weak", c.weak).put("insights", c.insights))
        }
        return JSONObject().put("total", o.optInt("total")).put("insights", o.optJSONObject("insights")?.length() ?: 0)
            .put("studied", o.optInt("studied")).put("domains", domains)
            .put("open", JSONArray().apply { openQuestions().forEach { put(it) } })
            .put("journal", o.optJSONArray("journal") ?: JSONArray())
    }

    fun reset() = p.edit().clear().apply()
}
