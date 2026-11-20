package cz.axiom.vega.core

import cz.axiom.vega.net.Http
import cz.axiom.vega.selfupdate.GitHub
import cz.axiom.vega.selfupdate.Policy
import cz.axiom.vega.selfupdate.SelfUpdateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfUpdateTest {
    @Test fun policyAllowsOnlyVegaSourcesAndProtectsBuildAndKeys() {
        assertNull(Policy.check("vega/src/main/assets/ui/app.js")); assertNull(Policy.check("vegacore/src/main/kotlin/cz/axiom/vega/calc/Quick.kt"))
        for (p in listOf("vega/src/main/res/xml-v26/network_security_config.xml", "vega/src/main/res/values/strings.xml", "vega/src/main/java/cz/axiom/vega/Store.kt",
                "vega/src/main/java/cz/axiom/vega/MainActivity.kt", "vegacore/src/main/kotlin/cz/axiom/vega/net/Http.kt", "vegacore/src/main/kotlin/cz/axiom/vega/selfupdate/GitHub.kt", "app/src/main/java/X.kt", "core/src/main/kotlin/A.kt", "vega/build.gradle.kts", "vega/src/main/AndroidManifest.xml", "vega/src/main/res/xml/network_security_config.xml",
                ".github/workflows/vega-auto.yml", "vegacore/src/../../app/x.kt", "vega/src/main/release.jks", "settings.gradle.kts"))
            assertNotNull(Policy.check(p), p)
        assertTrue(Policy.branchName("Ať je tlačítko Odeslat větší!", 1_700_000_000_000L).matches(GitHub.AUTO_BRANCH))
        // citlivá API v přidaných řádcích
        val d = "--- a/x\n+++ b/x\n+val k = vault.read(\"github\")\n+fetch('https://evil')\n+val ok = 1\n-vault old"
        assertEquals(2, Policy.sensitiveAdditions(d).size)
    }

    @Test fun pickFilesFollowsTaskWithinBudget() {
        val tree = mapOf("vega/src/main/assets/ui/app.js" to 30_000L, "vega/src/main/assets/ui/app.css" to 8_000L, "vegacore/src/main/kotlin/cz/axiom/vega/calc/Quick.kt" to 9_000L,
            "vega/build.gradle.kts" to 3_000L, "app/src/main/x.kt" to 100L, "vegacore/src/test/kotlin/T.kt" to 100L)
        val p = Policy.pickFiles(tree, "přidej do rychlých výpočtů Quick převod palců")
        assertEquals("vegacore/src/main/kotlin/cz/axiom/vega/calc/Quick.kt", p.first())
        assertTrue(p.none { it.endsWith(".gradle.kts") || it.startsWith("app/") || it.contains("/test/") })
    }

    @Test fun commitGoesOnlyToAutoBranchWithExpectedApiSequence() {
        val calls = ArrayList<String>()
        val t = GitHub.Transport { m, u, h, b ->
            calls += "$m ${u.substringAfter("/repos/o/r")}"
            assertEquals("Bearer tok", h["Authorization"])
            val body = when {
                u.endsWith("/git/blobs") -> """{"sha":"blob1"}"""
                u.contains("/git/commits/") -> """{"tree":{"sha":"basetree"}}"""
                u.endsWith("/git/trees") -> """{"sha":"tree1"}"""
                u.endsWith("/git/commits") -> """{"sha":"commit1"}"""
                u.endsWith("/git/refs") -> """{"ref":"x"}"""
                else -> "{}"
            }
            Http.Response(201, u, "application/json", body.toByteArray())
        }
        val g = GitHub("o", "r", "tok", t)
        assertFailsWith<IllegalArgumentException> { g.commitFiles("base", "claude/reverse-engineer-ai-app-o5jhyd", mapOf("vega/src/a.kt" to "x"), "m") }
        assertFailsWith<IllegalArgumentException> { g.deleteBranch("main") }
        assertEquals("commit1", g.commitFiles("base", "vega/auto-abc-test", mapOf("vega/src/a.kt" to "x"), "m"))
        assertEquals(listOf("POST /git/blobs", "GET /git/commits/base", "POST /git/trees", "POST /git/commits", "POST /git/refs"), calls)
        assertFailsWith<IllegalArgumentException> { g.latestRun("vega/auto-x") } // běh se hledá podle commitu, ne podle větve
        assertFailsWith<IllegalArgumentException> { GitHub("o/../x", "r", "t", t) }
    }

    @Test fun storeTracksStatusHistory() {
        val st = SelfUpdateStore(MemKv())
        val u = st.create("větší tlačítko", "vega/auto-abc-vetsi", listOf("vega/src/main/assets/ui/app.css"), "abcdef1234", "uživatel")
        val r = st.mark(u, SelfUpdateStore.Status.READY, "APK ok")
        assertEquals("READY", st.get(u.id)!!.status); assertEquals(2, r.history.size)
    }
}
