package app.smugly

import org.json.JSONArray
import org.json.JSONObject

object XrayFormCodec {
    fun toJson(form: XrayForm, socksPort: Int): String =
        XrayConfigBuilder.buildWithProxy(proxyOutbound(form), socksPort)

    fun proxyOutbound(form: XrayForm): JSONObject {
        val link = toLink(form)
        if (form.protocol == XrayFormProtocol.VLESS) return XrayConfigBuilder.vlessOutbound(link)
        val settings = JSONObject()
            .put("address", link.server)
            .put("port", link.port)
            .put("level", XrayConfigBuilder.USER_LEVEL)
        when (form.protocol) {
            XrayFormProtocol.VMESS -> settings
                .put("id", form.secret.trim())
                .put("security", form.vmessSecurity.ifBlank { "auto" })
            XrayFormProtocol.TROJAN -> settings.put("password", form.secret)
            XrayFormProtocol.SHADOWSOCKS -> settings
                .put("method", form.ssMethod.ifBlank { XrayForm.SS_METHODS.first() })
                .put("password", form.secret)
            XrayFormProtocol.VLESS -> Unit
        }
        return JSONObject()
            .put("tag", XrayConfigBuilder.PROXY_TAG)
            .put("protocol", form.protocol.id)
            .put("settings", settings)
            .put("streamSettings", XrayConfigBuilder.streamSettings(link))
            .put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    fun fromJson(json: String): XrayForm? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val proxy = findProxy(root.optJSONArray("outbounds") ?: return null) ?: return null
        val protocol = XrayFormProtocol.of(proxy.optString("protocol")) ?: return null
        val server = serverOf(proxy.optJSONObject("settings") ?: JSONObject())
        val stream = proxy.optJSONObject("streamSettings") ?: JSONObject()

        var form = XrayForm(
            protocol = protocol,
            address = server.optString("address"),
            port = server.optInt("port").takeIf { it > 0 }?.toString().orEmpty(),
            secret = when (protocol) {
                XrayFormProtocol.VLESS, XrayFormProtocol.VMESS -> server.optString("id")
                XrayFormProtocol.TROJAN, XrayFormProtocol.SHADOWSOCKS -> server.optString("password")
            },
            flow = if (protocol == XrayFormProtocol.VLESS) server.optString("flow") else "",
            vlessEncryption = server.optString("encryption").ifBlank { "none" },
            vmessSecurity = if (protocol == XrayFormProtocol.VMESS) {
                server.optString("security").ifBlank { "auto" }
            } else {
                "auto"
            },
            ssMethod = if (protocol == XrayFormProtocol.SHADOWSOCKS) {
                server.optString("method").ifBlank { XrayForm.SS_METHODS.first() }
            } else {
                XrayForm().ssMethod
            }
        )
        form = readTransport(form, stream)
        return readSecurity(form, stream)
    }

    private fun findProxy(outbounds: JSONArray): JSONObject? {
        val all = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .filter { XrayFormProtocol.of(it.optString("protocol")) != null }
        return all.firstOrNull { it.optString("tag") == XrayConfigBuilder.PROXY_TAG } ?: all.firstOrNull()
    }

    private fun serverOf(settings: JSONObject): JSONObject {
        if (settings.has("address")) return settings
        settings.optJSONArray("vnext")?.optJSONObject(0)?.let { target ->
            val merged = JSONObject()
                .put("address", target.optString("address"))
                .put("port", target.optInt("port"))
            target.optJSONArray("users")?.optJSONObject(0)?.let { user ->
                user.keys().forEach { key -> merged.put(key, user.get(key)) }
            }
            return merged
        }
        return settings.optJSONArray("servers")?.optJSONObject(0) ?: settings
    }

    private fun readTransport(form: XrayForm, stream: JSONObject): XrayForm {
        val network = when (val raw = stream.optString("network").lowercase().ifBlank { "tcp" }) {
            "raw" -> "tcp"
            "websocket" -> "ws"
            "splithttp" -> "xhttp"
            "mkcp" -> "kcp"
            else -> raw
        }
        val base = form.copy(network = network)
        return when (network) {
            "tcp" -> {
                val header = (stream.optJSONObject("rawSettings") ?: stream.optJSONObject("tcpSettings"))
                    ?.optJSONObject("header")
                val type = header?.optString("type")?.ifBlank { null } ?: "none"
                if (type != "http") {
                    base.copy(headerType = type)
                } else {
                    val request = header?.optJSONObject("request")
                    base.copy(
                        headerType = type,
                        host = joined(request?.optJSONObject("headers")?.opt("Host")),
                        path = joined(request?.opt("path"))
                    )
                }
            }
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                base.copy(
                    host = ws.optString("host").ifBlank { joined(ws.optJSONObject("headers")?.opt("Host")) },
                    path = ws.optString("path")
                )
            }
            "httpupgrade" -> {
                val hu = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                base.copy(host = hu.optString("host"), path = hu.optString("path"))
            }
            "xhttp" -> {
                val xhttp = stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings") ?: JSONObject()
                base.copy(
                    host = xhttp.optString("host"),
                    path = xhttp.optString("path"),
                    xhttpMode = xhttp.optString("mode").ifBlank { "auto" },
                    xhttpExtra = xhttp.optJSONObject("extra")?.toString().orEmpty()
                )
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                base.copy(
                    serviceName = grpc.optString("serviceName"),
                    authority = grpc.optString("authority"),
                    grpcMulti = grpc.optBoolean("multiMode")
                )
            }
            "kcp" -> {
                val kcp = stream.optJSONObject("kcpSettings") ?: JSONObject()
                base.copy(
                    kcpHeaderType = kcp.optJSONObject("header")?.optString("type").orEmpty(),
                    kcpSeed = kcp.optString("seed")
                )
            }
            else -> base
        }
    }

    private fun readSecurity(form: XrayForm, stream: JSONObject): XrayForm {
        val security = stream.optString("security").lowercase()
        val tls = when (security) {
            XrayForm.SECURITY_TLS -> stream.optJSONObject("tlsSettings")
            XrayForm.SECURITY_REALITY -> stream.optJSONObject("realitySettings")
            else -> return form.copy(security = XrayForm.SECURITY_NONE)
        } ?: JSONObject()
        return form.copy(
            security = security,
            sni = tls.optString("serverName"),
            fingerprint = tls.optString("fingerprint"),
            alpn = joined(tls.opt("alpn")),
            allowInsecure = tls.optBoolean("allowInsecure"),
            echConfigList = tls.optString("echConfigList"),
            verifyPeerCertByName = tls.optString("verifyPeerCertByName"),
            pinnedPeerCertSha256 = tls.optString("pinnedPeerCertSha256"),
            publicKey = tls.optString("publicKey"),
            shortId = tls.optString("shortId"),
            spiderX = tls.optString("spiderX"),
            mldsa65Verify = tls.optString("mldsa65Verify")
        )
    }

    private fun joined(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }.joinToString(",")
        is String -> value
        else -> ""
    }

    private fun toLink(form: XrayForm): VlessLink {
        val tcpHttp = form.network == "tcp" && form.headerType == "http"
        val hostAndPath = XrayForm.hasHostAndPath(form.network, form.headerType)
        val security = form.security.takeIf { it == XrayForm.SECURITY_TLS || it == XrayForm.SECURITY_REALITY }
        val reality = security == XrayForm.SECURITY_REALITY
        return VlessLink(
            remarks = "",
            server = form.address.trim(),
            port = form.portNumber ?: 443,
            uuid = form.secret.trim(),
            encryption = form.vlessEncryption.trim().ifBlank { "none" },
            flow = form.flow.trim().takeIf { it.isNotEmpty() && form.protocol == XrayFormProtocol.VLESS },
            security = security,
            sni = form.sni.trim().takeIf { it.isNotEmpty() && security != null },
            fingerprint = form.fingerprint.trim().takeIf { it.isNotEmpty() && security != null },
            alpn = if (security == XrayForm.SECURITY_TLS) {
                form.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            },
            allowInsecure = form.allowInsecure && security == XrayForm.SECURITY_TLS,
            echConfigList = form.echConfigList.takeIf { it.isNotBlank() && security == XrayForm.SECURITY_TLS },
            verifyPeerCertByName = form.verifyPeerCertByName.takeIf { it.isNotBlank() && security == XrayForm.SECURITY_TLS },
            pinnedPeerCertSha256 = form.pinnedPeerCertSha256.takeIf { it.isNotBlank() && security == XrayForm.SECURITY_TLS },
            publicKey = form.publicKey.trim().takeIf { it.isNotEmpty() && reality },
            shortId = form.shortId.trim().takeIf { it.isNotEmpty() && reality },
            spiderX = form.spiderX.trim().takeIf { it.isNotEmpty() && reality },
            mldsa65Verify = form.mldsa65Verify.takeIf { it.isNotBlank() && reality },
            network = form.network,
            headerType = when (form.network) {
                "tcp" -> if (tcpHttp) "http" else null
                "kcp" -> form.kcpHeaderType.takeIf { it.isNotBlank() }
                else -> null
            },
            host = form.host.trim().takeIf { it.isNotEmpty() && hostAndPath },
            path = form.path.trim().takeIf { it.isNotEmpty() && hostAndPath },
            serviceName = form.serviceName.trim().takeIf { form.network == "grpc" },
            mode = when (form.network) {
                "xhttp" -> form.xhttpMode.ifBlank { "auto" }
                "grpc" -> if (form.grpcMulti) "multi" else "gun"
                else -> null
            },
            authority = form.authority.trim().takeIf { it.isNotEmpty() && form.network == "grpc" },
            xhttpExtra = form.xhttpExtra.takeIf { it.isNotBlank() && form.network == "xhttp" },
            seed = form.kcpSeed.takeIf { it.isNotBlank() && form.network == "kcp" }
        )
    }
}
