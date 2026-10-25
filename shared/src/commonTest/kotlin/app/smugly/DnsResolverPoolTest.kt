package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals

class DnsResolverPoolTest {

    @Test
    fun default_is_local_only() {
        assertEquals(listOf(DnsResolverPool.LOCAL_SENTINEL), DnsResolverPool.parse(DnsResolverPool.DEFAULT_RAW))
    }

    @Test
    fun old_default_collapses_to_local() {
        val old = "(local)\n82.151.127.188\n188.0.190.47\n185.22.235.137\n46.254.19.23"
        assertEquals(DnsResolverPool.DEFAULT_RAW, DnsResolverPool.normalize(old))
    }

    @Test
    fun retired_ips_are_dropped_but_custom_ones_stay() {
        val raw = "9.9.9.9\n82.151.127.188\n(local dns-resolvers)"
        assertEquals(listOf("9.9.9.9", DnsResolverPool.LOCAL_SENTINEL), DnsResolverPool.parse(DnsResolverPool.normalize(raw)))
    }

    @Test
    fun pool_of_only_retired_ips_falls_back_to_local() {
        assertEquals(DnsResolverPool.DEFAULT_RAW, DnsResolverPool.normalize("46.254.19.23\n\n188.0.190.47"))
    }
}
