package app.smugly

data class XrayForm(
    val protocol: XrayFormProtocol = XrayFormProtocol.VLESS,
    val address: String = "",
    val port: String = "443",
    val secret: String = "",
    val flow: String = "",
    val vlessEncryption: String = "none",
    val vmessSecurity: String = "auto",
    val ssMethod: String = "aes-256-gcm",
    val network: String = "tcp",
    val headerType: String = "none",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val authority: String = "",
    val grpcMulti: Boolean = false,
    val xhttpMode: String = "auto",
    val security: String = SECURITY_TLS,
    val sni: String = "",
    val fingerprint: String = "chrome",
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val xhttpExtra: String = "",
    val kcpSeed: String = "",
    val kcpHeaderType: String = "",
    val echConfigList: String = "",
    val verifyPeerCertByName: String = "",
    val pinnedPeerCertSha256: String = "",
    val mldsa65Verify: String = "",
    val allowInsecure: Boolean = false
) {
    val portNumber: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    fun missingField(): Field? = when {
        address.isBlank() -> Field.ADDRESS
        portNumber == null -> Field.PORT
        secret.isBlank() -> Field.SECRET
        security == SECURITY_REALITY && publicKey.isBlank() -> Field.PUBLIC_KEY
        else -> null
    }

    enum class Field { ADDRESS, PORT, SECRET, PUBLIC_KEY }

    companion object {
        const val SECURITY_NONE = "none"
        const val SECURITY_TLS = "tls"
        const val SECURITY_REALITY = "reality"

        val SECURITIES = listOf(SECURITY_NONE, SECURITY_TLS, SECURITY_REALITY)
        val FLOWS = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
        val NETWORKS = listOf("tcp", "ws", "grpc", "xhttp", "httpupgrade", "kcp")
        val TCP_HEADERS = listOf("none", "http")
        val XHTTP_MODES = listOf("auto", "packet-up", "stream-up", "stream-one")
        val FINGERPRINTS = listOf(
            "", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"
        )
        val ALPNS = listOf("", "h2", "http/1.1", "h2,http/1.1", "h3", "h3,h2,http/1.1")
        val VMESS_SECURITIES = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")
        val SS_METHODS = listOf(
            "aes-128-gcm",
            "aes-256-gcm",
            "chacha20-ietf-poly1305",
            "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305"
        )

        fun hasHostAndPath(network: String, headerType: String): Boolean = when (network) {
            "ws", "httpupgrade", "xhttp" -> true
            "tcp" -> headerType == "http"
            else -> false
        }
    }
}

enum class XrayFormProtocol(val id: String, val label: String) {
    VLESS("vless", "VLESS"),
    VMESS("vmess", "VMess"),
    TROJAN("trojan", "Trojan"),
    SHADOWSOCKS("shadowsocks", "Shadowsocks");

    companion object {
        fun of(id: String): XrayFormProtocol? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
