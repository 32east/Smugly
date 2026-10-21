package app.smugly.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.GlobalSettings
import app.smugly.Strings
import app.smugly.defaultConfig
import app.smugly.subscription.Subscription
import app.smugly.ui.ConnectUiState
import app.smugly.ui.SmuglyApp
import app.smugly.ui.SmuglyPlatform
import app.smugly.ui.defaultGlobalSettings
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private sealed interface Gesture {
    data class Down(val at: Offset, val mouse: Boolean = false) : Gesture
    data class Move(val to: Offset, val steps: Int = 12) : Gesture
    data object Up : Gesture
    data class Wait(val ms: Long) : Gesture
    data class Shot(val name: String) : Gesture
}

private class StubPlatform(private val subs: Int) : SmuglyPlatform {
    private val base = defaultConfig(mode = Config.Mode.VPN)
    private var settings = defaultGlobalSettings(Config.Mode.VPN)
    private val profiles = buildList {
        add(ConfigProfile(id = "local", name = "Локальный", config = base))
        repeat(subs) { add(ConfigProfile(id = "s$it-p", name = "Сервер $it", config = base, subscriptionId = "s$it")) }
    }
    override fun loadSubscriptions(): List<Subscription> =
        List(subs) { Subscription(id = "s$it", name = "Подписка номер $it", url = "https://example.invalid/$it") }
    override fun loadProfiles(): List<ConfigProfile> = profiles
    override fun loadActiveProfileId(): String? = "local"
    override fun setActiveProfile(id: String) {}
    override fun saveProfile(profile: ConfigProfile): ConfigProfile = profile
    override fun addProfile(name: String, config: Config): ConfigProfile = ConfigProfile(id = "new", name = name, config = config)
    override fun deleteProfile(id: String): ConfigProfile? = null
    override fun reorderProfiles(orderedIds: List<String>) {}
    override fun loadGlobalSettings(): GlobalSettings = settings
    override fun saveGlobalSettings(settings: GlobalSettings) { this.settings = settings }
    override fun toggleConnect() {}
    override fun observeConnect(onChange: (ConnectUiState) -> Unit): () -> Unit {
        onChange(ConnectUiState.idle())
        return {}
    }
    override fun readClipboard(): String = ""
    override fun writeClipboard(text: String) {}
    override fun importFromText(text: String): List<ConfigProfile> = emptyList()
    override fun exportProfileLink(profile: ConfigProfile): String = ""
    override fun shareLog() {}
    override fun showCrashReport() {}
    override fun readCrashReportText(): String = ""
    override fun readDebugLog(): String = ""
    override fun pickImportFile(onResult: (String?) -> Unit) = onResult(null)
    override fun supportsSystemVpn(): Boolean = true
    override fun toast(message: String) {}
    override fun validateXrayConfig(json: String): String? = null
    override fun formatXrayJson(json: String): String? = json
    override fun localDnsResolver(): String? = null
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "." }).apply { mkdirs() }
    Strings.set(AppLanguage.RU)

    fun run(name: String, subs: Int, steps: List<Gesture>) {
        val platform = StubPlatform(subs)
        val scene = ImageComposeScene(width = 1080, height = 2100, density = Density(2.75f)) {
            SmuglyApp(platform)
        }
        var t = 0L
        var finger = Offset.Zero
        var mouse = false
        fun frame() {
            t += 16
            scene.render(t * 1_000_000L)
        }
        fun send(type: PointerEventType, at: Offset) {
            if (mouse) {
                scene.sendPointerEvent(
                    type,
                    at,
                    timeMillis = t,
                    type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = type != PointerEventType.Release),
                    button = if (type == PointerEventType.Move) null else PointerButton.Primary
                )
            } else {
                scene.sendPointerEvent(type, at, timeMillis = t, type = PointerType.Touch)
            }
        }
        try {
            repeat(10) { frame() }
            for (step in steps) {
                when (step) {
                    is Gesture.Down -> {
                        finger = step.at
                        mouse = step.mouse
                        send(PointerEventType.Press, finger)
                        frame()
                    }
                    is Gesture.Move -> {
                        val from = finger
                        for (i in 1..step.steps) {
                            finger = from + (step.to - from) * (i / step.steps.toFloat())
                            send(PointerEventType.Move, finger)
                            frame()
                            frame()
                        }
                    }
                    Gesture.Up -> {
                        send(PointerEventType.Release, finger)
                        frame()
                    }
                    is Gesture.Wait -> repeat((step.ms / 16).toInt()) {
                        frame()
                        Thread.sleep(16)
                    }
                    is Gesture.Shot -> {
                        val image = scene.render(t * 1_000_000L)
                        File(outDir, "$name-${step.name}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    }
                }
            }
        } finally {
            scene.close()
        }
        println("done $name")
    }

    fun swipe(from: Offset, to: Offset, mouse: Boolean = false) =
        listOf(Gesture.Down(from, mouse), Gesture.Move(to), Gesture.Up, Gesture.Wait(500))

    val center = Offset(540f, 1300f)
    val right = Offset(980f, 1300f)
    val left = Offset(100f, 1300f)
    val only = args.drop(1).toSet()
    val scenarios = linkedMapOf<String, Pair<Int, List<Gesture>>>(
        "start" to (3 to listOf(Gesture.Shot("0"))),
        "home-center" to (0 to swipe(center, right) + Gesture.Shot("after")),
        "home-longpress" to (0 to listOf(Gesture.Down(center), Gesture.Wait(700)) + listOf(Gesture.Move(right), Gesture.Up, Gesture.Wait(500), Gesture.Shot("after"))),
        "folder1" to (3 to swipe(right, left) + Gesture.Shot("on1") + swipe(left, right) + Gesture.Shot("back0") + swipe(center, right) + Gesture.Shot("drawer")),
        "mouse-center" to (0 to swipe(center, right, mouse = true) + Gesture.Shot("after")),
        "mouse-edge" to (0 to swipe(Offset(20f, 1300f), right, mouse = true) + Gesture.Shot("after"))
    )
    for ((name, scenario) in scenarios) {
        if (only.isNotEmpty() && name !in only) continue
        run(name, scenario.first, scenario.second)
    }
    val extra = args.drop(1).filter { it.startsWith("tabs:") || it.startsWith("settings:") }
    for (spec in extra) {
        val parts = spec.split(":")
        val pts = parts.drop(1).map { p -> p.split(",").let { Offset(it[0].toFloat(), it[1].toFloat()) } }
        when (parts[0]) {
            "tabs" -> run("tabs", 8, swipe(pts[0], pts[1]) + Gesture.Shot("scrolled") + swipe(pts[1], pts[0]) + Gesture.Shot("back") + swipe(center, right) + Gesture.Shot("drawer"))
            "settings" -> run(
                "settings",
                0,
                listOf(Gesture.Down(pts[0]), Gesture.Up, Gesture.Wait(500), Gesture.Shot("menu"), Gesture.Down(pts[1]), Gesture.Up, Gesture.Wait(700), Gesture.Shot("screen")) +
                    swipe(center, right) + Gesture.Shot("after")
            )
        }
    }
}
