package app.smugly

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XrayRoutingTest {

    private val panelConfig = """
        {
          "inbounds": [{"tag": "socks", "protocol": "socks", "port": 10808}],
          "outbounds": [
            {"tag": "proxy", "protocol": "vless", "settings": {"address": "nl.example.com", "port": 443}},
            {"tag": "direct", "protocol": "freedom", "settings": {}},
            {"tag": "block", "protocol": "blackhole", "settings": {}}
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              {"type": "field", "ip": ["geoip:private"], "outboundTag": "direct"},
              {"type": "field", "domain": ["geosite:openai"], "outboundTag": "proxy"}
            ]
          }
        }
    """.trimIndent()

    private val bareConfig = """
        {"outbounds": [{"tag": "proxy", "protocol": "vless", "settings": {}}]}
    """.trimIndent()

    private fun rules(json: String): JSONArray = JSONObject(json).getJSONObject("routing").getJSONArray("rules")
    private fun outbounds(json: String): JSONArray = JSONObject(json).getJSONArray("outbounds")

    @Test
    fun nothingEnabledLeavesTheTextUntouched() {
        assertSame(panelConfig, XrayRouting.apply(panelConfig, RoutingSettings()))
    }

    @Test
    fun brokenJsonIsPassedThrough() {
        assertEquals("{not json", XrayRouting.apply("{not json", RoutingSettings(directRu = true)))
    }

    @Test
    fun directRuReusesTheProfilesOwnFreedomOutbound() {
        val out = XrayRouting.apply(panelConfig, RoutingSettings(directRu = true))
        val r = rules(out)
        assertEquals("smugly-ru-domain", r.getJSONObject(0).getString("ruleTag"))
        assertEquals("direct", r.getJSONObject(0).getString("outboundTag"))
        assertEquals(XrayRouting.RU_DOMAINS, r.getJSONObject(0).getJSONArray("domain").toList())
        assertEquals("smugly-ru-ip", r.getJSONObject(1).getString("ruleTag"))
        assertEquals(listOf("geoip:ru"), r.getJSONObject(1).getJSONArray("ip").toList())
        assertEquals(4, r.length(), "the profile's own rules follow ours")
        assertEquals(3, outbounds(out).length(), "no extra outbound when one already exists")
        assertEquals("IPIfNonMatch", JSONObject(out).getJSONObject("routing").getString("domainStrategy"))
    }

    @Test
    fun theFirstOutboundStaysTheDefault() {
        val out = XrayRouting.apply(bareConfig, RoutingSettings(directRu = true, blockAds = true))
        val obs = outbounds(out)
        assertEquals("proxy", obs.getJSONObject(0).getString("tag"))
        val tags = (0 until obs.length()).map { obs.getJSONObject(it).getString("tag") }
        assertEquals(listOf("proxy", XrayRouting.BLOCK_TAG, XrayRouting.DIRECT_TAG), tags)
        assertEquals("blackhole", obs.getJSONObject(1).getString("protocol"))
        assertEquals("freedom", obs.getJSONObject(2).getString("protocol"))
    }

    @Test
    fun bareConfigGetsARoutingBlockWithoutLocalResolution() {
        val out = XrayRouting.apply(bareConfig, RoutingSettings(blockAds = true))
        val routing = JSONObject(out).getJSONObject("routing")
        assertEquals("AsIs", routing.getString("domainStrategy"))
        val rule = routing.getJSONArray("rules").getJSONObject(0)
        assertEquals(XrayRouting.BLOCK_TAG, rule.getString("outboundTag"))
        assertEquals(listOf("geosite:category-ads-all"), rule.getJSONArray("domain").toList())
    }

    @Test
    fun adsAreBlockedBeforeRussianSitesGoDirect() {
        val out = XrayRouting.apply(panelConfig, RoutingSettings(directRu = true, blockAds = true))
        val tags = rules(out).let { r -> (0 until 3).map { r.getJSONObject(it).getString("ruleTag") } }
        assertEquals(listOf("smugly-ads", "smugly-ru-domain", "smugly-ru-ip"), tags)
        assertEquals("block", rules(out).getJSONObject(0).getString("outboundTag"))
    }

    @Test
    fun applyingTwiceDoesNotStackRules() {
        val s = RoutingSettings(directRu = true, blockAds = true)
        val once = XrayRouting.apply(panelConfig, s)
        val twice = XrayRouting.apply(once, s)
        assertEquals(rules(once).toString(), rules(twice).toString())
        assertEquals(outbounds(once).length(), outbounds(twice).length())
    }

    @Test
    fun switchingOffRemovesOurRulesOnly() {
        val on = XrayRouting.apply(panelConfig, RoutingSettings(directRu = true, blockAds = true))
        val off = XrayRouting.apply(on, RoutingSettings())
        val r = rules(off)
        assertEquals(2, r.length())
        assertEquals("geoip:private", r.getJSONObject(0).getJSONArray("ip").getString(0))
    }

    @Test
    fun aChainedFreedomIsNotMistakenForDirect() {
        val chained = """
            {"outbounds": [
              {"tag": "proxy", "protocol": "vless", "settings": {}},
              {"tag": "frag", "protocol": "freedom", "settings": {}, "streamSettings": {"sockopt": {"dialerProxy": "x"}}},
              {"tag": "redir", "protocol": "freedom", "settings": {"redirect": "1.2.3.4:53"}}
            ]}
        """.trimIndent()
        val out = XrayRouting.apply(chained, RoutingSettings(directRu = true))
        assertEquals(XrayRouting.DIRECT_TAG, rules(out).getJSONObject(0).getString("outboundTag"))
    }

    @Test
    fun configWithoutOutboundsIsLeftForXrayToReject() {
        val noOutbounds = """{"inbounds": []}"""
        assertEquals(noOutbounds, XrayRouting.apply(noOutbounds, RoutingSettings(directRu = true)))
    }

    private fun JSONArray.toList(): List<String> = (0 until length()).map { getString(it) }

    @Test
    fun resultIsValidJson() {
        val out = XrayRouting.apply(panelConfig, RoutingSettings(directRu = true, blockAds = true))
        assertTrue(JSONObject(out).has("inbounds"))
    }
}
