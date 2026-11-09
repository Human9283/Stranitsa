package app.stranitsa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersionsByNumbers() {
        assertTrue(Updates.isNewer("1.10.0", "1.9"))
        assertTrue(Updates.isNewer("v2", "1.99.99"))
        assertFalse(Updates.isNewer("1.0", "1.0.0"))
        assertFalse(Updates.isNewer("1.0.1", "1.1"))
    }

    @Test fun parsesGitHubRelease() {
        val json = """{"tag_name":"v1.2.0","html_url":"https://github.com/o/r/releases/tag/v1.2.0",
            "assets":[{"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/Stranitsa-1.2.0.deb"},
            {"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/Stranitsa-1.2.0.apk"}]}"""
        val r = Updates.parse(json)!!
        assertEquals("1.2.0", r.version)
        assertEquals("https://github.com/o/r/releases/tag/v1.2.0", r.pageUrl)
        assertEquals("https://github.com/o/r/releases/download/v1.2.0/Stranitsa-1.2.0.apk", r.apkUrl)
    }
}

class UpdateDownloadTest {
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun parsesDigestAndSizeOfTheApk() {
        val s = "a".repeat(64); val t = "b".repeat(64)
        val json = """{"tag_name":"v1.2.0","assets":[
            {"id":1,"name":"x.deb","uploader":{"login":"u"},"size":111,"digest":"sha256:$t","browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/x.deb"},
            {"id":2,"name":"x.apk","uploader":{"login":"u"},"size":4096,"digest":"sha256:$s","download_count":0,"browser_download_url":"https://github.com/o/r/releases/download/v1.2.0/x.apk"}]}"""
        val r = Updates.parse(json)!!
        assertEquals(s, r.apkSha256)
        assertEquals(4096L, r.apkSize)
    }

    @Test fun noDigestIsFine() {
        val r = Updates.parse("""{"tag_name":"v2","assets":[{"browser_download_url":"https://x/y.apk"}]}""")!!
        assertEquals(null, r.apkSha256)
        assertEquals(null, r.apkSize)
    }

    @Test fun savesOnlyAfterVerification() {
        val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val dest = java.io.File(dir, "a.apk")
        val seen = mutableListOf<Long>()
        Updates.copyVerified(data.inputStream(), data.size.toLong(), dest, sha(data), data.size.toLong()) { d, _ -> seen += d }
        assertTrue(dest.isFile)
        assertEquals(data.size.toLong(), seen.last())
        assertTrue(seen.size >= 2)
    }

    @Test fun wrongChecksumLeavesNoFile() {
        val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
        val data = ByteArray(1000) { 7 }
        val dest = java.io.File(dir, "a.apk")
        val e = runCatching { Updates.copyVerified(data.inputStream(), 1000, dest, "0".repeat(64), 1000) }.exceptionOrNull()
        assertEquals("checksum mismatch", e?.message)
        assertFalse(dest.exists()); assertFalse(java.io.File(dir, "a.apk.part").exists())
    }

    @Test fun truncatedDownloadIsRejected() {
        val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
        val dest = java.io.File(dir, "a.apk")
        val e = runCatching { Updates.copyVerified(ByteArray(500).inputStream(), 1000, dest, null, null) }.exceptionOrNull()
        assertEquals("incomplete download", e?.message)
        assertFalse(dest.exists())
    }

    @Test fun cancelStopsAndCleansUp() {
        val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
        val dest = java.io.File(dir, "a.apk")
        val e = runCatching { Updates.copyVerified(ByteArray(10).inputStream(), 10, dest, null, null, cancelled = { true }) }.exceptionOrNull()
        assertEquals("cancelled", e?.message)
        assertFalse(dest.exists()); assertFalse(java.io.File(dir, "a.apk.part").exists())
    }
}
