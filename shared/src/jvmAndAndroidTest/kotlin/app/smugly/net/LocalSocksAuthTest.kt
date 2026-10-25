package app.smugly.net

import app.smugly.subscription.SubscriptionFetcher
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSocksAuthTest {

    private fun fakeSocksWithAuth(seen: AtomicReference<Pair<String, String>?>, offered: AtomicReference<List<Int>>): ServerSocket {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    input.readUnsignedByte()
                    val n = input.readUnsignedByte()
                    offered.set((0 until n).map { input.readUnsignedByte() })
                    out.write(byteArrayOf(5, 2))
                    input.readUnsignedByte()
                    val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    seen.set(String(user) to String(pass))
                    out.write(byteArrayOf(1, 0))
                    input.readUnsignedByte()
                    input.readUnsignedByte()
                    input.readUnsignedByte()
                    when (input.readUnsignedByte()) {
                        1 -> input.readFully(ByteArray(4))
                        3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                        4 -> input.readFully(ByteArray(16))
                    }
                    input.readUnsignedShort()
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    out.flush()
                }
            }
        }
        return server
    }

    @Test
    fun localProxyRouteSendsTheAppCredentials() {
        val seen = AtomicReference<Pair<String, String>?>(null)
        val offered = AtomicReference<List<Int>>(emptyList())
        fakeSocksWithAuth(seen, offered).use { server ->
            val spec = SubscriptionFetcher.ProxySpec("127.0.0.1", server.localPort, username = "smugly", password = "s3cret")
            Socket(spec.toJavaProxy()).use { socket ->
                socket.connect(InetSocketAddress.createUnresolved("example.com", 443), 3_000)
            }
        }
        assertTrue(2 in offered.get())
        assertEquals("smugly" to "s3cret", seen.get())
    }

    @Test
    fun credentialsAreOnlyHandedToLoopbackSocks() {
        LocalSocksAuth.register(40123, "u", "p")
        assertEquals("u", LocalSocksAuth.lookup("SOCKS5", true, 40123)?.userName)
        assertNull(LocalSocksAuth.lookup("SOCKS5", false, 40123))
        assertNull(LocalSocksAuth.lookup("http", true, 40123))
        assertNull(LocalSocksAuth.lookup("SOCKS5", true, 40124))
        LocalSocksAuth.register(40123, null, null)
        assertNull(LocalSocksAuth.lookup("SOCKS5", true, 40123))
    }

    @Test
    fun specStringNeverShowsThePassword() {
        val spec = SubscriptionFetcher.ProxySpec("127.0.0.1", 1080, username = "smugly", password = "s3cret")
        assertFalse("s3cret" in spec.toString())
    }
}
