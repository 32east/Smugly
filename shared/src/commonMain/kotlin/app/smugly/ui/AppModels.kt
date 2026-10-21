package app.smugly.ui

import app.smugly.AppLanguage
import app.smugly.AppRelease
import app.smugly.AppVersion
import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.DnsResolverPool
import app.smugly.GlobalSettings
import app.smugly.S
import app.smugly.UpdateStatus
import app.smugly.defaultConfig
import app.smugly.t

/**
 * Main tabs reachable from the drawer. The profile editor is not a tab — it is a
 * layer drawn over the current tab (see `SmuglyApp`).
 */
enum class AppScreen {
    HOME,
    SETTINGS,
    DIAGNOSTICS,
    PER_APP
}

/**
 * One installed app in the split-tunnelling picker.
 *
 * The icon is deliberately not in here: labels for ~300 packages are one cheap PackageManager
 * sweep, whereas decoding that many icons up front is seconds of jank for rows nobody scrolls
 * to. [SmuglyPlatform.loadAppIcon] fetches them per row instead.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Preinstalled / platform-signed. Hidden behind a toggle — the list is mostly these. */
    val isSystem: Boolean
)

data class ConnectUiState(
    val statusText: String = "",
    val trafficText: String = "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)",
    val running: Boolean = false,
    val connecting: Boolean = false,
    val diagnosticsText: String = ""
) {
    companion object {
        fun idle() = ConnectUiState(statusText = t(S.STATUS_NOT_CONNECTED))
    }
}

/** What the row shows for a profile's measured latency. */
data class LatencyUi(
    val measuring: Boolean = false,
    val ms: Int? = null,
    val failed: Boolean = false
)

data class EditorDraft(
    val profileId: String?,
    val name: String,
    val config: Config,
    val xrayForm: app.smugly.XrayForm = app.smugly.XrayForm()
)

/**
 * Actions the host window can trigger from outside the Compose tree — desktop keyboard shortcuts,
 * a future menu bar or tray menu.
 *
 * The UI fills these in while it is composed. Going through a holder rather than a key modifier
 * inside `SmuglyApp` is deliberate: window-level `onKeyEvent` fires only after the focused
 * component declined the key, so Ctrl+V still pastes text normally while a text field has focus.
 */
class AppShortcuts {
    var importFromClipboard: (() -> Unit)? = null
}

/**
 * Platform hooks the shared Compose UI calls for tunnel control, import/export, clipboard.
 * Android wires VpnService; desktop/iOS can implement proxy-only / no-ops.
 */
interface SmuglyPlatform {
    fun loadProfiles(): List<ConfigProfile>
    fun loadActiveProfileId(): String?
    fun setActiveProfile(id: String)
    /**
     * User tapped a profile card. Sets it active and, if the tunnel is up, reconnects
     * (same as original Android [selectProfile]).
     */
    fun selectProfile(id: String) {
        setActiveProfile(id)
    }
    fun saveProfile(profile: ConfigProfile): ConfigProfile
    fun addProfile(name: String, config: Config): ConfigProfile
    /** Null once the last profile is gone — an empty list is allowed. */
    fun deleteProfile(id: String): ConfigProfile?
    fun reorderProfiles(orderedIds: List<String>)
    fun loadGlobalSettings(): GlobalSettings
    fun saveGlobalSettings(settings: GlobalSettings)
    fun toggleConnect()
    fun observeConnect(onChange: (ConnectUiState) -> Unit): () -> Unit
    fun readClipboard(): String
    fun writeClipboard(text: String)
    fun importFromText(text: String): List<ConfigProfile>
    fun exportProfileLink(profile: ConfigProfile): String
    fun shareLog()
    fun showCrashReport()
    /**
     * Raw crash-report body for an in-app dialog (empty string if nothing saved yet).
     * Prefer this + UI dialog over [showCrashReport] on multiplatform Compose.
     */
    fun readCrashReportText(): String
    /** Full text of the on-disk debug log (or empty / hint when logging is off). */
    fun readDebugLog(): String
    fun pickImportFile(onResult: (String?) -> Unit)
    fun supportsSystemVpn(): Boolean
    fun toast(message: String)
    /**
     * Subscribe to toast messages for in-app snackbars (desktop).
     * Android may leave the default no-op and keep system Toast in [toast].
     */
    fun observeToast(onToast: (String) -> Unit): () -> Unit = { }

    /**
     * Profiles / subscriptions were written outside the UI and it should reload.
     *
     * The `install-sub` deep link is the case that needs it: the host activity does the fetch on
     * its own thread, so nothing in the composition knows the folder it created has filled up.
     * Platforms that cannot write behind the UI's back leave the default, which never fires.
     */
    fun observeDataChanged(onChange: () -> Unit): () -> Unit = { }

    /**
     * A deep link arrived before (or outside) the Compose tree. Deliver the raw text so the UI
     * can run its own import path — including the "Importing…" overlay — instead of the host
     * fetching behind its back.
     */
    fun observePendingImport(onImport: (String) -> Unit): () -> Unit = { }
    fun validateXrayConfig(json: String): String?
    fun formatXrayJson(json: String): String?
    fun xrayFormToJson(form: app.smugly.XrayForm, socksPort: Int): String? = null
    fun xrayFormFromJson(json: String): app.smugly.XrayForm? = null
    fun localDnsResolver(): String?

    // ---- split tunnelling (per-app proxy) ----
    // Only a platform that owns the routing table can do this; on desktop the tunnel is a local
    // proxy the OS points every app at, so there is nothing per-app to decide.

    /** Whether the per-app screen is reachable at all (Android: yes; desktop / iOS: no). */
    fun supportsPerAppProxy(): Boolean = false

    /**
     * Every app that could use the network, own package excluded (it must never ride the
     * tunnel — see [app.smugly.PerAppFilter]). Sweeps PackageManager, so call it off the UI
     * thread.
     */
    fun loadInstalledApps(): List<InstalledApp> = emptyList()

    /** Launcher icon for one package, or null when it has none / cannot be decoded. */
    fun loadAppIcon(packageName: String): androidx.compose.ui.graphics.ImageBitmap? = null

    // ---- in-app updates ----
    // The app is distributed as an APK on GitHub, so it has to tell the user about a new
    // release itself. Platforms that install some other way leave these inert.

    fun supportsAppUpdates(): Boolean = false

    fun supportsTunTuning(): Boolean = false

    fun supportsAutoConnectOnBoot(): Boolean = false

    fun defaultConnectionMode(): Config.Mode = Config.Mode.PROXY

    /** Version actually installed — not the constant compiled into the UI. */
    fun installedVersionName(): String = AppVersion.name

    fun observeUpdate(onChange: (UpdateStatus) -> Unit): () -> Unit = { }

    /**
     * Silent start-up check, throttled by the platform. Reports nothing unless there really is
     * a newer release — a rate-limited API or a phone with no signal must not raise a dialog
     * every launch.
     */
    fun autoCheckForUpdate(enabled: Boolean) {}

    /** Explicit check: reports "up to date" and failures too, unlike the start-up one. */
    fun checkForUpdate() {}

    fun downloadUpdate(release: AppRelease) {}

    /** Re-open the system installer for an APK already downloaded this session. */
    fun installUpdate(release: AppRelease) {}

    /** Stop offering this particular version until the user checks again by hand. */
    fun skipUpdate(release: AppRelease) {}

    /** Close the update prompt without deciding anything. */
    fun dismissUpdate() {}

    // ---- subscriptions (folders) ----
    // Defaults keep platforms that have not wired storage yet (iOS) compiling and inert.

    fun loadSubscriptions(): List<app.smugly.subscription.Subscription> = emptyList()

    /**
     * Import a subscription from anything the user pasted or opened: a plain URL, an
     * `install-sub` deep link, or `sub://…`. Runs the network, so call it off the UI thread.
     * Returns an error message, or null on success.
     */
    fun addSubscription(rawUrl: String): String? = "subscriptions are not supported on this platform"

    /** Re-fetch one subscription. Returns an error message, or null on success. */
    fun refreshSubscription(id: String): String? = "subscriptions are not supported on this platform"

    /**
     * Refresh every subscription whose interval has elapsed. Networked — call off the UI thread.
     * Returns how many were actually refreshed.
     */
    fun refreshDueSubscriptions(): Int = 0

    fun deleteSubscription(id: String) {}
    fun renameSubscription(id: String, name: String) {}

    /**
     * Create ([id] null) or update a folder from the editor. Networked when the URL is new or
     * changed — call off the UI thread. Returns an error message, or null on success.
     */
    fun saveSubscription(
        id: String?,
        name: String,
        url: String,
        enabled: Boolean,
        updateIntervalMinutes: Long,
        allowReorder: Boolean,
        showInfo: Boolean
    ): String? = "subscriptions are not supported on this platform"

    /** Persist a new folder-tab order (subscription ids only; Home's slot is a global setting). */
    fun reorderSubscriptions(orderedIds: List<String>) {}

    /**
     * Time the first packet back from this profile's own server (see `LatencyProbe`).
     * Runs off the UI thread; the callback may arrive on any thread.
     */
    /**
     * Hand [content] to the user as a file (share sheet on Android, a file on disk on desktop).
     * Used for exporting a whole folder; the text is what the importer accepts back.
     */
    fun exportTextFile(fileName: String, content: String) {}

    fun measureLatency(profile: ConfigProfile, onResult: (Result<Int>) -> Unit) {
        onResult(Result.failure(UnsupportedOperationException("not supported on this platform")))
    }

    /** True when [text] should be treated as a subscription rather than a single config. */
    fun looksLikeSubscription(text: String): Boolean = false

    fun account(): app.smugly.account.AccountApi? = null

    fun hasEmbeddedWebCabinet(): Boolean = false

    fun openWebCabinet(url: String, externalLoginUrl: String? = null) = openExternalUrl(url)

    fun closeWebCabinet() {}

    fun isWebCabinetOpen(): Boolean = false

    fun forgetWebCabinetSession() {}

    fun takeOAuthCode(): String? = null

    fun openExternalUrl(url: String) {}

    fun observeAccountWake(onWake: () -> Unit): () -> Unit = { }
}

fun emptyDraft(base: Config = defaultConfig(mode = Config.Mode.PROXY)): EditorDraft =
    EditorDraft(
        profileId = null,
        name = t(S.CD_NEW_PROFILE),
        config = base
    )

/**
 * The grey line under a profile's name: which tunnel it runs on.
 *
 * Deliberately not the domain / login any more — those are per-profile trivia, while the thing
 * worth scanning a list of profiles for is how each one connects. Xray says "Xray-core" rather
 * than plain "Xray" because that is the component actually carrying the traffic.
 */
fun profileSubtitle(profile: ConfigProfile): String = when (profile.config.protocol) {
    Config.TunnelProtocol.S3FU -> t(S.PROTOCOL_S3FU)
    Config.TunnelProtocol.CDNFU -> t(S.PROTOCOL_CDNFU)
    Config.TunnelProtocol.XRAY -> t(S.PROTOCOL_XRAY_CORE)
    Config.TunnelProtocol.SLIPSTREAM -> t(S.PROTOCOL_SLIPSTREAM)
}

fun defaultGlobalSettings(mode: Config.Mode = Config.Mode.PROXY): GlobalSettings =
    GlobalSettings(
        listenPort = 1080,
        mode = mode,
        fileLogging = false,
        trafficNotification = false,
        localSocksAuthEnabled = true,
        localSocksUsername = GlobalSettings.DEFAULT_LOCAL_SOCKS_USERNAME,
        localSocksPassword = "changeme",
        language = AppLanguage.SYSTEM,
        dnsResolverPool = DnsResolverPool.DEFAULT_RAW
    )
