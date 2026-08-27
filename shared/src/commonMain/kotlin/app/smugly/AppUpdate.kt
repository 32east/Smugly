package app.smugly

/**
 * One published release of the app, as the update checker understands it.
 *
 * [apkUrl] is the release's APK asset. A release without one is not an update this app can
 * install, and the checker drops it rather than offering a dead button.
 */
data class AppRelease(
    /** Tag with any leading `v` stripped — what gets compared and shown. */
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** Release page, for "what changed" when the notes are empty. */
    val htmlUrl: String
)

/** Where the updater is right now. Drives both the dialog and the Settings row. */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()

    /** Checked, and this build is the newest one published. */
    object UpToDate : UpdateStatus()

    /** A newer release exists and has not been downloaded yet. */
    data class Available(val release: AppRelease) : UpdateStatus()

    /** [total] is 0 when the server sent no Content-Length — show a spinner, not 0%. */
    data class Downloading(
        val release: AppRelease,
        val downloaded: Long,
        val total: Long
    ) : UpdateStatus() {
        val fraction: Float? get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** APK is on disk; the system installer is the only step left. */
    data class ReadyToInstall(val release: AppRelease) : UpdateStatus()

    data class Failed(val message: String) : UpdateStatus()
}

/**
 * Compares release tags the way humans read them: `v1.10.0` is newer than `v1.9.0`, and a
 * pre-release (`1.2.0-beta1`) is older than the final `1.2.0` it leads to.
 *
 * Deliberately tolerant — the tag is typed by a person at release time, so a stray `v`, a
 * missing patch component or a build suffix must not be read as "no update" (silently never
 * updating) or as "downgrade available" (offering the running build back).
 */
object VersionCompare {
    /** True when [candidate] is a strictly newer version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** Negative / zero / positive, like any comparator. */
    fun compare(a: String, b: String): Int {
        val (aCore, aPre) = split(a)
        val (bCore, bPre) = split(b)
        val size = maxOf(aCore.size, bCore.size)
        for (i in 0 until size) {
            val diff = aCore.getOrElse(i) { 0 } - bCore.getOrElse(i) { 0 }
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        // Same numbers: a release outranks any pre-release of itself (1.2.0 > 1.2.0-rc1).
        return when {
            aPre.isEmpty() && bPre.isEmpty() -> 0
            aPre.isEmpty() -> 1
            bPre.isEmpty() -> -1
            else -> aPre.compareTo(bPre).coerceIn(-1, 1)
        }
    }

    /** `"v1.2.3-rc1"` → `[1, 2, 3]` + `"rc1"`. Non-numeric junk in a component reads as 0. */
    private fun split(raw: String): Pair<List<Int>, String> {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V").trim()
        val cut = trimmed.indexOfFirst { it == '-' || it == '+' || it == ' ' || it == '_' }
        val core = if (cut >= 0) trimmed.substring(0, cut) else trimmed
        val pre = if (cut >= 0) trimmed.substring(cut + 1) else ""
        val parts = core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return parts to pre
    }
}
