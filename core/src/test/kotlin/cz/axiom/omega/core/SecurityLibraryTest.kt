package cz.axiom.omega.core

import cz.axiom.omega.core.agent.AgentRole
import cz.axiom.omega.core.library.Libraries
import cz.axiom.omega.core.security.SecTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityLibraryTest {

    @Test fun hashes() {
        val h = SecTools.hash("abc")
        assertTrue(h.contains("MD5: 900150983cd24fb0d6963f7d28e17f72"))
        assertTrue(h.contains("SHA-256: ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertEquals("SHA-1 (40 hex)", SecTools.identifyHash("a9993e364706816aba3e25717850c26c9cd0d89d"))
        assertEquals("bcrypt", SecTools.identifyHash("\$2b\$12\$abcdefghijklmnopqrstuv"))
    }

    @Test fun encodeRoundTrip() {
        assertEquals("ahoj světe", SecTools.encode("base64d", SecTools.encode("base64", "ahoj světe")))
        assertEquals("ahoj", SecTools.encode("hexd", SecTools.encode("hex", "ahoj")))
        assertEquals("a b&c", SecTools.encode("urld", SecTools.encode("url", "a b&c")))
    }

    @Test fun jwtWarnsOnAlgNone() {
        val b64 = { s: String -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray()) }
        val t = b64("""{"alg":"none","typ":"JWT"}""") + "." + b64("""{"sub":"42"}""") + "."
        val r = SecTools.jwt(t)
        assertTrue(r, r.contains("alg=none"))
        assertTrue(r, r.contains("chybí exp"))
    }

    @Test fun passwordStrength() {
        assertTrue(SecTools.passwordStrength("heslo123").startsWith("Síla: SLABÉ"))
        assertTrue(SecTools.passwordStrength("t7#Qm!vR2zLw9@pXe4").contains("SILNÉ"))
    }

    @Test fun pwnedParse() {
        val body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:3861493\r\n"
        assertEquals(3861493, SecTools.parsePwned(body, "1E4C9B93F3F0682250B6CF8331B7EE68FD8"))
        assertEquals(0, SecTools.parsePwned(body, "FFFF"))
    }

    @Test fun secretScan() {
        val r = SecTools.secretScan("aws=AKIAIOSFODNN7EXAMPLE\ntoken: ghp_${"a".repeat(36)}\npassword = Sup3rTajne!")
        assertTrue(r, r.contains("AWS Access Key"))
        assertTrue(r, r.contains("GitHub token"))
        assertTrue(r, r.contains("Heslo v konfiguraci"))
        assertFalse(r, r.contains("AKIAIOSFODNN7EXAMPLE")) // tajemství se maskuje
        assertEquals("Žádná tajemství nenalezena.", SecTools.secretScan("nic tu není"))
    }

    @Test fun headerAudit() {
        val bad = SecTools.auditHeaders(mapOf("Server" to listOf("Apache/2.4.1"), "Set-Cookie" to listOf("sid=1; Path=/")), https = true)
        assertTrue(bad.score < 50)
        assertTrue(bad.findings.any { it.contains("CHYBÍ content-security-policy") })
        assertTrue(bad.findings.any { it.contains("bez HttpOnly") })
        val good = SecTools.auditHeaders(
            mapOf(
                "Strict-Transport-Security" to listOf("max-age=63072000"), "Content-Security-Policy" to listOf("default-src 'self'; frame-ancestors 'none'"),
                "X-Content-Type-Options" to listOf("nosniff"), "Referrer-Policy" to listOf("no-referrer"), "Permissions-Policy" to listOf("camera=()"),
            ),
            https = true,
        )
        assertEquals(100, good.score)
    }

    @Test fun crtShParse() {
        val body = """[{"name_value":"www.example.cz\n*.example.cz"},{"name_value":"mail.example.cz"},{"name_value":"evil.com"}]"""
        assertEquals(listOf("example.cz", "mail.example.cz", "www.example.cz"), SecTools.parseCrtSh(body, "example.cz"))
    }

    @Test fun libraryQueryAndScore() {
        assertEquals("\"tepel\"* \"cerpa\"*", Libraries.ftsQuery("Jak funguje tepelné čerpadlo?", and = true))
        assertEquals("\"tepel\"* OR \"cerpa\"*", Libraries.ftsQuery("tepelné čerpadlo", and = false))
        assertNull(Libraries.ftsQuery("jak a co", and = true))
        assertTrue(Libraries.score("tepelné čerpadlo", "Tepelné čerpadlo", "…") > Libraries.score("tepelné čerpadlo", "Kotel", "tepelné čerpadlo"))
        assertEquals(534_044, Libraries.of("wiki-cs")!!.rows)
    }

    @Test fun agentRoles() {
        assertEquals(AgentRole.SECURITY, AgentRole.of("security"))
        assertTrue(AgentRole.SECURITY.instruction.contains("povolení"))
    }
}
