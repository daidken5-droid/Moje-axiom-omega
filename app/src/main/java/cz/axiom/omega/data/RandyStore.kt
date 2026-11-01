package cz.axiom.omega.data

import android.content.Context
import cz.axiom.omega.core.agent.Variant
import cz.axiom.omega.core.randy.Supervisor
import org.json.JSONArray
import org.json.JSONObject

/** Paměť RANDY Supervisoru: statistiky efektivity variant a poslední rozhodnutí (pro přehled v Nastavení). */
class RandyStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("randy", Context.MODE_PRIVATE)

    @Synchronized
    fun stats(): Map<Variant, Supervisor.VariantStat> {
        val o = runCatching { JSONObject(p.getString("stats", "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().mapNotNull { k ->
            val v = Variant.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
            val j = o.getJSONObject(k)
            v to Supervisor.VariantStat(j.optInt("runs"), j.optLong("ms"), if (j.has("conf")) j.getInt("conf") else null, j.optInt("good"), j.optInt("bad"), j.optInt("esc"))
        }.toMap()
    }

    @Synchronized
    private fun put(v: Variant, st: Supervisor.VariantStat) {
        val o = runCatching { JSONObject(p.getString("stats", "{}")!!) }.getOrDefault(JSONObject())
        o.put(v.name, JSONObject().put("runs", st.runs).put("ms", st.avgMillis).put("conf", st.avgConfidence ?: JSONObject.NULL)
            .put("good", st.good).put("bad", st.bad).put("esc", st.escalations))
        p.edit().putString("stats", o.toString()).apply()
    }

    fun record(v: Variant, millis: Long, confidence: Int?, escalated: Boolean) = put(v, Supervisor.update(stats()[v], millis, confidence, escalated))

    fun rate(variantName: String, good: Boolean) {
        val v = Variant.entries.firstOrNull { it.name == variantName } ?: return
        put(v, Supervisor.rated(stats()[v], good))
    }

    @Synchronized
    fun log(entry: JSONObject) {
        val arr = runCatching { JSONArray(p.getString("log", "[]")!!) }.getOrDefault(JSONArray())
        val out = JSONArray().put(entry.put("time", System.currentTimeMillis()))
        for (i in 0 until minOf(arr.length(), 29)) out.put(arr.getJSONObject(i))
        p.edit().putString("log", out.toString()).apply()
    }

    fun toJson(authority: Supervisor.Authority): JSONObject {
        val st = stats()
        val variants = JSONArray()
        var runs = 0; var effSum = 0; var effN = 0; var esc = 0
        for (v in Variant.entries) {
            val s = st[v] ?: continue
            runs += s.runs; esc += s.escalations
            s.efficiency?.let { effSum += it * s.runs; effN += s.runs }
            variants.put(JSONObject().put("id", v.name).put("title", v.title).put("icon", v.icon).put("runs", s.runs).put("avgMs", s.avgMillis)
                .put("conf", s.avgConfidence ?: JSONObject.NULL).put("approval", s.approval ?: JSONObject.NULL).put("eff", s.efficiency ?: JSONObject.NULL))
        }
        return JSONObject().put("authority", authority.name).put("runs", runs).put("escalations", esc)
            .put("efficiency", if (effN > 0) effSum / effN else JSONObject.NULL).put("variants", variants)
            .put("log", runCatching { JSONArray(p.getString("log", "[]")!!) }.getOrDefault(JSONArray()))
            .put("authorities", JSONArray().apply { Supervisor.Authority.entries.forEach { put(JSONObject().put("id", it.name).put("label", it.label).put("desc", it.description)) } })
    }

    fun reset() = p.edit().clear().apply()
}
