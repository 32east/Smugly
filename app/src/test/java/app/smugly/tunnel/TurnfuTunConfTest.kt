package app.smugly.tunnel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnfuTunConfTest {
    @Test
    fun configAddressesAreReadFromJsonFields() {
        val config = TurnfuProcess.parseTunConf(JSONObject("""{"t":"config","ip":"10.66.128.30","dns":["1.1.1.1","8.8.8.8"],"ip6":"fd66:67::801E"}"""))
        assertEquals("10.66.128.30", config?.ip)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), config?.dns)
        assertEquals("fd66:67::801e", config?.ip6)
    }

    @Test
    fun emptyIpv6StaysIpv4Only() {
        val config = TurnfuProcess.parseTunConf(JSONObject("""{"t":"config","ip":"10.66.128.30","dns":["1.1.1.1"],"ip6":""}"""))
        assertNull(config?.ip6)
        assertNull(TurnfuProcess.parseTunConf(JSONObject("""{"t":"config","ip":"10.66.128.30","dns":["1.1.1.1"],"ip6":"fd66::zz"}""")))
    }
}
