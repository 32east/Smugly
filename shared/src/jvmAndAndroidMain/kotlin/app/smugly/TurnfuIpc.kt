package app.smugly

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class TurnfuTunnelConfig(val ip: String, val dns: List<String>, val ip6: String?)

object TurnfuIpc {
    fun configuration(
        host: String,
        port: Int,
        password: String,
        calls: List<String>,
        workers: Int,
        tunSocket: String = "",
        probe: Boolean = false,
        network: Long = 0,
        captcha: String = "auto",
        token: String = ""
    ): JSONObject {
        val server = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
        val session = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        return JSONObject()
            .put("mode", if (probe) "probe" else "tunnel")
            .put("server", server)
            .put("password", password)
            .put("calls", JSONArray(calls))
            .put("workers", workers)
            .put("exact_workers", probe)
            .put("tun_socket", tunSocket)
            .put("epoch", System.currentTimeMillis())
            .put("session", session)
            .put("captcha", captcha)
            .put("network", network)
            .put("embedded", true)
            .put("auth", if (token.isNotEmpty() && calls.isEmpty()) "account" else "links")
            .put("account_token", if (calls.isEmpty()) token else "")
    }

    fun command(name: String, key: String? = null, value: Any? = null): JSONObject =
        JSONObject().put("cmd", name).apply { if (key != null) put(key, value) }

    fun event(line: String): JSONObject? = runCatching {
        JSONObject(line).takeIf { it.optString("t").isNotEmpty() }
    }.getOrNull()

    fun tunnelConfig(event: JSONObject): TurnfuTunnelConfig? {
        if (event.optString("t") != "config") return null
        val ip = event.optString("ip")
        val octets = ip.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.any { c -> !c.isDigit() } || it.toIntOrNull() !in 0..255 }) return null
        val rawDns = event.optJSONArray("dns") ?: return null
        val dns = List(rawDns.length()) { rawDns.optString(it) }.filter(String::isNotBlank)
        if (dns.isEmpty() || dns.size > 4) return null
        val rawIp6 = event.optString("ip6").trim().lowercase()
        val ip6 = rawIp6.takeIf { it.isNotEmpty() }
        if (ip6 != null && (ip6.length !in 3..39 || !ip6.contains(':') || ip6.any { it != ':' && it !in '0'..'9' && it !in 'a'..'f' })) return null
        return TurnfuTunnelConfig(ip, dns, ip6)
    }
}
