package app.smugly

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunPlanTest {

    @Test
    fun defaultsRouteEverythingLikeBefore() {
        val s = TunSettings()
        assertEquals(listOf(TunRoute("0.0.0.0", 0)), TunPlan.ipv4Routes(s))
        assertEquals(listOf(TunRoute("::", 0)), TunPlan.ipv6Routes(s))
        assertEquals(1500, TunPlan.mtu(s))
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), TunPlan.dnsServers(s.dnsServers))
    }

    @Test
    fun ipv6OffRoutesNoIpv6AtAll() {
        assertEquals(emptyList(), TunPlan.ipv6Routes(TunSettings(ipv6 = false)))
        assertEquals(emptyList(), TunPlan.ipv6Routes(TunSettings(ipv6 = false, bypassLan = true)))
    }

    @Test
    fun bypassLanKeepsOnlyPublicIpv6() {
        assertEquals(listOf(TunPlan.PUBLIC_V6), TunPlan.ipv6Routes(TunSettings(bypassLan = true)))
    }

    @Test
    fun bypassLanLeavesPrivateRangesOut() {
        val routes = TunPlan.ipv4Routes(TunSettings(bypassLan = true))
        for (ip in listOf("192.168.1.1", "192.168.0.10", "10.0.0.1", "10.20.30.40", "172.16.0.1",
            "172.31.255.254", "169.254.1.1", "127.0.0.1", "224.0.0.251", "239.255.255.250",
            "255.255.255.255", "0.1.2.3")) {
            assertFalse(TunPlan.isRouted(routes, ip), "$ip must stay out of the tunnel")
        }
    }

    @Test
    fun bypassLanStillCarriesEveryPublicAddress() {
        val routes = TunPlan.ipv4Routes(TunSettings(bypassLan = true))
        for (ip in listOf("1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.0", "172.15.255.255",
            "172.32.0.0", "192.167.255.255", "192.169.0.0", "100.64.0.1", "223.255.255.255",
            "198.18.0.5", "77.88.8.8")) {
            assertTrue(TunPlan.isRouted(routes, ip), "$ip must go through the tunnel")
        }
    }

    @Test
    fun bypassLanKeepsTheTunnelsOwnSubnet() {
        val routes = TunPlan.ipv4Routes(TunSettings(bypassLan = true))
        assertTrue(TunPlan.isRouted(routes, "10.255.0.1"), "the fake-IP DNS lives here")
        assertTrue(TunPlan.isRouted(routes, "10.255.0.2"))
        assertFalse(TunPlan.isRouted(routes, "10.255.1.1"))
    }

    @Test
    fun bypassLanMatchesTheExclusionListForRandomAddresses() {
        val routes = TunPlan.ipv4Routes(TunSettings(bypassLan = true))
        val excluded = TunPlan.LAN_V4
        val rnd = Random(42)
        repeat(20_000) {
            val ip = TunPlan.formatIpv4(rnd.nextLong(0, 1L shl 32))
            val shouldRoute = !TunPlan.isRouted(excluded, ip) || TunPlan.isRouted(listOf(TunPlan.TUN_SUBNET_V4), ip)
            assertEquals(shouldRoute, TunPlan.isRouted(routes, ip), ip)
        }
    }

    @Test
    fun bypassLanRoutesAreAlignedAndFew() {
        val routes = TunPlan.ipv4Routes(TunSettings(bypassLan = true))
        assertTrue(routes.size <= 64, "routes=${routes.size}")
        for (r in routes) {
            val start = TunPlan.parseIpv4(r.address)!!
            val size = 1L shl (32 - r.prefix)
            assertEquals(0L, start % size, "$r is not aligned to its prefix")
        }
    }

    @Test
    fun mtuIsClampedToWhatAndroidAccepts() {
        assertEquals(1280, TunPlan.mtu(TunSettings(mtu = 576)))
        assertEquals(9000, TunPlan.mtu(TunSettings(mtu = 65000)))
        assertEquals(1400, TunPlan.mtu(TunSettings(mtu = 1400)))
    }

    @Test
    fun dnsParsingKeepsValidAddressesInOrder() {
        assertEquals(
            listOf("94.140.14.14", "2a10:50c0::ad1:ff", "1.1.1.1"),
            TunPlan.dnsServers("94.140.14.14, 2a10:50c0::ad1:ff\n1.1.1.1; 94.140.14.14")
        )
    }

    @Test
    fun dnsParsingFallsBackWhenNothingIsUsable() {
        assertEquals(TunPlan.DEFAULT_DNS, TunPlan.dnsServers(""))
        assertEquals(TunPlan.DEFAULT_DNS, TunPlan.dnsServers("dns.google, 300.1.1.1"))
        assertEquals(listOf("dns.google", "300.1.1.1"), TunPlan.invalidDnsEntries("dns.google, 300.1.1.1, 8.8.4.4"))
    }

    @Test
    fun ipLiteralValidation() {
        for (ok in listOf("0.0.0.0", "255.255.255.255", "::", "::1", "fe80::1", "2001:db8::8a2e:370:7334",
            "1:2:3:4:5:6:7:8", "::ffff:192.168.1.1")) {
            assertTrue(TunPlan.isIpLiteral(ok), ok)
        }
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01234.1.1.1", "a.b.c.d", "1::2::3",
            ":::", "1:2:3:4:5:6:7:8:9", "12345::1", "dns.google", "::ffff:999.1.1.1", "1.1.1.1 ")) {
            assertFalse(TunPlan.isIpLiteral(bad), bad)
        }
    }
}
