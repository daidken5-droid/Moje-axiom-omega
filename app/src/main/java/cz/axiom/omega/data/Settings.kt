package cz.axiom.omega.data

import android.content.Context
import cz.axiom.omega.core.llm.ClaudeEngine
import cz.axiom.omega.core.llm.KimiEngine
import cz.axiom.omega.core.step0.Rates
import org.json.JSONObject

/** Ne-tajná nastavení (tajné klíče jsou ve [Vault]). */
class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("omega", Context.MODE_PRIVATE)

    /** auto | local | kimi | server | claude | council */
    var mode: String
        get() = p.getString("mode", "auto")!!
        set(v) = p.edit().putString("mode", v).apply()

    var kimiBase: String
        get() = p.getString("kimiBase", KimiEngine.CLOUD_BASE)!!
        set(v) = p.edit().putString("kimiBase", v.trim().trimEnd('/')).apply()
    var kimiModel: String
        get() = p.getString("kimiModel", KimiEngine.CLOUD_MODEL)!!
        set(v) = p.edit().putString("kimiModel", v.trim()).apply()

    var serverUrl: String
        get() = p.getString("serverUrl", "")!!
        set(v) = p.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()
    var serverModel: String
        get() = p.getString("serverModel", "kimi-k3")!!
        set(v) = p.edit().putString("serverModel", v.trim()).apply()

    var nodeUrl: String
        get() = p.getString("nodeUrl", "")!!
        set(v) = p.edit().putString("nodeUrl", v.trim().trimEnd('/')).apply()
    var nodeModel: String
        get() = p.getString("nodeModel", "axiom")!!
        set(v) = p.edit().putString("nodeModel", v.trim()).apply()
    var hfRepo: String
        get() = p.getString("hfRepo", "")!!
        set(v) = p.edit().putString("hfRepo", v.trim()).apply()
    var agentSteps: Int
        get() = p.getInt("agentSteps", 10)
        set(v) = p.edit().putInt("agentSteps", v.coerceIn(3, 25)).apply()
    var semantic: Boolean
        get() = p.getBoolean("semantic", true)
        set(v) = p.edit().putBoolean("semantic", v).apply()

    var claudeModel: String
        get() = p.getString("claudeModel", ClaudeEngine.DEFAULT_MODEL)!!
        set(v) = p.edit().putString("claudeModel", v.trim()).apply()

    var activeBrain: String?
        get() = p.getString("activeBrain", null)
        set(v) = p.edit().putString("activeBrain", v).apply()

    var maxPasses: Int
        get() = p.getInt("maxPasses", 3)
        set(v) = p.edit().putInt("maxPasses", v.coerceIn(1, 3)).apply()
    var localMaxPasses: Int
        get() = p.getInt("localMaxPasses", 2)
        set(v) = p.edit().putInt("localMaxPasses", v.coerceIn(1, 3)).apply()
    var reflect: Boolean
        get() = p.getBoolean("reflect", true)
        set(v) = p.edit().putBoolean("reflect", v).apply()
    var web: Boolean
        get() = p.getBoolean("web", true)
        set(v) = p.edit().putBoolean("web", v).apply()
    var thinking: Boolean
        get() = p.getBoolean("thinking", false)
        set(v) = p.edit().putBoolean("thinking", v).apply()
    var ctxTokens: Int
        get() = p.getInt("ctxTokens", 8192)
        set(v) = p.edit().putInt("ctxTokens", v.coerceIn(2048, 16384)).apply()
    /** false = kontext volí profil zařízení podle režimu výkonu (doporučeno) */
    var ctxManual: Boolean
        get() = p.getBoolean("ctxManual", false)
        set(v) = p.edit().putBoolean("ctxManual", v).apply()
    /** Okamžité odpovědi bez mozku na jednoduché otázky (Ω Flash · okamžitě). */
    var instant: Boolean
        get() = p.getBoolean("instant", true)
        set(v) = p.edit().putBoolean("instant", v).apply()
    /** Claude jako posila: těžké otázky (Sherlock / hluboké úrovně) poslat Claude, když je klíč a internet. */
    var claudeBoost: Boolean
        get() = p.getBoolean("claudeBoost", true)
        set(v) = p.edit().putBoolean("claudeBoost", v).apply()
    /** Velmi složité otázky automaticky řešit oběma mozky (telefon + cloud současně). */
    var autoDuo: Boolean
        get() = p.getBoolean("autoDuo", true)
        set(v) = p.edit().putBoolean("autoDuo", v).apply()
    /** Mozek držet trvale v paměti (služba „AVIOR připraven“) – dotazy bez čekání na načtení. */
    var keepBrain: Boolean
        get() = p.getBoolean("keepBrain", true)
        set(v) = p.edit().putBoolean("keepBrain", v).apply()
    /** Paměťově bezpečný režim (zapne se sám po pádu kvůli paměti): menší kontext, bez sémantiky, úsporné váhy. */
    var memSafe: Boolean
        get() = p.getBoolean("memSafe", false)
        set(v) = p.edit().putBoolean("memSafe", v).apply()
    /** Čas posledního ukončení procesu, které už uživatel viděl (ApplicationExitInfo). */
    var exitSeen: Long
        get() = p.getLong("exitSeen", 0)
        set(v) = p.edit().putLong("exitSeen", v).apply()
    /** Režim výkonu: BASIC | ADVANCED | SHERLOCK */
    var power: String
        get() = p.getString("power", "ADVANCED")!!
        set(v) = p.edit().putString("power", cz.axiom.omega.core.device.Power.of(v).name).apply()
    var theme: String
        get() = p.getString("theme", "obsidian")!!
        set(v) = p.edit().putString("theme", if (v in setOf("obsidian", "platinum", "auto")) v else "obsidian").apply()
    var font: Int
        get() = p.getInt("font", 15)
        set(v) = p.edit().putInt("font", v.coerceIn(13, 21)).apply()
    var userName: String
        get() = p.getString("userName", "")!!
        set(v) = p.edit().putString("userName", v.trim().take(40)).apply()
    var councilJudge: String
        get() = p.getString("councilJudge", "local")!!
        set(v) = p.edit().putString("councilJudge", v).apply()
    /** Pravomoci RANDY Supervisoru: ADVISORY | GOVERN | FULL */
    var randyAuthority: String
        get() = p.getString("randyAuthority", "FULL")!!
        set(v) = p.edit().putString("randyAuthority", cz.axiom.omega.core.randy.Supervisor.Authority.of(v).name).apply()
    /** Učit se z každé otázky (kompetence, poznatky, kalibrace). */
    var learning: Boolean
        get() = p.getBoolean("learning", true)
        set(v) = p.edit().putBoolean("learning", v).apply()
    /** Samostudium otevřených otázek při nabíjení na Wi-Fi. */
    var selfStudy: Boolean
        get() = p.getBoolean("selfStudy", true)
        set(v) = p.edit().putBoolean("selfStudy", v).apply()
    /** Doladění: aktivní LoRA adaptér (název souboru v files/lora) a jeho síla v %. */
    var lora: String
        get() = p.getString("lora", "")!!
        set(v) = p.edit().putString("lora", v).apply()
    var loraScale: Int
        get() = p.getInt("loraScale", 100)
        set(v) = p.edit().putInt("loraScale", v.coerceIn(10, 200)).apply()
    /** Služby Hugging Face (Inference Providers) – modely a přepínače. */
    var hfChat: Boolean
        get() = p.getBoolean("hfChat", true)
        set(v) = p.edit().putBoolean("hfChat", v).apply()
    var hfVision: Boolean
        get() = p.getBoolean("hfVision", true)
        set(v) = p.edit().putBoolean("hfVision", v).apply()
    var hfChatModel: String
        get() = p.getString("hfChatModel", cz.axiom.omega.core.hub.HfServices.DEFAULT_CHAT)!!
        set(v) = p.edit().putString("hfChatModel", v.trim()).apply()
    var hfVisionModel: String
        get() = p.getString("hfVisionModel", cz.axiom.omega.core.hub.HfServices.DEFAULT_VISION)!!
        set(v) = p.edit().putString("hfVisionModel", v.trim()).apply()
    var hfAsrModel: String
        get() = p.getString("hfAsrModel", cz.axiom.omega.core.hub.HfServices.DEFAULT_ASR)!!
        set(v) = p.edit().putString("hfAsrModel", v.trim()).apply()
    var hfImageModel: String
        get() = p.getString("hfImageModel", cz.axiom.omega.core.hub.HfServices.DEFAULT_IMAGE)!!
        set(v) = p.edit().putString("hfImageModel", v.trim()).apply()
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    var rates: Rates
        get() = runCatching {
            val o = JSONObject(p.getString("rates", null)!!)
            val m = o.getJSONObject("map")
            Rates(m.keys().asSequence().associateWith { m.getDouble(it) }, o.getString("date"), o.getString("source"))
        }.getOrDefault(Rates.FALLBACK)
        set(v) = p.edit().putString(
            "rates",
            JSONObject().put("date", v.date).put("source", v.source).put("map", JSONObject(v.czkPerUnit as Map<*, *>)).toString(),
        ).putLong("ratesAt", System.currentTimeMillis()).apply()
    val ratesAt: Long get() = p.getLong("ratesAt", 0)

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode).put("kimiBase", kimiBase).put("kimiModel", kimiModel)
        .put("serverUrl", serverUrl).put("serverModel", serverModel).put("claudeModel", claudeModel)
        .put("activeBrain", activeBrain ?: JSONObject.NULL).put("maxPasses", maxPasses).put("localMaxPasses", localMaxPasses)
        .put("reflect", reflect).put("web", web).put("thinking", thinking).put("ctxTokens", ctxTokens)
        .put("theme", theme).put("font", font).put("userName", userName).put("councilJudge", councilJudge)
        .put("onboarded", onboarded).put("rates", rates.label())
        .put("nodeUrl", nodeUrl).put("nodeModel", nodeModel).put("hfRepo", hfRepo).put("agentSteps", agentSteps).put("semantic", semantic).put("randyAuthority", randyAuthority).put("power", power).put("ctxManual", ctxManual).put("keepBrain", keepBrain).put("instant", instant).put("autoDuo", autoDuo).put("claudeBoost", claudeBoost).put("memSafe", memSafe)
        .put("hfChat", hfChat).put("hfVision", hfVision).put("hfChatModel", hfChatModel).put("hfVisionModel", hfVisionModel)
        .put("hfAsrModel", hfAsrModel).put("hfImageModel", hfImageModel).put("learning", learning).put("selfStudy", selfStudy).put("lora", lora).put("loraScale", loraScale)

    fun apply(o: JSONObject) {
        if (o.has("mode")) mode = o.getString("mode").takeIf { it in MODES } ?: "auto"
        if (o.has("kimiBase")) kimiBase = o.getString("kimiBase").ifBlank { KimiEngine.CLOUD_BASE }
        if (o.has("kimiModel")) kimiModel = o.getString("kimiModel").ifBlank { KimiEngine.CLOUD_MODEL }.also(::checkModelId)
        if (o.has("serverUrl")) serverUrl = o.getString("serverUrl")
        if (o.has("serverModel")) serverModel = o.getString("serverModel").ifBlank { "kimi-k3" }.also(::checkModelId)
        if (o.has("claudeModel")) claudeModel = o.getString("claudeModel").ifBlank { ClaudeEngine.DEFAULT_MODEL }.also(::checkModelId)
        if (o.has("maxPasses")) maxPasses = o.getInt("maxPasses")
        if (o.has("localMaxPasses")) localMaxPasses = o.getInt("localMaxPasses")
        if (o.has("reflect")) reflect = o.getBoolean("reflect")
        if (o.has("web")) web = o.getBoolean("web")
        if (o.has("thinking")) thinking = o.getBoolean("thinking")
        if (o.has("ctxTokens")) { val c = o.getInt("ctxTokens"); if (c <= 0) ctxManual = false else { ctxManual = true; ctxTokens = c } }
        if (o.has("power")) power = o.getString("power")
        if (o.has("hfChat")) hfChat = o.getBoolean("hfChat")
        if (o.has("hfVision")) hfVision = o.getBoolean("hfVision")
        for (k in listOf("hfChatModel", "hfVisionModel", "hfAsrModel", "hfImageModel")) if (o.has(k)) {
            val v = o.getString(k).also(::checkModelId)
            when (k) { "hfChatModel" -> hfChatModel = v; "hfVisionModel" -> hfVisionModel = v; "hfAsrModel" -> hfAsrModel = v; else -> hfImageModel = v }
        }
        if (o.has("theme")) theme = o.getString("theme")
        if (o.has("font")) font = o.getInt("font")
        if (o.has("userName")) userName = o.getString("userName")
        if (o.has("councilJudge")) councilJudge = o.getString("councilJudge").takeIf { it in setOf("claude", "kimi", "server", "local", "node") } ?: "kimi"
        if (o.has("onboarded")) onboarded = o.getBoolean("onboarded")
        if (o.has("nodeUrl")) nodeUrl = o.getString("nodeUrl")
        if (o.has("nodeModel")) nodeModel = o.getString("nodeModel").ifBlank { "axiom" }.also(::checkModelId)
        if (o.has("hfRepo")) hfRepo = o.getString("hfRepo").also { require(it.isEmpty() || it.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+"))) { "Repozitář ve tvaru uživatel/jméno." } }
        if (o.has("agentSteps")) agentSteps = o.getInt("agentSteps")
        if (o.has("semantic")) semantic = o.getBoolean("semantic")
        if (o.has("randyAuthority")) randyAuthority = o.getString("randyAuthority")
        if (o.has("learning")) learning = o.getBoolean("learning")
        if (o.has("selfStudy")) selfStudy = o.getBoolean("selfStudy")
        if (o.has("lora")) lora = o.getString("lora").takeIf { it.isEmpty() || it.matches(Regex("[A-Za-z0-9._ -]{1,120}")) } ?: ""
        if (o.has("loraScale")) loraScale = o.getInt("loraScale")
        if (o.has("keepBrain")) keepBrain = o.getBoolean("keepBrain")
        if (o.has("instant")) instant = o.getBoolean("instant")
        if (o.has("autoDuo")) autoDuo = o.getBoolean("autoDuo")
        if (o.has("claudeBoost")) claudeBoost = o.getBoolean("claudeBoost")
        if (o.has("memSafe")) memSafe = o.getBoolean("memSafe")
    }

    private fun checkModelId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9._:/\\[\\]-]{1,120}"))) { "Neplatné ID modelu: $id" }
    }

    companion object {
        val MODES = setOf("auto", "turbo", "local", "kimi", "node", "server", "claude", "hf", "council", "duo")
    }
}
