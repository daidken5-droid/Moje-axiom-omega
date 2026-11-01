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

    /**
     * Uživatel odpověď ocenil (✓) → uloží se jako vzorová ukázka oblasti (few-shot v telefonu)
     * a jako trénovací příklad pro doladění modelu (LoRA). Hodnocení ✗ ukázku z oblasti odstraní.
     */
    @Synchronized
    fun exemplarRated(question: String, answer: String, good: Boolean) {
        if (question.isBlank() || answer.isBlank()) return
        val o = root()
        val d = Cognition.domainOf(question)
        val ex = o.optJSONObject("exemplars") ?: JSONObject()
        val list = ex.optJSONArray(d.name) ?: JSONArray()
        val k = Cognition.key(question)
        val keep = JSONArray()
        for (i in 0 until list.length()) list.getJSONObject(i).let { if (it.optString("key") != k) keep.put(it) }
        if (good && answer.length in 40..4_000) keep.put(JSONObject().put("key", k).put("q", question.take(600)).put("a", answer.take(4_000)))
        while (keep.length() > 5) keep.remove(0)
        ex.put(d.name, keep); o.put("exemplars", ex)
        val train = o.optJSONArray("training") ?: JSONArray()
        if (good && answer.length in 40..8_000) {
            train.put(JSONObject().put("q", question.take(2_000)).put("a", answer.take(8_000)).put("domain", d.name).put("time", System.currentTimeMillis()))
            while (train.length() > 500) train.remove(0)
        }
        o.put("training", train)
        save(o)
    }

    /** Nejnovější oceněná ukázka z oblasti – pro few-shot (zkrácená, aby šetřila kontext). */
    fun exemplar(d: Domain): String? {
        val list = root().optJSONObject("exemplars")?.optJSONArray(d.name) ?: return null
        if (list.length() == 0) return null
        val e = list.getJSONObject(list.length() - 1)
        val a = e.getString("a")
        if (a.length > 1_500) return null
        return "UKÁZKA ODPOVĚDI, KTEROU UŽIVATEL OCENIL (napodob kvalitu, strukturu a styl – ne obsah):\nOtázka: ${e.getString("q").take(300)}\nOdpověď: $a"
    }

    fun trainingCount(): Int = root().optJSONArray("training")?.length() ?: 0

    /** Trénovací data ve formátu chat JSONL (OpenAI/Unsloth/HF) – oceněné odpovědi. */
    fun trainingJsonl(system: String): List<String> {
        val t = root().optJSONArray("training") ?: return emptyList()
        return (0 until t.length()).map { t.getJSONObject(it) }.map { e ->
            JSONObject().put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", e.getString("q")))
                .put(JSONObject().put("role", "assistant").put("content", e.getString("a")))).toString()
        }
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
            .put("training", o.optJSONArray("training")?.length() ?: 0)
    }

    fun reset() = p.edit().clear().apply()
}
