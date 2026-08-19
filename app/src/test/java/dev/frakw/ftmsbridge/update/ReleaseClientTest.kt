package dev.frakw.ftmsbridge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseClientTest {
    @Test
    fun `parses stable release and exact assets`() {
        val release = parseRelease(releaseJson())
        assertEquals("v1.2.3", release.tag)
        assertEquals(1_002_003L, release.versionCode)
        assertEquals("ftms-bridge-v1.2.3.apk", release.apkName)
        assertEquals("https://example.test/app.apk", release.apkUrl)
    }

    @Test
    fun `rejects malformed versions and missing assets`() {
        assertThrows(UpdateException::class.java) { versionCode("1.2.3") }
        assertThrows(UpdateException::class.java) { versionCode("v1.1000.0") }
        assertThrows(UpdateException::class.java) { parseRelease(releaseJson().replace(".apk.sha256", ".txt")) }
    }

    @Test
    fun `parses checksum and verifies named file`() {
        val hash = "a".repeat(64)
        assertEquals(hash, parseChecksum("$hash  ftms-bridge-v1.2.3.apk\n", "ftms-bridge-v1.2.3.apk"))
        assertThrows(UpdateException::class.java) { parseChecksum("$hash  other.apk", "ftms-bridge-v1.2.3.apk") }
    }

    @Test
    fun `calculates file sha256`() {
        val file = File.createTempFile("updater", ".apk").apply { writeText("abc") }
        try {
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `automatic checks honor build state and daily interval`() {
        val day = UpdatePreferences.CHECK_INTERVAL
        assertTrue(shouldAutomaticCheck(debug = false, UpdateStatus.IDLE, lastCheck = day, now = day * 2))
        assertFalse(shouldAutomaticCheck(debug = false, UpdateStatus.IDLE, lastCheck = day, now = day * 2 - 1))
        assertFalse(shouldAutomaticCheck(debug = true, UpdateStatus.IDLE, lastCheck = 0, now = day))
        assertFalse(shouldAutomaticCheck(debug = false, UpdateStatus.READY, lastCheck = 0, now = day))
    }

    private fun releaseJson() = """
        {
          "tag_name": "v1.2.3",
          "name": "v1.2.3",
          "body": "Changes",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "ftms-bridge-v1.2.3.apk", "browser_download_url": "https://example.test/app.apk"},
            {"name": "ftms-bridge-v1.2.3.apk.sha256", "browser_download_url": "https://example.test/app.sha256"}
          ]
        }
    """.trimIndent()
}
