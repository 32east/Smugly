package app.smugly.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.smugly.AppRelease
import app.smugly.UpdateStatus
import app.smugly.S
import app.smugly.VersionCompare
import app.smugly.subscription.SubscriptionFetcher.ProxySpec
import app.smugly.t
import app.smugly.util.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks GitHub for a newer build, downloads its APK and hands it to the system installer.
 *
 * Android has no way for an ordinary app to install silently — that needs to be the device
 * owner or a platform-signed installer. So "automatic" means everything up to the last step:
 * the check, the download and opening the installer happen on their own; the final Install tap
 * belongs to the OS and cannot be skipped from here.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "lastCheckMs"
    private const val KEY_SKIPPED_VERSION = "skippedVersion"

    /** How often the silent start-up check may actually hit the network. */
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    /** Downloads bigger than this are refused rather than filling the user's storage. */
    private const val MAX_APK_BYTES = 300L * 1024 * 1024
    /** Resume attempts per route before giving up and trying the next one. */
    private const val DOWNLOAD_ATTEMPTS = 4

    private val listeners = CopyOnWriteArrayList<(UpdateStatus) -> Unit>()
    private val busy = AtomicBoolean(false)

    @Volatile
    var status: UpdateStatus = UpdateStatus.Idle
        private set

    fun observe(onChange: (UpdateStatus) -> Unit): () -> Unit {
        listeners.add(onChange)
        onChange(status)
        return { listeners.remove(onChange) }
    }

    private fun publish(next: UpdateStatus) {
        status = next
        listeners.forEach { it(next) }
    }

    /**
     * The version actually installed, not the constant compiled into the UI.
     *
     * `AppVersion.name` and the Gradle `versionName` are two hand-maintained copies of the same
     * number; comparing against the package manager's value means a release can never be
     * offered (or hidden) because those two drifted apart.
     */
    fun installedVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("").ifBlank { app.smugly.AppVersion.name }

    /**
     * Start-up check. Silent by design: it only ever moves the status to [UpdateStatus.Available],
     * never to UpToDate or Failed, because nobody asked — a rate-limited GitHub or a plane-mode
     * phone must not produce a dialog on every launch. [checkNow] is the one that reports back.
     */
    fun autoCheck(context: Context, enabled: Boolean, routes: List<ProxySpec?> = listOf(null)) {
        if (!enabled) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        // `now < last` catches a clock that moved backwards — otherwise a wrong date could
        // park the next check months away.
        if (last != 0L && now - last < AUTO_CHECK_INTERVAL_MS && now >= last) return
        if (!busy.compareAndSet(false, true)) return
        Thread({
            try {
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                val release = findNewer(context, routes) ?: return@Thread
                if (release.versionName == prefs.getString(KEY_SKIPPED_VERSION, null)) {
                    AppLog.i(TAG, "update ${release.versionName} skipped by user")
                    return@Thread
                }
                publish(UpdateStatus.Available(release))
            } catch (e: Throwable) {
                AppLog.w(TAG, "auto update check failed: ${e.message}")
            } finally {
                busy.set(false)
            }
        }, "update-autocheck").start()
    }

    /** Explicit "check for updates". Reports every outcome, including "you are up to date". */
    fun checkNow(context: Context, routes: List<ProxySpec?> = listOf(null)) {
        if (!busy.compareAndSet(false, true)) return
        publish(UpdateStatus.Checking)
        Thread({
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    // An explicit check means the user wants to see it again.
                    .remove(KEY_SKIPPED_VERSION)
                    .apply()
                val release = findNewer(context, routes)
                publish(if (release == null) UpdateStatus.UpToDate else UpdateStatus.Available(release))
            } catch (e: Throwable) {
                publish(UpdateStatus.Failed(e.message ?: "update check failed"))
            } finally {
                busy.set(false)
            }
        }, "update-check").start()
    }

    private fun findNewer(context: Context, routes: List<ProxySpec?>): AppRelease? {
        val current = installedVersion(context)
        val release = GithubReleases.latest(routes = routes).getOrThrow() ?: run {
            AppLog.i(TAG, "no published release with an APK")
            return null
        }
        val newer = VersionCompare.isNewer(release.versionName, current)
        AppLog.i(
            TAG,
            "update check: installed=$current latest=${release.versionName} newer=$newer"
        )
        return if (newer) release else null
    }

    /** Download the APK, then open the installer. Progress lands on [observe]. */
    fun downloadAndInstall(context: Context, release: AppRelease, routes: List<ProxySpec?> = listOf(null)) {
        if (!busy.compareAndSet(false, true)) return
        publish(UpdateStatus.Downloading(release, 0, release.apkSizeBytes))
        Thread({
            try {
                val apk = download(context, release, routes)
                publish(UpdateStatus.ReadyToInstall(release))
                install(context, apk)
            } catch (e: Throwable) {
                // The raw message is a stack-shaped socket error; the toast gets the one thing
                // the user can act on, and the log keeps the detail.
                AppLog.e(TAG, "update download failed", e)
                publish(UpdateStatus.Failed(t(S.TOAST_UPDATE_DOWNLOAD_FAILED)))
            } finally {
                busy.set(false)
            }
        }, "update-download").start()
    }

    /** Re-open the installer for an APK that was already downloaded this session. */
    fun install(context: Context, release: AppRelease) {
        val apk = apkFile(context, release)
        if (!apk.isFile) {
            publish(UpdateStatus.Available(release))
            return
        }
        install(context, apk)
    }

    /** "Not now" for this particular version — the start-up check stops raising it. */
    fun skip(context: Context, release: AppRelease) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SKIPPED_VERSION, release.versionName)
            .apply()
        publish(UpdateStatus.Idle)
    }

    fun dismiss() {
        publish(UpdateStatus.Idle)
    }

    private fun updateDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    private fun apkFile(context: Context, release: AppRelease): File =
        File(updateDir(context), "smugly-${release.versionName}.apk")

    /**
     * Download the APK, trying each route until one answers; a null entry means "direct".
     *
     * This is not belt-and-braces. GitHub hands the API out over `api.github.com` but serves
     * the asset itself from `release-assets.githubusercontent.com`, and on the networks this
     * app is built for the second host is blocked while the first is not — measured: the API
     * answered 200 and the asset host timed out. So an updater that only ever dialled direct
     * could see the release and never be able to fetch it. Routing through our own local proxy
     * is the fix, and it is why the app's own package is excluded from the TUN: our traffic
     * does not ride the tunnel by accident, it has to ask for it.
     */
    private fun download(context: Context, release: AppRelease, routes: List<ProxySpec?>): File {
        var last: Throwable? = null
        for (route in routes.ifEmpty { listOf(null) }) {
            val result = runCatching { downloadVia(context, release, route) }
            result.getOrNull()?.let { return it }
            last = result.exceptionOrNull()
            AppLog.w(
                TAG,
                "update download via ${route?.let { "${it.host}:${it.port}" } ?: "direct"} " +
                    "failed: ${last?.message}"
            )
        }
        throw last ?: IllegalStateException("no route to the release asset")
    }

    private fun downloadVia(context: Context, release: AppRelease, route: ProxySpec?): File {
        val target = apkFile(context, release)
        // Download to a sibling temp and rename on success, so a connection dropped halfway can
        // never leave a truncated file that the next run happily tries to install.
        val partial = File(target.parentFile, target.name + ".part")
        // Older versions' downloads are dead weight once this one starts.
        updateDir(context).listFiles()?.forEach {
            if (it != target && it != partial) it.delete()
        }
        if (target.isFile && target.length() > 0) {
            AppLog.i(TAG, "update ${release.versionName} already downloaded")
            return target
        }

        // Whatever this route inherited from the last one is where it starts, and the bar has to
        // say so straight away: a new route that begins by connecting publishes nothing for
        // fifteen seconds, and the bar would otherwise sit at the *previous* route's percentage
        // looking frozen.
        publish(UpdateStatus.Downloading(release, partial.length(), release.apkSizeBytes))

        var attempt = 0
        while (true) {
            attempt++
            val before = partial.length()
            try {
                fetchInto(partial, release, route)
                break
            } catch (e: Throwable) {
                // Resuming is only worth it while the connection is still making headway. A
                // tunnelled 40 MB download over a lossy transport does drop — measured: an S3
                // profile died at 62% with an SSL record error — and without this the whole
                // thing restarts from zero every time and may never finish.
                val progressed = partial.length() > before
                if (attempt >= DOWNLOAD_ATTEMPTS || !progressed) throw e
                AppLog.w(
                    TAG,
                    "update download interrupted at ${partial.length()} bytes " +
                        "(attempt $attempt): ${e.message}"
                )
            }
        }

        val expected = release.apkSizeBytes
        if (expected > 0 && partial.length() != expected) {
            error("download truncated at ${partial.length()} of $expected bytes")
        }
        if (partial.length() <= 0) error("downloaded nothing")
        target.delete()
        if (!partial.renameTo(target)) error("could not store the downloaded APK")
        AppLog.i(TAG, "update ${release.versionName} downloaded: ${target.length()} bytes")
        return target
    }

    /**
     * One GET into [partial], resuming from whatever is already in it.
     *
     * A server that honours the range answers 206 and we append; one that ignores it answers 200
     * with the whole file, and the only safe reading of that is "start over" — appending a full
     * body onto a partial one produces a corrupt APK that still installs far enough to fail.
     */
    private fun fetchInto(partial: File, release: AppRelease, route: ProxySpec?) {
        var url = URL(release.apkUrl)
        var redirects = 0
        while (true) {
            val have = partial.length()
            val opened = if (route == null) url.openConnection() else url.openConnection(route.toJavaProxy())
            val connection = (opened as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Followed by hand: GitHub redirects release assets to another host, and
                // HttpURLConnection refuses to follow a redirect that changes host — which
                // would otherwise arrive here as an empty body.
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Smugly-Updater")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("redirect without a location")
                    if (++redirects > 5) error("too many redirects")
                    url = URL(url, location)
                    continue
                }
                if (code !in 200..299) error("HTTP $code")
                val resuming = code == 206 && have > 0
                if (!resuming && have > 0) {
                    AppLog.i(TAG, "server ignored the range request; restarting the download")
                }
                // The API already told us the asset's size, and it is the one number that stays
                // true across routes, retries and a 206 whose Content-Length is only the tail.
                val total = release.apkSizeBytes.takeIf { it > 0 }
                    ?: connection.contentLengthLong.takeIf { it > 0 }?.plus(if (resuming) have else 0L)
                    ?: 0L
                if (total > MAX_APK_BYTES) error("APK is unexpectedly large ($total bytes)")
                var written = if (resuming) have else 0L
                var lastPublished = written
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(partial, resuming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (written > MAX_APK_BYTES) error("APK is unexpectedly large")
                            // One update per 64 KB would be ~700 recompositions for a 43 MB
                            // file; a percent of progress is as fine-grained as a bar can show.
                            if (total <= 0 || written - lastPublished >= total / 100 + 1) {
                                lastPublished = written
                                publish(UpdateStatus.Downloading(release, written, total))
                            }
                        }
                        output.flush()
                    }
                }
                if (total > 0 && written < total) error("stream ended at $written of $total bytes")
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // Android 8+ asks per app, once. Send the user straight to that toggle instead of
            // firing an install intent the system will silently refuse.
            AppLog.i(TAG, "install permission missing; opening the settings page")
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { AppLog.w(TAG, "could not open install-sources settings: ${it.message}") }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                AppLog.e(TAG, "could not open the installer", it)
                publish(UpdateStatus.Failed(it.message ?: "could not open the installer"))
            }
    }
}
