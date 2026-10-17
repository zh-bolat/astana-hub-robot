package kz.astanahub.robot.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendUrlPolicyTest {

    private val release = BackendUrlPolicy.fromBuildConfig(
        backendUrl = "https://robot-api.example.kz/v1",
        allowedHostsCsv = " staging.example.kz , ",
        debug = false,
    )
    private val debug = BackendUrlPolicy.fromBuildConfig(
        backendUrl = "https://robot-api.example.kz/v1",
        allowedHostsCsv = "staging.example.kz",
        debug = true,
    )

    private fun BackendUrlPolicy.valid(raw: String) = (validate(raw) as BackendUrlPolicy.Result.Valid).url

    @Test
    fun `https allowlisted hosts are accepted and normalized`() {
        assertEquals("https://robot-api.example.kz/v1", release.valid("robot-api.example.kz"))
        assertEquals("https://staging.example.kz/v1", release.valid("https://STAGING.example.kz/"))
        assertEquals("https://robot-api.example.kz:8443/api/v1", release.valid("https://robot-api.example.kz:8443/api/v1"))
    }

    @Test
    fun `https to other host is rejected`() {
        assertFalse(release.isAllowed("https://evil.example.com/v1"))
        assertFalse(release.isAllowed("https://robot-api.example.kz.evil.com/v1"))
        assertFalse(debug.isAllowed("evil.example.com"))
    }

    @Test
    fun `http private ip only in debug`() {
        assertEquals("http://10.54.180.166:8000/v1", debug.valid("10.54.180.166:8000"))
        assertEquals("http://10.0.2.2:8000/v1", debug.valid("http://10.0.2.2:8000/v1"))
        assertEquals("http://192.168.1.5:8000/v1", debug.valid("http://192.168.1.5:8000/v1/"))
        assertTrue(debug.isAllowed("http://172.16.0.1/v1"))
        assertTrue(debug.isAllowed("http://172.31.255.255/v1"))
        assertFalse(debug.isAllowed("http://172.32.0.1/v1"))
        assertFalse(debug.isAllowed("http://8.8.8.8/v1"))
        assertFalse(debug.isAllowed("http://robot-api.example.kz/v1"))

        assertFalse(release.isAllowed("http://10.0.2.2:8000/v1"))
        assertFalse(release.isAllowed("http://192.168.1.5:8000/v1"))
        // Без схемы в release — https, а приватный IP не в allowlist.
        assertFalse(release.isAllowed("192.168.1.5:8000"))
    }

    @Test
    fun `garbage and tricks are rejected`() {
        for (raw in listOf("", "   ", "::::", "ftp://robot-api.example.kz", "javascript:alert(1)",
            "https://user:pass@robot-api.example.kz/v1", "https://robot-api.example.kz/v1?x=1",
            "https://robot-api.example.kz/v1#frag", "http://999.1.1.1/v1", "http://10.0.0/v1")) {
            assertFalse(raw, release.isAllowed(raw))
            assertFalse(raw, debug.isAllowed(raw))
        }
        assertTrue(release.validate("evil.com") is BackendUrlPolicy.Result.Invalid)
    }

    @Test
    fun `saved url that no longer passes falls back to build default`() {
        val def = "https://robot-api.example.kz/v1"
        assertEquals(def, BackendSettings.resolve("http://192.168.1.5:8000/v1", def, release))
        assertEquals(def, BackendSettings.resolve("https://evil.example.com/v1", def, release))
        assertEquals(def, BackendSettings.resolve(null, def, release))
        assertEquals("https://staging.example.kz/v1", BackendSettings.resolve("https://staging.example.kz/v1", def, release))
        assertEquals("http://192.168.1.5:8000/v1", BackendSettings.resolve("http://192.168.1.5:8000/v1", def, debug))
    }

    @Test
    fun `private ipv4 ranges`() {
        assertTrue(BackendUrlPolicy.isPrivateIpv4("10.0.2.2"))
        assertTrue(BackendUrlPolicy.isPrivateIpv4("192.168.0.1"))
        assertFalse(BackendUrlPolicy.isPrivateIpv4("192.169.0.1"))
        assertFalse(BackendUrlPolicy.isPrivateIpv4("11.0.0.1"))
        assertFalse(BackendUrlPolicy.isPrivateIpv4("10.0.0.1.example.com"))
    }
}
