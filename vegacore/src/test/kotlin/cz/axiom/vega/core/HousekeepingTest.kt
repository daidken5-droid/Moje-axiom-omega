package cz.axiom.vega.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HousekeepingTest {
    private val day = 86_400_000L

    @Test fun removesOrphansAndStaleButKeepsLive() {
        var t = 1_000_000_000_000L
        val kv = MemKv { t }
        kv.write("chats.json", """[{"id":"cLive","title":"a"}]""")
        for (c in listOf("cLive", "cGone")) {
            kv.write("chat/$c.json", "[]"); kv.write("evidence/$c.json", "[]"); kv.write("draft/button/$c.json", "{}")
            kv.write("resume/$c/r1.json", "{}")
        }
        kv.write("cache/long/abc-0", "x"); kv.write("evidence/temp.json", "[]")
        // úloha s porovnáním v živém chatu + její příloha; osiřelá příloha
        val task = RevTask(kv, "t1"); task.start(listOf(RevTask.Input("a.csv", "sha1")))
        kv.write("state/compare/cLive", "t1"); kv.write("taskfile/sha1", "AAA"); kv.write("taskfile/shaX", "BBB")
        kv.write("task/t0.json", """{"revs":[]}""")

        t += 2 * day
        kv.write("resume/cLive/r2.json", "{}") // čerstvé
        val r = Housekeeping.run(kv, now = t + 1, taskDays = 1)

        assertNotNull(kv.read("chat/cLive.json")); assertNotNull(kv.read("evidence/cLive.json")); assertNotNull(kv.read("resume/cLive/r2.json"))
        assertNull(kv.read("chat/cGone.json")); assertNull(kv.read("evidence/cGone.json")); assertNull(kv.read("draft/button/cGone.json")); assertNull(kv.read("resume/cGone/r1.json"))
        assertNull(kv.read("evidence/temp.json"), "dočasný chat po dni pryč")
        assertNotNull(kv.read("cache/long/abc-0"), "mezivýsledky 3 dny drží")
        assertNotNull(kv.read("task/t1.json")); assertNotNull(kv.read("taskfile/sha1"), "příloha živé úlohy zůstává")
        assertNull(kv.read("taskfile/shaX")); assertNull(kv.read("task/t0.json"))
        assertEquals(7, r.deleted, r.toString())

        t += 8 * day
        Housekeeping.run(kv, now = t)
        assertNull(kv.read("cache/long/abc-0")); assertNull(kv.read("resume/cLive/r2.json"))
        assertNotNull(kv.read("chat/cLive.json"))
    }

    @Test fun missingChatListDeletesNothing() {
        val kv = MemKv(); kv.write("chat/c1.json", "[]"); kv.write("evidence/c1.json", "[]")
        assertEquals(0, Housekeeping.run(kv).deleted)
        kv.write("chats.json", "{rozbité"); assertEquals(0, Housekeeping.run(kv).deleted)
    }
}
