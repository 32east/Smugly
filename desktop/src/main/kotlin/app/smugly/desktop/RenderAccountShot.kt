package app.smugly.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.Strings
import app.smugly.ui.ConnectUiState
import app.smugly.ui.components.AccountNoPlanPanel
import app.smugly.ui.components.LinkWaitingDialog
import app.smugly.ui.components.WelcomePanel
import app.smugly.ui.defaultGlobalSettings
import app.smugly.ui.screens.HomeScreen
import app.smugly.ui.screens.SettingsScreen
import app.smugly.ui.theme.SmuglyTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "." }).apply { mkdirs() }
    Strings.set(AppLanguage.RU)
    fun shot(name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 1080, height = 2100, density = Density(2.75f)) {
            SmuglyTheme { content() }
        }
        try {
            scene.render(0)
            val image = scene.render(600_000_000L)
            File(outDir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
    @Composable
    fun home(empty: @Composable (openAddMenu: () -> Unit) -> Unit) {
        HomeScreen(
            profiles = emptyList(),
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
            onToggle = {},
            emptyState = empty
        )
    }
    shot("welcome") { home { _ -> WelcomePanel(trialDays = 3, onBuy = {}, onSignIn = {}) } }
    shot("noplan") {
        home { _ ->
            AccountNoPlanPanel(
                accountName = "user@mail.ru",
                trialAvailable = true,
                trialDays = 3,
                trialNeedsTelegram = false,
                onOpenCabinet = {}
            )
        }
    }
    shot("waiting") {
        home { _ -> WelcomePanel(trialDays = 3, onBuy = {}, onSignIn = {}) }
        LinkWaitingDialog(visible = true, onReopen = {}, onCancel = {})
    }
    shot("settings") {
        SettingsScreen(
            settings = defaultGlobalSettings(),
            supportsVpn = true,
            showTrafficNotification = true,
            showUpdates = true,
            installedVersion = "0.1.2",
            onMenu = {},
            onChange = {},
            accountName = "user@mail.ru",
            signedIn = true
        )
    }
    println("wrote ${outDir.absolutePath}")
}
