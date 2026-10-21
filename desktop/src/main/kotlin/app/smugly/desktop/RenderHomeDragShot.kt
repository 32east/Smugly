package app.smugly.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.Strings
import app.smugly.defaultConfig
import app.smugly.ui.ConnectUiState
import app.smugly.ui.screens.HomeScreen
import app.smugly.ui.theme.SmuglyTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private sealed interface Step {
    data class Down(val at: Offset) : Step
    data class Move(val to: Offset, val steps: Int = 12) : Step
    data object Up : Step
    data class Wait(val ms: Long) : Step
    data class Shot(val name: String) : Step
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "." }).apply { mkdirs() }
    Strings.set(AppLanguage.RU)
    val base = defaultConfig(mode = Config.Mode.VPN)
    val start = listOf(
        ConfigProfile(id = "p3", name = "Новый профиль 3", config = base),
        ConfigProfile(id = "p2", name = "Новый профиль 2", config = base),
        ConfigProfile(id = "p1", name = "Новый профиль", config = base)
    )

    fun run(name: String, steps: List<Step>) {
        val reorders = mutableListOf<List<String>>()
        val scene = ImageComposeScene(width = 1080, height = 2100, density = Density(2.75f)) {
            SmuglyTheme {
                var profiles by remember { mutableStateOf(start) }
                HomeScreen(
                    profiles = profiles,
                    activeId = null,
                    connect = ConnectUiState.idle(),
                    onMenu = {},
                    onAddNew = {},
                    onImportClipboard = {},
                    onImportFile = {},
                    onSelect = {},
                    onEdit = {},
                    onDelete = {},
                    onExport = {},
                    onReorder = { ids ->
                        reorders += ids
                        profiles = ids.mapNotNull { id -> profiles.firstOrNull { it.id == id } }
                    },
                    onToggle = {}
                )
            }
        }
        var t = 0L
        var finger = Offset.Zero
        fun frame() {
            t += 16
            scene.render(t * 1_000_000L)
        }
        fun send(type: PointerEventType, at: Offset) {
            scene.sendPointerEvent(type, at, timeMillis = t, type = PointerType.Touch)
        }
        try {
            frame()
            for (step in steps) {
                when (step) {
                    is Step.Down -> {
                        finger = step.at
                        send(PointerEventType.Press, finger)
                        frame()
                    }
                    is Step.Move -> {
                        val from = finger
                        for (i in 1..step.steps) {
                            finger = from + (step.to - from) * (i / step.steps.toFloat())
                            send(PointerEventType.Move, finger)
                            frame()
                            frame()
                        }
                    }
                    Step.Up -> {
                        send(PointerEventType.Release, finger)
                        frame()
                    }
                    is Step.Wait -> {
                        repeat((step.ms / 16).toInt()) {
                            frame()
                            Thread.sleep(16)
                        }
                    }
                    is Step.Shot -> {
                        val image = scene.render(t * 1_000_000L)
                        File(outDir, "$name-${step.name}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    }
                }
            }
        } finally {
            scene.close()
        }
        println("$name reorders=$reorders")
    }

    val x = 540f
    val y = { slot: Int -> 365f + slot * 176f }
    run("static", listOf(Step.Shot("0")))
    run("press-release", listOf(Step.Down(Offset(x, y(1))), Step.Wait(900), Step.Shot("held"), Step.Up, Step.Wait(600), Step.Shot("after")))
    run("slot2-to-1", listOf(Step.Down(Offset(x, y(1))), Step.Wait(900), Step.Move(Offset(x, y(0) - 60f)), Step.Wait(300), Step.Shot("top"), Step.Up, Step.Wait(600), Step.Shot("after")))
    run("slot3-to-1", listOf(Step.Down(Offset(x, y(2))), Step.Wait(900), Step.Move(Offset(x, y(0) - 60f), steps = 20), Step.Wait(300), Step.Shot("top"), Step.Up, Step.Wait(600), Step.Shot("after"), Step.Wait(900), Step.Shot("after1500"), Step.Wait(1500), Step.Shot("after3000")))
}
