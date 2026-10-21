package app.smugly

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XrayFormCodecTest {
    private fun proxyOf(json: String): JSONObject =
        JSONObject(json).getJSONArray("outbounds").getJSONObject(0)

    private fun roundTrip(form: XrayForm): XrayForm =
        XrayFormCodec.fromJson(XrayFormCodec.toJson(form, 1080))!!

    @Test
    fun vlessRealityVisionSurvivesARoundTrip() {
        val form = XrayForm(
            address = "nl.example.com",
            port = "8443",
            secret = "11111111-2222-3333-4444-555555555555",
            flow = "xtls-rprx-vision",
            security = XrayForm.SECURITY_REALITY,
            sni = "ya.ru",
            fingerprint = "chrome",
            publicKey = "pbk-value",
            shortId = "ab12",
            spiderX = "/"
        )
        val json = XrayFormCodec.toJson(form, 1080)
        val proxy = proxyOf(json)
        assertEquals("vless", proxy.getString("protocol"))
        assertEquals("proxy", proxy.getString("tag"))
        val settings = proxy.getJSONObject("settings")
        assertEquals("nl.example.com", settings.getString("address"))
        assertEquals(8443, settings.getInt("port"))
        assertEquals("xtls-rprx-vision", settings.getString("flow"))
        val reality = proxy.getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("pbk-value", reality.getString("publicKey"))
        assertEquals("ya.ru", reality.getString("serverName"))
        assertFalse(reality.has("allowInsecure"))
        assertEquals(form, XrayFormCodec.fromJson(json))
    }

    @Test
    fun vmessWebSocketTls() {
        val form = XrayForm(
            protocol = XrayFormProtocol.VMESS,
            address = "1.2.3.4",
            secret = "11111111-2222-3333-4444-555555555555",
            vmessSecurity = "chacha20-poly1305",
            network = "ws",
            host = "cdn.example.com",
            path = "/ws",
            sni = "cdn.example.com",
            alpn = "h2,http/1.1"
        )
        val proxy = proxyOf(XrayFormCodec.toJson(form, 1080))
        assertEquals("chacha20-poly1305", proxy.getJSONObject("settings").getString("security"))
        assertEquals("/ws", proxy.getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"))
        assertEquals(form, roundTrip(form))
    }

    @Test
    fun trojanGrpcMultiAndShadowsocks() {
        val trojan = XrayForm(
            protocol = XrayFormProtocol.TROJAN,
            address = "t.example.com",
            secret = "pass word",
            network = "grpc",
            serviceName = "svc",
            grpcMulti = true,
            sni = "t.example.com"
        )
        assertEquals(trojan, roundTrip(trojan))
        assertEquals("pass word", proxyOf(XrayFormCodec.toJson(trojan, 1080)).getJSONObject("settings").getString("password"))

        val ss = XrayForm(
            protocol = XrayFormProtocol.SHADOWSOCKS,
            address = "ss.example.com",
            port = "8388",
            secret = "secret",
            ssMethod = "2022-blake3-aes-128-gcm",
            security = XrayForm.SECURITY_NONE
        )
        val proxy = proxyOf(XrayFormCodec.toJson(ss, 1080))
        assertEquals("2022-blake3-aes-128-gcm", proxy.getJSONObject("settings").getString("method"))
        assertFalse(proxy.getJSONObject("streamSettings").has("security"))
        assertEquals(ss, roundTrip(ss))
    }

    @Test
    fun tcpHttpHeaderIsWritten() {
        val form = XrayForm(
            address = "h.example.com",
            secret = "11111111-2222-3333-4444-555555555555",
            headerType = "http",
            host = "a.com,b.com",
            path = "/x",
            security = XrayForm.SECURITY_NONE
        )
        val raw = proxyOf(XrayFormCodec.toJson(form, 1080)).getJSONObject("streamSettings").getJSONObject("rawSettings")
        val header = raw.getJSONObject("header")
        assertEquals("http", header.getString("type"))
        assertEquals("a.com", header.getJSONObject("request").getJSONObject("headers").getJSONArray("Host").getString(0))
        assertEquals(form, roundTrip(form))
    }

    @Test
    fun xhttpKeepsModeAndExtra() {
        val form = XrayForm(
            address = "x.example.com",
            secret = "11111111-2222-3333-4444-555555555555",
            network = "xhttp",
            path = "/up",
            xhttpMode = "packet-up",
            xhttpExtra = """{"xPaddingBytes":"100-1000"}""",
            sni = "x.example.com"
        )
        assertEquals(form, roundTrip(form))
    }

    @Test
    fun kcpWithoutHeaderWritesNoHeader() {
        val form = XrayForm(
            address = "k.example.com",
            secret = "11111111-2222-3333-4444-555555555555",
            network = "kcp",
            security = XrayForm.SECURITY_NONE
        )
        val kcp = proxyOf(XrayFormCodec.toJson(form, 1080)).getJSONObject("streamSettings").getJSONObject("kcpSettings")
        assertFalse(kcp.has("header"))
        assertFalse(kcp.has("seed"))
    }

    @Test
    fun readsImportedLinksAndLegacyVnext() {
        val link = VlessLinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@srv.example.com:443" +
                "?type=tcp&security=reality&pbk=KEY&sid=01&sni=ya.ru&fp=firefox&flow=xtls-rprx-vision#Name"
        )!!
        val form = XrayFormCodec.fromJson(XrayConfigBuilder.build(link, 1080))!!
        assertEquals("srv.example.com", form.address)
        assertEquals("KEY", form.publicKey)
        assertEquals("firefox", form.fingerprint)
        assertEquals("xtls-rprx-vision", form.flow)

        val legacy = """
            {"outbounds":[
              {"protocol":"freedom","tag":"direct"},
              {"protocol":"vmess","tag":"proxy","settings":{"vnext":[{"address":"v.example.com","port":10086,
                "users":[{"id":"11111111-2222-3333-4444-555555555555","security":"aes-128-gcm"}]}]},
               "streamSettings":{"network":"raw","security":"tls","tlsSettings":{"serverName":"v.example.com"}}}
            ]}
        """.trimIndent()
        val vmess = XrayFormCodec.fromJson(legacy)!!
        assertEquals(XrayFormProtocol.VMESS, vmess.protocol)
        assertEquals("10086", vmess.port)
        assertEquals("aes-128-gcm", vmess.vmessSecurity)
        assertEquals("tcp", vmess.network)
        assertEquals(XrayForm.SECURITY_TLS, vmess.security)
    }

    @Test
    fun configWithoutAProxyIsNotAForm() {
        assertNull(XrayFormCodec.fromJson("""{"outbounds":[{"protocol":"freedom"}]}"""))
        assertNull(XrayFormCodec.fromJson("not json"))
    }

    @Test
    fun missingFields() {
        assertEquals(XrayForm.Field.ADDRESS, XrayForm().missingField())
        assertEquals(XrayForm.Field.PORT, XrayForm(address = "a", port = "0").missingField())
        assertEquals(XrayForm.Field.SECRET, XrayForm(address = "a").missingField())
        assertEquals(
            XrayForm.Field.PUBLIC_KEY,
            XrayForm(address = "a", secret = "s", security = XrayForm.SECURITY_REALITY).missingField()
        )
        assertTrue(XrayForm(address = "a", secret = "s").missingField() == null)
    }
}
