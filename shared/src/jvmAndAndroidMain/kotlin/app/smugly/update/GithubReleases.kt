package app.smugly.update

import app.smugly.AppRelease
import app.smugly.VersionCompare
import app.smugly.subscription.SubscriptionFetcher.ProxySpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the app's own GitHub releases to answer one question: is there a newer APK than the
 * build that is running.
 *
 * Plain `HttpURLConnection` and `org.json`, like [app.smugly.subscription.SubscriptionFetcher]
 * — no new dependency for one JSON document. Parsing is split out of fetching ([parse]) so the
 * release-picking rules are testable without a network.
 */
object GithubReleases {
    /** `owner/repo` this build checks for updates. */
    const val DEFAULT_REPO = "32east/Smugly"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val USER_AGENT = "Smugly-Updater"

    /**
     * Newest published release that ships an APK, or null when the repo has none.
     *
     * `/releases` rather than `/releases/latest`: GitHub's "latest" excludes pre-releases
     * entirely, so a repo that only ever publishes them would look like it has no releases at
     * all. Draft releases are skipped — they are not visible to anyone else yet — while a
     * pre-release is a real published build and [VersionCompare] already ranks it below the
     * final version of the same number.
     */
    fun latest(
        repo: String = DEFAULT_REPO,
        routes: List<ProxySpec?> = listOf(null)
    ): Result<AppRelease?> =
        fetch("https://api.github.com/repos/$repo/releases?per_page=20", routes)
            .mapCatching { parse(it) }

    /** Picks the highest-versioned APK-bearing release out of the `/releases` payload. */
    fun parse(body: String): AppRelease? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var best: AppRelease? = null
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val parsed = parseRelease(release) ?: continue
            val current = best
            if (current == null || VersionCompare.isNewer(parsed.versionName, current.versionName)) {
                best = parsed
            }
        }
        return best
    }

    private fun parseRelease(release: JSONObject): AppRelease? {
        val tag = release.optString("tag_name").ifBlank { release.optString("name") }
        if (tag.isBlank()) return null
        val assets = release.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            // browser_download_url is the plain public URL; the API `url` needs an Accept
            // header and a token, which an unauthenticated client does not have.
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue
            apkUrl = url
            apkSize = asset.optLong("size", 0L)
            break
        }
        // A release with notes but no APK cannot be installed, so it is not an update.
        if (apkUrl.isBlank()) return null
        return AppRelease(
            versionName = tag.trim().removePrefix("v").removePrefix("V").trim(),
            tagName = tag.trim(),
            notes = release.optString("body").trim(),
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            htmlUrl = release.optString("html_url")
        )
    }

    /**
     * Try each route in turn and keep the first that answers; a null entry means "direct".
     *
     * The same reason subscriptions need this applies here, only harder: the network this app
     * exists to get around is the one it has to ask about its own updates over.
     */
    private fun fetch(url: String, routes: List<ProxySpec?>): Result<String> {
        var last: Throwable? = null
        for (route in routes.ifEmpty { listOf(null) }) {
            val result = fetchVia(url, route)
            if (result.isSuccess) return result
            last = result.exceptionOrNull()
        }
        return Result.failure(last ?: IllegalStateException("no route to GitHub"))
    }

    private fun fetchVia(url: String, route: ProxySpec?): Result<String> = runCatching {
        val parsed = URL(url)
        val opened = if (route == null) parsed.openConnection() else parsed.openConnection(route.toJavaProxy())
        val connection = (opened as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // 403 here is nearly always the unauthenticated rate limit (60/h per IP), which
                // is a very different thing from "no releases" — say which one it was.
                error("GitHub returned HTTP $code")
            }
            connection.inputStream.use { stream ->
                // Collect bytes and decode once at the end. Decoding each 16 KB chunk on its
                // own splits any multi-byte character that straddles the boundary, which would
                // land as mojibake in exactly the field most likely to be non-ASCII: the
                // Russian release notes shown in the update dialog.
                val buffer = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (out.size() + read > MAX_BODY_BYTES) error("release list is unexpectedly large")
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
