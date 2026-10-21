package app.smugly.net

import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LocalConnectProxy(private val upstream: () -> Upstream) {

    sealed interface Upstream {
        data object Direct : Upstream
        data class Socks(val port: Int, val username: String?, val password: String?) : Upstream
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "smugly-connect-proxy").apply { isDaemon = true }
    }
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    fun start(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { serve(client) }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = client.getInputStream()
            val head = readHead(input) ?: return
            val requestLine = head.decodeToString().substringBefore("\r\n")
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val connect = parts[0].equals("CONNECT", ignoreCase = true)
            val (host, port) = if (connect) {
                splitHostPort(parts[1], 443)
            } else {
                val authority = parts[1].substringAfter("://", "").substringBefore('/')
                if (authority.isBlank()) return
                splitHostPort(authority, 80)
            }
            remote = open(host, port)
            client.soTimeout = 0
            remote.soTimeout = 0
            val out = client.getOutputStream()
            if (connect) {
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            } else {
                remote.getOutputStream().write(head)
                remote.getOutputStream().flush()
            }
            val upstreamSocket = remote
            pool.execute { pipe(client.getInputStream(), upstreamSocket.getOutputStream(), client, upstreamSocket) }
            pipe(upstreamSocket.getInputStream(), out, client, upstreamSocket)
        } catch (e: Throwable) {
            PlatformLog.log(LogLevel.WARN, TAG, "proxy request failed: ${e.message}")
            if (remote == null) {
                runCatching {
                    client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
            runCatching { client.close() }
            runCatching { remote?.close() }
        }
    }

    private fun open(host: String, port: Int): Socket = when (val route = upstream()) {
        Upstream.Direct -> Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        is Upstream.Socks -> Socks5Client.connect(
            proxyPort = route.port,
            host = host,
            port = port,
            username = route.username,
            password = route.password,
            timeoutMs = CONNECT_TIMEOUT_MS
        )
    }

    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private fun readHead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
        }
        return null
    }

    private fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int> {
        if (authority.startsWith("[")) {
            val host = authority.substringAfter('[').substringBefore(']')
            val port = authority.substringAfter("]:", "").toIntOrNull() ?: defaultPort
            return host to port
        }
        val host = authority.substringBeforeLast(':')
        val port = authority.substringAfterLast(':', "").toIntOrNull()
        return if (port == null) authority to defaultPort else host to port
    }

    private companion object {
        const val TAG = "LocalConnectProxy"
        const val HEADER_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 12_000
        const val MAX_HEAD_BYTES = 32 * 1024
    }
}
