package app.smugly.account

import app.smugly.AppVersion
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import app.smugly.subscription.SubscriptionFetcher
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AccountHttp(
    private val routes: () -> List<SubscriptionFetcher.ProxySpec?>,
    private val transport: Transport = UrlConnectionTransport
) {
    data class Reply(val code: Int, val json: JSONObject?)

    fun interface Transport {
        fun exchange(
            url: String,
            method: String,
            token: String?,
            body: String?,
            route: SubscriptionFetcher.ProxySpec?
        ): Pair<Int, String>
    }

    fun request(base: String, method: String, path: String, token: String? = null, body: JSONObject? = null): Result<Reply> {
        val url = base.trimEnd('/') + path
        var last: Throwable? = null
        for (route in routes().ifEmpty { listOf(null) }) {
            try {
                val (code, text) = transport.exchange(url, method, token, body?.toString(), route)
                val json = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("ok") }
                if (json == null) {
                    last = IllegalStateException("HTTP $code from $base is not the Smugly API")
                    continue
                }
                return Result.success(Reply(code, json))
            } catch (e: Throwable) {
                last = e
                PlatformLog.log(
                    LogLevel.INFO, TAG,
                    "$method $path via ${route?.let { "${it.host}:${it.port}" } ?: "direct"} failed: ${e.message}"
                )
            }
        }
        return Result.failure(last ?: IllegalStateException("no route"))
    }

    fun fetchText(url: String): Result<String> {
        var last: Throwable? = null
        for (route in routes().ifEmpty { listOf(null) }) {
            try {
                val (code, text) = transport.exchange(url, "GET", null, null, route)
                if (code == 200) return Result.success(text)
                last = IllegalStateException("HTTP $code from $url")
            } catch (e: Throwable) {
                last = e
                PlatformLog.log(
                    LogLevel.INFO, TAG,
                    "GET $url via ${route?.let { "${it.host}:${it.port}" } ?: "direct"} failed: ${e.message}"
                )
            }
        }
        return Result.failure(last ?: IllegalStateException("no route"))
    }

    object UrlConnectionTransport : Transport {
        override fun exchange(
            url: String,
            method: String,
            token: String?,
            body: String?,
            route: SubscriptionFetcher.ProxySpec?
        ): Pair<Int, String> {
            val parsed = URL(url)
            val opened = if (route == null) parsed.openConnection() else parsed.openConnection(route.toJavaProxy())
            val conn = opened as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Smugly/${AppVersion.name} (account)")
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { input -> readBounded(input) }.orEmpty()
                return code to text
            } finally {
                runCatching { conn.disconnect() }
            }
        }

        private fun readBounded(input: java.io.InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                require(total <= MAX_BODY_BYTES) { "response too large" }
                out.write(buf, 0, n)
            }
            return out.toByteArray().decodeToString()
        }
    }

    private companion object {
        const val TAG = "AccountHttp"
        const val CONNECT_TIMEOUT_MS = 7_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_BODY_BYTES = 512 * 1024
    }
}
