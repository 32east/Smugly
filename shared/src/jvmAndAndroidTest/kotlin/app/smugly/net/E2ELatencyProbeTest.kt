package app.smugly.net

import app.smugly.Config
import app.smugly.defaultConfig
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The probe against a stand-in engine: a real SOCKS5 listener that answers the HTTP request itself.
 * No tunnel binary is involved, so this exercises the part that is the same for every protocol —
 * handshake, remote-resolved CONNECT, timing, and what happens when the engine misbehaves.
 */
class E2ELatencyProbeTest {

    @Test
    fun `measures a round trip through the engine's socks port`() {
        FakeSocksEngine().use { engine ->
            val result = E2ELatencyProbe.measure(xrayConfig(), engine.launcher())
            assertTrue(result.isSuccess, "probe failed: ${result.exceptionOrNull()?.message}")
            assertTrue(result.getOrThrow() >= 1, "latency must be a positive number of ms")
            // The name has to reach the engine unresolved — that is what makes the exit's DNS part
            // of the measurement instead of this machine's.
            assertEquals("cp.cloudflare.com", engine.lastRequestedHost)
            assertEquals(80, engine.lastRequestedPort)
        }
    }

    @Test
    fun `a profile whose tunnel never comes up fails instead of reporting a number`() {
        FakeSocksEngine().use { engine ->
            val result = E2ELatencyProbe.measure(xrayConfig(), engine.launcher(neverReady = true))
            assertTrue(result.isFailure, "a tunnel that never came up must not produce a latency")
            assertTrue(
                result.exceptionOrNull()?.message.orEmpty().contains("did not come up"),
                "unexpected reason: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    @Test
    fun `an exit that accepts and says nothing is a failure, not a fast server`() {
        FakeSocksEngine(answerHttp = false).use { engine ->
            val result = E2ELatencyProbe.measure(xrayConfig(), engine.launcher())
            assertTrue(result.isFailure, "a silent exit must not read as a working profile")
        }
    }

    @Test
    fun `a variant that never comes up hands over to the next one`() {
        val engines = mutableListOf<FakeSocksEngine>()
        try {
            val launcher = variantLauncher(engines) { variant ->
                if (variant.resolverTransport == Config.ResolverTransport.UDP) null else FakeSocksEngine()
            }
            val result = E2ELatencyProbe.measure(slipstreamAutoConfig(), launcher)
            assertTrue(result.isSuccess, "probe failed: ${result.exceptionOrNull()?.message}")
            assertEquals(
                listOf(Config.ResolverTransport.UDP, Config.ResolverTransport.TCP),
                launcher.launched.map { it.resolverTransport }
            )
        } finally {
            engines.forEach { it.close() }
        }
    }

    @Test
    fun `a slow variant is not the answer when another one is fast`() {
        val engines = mutableListOf<FakeSocksEngine>()
        try {
            val launcher = variantLauncher(engines) { variant ->
                FakeSocksEngine(answerDelayMs = if (variant.resolverTransport == Config.ResolverTransport.UDP) 2_500 else 0)
            }
            val result = E2ELatencyProbe.measure(slipstreamAutoConfig(), launcher)
            assertTrue(result.isSuccess, "probe failed: ${result.exceptionOrNull()?.message}")
            assertTrue(result.getOrThrow() < 2_000, "the fast transport must win, got ${result.getOrThrow()}ms")
            assertEquals(2, launcher.launched.size)
        } finally {
            engines.forEach { it.close() }
        }
    }

    @Test
    fun `a fast first variant is not followed by a second launch`() {
        val engines = mutableListOf<FakeSocksEngine>()
        try {
            val launcher = variantLauncher(engines) { FakeSocksEngine() }
            val result = E2ELatencyProbe.measure(slipstreamAutoConfig(), launcher)
            assertTrue(result.isSuccess, "probe failed: ${result.exceptionOrNull()?.message}")
            assertEquals(1, launcher.launched.size)
        } finally {
            engines.forEach { it.close() }
        }
    }

    @Test
    fun `s3fu and cdnfu are timed after a warm-up request, not on the session-opening one`() {
        for (protocol in listOf(Config.TunnelProtocol.S3FU, Config.TunnelProtocol.CDNFU)) {
            FakeSocksEngine(firstAnswerDelayMs = 1_500).use { engine ->
                val result = E2ELatencyProbe.measure(defaultConfig().copy(protocol = protocol), engine.launcher())
                assertTrue(result.isSuccess, "$protocol probe failed: ${result.exceptionOrNull()?.message}")
                assertTrue(result.getOrThrow() < 1_000, "$protocol timed the warm-up: ${result.getOrThrow()}ms")
                assertEquals(2, engine.requests, "$protocol must send exactly one warm-up and one timed request")
            }
        }
    }

    @Test
    fun `xray is timed on its first request`() {
        FakeSocksEngine(firstAnswerDelayMs = 300).use { engine ->
            val result = E2ELatencyProbe.measure(xrayConfig(), engine.launcher())
            assertTrue(result.isSuccess, "probe failed: ${result.exceptionOrNull()?.message}")
            assertTrue(result.getOrThrow() >= 300, "xray must not get a warm-up, got ${result.getOrThrow()}ms")
            assertEquals(1, engine.requests)
        }
    }

    @Test
    fun `a protocol the platform cannot run falls back instead of throwing`() {
        // No launcher at all: the shared fallback answers, and for an auto-DNS Slipstream profile
        // that is a failure with a reason — never a made-up number.
        val config = defaultConfig().copy(
            protocol = Config.TunnelProtocol.SLIPSTREAM,
            resolverMode = Config.ResolverMode.AUTO,
            resolverHost = "",
            domain = "tunnel.example.com"
        )
        val result = E2ELatencyProbe.measure(config, launcher = null)
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("auto-DNS"),
            "unexpected reason: ${result.exceptionOrNull()?.message}"
        )
    }

    private fun xrayConfig(): Config = defaultConfig().copy(
        protocol = Config.TunnelProtocol.XRAY,
        xrayConfigJson = """{"outbounds":[{"protocol":"freedom"}]}"""
    )

    private fun slipstreamAutoConfig(): Config = defaultConfig().copy(
        protocol = Config.TunnelProtocol.SLIPSTREAM,
        resolverMode = Config.ResolverMode.AUTO,
        domain = "tunnel.example.com"
    )

    private class VariantLauncher(
        private val engines: MutableList<FakeSocksEngine>,
        private val engineFor: (Config) -> FakeSocksEngine?
    ) : E2ELatencyProbe.Launcher {
        val launched = mutableListOf<Config>()

        override fun supports(protocol: Config.TunnelProtocol) = true
        override fun readyTimeoutMs(protocol: Config.TunnelProtocol) = 500L
        override fun variants(config: Config) = listOf(
            config.copy(resolverTransport = Config.ResolverTransport.UDP),
            config.copy(resolverTransport = Config.ResolverTransport.TCP)
        )

        override fun launch(config: Config, socksPort: Int): E2ELatencyProbe.Session {
            launched += config
            val engine = engineFor(config)?.also { engines += it }
            engine?.listenOn(socksPort)
            return object : E2ELatencyProbe.Session {
                override fun awaitReady(timeoutMs: Long) = engine != null
                override fun close() {
                    engine?.close()
                }
            }
        }
    }

    private fun variantLauncher(
        engines: MutableList<FakeSocksEngine>,
        engineFor: (Config) -> FakeSocksEngine?
    ) = VariantLauncher(engines, engineFor)

    /**
     * Stands in for an engine: speaks SOCKS5 no-auth on a port the probe picks, and plays the far
     * side of the connection so no traffic leaves the machine.
     */
    private class FakeSocksEngine(
        private val answerHttp: Boolean = true,
        private val answerDelayMs: Long = 0,
        private val firstAnswerDelayMs: Long = 0
    ) : AutoCloseable {
        private val served = java.util.concurrent.atomic.AtomicInteger()
        val requests: Int get() = served.get()
        @Volatile var lastRequestedHost: String? = null
        @Volatile var lastRequestedPort: Int = 0
        private var server: ServerSocket? = null

        fun launcher(neverReady: Boolean = false) = object : E2ELatencyProbe.Launcher {
            override fun supports(protocol: Config.TunnelProtocol) = true
            override fun readyTimeoutMs(protocol: Config.TunnelProtocol) = 1_000L
            override fun launch(config: Config, socksPort: Int): E2ELatencyProbe.Session {
                if (!neverReady) listenOn(socksPort)
                return object : E2ELatencyProbe.Session {
                    override fun awaitReady(timeoutMs: Long) = !neverReady
                    override fun close() = this@FakeSocksEngine.close()
                }
            }
        }

        fun listenOn(port: Int) {
            val socket = ServerSocket(port)
            server = socket
            thread(isDaemon = true, name = "fake-socks") {
                runCatching {
                    while (!socket.isClosed) {
                        val client = socket.accept()
                        thread(isDaemon = true) { runCatching { serve(client) } }
                    }
                }
            }
        }

        private fun serve(client: Socket) = client.use {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val methodCount = readMethods(input)
            assertTrue(methodCount > 0, "client offered no auth methods")
            output.write(byteArrayOf(0x05, 0x00)) // no auth
            output.flush()

            val head = input.readExactly(4)
            assertEquals(0x05.toByte(), head[0])
            assertEquals(0x01.toByte(), head[1], "must be CONNECT")
            assertEquals(0x03.toByte(), head[3], "must send the host name, not an address")
            val hostLength = input.read()
            lastRequestedHost = String(input.readExactly(hostLength), Charsets.US_ASCII)
            val portBytes = input.readExactly(2)
            lastRequestedPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // Success, bound to 0.0.0.0:0 — the address is ignored by every client.
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            readRequestHead(input)
            val first = served.incrementAndGet() == 1
            if (answerHttp) {
                if (first && firstAnswerDelayMs > 0) Thread.sleep(firstAnswerDelayMs)
                if (answerDelayMs > 0) Thread.sleep(answerDelayMs)
                output.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
            } else {
                // Accept and stay silent: the shape a black-holing exit has.
                Thread.sleep(2_000)
            }
        }

        private fun readMethods(input: InputStream): Int {
            val greeting = input.readExactly(2)
            assertEquals(0x05.toByte(), greeting[0], "not SOCKS5")
            val count = greeting[1].toInt() and 0xFF
            input.readExactly(count)
            return count
        }

        private fun readRequestHead(input: InputStream) {
            val seen = StringBuilder()
            while (!seen.endsWith("\r\n\r\n") && seen.length < 4096) {
                val b = input.read()
                if (b < 0) break
                seen.append(b.toChar())
            }
        }

        private fun InputStream.readExactly(n: Int): ByteArray {
            val buffer = ByteArray(n)
            var read = 0
            while (read < n) {
                val got = read(buffer, read, n - read)
                check(got >= 0) { "stream ended after $read of $n bytes" }
                read += got
            }
            return buffer
        }

        override fun close() {
            runCatching { server?.close() }
            server = null
        }
    }
}
