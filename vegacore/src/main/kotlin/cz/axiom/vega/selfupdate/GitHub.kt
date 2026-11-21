package cz.axiom.vega.selfupdate

import cz.axiom.vega.net.Http
import cz.axiom.vega.net.NetException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Minimální klient GitHub REST pro samoúpravy. TVRDÁ POJISTKA: zapisovat (větev, commit, mazání) jde jen do větví
 * `vega/auto-…` – nikdy do hlavní ani pracovní větve. Token má mít jen Contents RW + Actions Read na jeden repozitář,
 * BEZ oprávnění Workflows (GitHub pak sám odmítne každou změnu CI).
 */
class GitHub(val owner: String, val repo: String, private val token: String, private val transport: Transport = Transport.HTTP) {
    fun interface Transport {
        fun call(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Http.Response
        companion object { val HTTP = Transport { m, u, h, b -> Http.request(m, u, h, b) } }
    }
    data class Run(val id: Long, val status: String, val conclusion: String?, val url: String, val headSha: String)

    init { require(Regex("^[A-Za-z0-9-]{1,39}$").matches(owner) && Regex("^[A-Za-z0-9._-]{1,100}$").matches(repo)) { "Neplatný repozitář $owner/$repo" } }
    private val api = "https://api.github.com/repos/$owner/$repo"
    private val json = Json { ignoreUnknownKeys = true }
    val headers get() = mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28", "User-Agent" to Http.USER_AGENT)

    private fun call(method: String, path: String, body: JsonObject? = null, ok: Set<Int> = setOf(200, 201)): JsonObject? {
        val r = transport.call(method, api + path, headers + (if (body != null) mapOf("Content-Type" to "application/json") else emptyMap()), body?.toString()?.toByteArray())
        if (r.code == 204) return null
        if (r.code !in ok) {
            val msg = runCatching { (json.parseToJsonElement(String(r.body)).jsonObject["message"] as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: ""
            throw NetException(when (r.code) {
                401 -> "GitHub: neplatný token."; 403 -> "GitHub: token na to nemá oprávnění ($msg)."; 404 -> "GitHub: nenalezeno – repozitář, větev nebo oprávnění tokenu ($msg)."
                422 -> "GitHub odmítl požadavek: $msg"; else -> "GitHub HTTP ${r.code}: $msg" }, retryable = r.code >= 500)
        }
        return runCatching { json.parseToJsonElement(String(r.body, Charsets.UTF_8)).jsonObject }.getOrNull()
    }
    private fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull

    /** První rodič commitu (základ, ze kterého úprava vznikla). */
    fun parentSha(commit: String): String? = ((call("GET", "/git/commits/$commit")?.get("parents") as? JsonArray)?.firstOrNull() as? JsonObject)?.let { s(it, "sha") }
    fun refSha(branch: String): String = s(call("GET", "/git/ref/heads/$branch")?.get("object") as? JsonObject, "sha") ?: throw NetException("Větev $branch nemá commit.")

    /** Soubory stromu (cesta → velikost). */
    fun tree(sha: String): Map<String, Long> {
        val o = call("GET", "/git/trees/$sha?recursive=1") ?: return emptyMap()
        return (o["tree"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.takeIf { s(it, "type") == "blob" }?.let { s(it, "path")!! to ((it["size"] as? JsonPrimitive)?.longOrNull ?: 0L) } }.toMap()
    }

    fun file(path: String, ref: String): String {
        val o = call("GET", "/contents/${path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}?ref=$ref")
        val b64 = s(o, "content") ?: throw NetException("Soubor $path nejde načíst.")
        return String(java.util.Base64.getMimeDecoder().decode(b64), Charsets.UTF_8)
    }

    /** Jeden commit se všemi soubory na NOVOU větev [branch] (odvozenou od [baseSha]). Vrací sha commitu. */
    fun commitFiles(baseSha: String, branch: String, files: Map<String, String>, message: String): String {
        requireAutoBranch(branch)
        require(files.isNotEmpty()) { "Žádné změny." }
        val entries = files.map { (p, content) ->
            val blob = s(call("POST", "/git/blobs", buildJsonObject { put("content", java.util.Base64.getEncoder().encodeToString(content.toByteArray())); put("encoding", "base64") }), "sha")!!
            buildJsonObject { put("path", p); put("mode", "100644"); put("type", "blob"); put("sha", blob) }
        }
        val baseTree = s(call("GET", "/git/commits/$baseSha")?.get("tree") as? JsonObject, "sha")!!
        val tree = s(call("POST", "/git/trees", buildJsonObject { put("base_tree", baseTree); put("tree", JsonArray(entries)) }), "sha")!!
        val commit = s(call("POST", "/git/commits", buildJsonObject { put("message", message); put("tree", tree); put("parents", buildJsonArray { add(JsonPrimitive(baseSha)) }) }), "sha")!!
        call("POST", "/git/refs", buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", commit) })
        return commit
    }

    /** Běh PRÁVĚ workflow samoúprav pro PRÁVĚ tento commit (ne jiný workflow ani pozdější push do větve). */
    fun latestRun(commit: String, workflow: String = "vega-auto.yml"): Run? {
        require(Regex("^[0-9a-f]{40}$").matches(commit)) { "Neplatný commit." }
        val o = call("GET", "/actions/workflows/$workflow/runs?head_sha=$commit&per_page=1") ?: return null
        val r = (o["workflow_runs"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return Run((r["id"] as JsonPrimitive).longOrNull ?: 0, s(r, "status") ?: "?", s(r, "conclusion"), s(r, "html_url") ?: "", s(r, "head_sha") ?: "")
    }

    /** Id artefaktu [name] v běhu (nebo null). */
    fun artifact(runId: Long, name: String): Long? {
        val o = call("GET", "/actions/runs/$runId/artifacts") ?: return null
        return (o["artifacts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { s(it, "name") == name && s(it, "expired") != "true" }?.let { (it["id"] as JsonPrimitive).longOrNull }
    }
    fun artifactZipUrl(id: Long) = "$api/actions/artifacts/$id/zip"

    fun deleteBranch(branch: String) { requireAutoBranch(branch); call("DELETE", "/git/refs/heads/$branch", ok = setOf(200, 204, 422)) }

    companion object {
        val AUTO_BRANCH = Regex("^vega/auto-[a-z0-9-]{3,60}$")
        fun requireAutoBranch(b: String) = require(AUTO_BRANCH.matches(b)) { "Samoúprava smí psát jen do větví vega/auto-… (ne „$b“)." }
    }
}
