package app.smugly.ui

import app.smugly.Config
import app.smugly.RoutingSettings
import app.smugly.TunSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileProfileStoreSettingsTest {
    private val dir: File = Files.createTempDirectory("smugly-store").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun newSettingsSurviveARestart() {
        val store = FileProfileStore(dir)
        val s = store.loadGlobalSettings().copy(
            mode = Config.Mode.VPN,
            tun = TunSettings(mtu = 1380, ipv6 = false, bypassLan = true, dnsServers = "9.9.9.9"),
            routing = RoutingSettings(directRu = true, blockAds = false),
            autoConnectOnBoot = true,
            allowLan = true
        )
        store.saveGlobalSettings(s)
        assertEquals(s, FileProfileStore(dir).loadGlobalSettings())
    }

    @Test
    fun aSettingsFileFromAnOlderBuildLoadsWithDefaults() {
        File(dir, "settings.json").writeText("""{"listenPort": 2080, "mode": "VPN", "language": "RU"}""")
        File(dir, "profiles.json").writeText("[]")
        val loaded = FileProfileStore(dir).loadGlobalSettings()
        assertEquals(2080, loaded.listenPort)
        assertEquals(TunSettings(), loaded.tun)
        assertEquals(RoutingSettings(), loaded.routing)
        assertFalse(loaded.allowLan)
    }

    @Test
    fun handEditedBooleansAreAccepted() {
        File(dir, "settings.json").writeText("""{"listenPort": 1080, "allowLan": true, "tunMtu": 1400}""")
        File(dir, "profiles.json").writeText("[]")
        val loaded = FileProfileStore(dir).loadGlobalSettings()
        assertTrue(loaded.allowLan)
        assertEquals(1400, loaded.tun.mtu)
    }
}
