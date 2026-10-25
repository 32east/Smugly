package app.smugly.desktop

import app.smugly.Config
import app.smugly.defaultConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopProfileLinksTest {

    @Test
    fun s3fu_query_link_is_imported() {
        val uri = "s3fu://import?endpoint=https%3A%2F%2Fs3.example.com&bucket=bkt" +
            "&accesskey=AK&secretkey=SK&prefix=s3fu_abc&psk=deadbeef&name=Russia"
        val p = DesktopProfileLinks.parse(uri)
        assertNotNull(p)
        assertEquals(Config.TunnelProtocol.S3FU, p.config.protocol)
        assertEquals("https://s3.example.com", p.config.s3Endpoint)
        assertEquals("bkt", p.config.s3Bucket)
        assertEquals("AK", p.config.s3AccessKey)
        assertEquals("SK", p.config.s3SecretKey)
        assertEquals("s3fu_abc", p.config.s3Prefix)
        assertEquals("deadbeef", p.config.s3Psk)
        assertEquals("Russia", p.name)
    }

    @Test
    fun slipstream_query_link_is_imported() {
        val uri = "slipstream://import?domain=ee.example.com&resolvermode=auto" +
            "&username=slipstream&password=secret&name=Estonia"
        val p = DesktopProfileLinks.parse(uri, base = defaultConfig(mode = Config.Mode.PROXY))
        assertNotNull(p)
        assertEquals(Config.TunnelProtocol.SLIPSTREAM, p.config.protocol)
        assertEquals("ee.example.com", p.config.domain)
        assertEquals(Config.ResolverMode.AUTO, p.config.resolverMode)
        assertEquals(Config.AuthMode.LOGIN_PASSWORD, p.config.authMode)
        assertEquals("slipstream", p.config.username)
        assertEquals("secret", p.config.password)
        assertEquals("Estonia", p.name)
    }

    @Test
    fun preferred_name_wins_over_query_name() {
        val p = DesktopProfileLinks.parse(
            "s3fu://import?endpoint=https://edge.example&psk=x&name=FromQuery",
            preferredName = "FromPanel"
        )
        assertNotNull(p)
        assertEquals("FromPanel", p.name)
    }

    @Test
    fun cdnfu_links_are_no_longer_imported() {
        assertEquals(null, DesktopProfileLinks.parse("cdnfu://import?url=https://edge.example&psk=abc"))
    }
}
