package app.smugly.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.Strings
import app.smugly.XrayForm
import app.smugly.XrayFormCodec
import app.smugly.XrayFormProtocol
import app.smugly.defaultConfig
import app.smugly.ui.EditorDraft
import app.smugly.ui.screens.ProfileEditorScreen
import app.smugly.ui.theme.SmuglyTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "." }).apply { mkdirs() }
    Strings.set(if (args.getOrNull(1) == "en") AppLanguage.EN else AppLanguage.RU)
    val base = defaultConfig(mode = Config.Mode.VPN)

    fun shot(
        name: String,
        start: EditorDraft,
        height: Int = 2100,
        width: Int = 1080,
        scrollSteps: List<Int> = listOf(0),
        tap: Offset? = null
    ) {
        val scroll = ScrollState(0)
        val scene = ImageComposeScene(width = width, height = height, density = Density(2.75f)) {
            SmuglyTheme {
                var draft by remember { mutableStateOf(start) }
                ProfileEditorScreen(
                    draft = draft,
                    onBack = {},
                    onChange = { draft = it },
                    onSave = {},
                    onDelete = null,
                    onLocalDns = {},
                    xrayFormToJson = { XrayFormCodec.toJson(it, 1080) },
                    xrayFormFromJson = { XrayFormCodec.fromJson(it) },
                    scrollState = scroll
                )
            }
        }
        try {
            var t = 0L
            scene.render(t)
            if (tap != null) {
                scene.sendPointerEvent(PointerEventType.Move, tap)
                scene.sendPointerEvent(PointerEventType.Press, tap)
                t += 50_000_000L
                scene.render(t)
                scene.sendPointerEvent(PointerEventType.Release, tap)
                repeat(40) {
                    t += 16_000_000L
                    scene.render(t)
                }
            }
            for ((i, y) in scrollSteps.withIndex()) {
                runBlocking { scroll.scrollTo(y) }
                t += 600_000_000L
                val image = scene.render(t)
                val suffix = if (scrollSteps.size == 1) "" else "-${i + 1}"
                File(outDir, "$name$suffix.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            println("$name maxScroll=${scroll.maxValue}")
        } finally {
            scene.close()
        }
    }

    fun sequence(name: String, start: EditorDraft, taps: List<Offset>, captureMs: List<Int>) {
        val scene = ImageComposeScene(width = 1080, height = 2100, density = Density(2.75f)) {
            SmuglyTheme {
                var draft by remember { mutableStateOf(start) }
                ProfileEditorScreen(
                    draft = draft,
                    onBack = {},
                    onChange = { draft = it },
                    onSave = {},
                    onDelete = null,
                    onLocalDns = {},
                    xrayFormToJson = { XrayFormCodec.toJson(it, 1080) },
                    xrayFormFromJson = { XrayFormCodec.fromJson(it) }
                )
            }
        }
        try {
            var t = 0L
            scene.render(t)
            for ((i, tap) in taps.withIndex()) {
                scene.sendPointerEvent(PointerEventType.Move, tap)
                scene.sendPointerEvent(PointerEventType.Press, tap)
                t += 50_000_000L
                scene.render(t)
                scene.sendPointerEvent(PointerEventType.Release, tap)
                val last = i == taps.lastIndex
                var elapsed = 0
                val until = if (last) captureMs.max() else 640
                while (elapsed < until) {
                    t += 16_000_000L
                    elapsed += 16
                    val image = scene.render(t)
                    if (last) {
                        captureMs.filter { it in (elapsed - 15)..elapsed }.forEach { ms ->
                            File(outDir, "$name-${ms}ms.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        }
                    }
                }
            }
            println("$name done")
        } finally {
            scene.close()
        }
    }

    val slip = base.copy(
        domain = "ee.smugvpn.com",
        resolverHost = "77.88.8.8",
        authMode = Config.AuthMode.LOGIN_PASSWORD,
        username = "user",
        password = "secret"
    )
    shot("editor-slipstream-full", EditorDraft(null, "Новый профиль", slip), height = 6000)
    shot("editor-slipstream-phone", EditorDraft(null, "Новый профиль", slip), scrollSteps = listOf(0, 1400, 99999))
    shot(
        "editor-slipstream-auto",
        EditorDraft("p1", "Эстония", slip.copy(resolverMode = Config.ResolverMode.AUTO, authMode = Config.AuthMode.NO_AUTH)),
        height = 5000
    )

    val xrayJson = base.copy(protocol = Config.TunnelProtocol.XRAY)
    shot("editor-xray-json-new", EditorDraft(null, "Новый профиль", xrayJson))

    val reality = XrayForm(
        address = "nl.smugvpn.com",
        secret = "4f0c2d0e-6b1a-4a43-9a51-1d2b1b0c7e11",
        flow = "xtls-rprx-vision",
        security = XrayForm.SECURITY_REALITY,
        sni = "ya.ru",
        publicKey = "hNz2bKZcF3m8jQxVbE7wY1rL0pTq5sUdGfA9cH6iJk4",
        shortId = "a1b2c3"
    )
    shot("editor-xray-manual-empty", EditorDraft(null, "Новый профиль", xrayJson.copy(xrayManual = true)), height = 5000)
    shot("editor-xray-manual-reality", EditorDraft(null, "Нидерланды", xrayJson.copy(xrayManual = true), reality), height = 5000)
    shot(
        "editor-xray-manual-phone",
        EditorDraft(null, "Нидерланды", xrayJson.copy(xrayManual = true), reality),
        scrollSteps = listOf(0, 1400, 99999)
    )
    shot(
        "editor-xray-vmess-ws",
        EditorDraft(
            "p2",
            "VMess",
            xrayJson.copy(xrayManual = true),
            XrayForm(
                protocol = XrayFormProtocol.VMESS,
                address = "1.2.3.4",
                secret = "4f0c2d0e-6b1a-4a43-9a51-1d2b1b0c7e11",
                network = "ws",
                host = "cdn.example.com",
                path = "/ws",
                alpn = "h2,http/1.1"
            )
        ),
        height = 5000
    )
    shot(
        "editor-xray-trojan-grpc",
        EditorDraft(
            "p3",
            "Trojan",
            xrayJson.copy(xrayManual = true),
            XrayForm(protocol = XrayFormProtocol.TROJAN, address = "t.example.com", secret = "pass", network = "grpc", serviceName = "svc")
        ),
        height = 5000
    )
    shot(
        "editor-xray-ss",
        EditorDraft(
            "p4",
            "SS",
            xrayJson.copy(xrayManual = true),
            XrayForm(protocol = XrayFormProtocol.SHADOWSOCKS, address = "ss.example.com", port = "8388", secret = "pass", security = XrayForm.SECURITY_NONE)
        ),
        height = 3600
    )
    shot(
        "editor-xray-menu",
        EditorDraft(null, "Нидерланды", xrayJson.copy(xrayManual = true), reality),
        tap = Offset(900f, 1108f)
    )
    val samples = mapOf(
        "vless-reality" to reality,
        "vless-ws-tls" to reality.copy(security = XrayForm.SECURITY_TLS, network = "ws", host = "cdn.example.com", path = "/ws", alpn = "h2,http/1.1", flow = ""),
        "vless-xhttp" to reality.copy(network = "xhttp", path = "/x", xhttpMode = "packet-up", flow = ""),
        "vless-grpc" to reality.copy(security = XrayForm.SECURITY_TLS, network = "grpc", serviceName = "svc", grpcMulti = true, flow = ""),
        "vless-httpupgrade" to reality.copy(security = XrayForm.SECURITY_TLS, network = "httpupgrade", path = "/hu", flow = ""),
        "vless-kcp" to reality.copy(security = XrayForm.SECURITY_NONE, network = "kcp", flow = ""),
        "vless-tcp-http" to reality.copy(security = XrayForm.SECURITY_NONE, headerType = "http", host = "a.com,b.com", path = "/p", flow = ""),
        "vmess-ws" to XrayForm(protocol = XrayFormProtocol.VMESS, address = "1.2.3.4", secret = reality.secret, network = "ws", path = "/ws", sni = "cdn.example.com"),
        "trojan-tls" to XrayForm(protocol = XrayFormProtocol.TROJAN, address = "t.example.com", secret = "pass"),
        "ss-aead" to XrayForm(protocol = XrayFormProtocol.SHADOWSOCKS, address = "ss.example.com", port = "8388", secret = "pass", security = XrayForm.SECURITY_NONE),
        "ss-2022" to XrayForm(protocol = XrayFormProtocol.SHADOWSOCKS, address = "ss.example.com", port = "8388", secret = "AAAAAAAAAAAAAAAAAAAAAA==", ssMethod = "2022-blake3-aes-128-gcm", security = XrayForm.SECURITY_NONE)
    )
    val jsonDir = File(outDir, "json").apply { mkdirs() }
    samples.forEach { (name, form) -> File(jsonDir, "$name.json").writeText(XrayFormCodec.toJson(form, 10808)) }
    sequence(
        "anim-json",
        EditorDraft(null, "Новый профиль", base.copy(protocol = Config.TunnelProtocol.XRAY, xrayManual = true)),
        taps = listOf(Offset(312f, 785f)),
        captureMs = listOf(96, 400)
    )
    sequence(
        "pick-xray",
        EditorDraft(null, "Новый профиль", base),
        taps = listOf(Offset(900f, 532f), Offset(600f, 1132f)),
        captureMs = listOf(400)
    )
    shot("editor-desktop", EditorDraft(null, "Нидерланды", xrayJson.copy(xrayManual = true), reality), width = 2400, height = 2400)
}
