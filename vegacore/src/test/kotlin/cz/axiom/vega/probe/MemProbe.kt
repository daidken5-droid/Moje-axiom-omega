package cz.axiom.vega.probe

import cz.axiom.vega.core.Memory
import cz.axiom.vega.core.Mem
import org.junit.Assume.assumeTrue
import org.junit.Test

class MemProbe {
    @Test fun probe() {
        assumeTrue(System.getProperty("probe") == "1")
        val pairs = listOf("Bydlím v Praze" to "Bydlím v Brně", "Mám psa Rexe" to "Mám kočku Micku", "Jmenuju se Petr" to "Jmenuju se Pavel", "Moje oblíbená barva je modrá" to "Moje oblíbená barva je zelená",
            "Pracuju jako učitel" to "Pracuju jako řidič", "Je mi 30 let" to "Je mi 31 let", "Odpovídej stručně" to "Odpovídej podrobně", "Mám rád kávu" to "Mám rád čaj", "Manželka se jmenuje Jana" to "Moje žena se jmenuje Eva", "Jsem vegetarián" to "Jím maso")
        for ((a, b) in pairs) {
            val kv = cz.axiom.vega.core.MemKv()
            val mem = Memory(kv) { 1L }
            mem.remember(a); val r = mem.remember(b)
            println("PROBE|$a → $b|${r.javaClass.simpleName}|${mem.all().map { it.text }}|topics ${Mem.topicOf(a)} / ${Mem.topicOf(b)}")
        }
    }
}
