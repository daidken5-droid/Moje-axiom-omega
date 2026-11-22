package cz.axiom.vega.probe

import cz.axiom.vega.news.News
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Živě (jen -Dprobe=1): stáhne všechny zdroje, okno pro „teď“ posunuté tak, aby zahrnulo dnešek, a vypíše témata. */
class NewsProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        val all = News.SOURCES.flatMap { s ->
            val r = runCatching { cz.axiom.vega.net.Http.get(s.url, maxBytes = 2_000_000) }
            val items = r.getOrNull()?.let { News.parse(cz.axiom.vega.core.Encoding.decode(it.body), s.id) } ?: emptyList()
            println("PROBE|zdroj ${s.id}|${r.exceptionOrNull()?.message ?: "HTTP ${r.getOrNull()?.code}"}|${items.size} položek, s datem ${items.count { it.published != null }}")
            items
        }
        val w = News.window(java.time.ZonedDateTime.now(News.ZONE).plusDays(1).withHour(5).withMinute(20))
        News.topics(all, w).forEachIndexed { i, t -> println("PROBE|téma ${i + 1}|${t.sources.size} zdr.|${t.sources}|${t.title.take(90)}") }
    }
}
