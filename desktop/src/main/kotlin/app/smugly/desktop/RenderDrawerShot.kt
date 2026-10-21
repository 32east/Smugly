package app.smugly.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.smugly.AppLanguage
import app.smugly.Strings
import app.smugly.ui.components.AppDrawerItem
import app.smugly.ui.components.DrawerPanel
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "." }).apply { mkdirs() }
    Strings.set(AppLanguage.RU)
    val selected = mutableStateOf(AppDrawerItem.DIAGNOSTICS)
    val scene = ImageComposeScene(width = 1080, height = 2100, density = Density(2.75f)) {
        SmuglyTheme {
            Box(Modifier.fillMaxSize().background(SmuglyBg)) {
                DrawerPanel(
                    selected = selected.value,
                    showDiagnostics = true,
                    showPerApp = true,
                    showWebCabinet = true,
                    onSelect = {},
                    modifier = Modifier.fillMaxHeight().width(280.dp)
                )
            }
        }
    }
    fun save(name: String, nanos: Long) {
        val image = scene.render(nanos)
        File(outDir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    try {
        scene.render(0)
        save("drawer-diag", 600_000_000L)
        selected.value = AppDrawerItem.HOME
        var t = 600_000_000L
        for (frame in 1..40) {
            t += 16_000_000L
            scene.render(t)
            if (frame in listOf(2, 4, 6, 8)) save("drawer-f$frame", t + 1)
        }
        save("drawer-home", t + 1_000_000_000L)
        Strings.set(AppLanguage.EN)
        save("drawer-home-en", t + 2_000_000_000L)
    } finally {
        scene.close()
    }
}
