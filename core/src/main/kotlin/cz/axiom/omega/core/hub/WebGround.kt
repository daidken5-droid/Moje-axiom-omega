package cz.axiom.omega.core.hub

import cz.axiom.omega.core.agent.Snippet
import cz.axiom.omega.core.util.TextUtil
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WEBOVÉ PODKLADY pro online modul (všechny cloudové modely, ne jen Claude): Wikipedie (cs → en) + DuckDuckGo,
 * paralelně a s tvrdým časovým limitem. Výsledek = podklady [n] se zdrojovou adresou → model cituje, UI ukáže odkazy.
 * Bez klíčů a bez účtu; nic se neposílá kromě hledaného výrazu.
 */
object WebGround {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "web-ground").apply { isDaemon = true } }

    data class Result(val snippets: List<Snippet>, val millis: Long)

    /** Najde podklady k [query]; nikdy nevyhodí výjimku, po [timeoutMs] vrátí, co stihl. */
    // krátká mezipaměť: „znovu“, eskalace RANDY nebo záloha po chybě nepošlou stejný dotaz na web podruhé (Wikimedia etiketa)
    private const val TTL = 10 * 60_000L
    private val cache = object : LinkedHashMap<String, Pair<Long, List<Snippet>>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, List<Snippet>>>) = size > 64
    }

    fun gather(query: String, max: Int = 5, timeoutMs: Long = 4_000, search: (String) -> List<Web.Hit> = { Web.search(it, 6) },
               wiki: (String, String) -> Web.WikiPage? = { q, lang -> Web.wikiSearch(q, lang, 3).firstOrNull()?.let { Web.wikiPage(it, lang) } },
               useCache: Boolean = true): Result {
        val t0 = System.currentTimeMillis()
        val q = query.trim().take(200)
        val key = TextUtil.fold(q).lowercase()
        if (useCache) synchronized(cache) { cache[key]?.takeIf { t0 - it.first < TTL }?.let { return Result(it.second.take(max), 0) } }
        val tasks = listOf(
            Callable { wiki(q, "cs")?.let { listOf(page(it, 1)) } ?: emptyList() },
            Callable { wiki(q, "en")?.let { listOf(page(it, 2)) } ?: emptyList() },
            Callable { search(q).mapIndexed { i, h -> Snippet(-(10L + i), h.title, h.snippet.ifBlank { h.title }, h.url) } },
        )
        val futures = tasks.map { pool.submit(it) }
        val out = ArrayList<Snippet>()
        for (f in futures) {
            val left = timeoutMs - (System.currentTimeMillis() - t0)
            runCatching { out += f.get(left.coerceAtLeast(1), TimeUnit.MILLISECONDS) }.onFailure { f.cancel(true) }
        }
        // anglická Wikipedie jen když česká nic nenašla; duplicitní adresy pryč
        val cs = out.any { it.id == -1L }
        val list = out.filter { !(cs && it.id == -2L) }.distinctBy { it.source }.take(max)
        if (useCache && list.isNotEmpty()) synchronized(cache) { cache[key] = t0 to list }
        return Result(list, System.currentTimeMillis() - t0)
    }

    private fun page(p: Web.WikiPage, id: Long) =
        Snippet(-id, "Wikipedie: ${p.title}", TextUtil.clip(p.text.substringBefore("\n\n\n").ifBlank { p.text }, 2_500), p.url)
}
