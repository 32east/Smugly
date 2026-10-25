package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TurnfuLinkTest {
    private val frozen = "turnfu2://AkcFFOIerBMsl3nkFjbH_Tgf2La2e4eWJT61gXwTsII4n-qEyz7qKSfqAtN7wWoDJUQKpf3-TK6tAi1Cy86EvVhpubYXDCsNe0ELVRJKX3awYw"

    @Test
    fun rustServerLinkDecodesWithTheSameHkdfAndAead() {
        val link = assertNotNull(TurnfuLink.parse(frozen))
        assertEquals("203.0.113.9", link.host)
        assertEquals(46000, link.port)
        assertEquals("fixture-pw", link.password)
        assertEquals(20, link.workers)
        assertEquals(listOf("AbCdEfGhIjKlMnOpQr"), link.callHashes)
    }

    @Test
    fun panelMetadataRoundTripsAndEachExportUsesAFreshNonce() {
        val data = TurnfuLinkData("194.213.18.21", 46000, "p@ss/w+rd", 20, 1200, "🇺🇸 США", emptyList())
        val a = TurnfuLink.importLink(data)
        val b = TurnfuLink.importLink(data)
        assertNotEquals(a, b)
        assertEquals(data, TurnfuLink.parse(a))
        assertEquals(data, TurnfuLink.parse(b))
    }

    @Test
    fun oldLinksAndDamagedCiphertextsAreRejected() {
        for (old in listOf("turnfu://import?host=example&password=x", "turnfu://AAAAAAAAAAA", "turnfu2://import?host=example", "cdnfu://import?url=x")) {
            assertNull(TurnfuLink.parse(old))
        }
        val damaged = frozen.dropLast(2) + "AA"
        assertNull(TurnfuLink.parse(damaged))
    }

    @Test
    fun credentialsAndMetadataSurviveProfileApplication() {
        val data = assertNotNull(TurnfuLink.parse(frozen))
        val base = defaultConfig()
        val profile = TurnfuLink.applyTo(base, data)
        val restored = ConfigJson.configFromString(ConfigJson.configToString(profile))
        assertEquals(Config.TunnelProtocol.TURNFU, restored.protocol)
        assertEquals(data.host, restored.turnfuHost)
        assertEquals(data.callHashes, restored.turnfuCalls)
        assertEquals(20, restored.turnfuWorkers)
    }

    @Test
    fun accountTokensUseTheSameEnvelope() {
        val data = TurnfuLinkData("edge.example", 46000, "pw", null, null, null, emptyList(), "account-test-token")
        assertEquals(data, TurnfuLink.parse(TurnfuLink.importLink(data)))
    }
}
