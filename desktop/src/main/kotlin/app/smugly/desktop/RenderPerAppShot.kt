package app.smugly.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.PerAppMode
import app.smugly.Strings
import app.smugly.ui.InstalledApp
import app.smugly.ui.screens.PerAppScreen
import app.smugly.ui.theme.SmuglyTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "." }).apply { mkdirs() }
    Strings.set(if (args.getOrNull(1) == "en") AppLanguage.EN else AppLanguage.RU)
    val apps = listOf(
        "9eSIM" to "ee.nekoko.nlpa.flavor1",
        "activ" to "com.kcell.myactiv",
        "AIDA64" to "com.finalwire.aida64",
        "Alfa Bank Patcher" to "ru.bluecat.alfabankpatcher",
        "Audio" to "com.razer.audiocompanion",
        "Authenticator" to "com.google.android.apps.authenticator2",
        "AyuGram" to "org.telegram.messenger",
        "BingX" to "pro.bingbon.app",
        "Brawl Stars" to "com.supercell.brawlstars",
        "Burger King" to "ru.burgerking",
        "Chrome" to "com.android.chrome",
        "Discord" to "com.discord",
        "YouTube" to "com.google.android.youtube"
    ).map { (label, pkg) -> InstalledApp(packageName = pkg, label = label, isSystem = false) }

    fun shot(
        name: String,
        mode: PerAppMode,
        selected: Set<String>,
        width: Int = 1080,
        density: Float = 2.75f,
        content: (@Composable () -> Unit)? = null
    ) {
        val scene = ImageComposeScene(width = width, height = 2100, density = Density(density)) {
            SmuglyTheme {
                content?.invoke() ?: PerAppScreen(
                    apps = apps,
                    loading = false,
                    mode = mode,
                    selected = selected,
                    onMenu = {},
                    onModeChange = {},
                    onSelectedChange = {},
                    loadIcon = { null }
                )
            }
        }
        try {
            scene.render(0)
            val image = scene.render(600_000_000L)
            File(outDir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }

    shot("perapp-off", PerAppMode.OFF, emptySet())
    shot("perapp-selected", PerAppMode.DISALLOWED, setOf("org.telegram.messenger", "com.discord", "com.google.android.youtube"))
    shot("perapp-np2", PerAppMode.ALLOWED, setOf("org.telegram.messenger"), density = 2.625f)
    shot("perapp-narrow", PerAppMode.ALLOWED, setOf("org.telegram.messenger"), width = 880)
    shot("perapp-loading", PerAppMode.OFF, emptySet()) {
        PerAppScreen(
            apps = emptyList(),
            loading = true,
            mode = PerAppMode.OFF,
            selected = emptySet(),
            onMenu = {},
            onModeChange = {},
            onSelectedChange = {},
            loadIcon = { null }
        )
    }

    fun frames(name: String, start: () -> Unit, change: () -> Unit, content: @Composable () -> Unit) {
        start()
        val scene = ImageComposeScene(width = 1080, height = 2100, density = Density(2.625f)) {
            SmuglyTheme { content() }
        }
        try {
            var t = 0L
            scene.render(t)
            t += 600_000_000L
            scene.render(t)
            change()
            t += 16_000_000L
            scene.render(t)
            File(outDir, "$name-tick.png").writeBytes(scene.render(t + 1).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            Thread.sleep(700)
            for (ms in listOf(0, 16, 48, 80, 120, 170, 240, 400)) {
                val image = scene.render(t + ms * 1_000_000L)
                File(outDir, "$name-${"%03d".format(ms)}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        } finally {
            scene.close()
        }
    }

    val picked = mutableStateOf(setOf("org.telegram.messenger"))
    frames("anim-tick", start = {}, change = { picked.value = picked.value + "com.finalwire.aida64" }) {
        PerAppScreen(
            apps = apps,
            loading = false,
            mode = PerAppMode.ALLOWED,
            selected = picked.value,
            onMenu = {},
            onModeChange = {},
            onSelectedChange = {},
            loadIcon = { null }
        )
    }
    val listed = mutableStateOf(emptyList<InstalledApp>())
    val busy = mutableStateOf(true)
    frames("anim-enter", start = {}, change = { listed.value = apps; busy.value = false }) {
        PerAppScreen(
            apps = listed.value,
            loading = busy.value,
            mode = PerAppMode.ALLOWED,
            selected = setOf("org.telegram.messenger", "com.discord"),
            onMenu = {},
            onModeChange = {},
            onSelectedChange = {},
            loadIcon = { null }
        )
    }
}
