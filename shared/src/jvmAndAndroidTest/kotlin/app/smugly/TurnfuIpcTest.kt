package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.json.JSONObject

class TurnfuIpcTest {
    @Test
    fun processConfigurationMatchesTheNativeJsonSchema() {
        val config = TurnfuIpc.configuration("2001:db8::1", 46000, "pw", listOf("call"), 20, tunSocket = "fd-socket", network = 123)
        assertEquals("[2001:db8::1]:46000", config.getString("server"))
        assertEquals("pw", config.getString("password"))
        assertEquals("fd-socket", config.getString("tun_socket"))
        assertEquals(123L, config.getLong("network"))
        assertEquals("links", config.getString("auth"))
        assertEquals(32, config.getString("session").length)
        assertEquals("stop", TurnfuIpc.command("stop").getString("cmd"))
    }

    @Test
    fun configEventsContainStructuredAddresses() {
        val event = assertNotNull(TurnfuIpc.event("""{"t":"config","ip":"10.66.128.30","dns":["1.1.1.1","8.8.8.8"],"ip6":"fd66:67::801E"}"""))
        val config = assertNotNull(TurnfuIpc.tunnelConfig(event))
        assertEquals("10.66.128.30", config.ip)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), config.dns)
        assertEquals("fd66:67::801e", config.ip6)
        assertNull(TurnfuIpc.event("__TURNFU_EVENT__|CONFIG|{}"))
        assertNull(TurnfuIpc.tunnelConfig(JSONObject("""{"t":"config","ip":"999.1.1.1","dns":["1.1.1.1"]}""")))
    }
}
