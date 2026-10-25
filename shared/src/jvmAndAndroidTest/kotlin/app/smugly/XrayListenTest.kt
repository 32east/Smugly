package app.smugly

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XrayListenTest {
    private val withInbounds = """
        {"inbounds": [
          {"tag": "socks", "protocol": "socks", "listen": "127.0.0.1", "port": 10808, "settings": {"auth": "password", "accounts": [{"user": "a", "pass": "b"}]}},
          {"tag": "http", "protocol": "http", "listen": "127.0.0.1", "port": 10809, "settings": {}}
        ], "outbounds": [{"tag": "proxy", "protocol": "vless"}]}
    """.trimIndent()

    private fun inbound(json: String, protocol: String): JSONObject {
        val arr = JSONObject(json).getJSONArray("inbounds")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("protocol") == protocol }
    }

    @Test
    fun loopbackUnlessLanIsAllowed() {
        assertEquals("127.0.0.1", XrayConfigBuilder.listenAddress(false))
        assertEquals("0.0.0.0", XrayConfigBuilder.listenAddress(true))
    }

    @Test
    fun defaultStaysOnLoopback() {
        val out = XrayConfigBuilder.withSocksPort(withInbounds, 1080)
        assertEquals("127.0.0.1", inbound(out, "socks").getString("listen"))
        assertEquals(1080, inbound(out, "socks").getInt("port"))
    }

    @Test
    fun allowLanOpensBothInbounds() {
        val out = XrayConfigBuilder.withSocksPort(withInbounds, 1080, "0.0.0.0")
            .let { XrayConfigBuilder.withHttpPort(it, 1081, "0.0.0.0") }
        assertEquals("0.0.0.0", inbound(out, "socks").getString("listen"))
        assertEquals("0.0.0.0", inbound(out, "http").getString("listen"))
        assertEquals(1081, inbound(out, "http").getInt("port"))
    }

    @Test
    fun localCredentialsReplaceThePanelAccounts() {
        val settings = inbound(XrayConfigBuilder.withSocksPort(withInbounds, 1080, "127.0.0.1", "smugly", "s3cret"), "socks")
            .getJSONObject("settings")
        assertEquals("password", settings.getString("auth"))
        val accounts = settings.getJSONArray("accounts")
        assertEquals(1, accounts.length())
        assertEquals("smugly", accounts.getJSONObject(0).getString("user"))
        assertEquals("s3cret", accounts.getJSONObject(0).getString("pass"))
    }

    @Test
    fun withoutCredentialsTheInboundIsOpen() {
        val settings = inbound(XrayConfigBuilder.withSocksPort(withInbounds, 1080), "socks").getJSONObject("settings")
        assertEquals("noauth", settings.getString("auth"))
        assertFalse(settings.has("accounts"))
        val half = inbound(XrayConfigBuilder.withSocksPort(withInbounds, 1080, "127.0.0.1", "smugly", ""), "socks")
        assertEquals("noauth", half.getJSONObject("settings").getString("auth"))
    }

    @Test
    fun injectedInboundCarriesTheCredentials() {
        val bare = """{"outbounds": [{"tag": "proxy", "protocol": "vless"}]}"""
        val settings = inbound(XrayConfigBuilder.withSocksPort(bare, 1080, "127.0.0.1", "u", "p"), "socks")
            .getJSONObject("settings")
        assertEquals("password", settings.getString("auth"))
        assertEquals("u", settings.getJSONArray("accounts").getJSONObject(0).getString("user"))
        assertEquals(true, settings.getBoolean("udp"))
    }

    @Test
    fun injectedInboundsGetTheListenAddressToo() {
        val bare = """{"outbounds": [{"tag": "proxy", "protocol": "vless"}]}"""
        val out = XrayConfigBuilder.withSocksPort(bare, 1080, "0.0.0.0")
            .let { XrayConfigBuilder.withHttpPort(it, 1081, "0.0.0.0") }
        assertEquals("0.0.0.0", inbound(out, "socks").getString("listen"))
        assertEquals("0.0.0.0", inbound(out, "http").getString("listen"))
    }
}
