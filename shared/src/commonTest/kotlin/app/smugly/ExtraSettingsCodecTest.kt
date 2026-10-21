package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtraSettingsCodecTest {

    private val base = GlobalSettings(
        listenPort = 1080,
        mode = Config.Mode.VPN,
        fileLogging = false,
        trafficNotification = false,
        localSocksAuthEnabled = true,
        localSocksUsername = "u",
        localSocksPassword = "p"
    )

    @Test
    fun roundTripKeepsEveryField() {
        val s = base.copy(
            tun = TunSettings(mtu = 1280, ipv6 = false, bypassLan = true, dnsServers = "94.140.14.14, 9.9.9.9"),
            routing = RoutingSettings(directRu = true, blockAds = true),
            autoConnectOnBoot = true,
            allowLan = true
        )
        val stored = ExtraSettingsCodec.encode(s)
        assertEquals(ExtraSettingsCodec.keys.toSet(), stored.keys)
        assertEquals(s, ExtraSettingsCodec.decode(base) { stored[it] })
    }

    @Test
    fun missingKeysGiveTheOldBehaviour() {
        val decoded = ExtraSettingsCodec.decode(base) { null }
        assertEquals(TunSettings(), decoded.tun)
        assertEquals(RoutingSettings(), decoded.routing)
        assertFalse(decoded.autoConnectOnBoot)
        assertFalse(decoded.allowLan)
    }

    @Test
    fun garbageFallsBackPerField() {
        val stored = mapOf(
            "tunMtu" to "lots",
            "tunIpv6" to "nope",
            "tunBypassLan" to "TRUE",
            "routeBlockAds" to " true ",
            "allowLan" to "1"
        )
        val decoded = ExtraSettingsCodec.decode(base) { stored[it] }
        assertEquals(TunSettings.DEFAULT_MTU, decoded.tun.mtu)
        assertTrue(decoded.tun.ipv6)
        assertTrue(decoded.tun.bypassLan)
        assertTrue(decoded.routing.blockAds)
        assertFalse(decoded.allowLan)
    }

    @Test
    fun outOfRangeMtuIsNotStoredBack() {
        val decoded = ExtraSettingsCodec.decode(base) { if (it == "tunMtu") "100" else null }
        assertEquals(TunSettings.DEFAULT_MTU, decoded.tun.mtu)
    }

    @Test
    fun decodeLeavesTheOlderFieldsAlone() {
        val custom = base.copy(listenPort = 2080, language = AppLanguage.RU, appHttpProxy = true)
        val decoded = ExtraSettingsCodec.decode(custom) { null }
        assertEquals(2080, decoded.listenPort)
        assertEquals(AppLanguage.RU, decoded.language)
        assertTrue(decoded.appHttpProxy)
    }

    @Test
    fun resetRestoresSettingsButKeepsState() {
        val dirty = base.copy(
            listenPort = 4444,
            mode = Config.Mode.PROXY,
            fileLogging = true,
            trafficNotification = true,
            localSocksAuthEnabled = false,
            language = AppLanguage.EN,
            dnsResolverPool = "1.2.3.4",
            homeFolderIndex = 3,
            lastFolderId = "sub-1",
            collapsedCategories = setOf("sub-1/cat"),
            appHttpProxy = true,
            perAppMode = PerAppMode.ALLOWED,
            perAppPackages = setOf("org.telegram.messenger"),
            autoUpdate = false,
            tun = TunSettings(mtu = 1300, ipv6 = false, bypassLan = true, dnsServers = "9.9.9.9"),
            routing = RoutingSettings(directRu = true, blockAds = true),
            autoConnectOnBoot = true,
            allowLan = true
        )
        val reset = dirty.resetToDefaults(Config.Mode.VPN)
        assertEquals(1080, reset.listenPort)
        assertEquals(Config.Mode.VPN, reset.mode)
        assertFalse(reset.fileLogging)
        assertFalse(reset.trafficNotification)
        assertTrue(reset.localSocksAuthEnabled)
        assertEquals(AppLanguage.SYSTEM, reset.language)
        assertEquals(DnsResolverPool.DEFAULT_RAW, reset.dnsResolverPool)
        assertFalse(reset.appHttpProxy)
        assertTrue(reset.autoUpdate)
        assertEquals(TunSettings(), reset.tun)
        assertEquals(RoutingSettings(), reset.routing)
        assertFalse(reset.autoConnectOnBoot)
        assertFalse(reset.allowLan)
        assertEquals("u", reset.localSocksUsername)
        assertEquals("p", reset.localSocksPassword)
        assertEquals(3, reset.homeFolderIndex)
        assertEquals("sub-1", reset.lastFolderId)
        assertEquals(setOf("sub-1/cat"), reset.collapsedCategories)
        assertEquals(PerAppMode.ALLOWED, reset.perAppMode)
        assertEquals(setOf("org.telegram.messenger"), reset.perAppPackages)
    }
}
