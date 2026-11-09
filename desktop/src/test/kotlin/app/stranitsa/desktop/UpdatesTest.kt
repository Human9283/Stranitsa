package app.stranitsa.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatesTest {
    @Test fun picksInstallerForThisSystem() {
        val json = """{"tag_name":"v1.3.0","html_url":"https://github.com/o/r/releases/tag/v1.3.0","assets":[
            {"browser_download_url":"https://x/Stranitsa-1.3.0.apk"},{"browser_download_url":"https://x/Stranitsa-1.3.0.msi"},
            {"browser_download_url":"https://x/stranitsa_1.3.0_amd64.deb"},{"browser_download_url":"https://x/Stranitsa-1.3.0.dmg"}]}"""
        val r = Updates.parse(json)!!
        assertEquals("1.3.0", r.version)
        val os = System.getProperty("os.name").lowercase()
        val want = when { os.contains("win") -> ".msi"; os.contains("mac") -> ".dmg"; else -> ".deb" }
        assertTrue(r.assetUrl!!.endsWith(want))
    }

    @Test fun versions() {
        assertTrue(Updates.isNewer("1.0.1", "1.0.0"))
        assertFalse(Updates.isNewer("1.0.0", "1.0.0"))
    }

    @Test fun parsesDigestAndSizeOfThisSystemsFile() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val json = """{"tag_name":"v1.2.0","assets":[
            {"name":"x.msi","uploader":{"login":"u"},"size":999,"digest":"sha256:$b","browser_download_url":"https://x/x.msi"},
            {"name":"x.deb","uploader":{"login":"u"},"size":4096,"digest":"sha256:$a","download_count":0,"browser_download_url":"https://x/x.deb"}]}"""
        val r = Updates.parse(json, os = "linux")!!
        assertEquals(a, r.assetSha256); assertEquals(4096L, r.assetSize)
        val w = Updates.parse(json, os = "windows 11")!!
        assertEquals(b, w.assetSha256); assertEquals(999L, w.assetSize)
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun savesOnlyAfterVerification() {
        val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
        val data = ByteArray(600_000) { (it % 251).toByte() }
        val dest = java.io.File(dir, "a.deb")
        Updates.copyVerified(data.inputStream(), data.size.toLong(), dest, sha(data), data.size.toLong())
        assertTrue(dest.isFile)
    }

    @Test fun badChecksumOrTruncationLeavesNoFile() {
        val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
        val dest = java.io.File(dir, "a.deb")
        val e1 = runCatching { Updates.copyVerified(ByteArray(10) { 1 }.inputStream(), 10, dest, "0".repeat(64), 10) }.exceptionOrNull()
        assertEquals("checksum mismatch", e1?.message)
        val e2 = runCatching { Updates.copyVerified(ByteArray(5).inputStream(), 10, dest, null, null) }.exceptionOrNull()
        assertEquals("incomplete download", e2?.message)
        val e3 = runCatching { Updates.copyVerified(ByteArray(5).inputStream(), 5, dest, null, null, cancelled = { true }) }.exceptionOrNull()
        assertEquals("cancelled", e3?.message)
        assertFalse(dest.exists()); assertFalse(java.io.File(dir, "a.deb.part").exists())
    }
}
