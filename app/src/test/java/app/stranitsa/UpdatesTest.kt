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
