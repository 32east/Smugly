package app.smugly.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.RootMode
import app.smugly.RoutingSettings
import app.smugly.Strings
import app.smugly.TunSettings
import app.smugly.ui.defaultGlobalSettings
import app.smugly.ui.screens.SettingsScreen
import app.smugly.ui.theme.SmuglyTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "." }).apply { mkdirs() }
    val lang = if (args.getOrNull(1) == "en") AppLanguage.EN else AppLanguage.RU
    Strings.set(lang)
    val phone = defaultGlobalSettings(mode = Config.Mode.VPN)
    val tuned = phone.copy(
        tun = TunSettings(mtu = 1280, ipv6 = false, bypassLan = true, dnsServers = "94.140.14.14, dns.google"),
        routing = RoutingSettings(directRu = true, blockAds = true),
        autoConnectOnBoot = true,
        allowLan = true
    )
    val rooted = tuned.copy(rootMode = RootMode.TUN2SOCKS, rootHotspot = true)

    fun shot(name: String, height: Int, scrollSteps: List<Int> = listOf(0), width: Int = 1080, content: @Composable (ScrollState) -> Unit) {
        val scroll = ScrollState(0)
        val scene = ImageComposeScene(width = width, height = height, density = Density(2.75f)) {
            SmuglyTheme { content(scroll) }
        }
        try {
            var t = 0L
            scene.render(t)
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

    @Composable
    fun android(s: app.smugly.GlobalSettings, advanced: Boolean, scroll: ScrollState, signedIn: Boolean = true) {
        SettingsScreen(
            settings = s,
            supportsVpn = true,
            showTrafficNotification = true,
            showUpdates = true,
            installedVersion = "0.1.2",
            onMenu = {},
            onChange = {},
            accountName = if (signedIn) "user@mail.ru" else "",
            signedIn = signedIn,
            showTunSettings = true,
            showAutoConnect = true,
            showRootSettings = true,
            startWithAdvanced = advanced,
            scrollState = scroll
        )
    }

    shot("settings-basic", 2100) { android(phone, false, it) }
    shot("settings-full-default", 7000) { android(phone, true, it) }
    shot("settings-full-tuned", 7000) { android(tuned, true, it) }
    shot("settings-signed-out", 2100) { android(phone, false, it, signedIn = false) }
    shot("settings-root-off", 7000) { android(phone, true, it) }
    shot("settings-root-on", 7000) { android(rooted, true, it) }
    shot("settings-root-on-narrow", 7000, width = 880) { android(rooted, true, it) }
    shot("settings-narrow", 7000, width = 880) { android(tuned.copy(localSocksAuthEnabled = true), true, it) }
    shot("settings-phone", 2100, listOf(0, 1500, 3000, 4500, 99999)) { android(tuned, true, it) }
    shot("settings-desktop", 6000) {
        SettingsScreen(
            settings = defaultGlobalSettings().copy(routing = RoutingSettings(directRu = true)),
            supportsVpn = true,
            showLocalSocksAuth = false,
            onMenu = {},
            onChange = {},
            startWithAdvanced = true,
            scrollState = it
        )
    }
}
