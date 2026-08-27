package app.smugly.desktop

import app.smugly.PerAppMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

private const val OWN = "Smugly5.exe"

class TunConfigTest {

    private fun config(
        mode: PerAppMode = PerAppMode.OFF,
        processes: Set<String> = emptySet(),
        socksPort: Int = 1080
    ): JSONObject = JSONObject(
        TunConfig.build(socksPort, mode, processes, listOf(OWN))
    )

    private fun routeRules(json: JSONObject): List<JSONObject> =
        json.getJSONObject("route").getJSONArray("rules").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    private fun dnsRules(json: JSONObject): List<JSONObject> =
        json.getJSONObject("dns").getJSONArray("rules").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    private fun JSONObject.processNames(): List<String> =
        optJSONArray("process_name")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: emptyList()

    @Test
    fun theTunnelIsTheDefaultWhenSplitTunnellingIsOff() {
        val json = config()
        assertEquals("proxy", json.getJSONObject("route").getString("final"))
        assertEquals("dns-remote", json.getJSONObject("dns").getString("final"))
    }

    @Test
    fun theProxyOutboundPointsAtTheLocalListener() {
        val proxy = config(socksPort = 2080).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", proxy.getString("type"))
        assertEquals("127.0.0.1", proxy.getString("server"))
        assertEquals(2080, proxy.getInt("server_port"))
    }

    /** "Selected apps bypass" — everything else keeps using the tunnel. */
    @Test
    fun disallowedSendsOnlyThePickedProcessesDirect() {
        val json = config(PerAppMode.DISALLOWED, setOf("chrome.exe", "discord.exe"))
        assertEquals("proxy", json.getJSONObject("route").getString("final"))
        val rule = routeRules(json).last()
        assertEquals(listOf("chrome.exe", "discord.exe"), rule.processNames())
        assertEquals("direct", rule.getString("outbound"))
    }

    /** "Only selected apps" — the default flips, and the picked ones are the exception. */
    @Test
    fun allowedInvertsTheDefault() {
        val json = config(PerAppMode.ALLOWED, setOf("chrome.exe"))
        assertEquals("direct", json.getJSONObject("route").getString("final"))
        val rule = routeRules(json).last()
        assertEquals(listOf("chrome.exe"), rule.processNames())
        assertEquals("proxy", rule.getString("outbound"))
    }

    /**
     * A TUN nothing is routed into looks connected while every packet leaves in the clear —
     * the same trap [app.smugly.PerAppFilter] guards on Android, and the same answer.
     */
    @Test
    fun anEmptyAllowListFallsBackToTunnellingEverything() {
        val json = config(PerAppMode.ALLOWED, emptySet())
        assertEquals("proxy", json.getJSONObject("route").getString("final"))
        assertEquals("dns-remote", json.getJSONObject("dns").getString("final"))
    }

    /** Route the tunnel's own processes into the tunnel and the packet re-enters its own stack. */
    @Test
    fun ourProcessAndEveryEngineAreAlwaysDirect() {
        val json = config(PerAppMode.DISALLOWED, setOf("chrome.exe"))
        val bypass = routeRules(json).first { it.optString("outbound") == "direct" && it.has("process_name") }
        val names = bypass.processNames()
        assertTrue(OWN in names, "own process missing from the bypass list: $names")
        TunConfig.ALWAYS_DIRECT.forEach {
            assertTrue(it in names, "$it missing from the bypass list")
        }
    }

    /** The bypass rule has to be matched before the user's own rules can send it to the proxy. */
    @Test
    fun theBypassRuleIsEvaluatedBeforeTheUserRule() {
        val rules = routeRules(config(PerAppMode.ALLOWED, setOf("chrome.exe")))
        val bypassAt = rules.indexOfFirst { OWN in it.processNames() }
        val userAt = rules.indexOfFirst { "chrome.exe" in it.processNames() }
        assertTrue(bypassAt in 0 until userAt, "bypass at $bypassAt, user rule at $userAt")
    }

    /** Even if the user picks the app itself, it must not end up tunnelled. */
    @Test
    fun pickingOurOwnProcessCannotTunnelIt() {
        val rules = routeRules(config(PerAppMode.ALLOWED, setOf(OWN, "chrome.exe")))
        val bypassAt = rules.indexOfFirst { OWN in it.processNames() }
        val userAt = rules.indexOfFirst { "chrome.exe" in it.processNames() }
        assertEquals("direct", rules[bypassAt].getString("outbound"))
        assertTrue(bypassAt < userAt)
    }

    /** A NAS, a printer or the router's own page must not be answered from another country. */
    @Test
    fun privateAddressesStayOnTheLan() {
        val rule = routeRules(config()).firstOrNull { it.optBoolean("ip_is_private", false) }
        assertTrue(rule != null, "no private-address rule")
        assertEquals("direct", rule!!.getString("outbound"))
    }

    /** A bypassed app resolving through the tunnel is a leak in the other direction. */
    @Test
    fun dnsFollowsTheSameSplitAsRouting() {
        val disallowed = dnsRules(config(PerAppMode.DISALLOWED, setOf("chrome.exe"))).last()
        assertEquals(listOf("chrome.exe"), disallowed.processNames())
        assertEquals("dns-local", disallowed.getString("server"))

        val allowed = config(PerAppMode.ALLOWED, setOf("chrome.exe"))
        assertEquals("dns-local", allowed.getJSONObject("dns").getString("final"))
        assertEquals("dns-remote", dnsRules(allowed).last().getString("server"))
    }

    @Test
    fun dnsQueriesAreHijackedRatherThanForwarded() {
        val hijack = routeRules(config()).firstOrNull { it.optString("action") == "hijack-dns" }
        assertTrue(hijack != null, "DNS is not hijacked; queries would leave as plain UDP/53")
    }

    @Test
    fun processNamesAreNormalizedHoweverTheyWereTyped() {
        assertEquals(
            listOf("chrome.exe", "discord.exe"),
            TunConfig.normalizeProcesses(
                listOf("""C:\Program Files\Google\Chrome\chrome.exe""", "Discord", "CHROME.EXE")
            )
        )
    }

    @Test
    fun blankEntriesNeverBecomeARule() {
        assertEquals(emptyList(), TunConfig.normalizeProcesses(listOf("", "   ")))
        val json = config(PerAppMode.DISALLOWED, setOf("", " "))
        assertFalse(
            routeRules(json).any { it.optString("outbound") == "direct" && "chrome.exe" in it.processNames() }
        )
    }

    @Test
    fun theControlApiIsLoopbackOnlyAndTokenGuarded() {
        val json = JSONObject(
            TunConfig.build(1081, PerAppMode.OFF, emptySet(), listOf(OWN), clashApiPort = 9099, clashApiSecret = "s3cr3t")
        )
        val api = json.getJSONObject("experimental").getJSONObject("clash_api")
        assertTrue(api.getString("external_controller").startsWith("127.0.0.1:"))
        assertEquals("s3cr3t", api.getString("secret"))
    }

    /** No port, no control surface — an API nobody reads is only an open door. */
    @Test
    fun theControlApiIsAbsentWhenNoPortWasGiven() {
        assertFalse(config().has("experimental"))
    }

    /**
     * Dumps a config for `sing-box check`. Not an assertion — the schema is the engine's, and the
     * only honest way to know it is accepted is to hand it to the engine (see docs/tun-mode.md).
     */
    @Test
    fun writeSampleConfigsForExternalValidation() {
        val dir = File(System.getProperty("java.io.tmpdir"), "smugly-tun-configs").apply { mkdirs() }
        mapOf(
            "off" to config(),
            "allowed" to config(PerAppMode.ALLOWED, setOf("chrome.exe", "discord.exe")),
            "disallowed" to config(PerAppMode.DISALLOWED, setOf("chrome.exe")),
            "api" to JSONObject(
                TunConfig.build(
                    1081, PerAppMode.DISALLOWED, setOf("chrome.exe"), listOf(OWN),
                    clashApiPort = 9099, clashApiSecret = "deadbeef"
                )
            )
        ).forEach { (name, json) ->
            File(dir, "tun-$name.json").writeText(json.toString(2))
        }
        assertTrue(File(dir, "tun-off.json").isFile)
    }
}
