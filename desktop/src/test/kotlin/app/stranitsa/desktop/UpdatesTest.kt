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
}
