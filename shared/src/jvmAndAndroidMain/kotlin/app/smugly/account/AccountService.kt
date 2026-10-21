package app.smugly.account

import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import app.smugly.subscription.SubscriptionFetcher
import app.smugly.subscription.SubscriptionRepository
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AccountService(
    private val port: Port,
    private val http: AccountHttp = AccountHttp({ port.fetchRoutes() })
) : AccountApi {

    interface Port {
        fun loadSession(): String?
        fun saveSession(json: String?)
        fun repository(): SubscriptionRepository
        fun fetchRoutes(): List<SubscriptionFetcher.ProxySpec?>
        fun deviceName(): String
        fun nowMs(): Long = System.currentTimeMillis()
        fun beforeManagedProfilesRemoved(profileIds: Set<String>) {}
        fun afterManagedAccessReissued(profileIds: Set<String>) {}
        fun loadMirrors(): String? = null
        fun saveMirrors(json: String) {}
    }

    private class MirrorCache(val bases: List<String>, val fetchedAtMs: Long)

    private data class Session(
        val token: String,
        val base: String,
        val bases: List<String>,
        val managedSubId: String?,
        val snapshot: AccountSnapshot?,
        val lastSyncMs: Long,
        val importedLinkKey: String? = null
    )

    private class Attempt(val base: String, val reply: AccountHttp.Reply?, val error: String?)

    private val lock = Any()
    private val syncLock = Any()
    private val mirrorLock = Any()
    private val mirrorRefreshing = AtomicBoolean(false)
    @Volatile private var trialDaysHint: Int? = null
    @Volatile private var preferredBase: String? = null
    @Volatile private var mirrorCache: MirrorCache? = null
    @Volatile private var mirrorAttemptMs = 0L

    override fun isSignedIn(): Boolean = load() != null

    override fun snapshot(): AccountSnapshot? = load()?.snapshot

    override fun managedSubscriptionId(): String? = load()?.managedSubId

    override fun trialDays(): Int? = load()?.snapshot?.trialDays?.takeIf { it > 0 } ?: trialDaysHint

    override fun fetchTrialDays(): Int? {
        trialDays()?.let { return it }
        val (answer, _) = hedgeBases(
            null,
            accept = { it.code == 200 && (it.json?.optInt("trial_days", 0) ?: 0) > 0 }
        ) { base -> http.request(base, "GET", "/api/smugly/ping") }
        val days = answer?.reply?.json?.optInt("trial_days", 0)?.takeIf { it > 0 } ?: return null
        trialDaysHint = days
        return days
    }

    override fun beginLink(): Result<AccountLink> {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val linkId = sha256Hex(secret)
        val (answer, error) = hedgeBases(
            null,
            accept = { it.code == 200 && it.json?.optBoolean("ok") == true }
        ) { base -> http.request(base, "GET", "/api/smugly/ping") }
        val won = answer ?: return Result.failure(IllegalStateException(error ?: "panel unreachable"))
        val base = won.base
        won.reply?.json?.optInt("trial_days", 0)?.takeIf { it > 0 }?.let { trialDaysHint = it }
        val device = URLEncoder.encode(port.deviceName().take(64), "UTF-8")
        return Result.success(
            AccountLink(
                secret = secret,
                browserUrl = "$base/app?smugly_link=$linkId&smugly_device=$device",
                base = base,
                startedAtMs = port.nowMs()
            )
        )
    }

    override fun pollLink(link: AccountLink): AccountLinkPoll {
        if (link.expired(port.nowMs())) return AccountLinkPoll.Failed("expired")
        val reply = http.request(link.base, "POST", "/api/smugly/link/poll", body = JSONObject().put("secret", link.secret))
            .getOrNull() ?: return AccountLinkPoll.Pending
        val json = reply.json ?: return AccountLinkPoll.Pending
        return when (json.optString("status")) {
            "linked" -> {
                val token = json.optString("token")
                if (token.isBlank()) return AccountLinkPoll.Failed("empty token")
                val name = json.optString("account")
                synchronized(lock) {
                    val previous = load()
                    save(
                        Session(
                            token = token,
                            base = link.base,
                            bases = previous?.bases.orEmpty(),
                            managedSubId = previous?.managedSubId,
                            snapshot = AccountSnapshot(AccountProfile(name), plan = null),
                            lastSyncMs = 0,
                            importedLinkKey = previous?.importedLinkKey
                        )
                    )
                }
                AccountLinkPoll.Linked(name)
            }
            "failed" -> AccountLinkPoll.Failed(json.optString("error").ifBlank { "failed" })
            else -> AccountLinkPoll.Pending
        }
    }

    override fun confirmOAuth(link: AccountLink, code: String): Boolean {
        if (link.expired(port.nowMs()) || code.isBlank()) return false
        val body = JSONObject().put("secret", link.secret).put("code", code)
        val (answer, _) = hedgeBases(
            null,
            accept = { it.json?.optString("status") in setOf("confirmed", "failed") }
        ) { base -> http.request(base, "POST", "/api/smugly/link/oauth", body = body) }
        return answer?.reply?.json?.optString("status") == "confirmed"
    }

    override fun sync(): AccountSyncResult = synchronized(syncLock) {
        val session = synchronized(lock) { load() }
        if (session == null) {
            synchronized(lock) { if (load() == null) removeManagedFolders(null) }
            return AccountSyncResult.NotSignedIn
        }
        val (answer, error) = hedgeBases(session, accept = ::isDecisive) { base ->
            http.request(base, "GET", "/api/smugly/account", token = session.token)
        }
        val won = answer ?: return AccountSyncResult.Offline(error ?: "panel unreachable")
        val reply = won.reply ?: return AccountSyncResult.Offline(error ?: "panel unreachable")
        val json = reply.json ?: return AccountSyncResult.Offline(error ?: "panel unreachable")
        synchronized(lock) {
            if (load()?.token != session.token) return AccountSyncResult.NotSignedIn
            when (reply.code) {
                401 -> {
                    signOutLocked()
                    return AccountSyncResult.SignedOut(
                        if (json.optString("reason") == "account_not_found") {
                            SignOutReason.ACCOUNT_GONE
                        } else {
                            SignOutReason.SESSION_INVALID
                        }
                    )
                }
                403 -> {
                    signOutLocked()
                    return AccountSyncResult.SignedOut(SignOutReason.BANNED)
                }
            }
        }
        return applyAccount(session, won.base, json)
    }

    override fun webCabinetUrl(): Result<String> {
        val session = synchronized(lock) { load() } ?: return Result.failure(IllegalStateException("not signed in"))
        val (answer, error) = hedgeBases(
            session,
            accept = { it.code == 200 && it.json?.optString("url").orEmpty().startsWith("https://") }
        ) { base -> http.request(base, "POST", "/api/smugly/web-session", token = session.token, body = JSONObject()) }
        val url = answer?.reply?.json?.optString("url")
            ?: return Result.failure(IllegalStateException(error ?: "panel unreachable"))
        return Result.success(url)
    }

    override fun signOut() {
        synchronized(lock) { signOutLocked() }
    }

    private fun signOutLocked() {
        removeManagedFolders(load()?.managedSubId)
        port.saveSession(null)
    }

    private fun applyAccount(session: Session, base: String, json: JSONObject): AccountSyncResult {
        val snapshot = parseSnapshot(json)
        val url = json.optString("subscription_url").trim()
        val bases = json.optJSONArray("api_bases")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("https://") }
        }.orEmpty()
        var managedId = session.managedSubId
        var changed = false
        var importedKey = session.importedLinkKey
        var accessReissued = false
        if (snapshot.plan != null && url.isNotBlank()) {
            val repo = port.repository()
            val before = repo.managed().firstOrNull()
            val beforeCount = before?.let { repo.profilesOf(it.id).size } ?: 0
            val wanted = panelLinkKey(url)
            val sameLink = repo.list().firstOrNull { wanted != null && panelLinkKey(it.url) == wanted }
            val sameUser = sameLink ?: repo.list().firstOrNull {
                !it.managed && wanted != null && panelLinkKey(it.url)?.substringBefore('/') == wanted.substringBefore('/')
            }
            val target = before ?: sameUser
            val effectiveUrl = if (
                target != null && panelLinkKey(target.url) == wanted &&
                (before == null || originOf(target.url) == originOf(url))
            ) target.url else url
            val reissued = before != null && wanted != null && importedKey != null && importedKey != wanted &&
                importedKey.substringBefore('/') == wanted.substringBefore('/')
            val result = repo.adoptManaged(effectiveUrl, candidateId = target?.id, forceRefresh = reissued)
            if (result.subscription.id.isNotBlank()) {
                managedId = result.subscription.id
                repo.managed().filter { it.id != managedId }.forEach { extra ->
                    port.beforeManagedProfilesRemoved(repo.profilesOf(extra.id).map { it.id }.toSet())
                    repo.delete(extra.id)
                }
            }
            if (result.isSuccess) {
                importedKey = wanted ?: importedKey
                accessReissued = reissued
            } else {
                PlatformLog.log(LogLevel.WARN, TAG, "managed subscription refresh failed: ${result.error}")
            }
            val afterProfiles = repo.profilesOf(managedId.orEmpty())
            changed = before == null || before.url != effectiveUrl || beforeCount != afterProfiles.size ||
                result.subscription.lastUpdatedMs != before.lastUpdatedMs || accessReissued
            if (accessReissued) {
                PlatformLog.log(LogLevel.INFO, TAG, "access reissued, managed folder re-imported")
                port.afterManagedAccessReissued(afterProfiles.map { it.id }.toSet())
            }
        } else {
            changed = removeManagedFolders(session.managedSubId)
            managedId = null
            importedKey = null
        }
        synchronized(lock) {
            val current = load()
            if (current == null) {
                removeManagedFolders(null)
                return AccountSyncResult.NotSignedIn
            }
            if (current.token == session.token) {
                save(
                    current.copy(
                        token = json.optString("token").ifBlank { session.token },
                        base = base,
                        bases = bases.ifEmpty { current.bases },
                        managedSubId = managedId,
                        snapshot = snapshot,
                        lastSyncMs = port.nowMs(),
                        importedLinkKey = importedKey
                    )
                )
            }
        }
        return AccountSyncResult.Ready(snapshot, changed, accessReissued)
    }

    private fun isDecisive(reply: AccountHttp.Reply): Boolean {
        val json = reply.json ?: return false
        return when (reply.code) {
            200 -> json.optBoolean("ok")
            401 -> json.has("reason")
            403 -> json.optString("reason") == "banned"
            else -> false
        }
    }

    private fun hedge(
        bases: List<String>,
        accept: (AccountHttp.Reply) -> Boolean,
        call: (String) -> Result<AccountHttp.Reply>
    ): Pair<Attempt?, String?> {
        if (bases.isEmpty()) return null to null
        val pool = Executors.newCachedThreadPool { task -> Thread(task, "smugly-account").apply { isDaemon = true } }
        val done = ExecutorCompletionService<Attempt>(pool)
        var launched = 0
        var finished = 0
        var lastError: String? = null
        fun launchNext() {
            val base = bases[launched++]
            done.submit(
                Callable {
                    val result = runCatching { call(base) }.getOrElse { Result.failure(it) }
                    Attempt(base, result.getOrNull(), result.exceptionOrNull()?.message)
                }
            )
        }
        try {
            launchNext()
            while (finished < launched) {
                val next = if (launched < bases.size) done.poll(HEDGE_DELAY_MS, TimeUnit.MILLISECONDS) else done.take()
                if (next == null) {
                    launchNext()
                    continue
                }
                finished++
                val attempt = next.get()
                val reply = attempt.reply
                if (reply != null && accept(reply)) {
                    preferredBase = attempt.base
                    return attempt to null
                }
                lastError = attempt.error ?: reply?.let { "HTTP ${it.code}" } ?: lastError
                if (finished == launched && launched < bases.size) launchNext()
            }
            return null to lastError
        } finally {
            pool.shutdownNow()
        }
    }

    private fun hedgeBases(
        session: Session?,
        accept: (AccountHttp.Reply) -> Boolean,
        call: (String) -> Result<AccountHttp.Reply>
    ): Pair<Attempt?, String?> {
        refreshMirrorsInBackground()
        val tried = candidateBases(session)
        val first = hedge(tried, accept, call)
        if (first.first != null) return first
        val latest = if (port.nowMs() - mirrorAttemptMs < MIRRORS_RETRY_MS) {
            synchronized(mirrorLock) { mirrors().bases }
        } else {
            refreshMirrors().orEmpty()
        }
        val fresh = latest.filter { it !in tried }
        if (fresh.isEmpty()) return first
        val second = hedge(fresh, accept, call)
        return if (second.first != null) second else first
    }

    private fun mirrors(): MirrorCache {
        mirrorCache?.let { return it }
        val stored = runCatching { port.loadMirrors()?.let { mirrorCacheFromJson(JSONObject(it)) } }.getOrNull()
        return (stored ?: MirrorCache(emptyList(), 0L)).also { mirrorCache = it }
    }

    private fun refreshMirrorsInBackground() {
        val now = port.nowMs()
        if (now - mirrors().fetchedAtMs < MIRRORS_TTL_MS || now - mirrorAttemptMs < MIRRORS_RETRY_MS) return
        if (!mirrorRefreshing.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    refreshMirrors()
                } finally {
                    mirrorRefreshing.set(false)
                }
            },
            "smugly-mirrors"
        ).apply { isDaemon = true }.start()
    }

    private fun refreshMirrors(): List<String>? {
        synchronized(mirrorLock) {
            mirrorAttemptMs = port.nowMs()
            val text = http.fetchText(MIRRORS_URL).getOrElse {
                PlatformLog.log(LogLevel.INFO, TAG, "mirror list unavailable: ${it.message}")
                return null
            }
            val bases = parseMirrors(text)
            if (bases.isEmpty()) {
                PlatformLog.log(LogLevel.WARN, TAG, "mirror list has no usable addresses")
                return null
            }
            val cache = MirrorCache(bases, port.nowMs())
            mirrorCache = cache
            runCatching { port.saveMirrors(mirrorCacheToJson(cache).toString()) }
            return bases
        }
    }

    private fun removeManagedFolders(knownId: String?): Boolean {
        val repo = port.repository()
        val targets = repo.list().filter { it.managed || it.id == knownId }
        if (targets.isEmpty()) return false
        port.beforeManagedProfilesRemoved(targets.flatMap { sub -> repo.profilesOf(sub.id).map { it.id } }.toSet())
        targets.forEach { repo.delete(it.id) }
        return true
    }

    private fun candidateBases(session: Session?): List<String> =
        (listOfNotNull(preferredBase, session?.base) + mirrors().bases + DEFAULT_BASES + session?.bases.orEmpty())
            .map { it.trimEnd('/') }
            .filter { it.startsWith("https://") }
            .distinct()

    private fun load(): Session? {
        val raw = port.loadSession()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { sessionFromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun save(session: Session) {
        port.saveSession(sessionToJson(session).toString())
    }

    companion object {
        private const val TAG = "AccountService"
        private const val HEDGE_DELAY_MS = 1_500L
        private const val MIRRORS_TTL_MS = 6 * 60 * 60 * 1000L
        private const val MIRRORS_RETRY_MS = 5 * 60 * 1000L
        private const val MAX_MIRRORS = 20

        const val MIRRORS_URL = "https://gist.githubusercontent.com/32east/cb4cde468b3dc5b49d1172f37832631d/raw/mirrors.txt"

        val DEFAULT_BASES = listOf(
            "https://m-eecfe96398.netlify.app",
            "https://m-ce3df4f992.netlify.app",
            "https://m-6aa90cd3a7.holyfuf11.workers.dev",
            "https://smugvpn.com"
        )

        internal fun parseMirrors(text: String): List<String> =
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val uri = runCatching { java.net.URI(if ("://" in line) line else "https://$line") }.getOrNull()
                        ?: return@mapNotNull null
                    val host = uri.host?.lowercase()?.takeIf { '.' in it } ?: return@mapNotNull null
                    val bare = uri.rawUserInfo == null && uri.rawQuery == null && uri.rawPath.orEmpty().trim('/').isEmpty()
                    if (uri.scheme != "https" || !bare) return@mapNotNull null
                    if (uri.port == -1) "https://$host" else "https://$host:${uri.port}"
                }
                .distinct()
                .take(MAX_MIRRORS)
                .toList()

        private fun originOf(url: String): String? = runCatching {
            val uri = java.net.URI(url.trim())
            "${uri.scheme.lowercase()}://${uri.rawAuthority.lowercase()}"
        }.getOrNull()

        private fun mirrorCacheToJson(cache: MirrorCache): JSONObject =
            JSONObject().put("bases", JSONArray(cache.bases)).put("fetchedAtMs", cache.fetchedAtMs)

        private fun mirrorCacheFromJson(json: JSONObject): MirrorCache = MirrorCache(
            bases = json.optJSONArray("bases")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                .orEmpty()
                .filter { it.startsWith("https://") },
            fetchedAtMs = json.optLong("fetchedAtMs")
        )

        internal fun panelLinkKey(url: String): String? {
            val match = Regex("""/miniapp/sub/(\d+)/([^/?#]+)""").find(url) ?: return null
            return match.groupValues[1] + "/" + match.groupValues[2]
        }

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        internal fun parseSnapshot(json: JSONObject): AccountSnapshot {
            val account = json.optJSONObject("account") ?: JSONObject()
            val sub = json.optJSONObject("subscription")
            val trial = json.optJSONObject("trial") ?: JSONObject()
            val plan = sub?.let {
                AccountPlan(
                    endsAtMs = parseTime(it.optString("end_date")),
                    isTrial = it.optBoolean("is_trial"),
                    deviceCount = it.optInt("device_count", 1),
                    dataLimitBytes = it.optLong("data_limit_bytes", 0),
                    dataUsedBytes = it.optLong("data_used_bytes", 0),
                    configSyncPending = it.optBoolean("config_sync_pending")
                )
            }
            return AccountSnapshot(
                profile = AccountProfile(
                    name = account.optString("name"),
                    email = account.optString("email"),
                    telegramLinked = account.optBoolean("telegram_linked")
                ),
                plan = plan,
                trialAvailable = trial.optBoolean("available"),
                trialDays = trial.optInt("days", 0),
                trialNeedsTelegram = trial.optBoolean("needs_telegram"),
                supportUrl = json.optString("support_url")
            )
        }

        internal fun parseTime(raw: String): Long = runCatching {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val text = raw.trim()
            val main = text.substring(0, 19)
            var ms = format.parse(main)!!.time
            val zone = text.substring(19).substringAfter('.').dropWhile { it.isDigit() }
                .ifBlank { text.substring(19) }
            if (zone.length >= 6 && (zone[0] == '+' || zone[0] == '-')) {
                val offset = (zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt()) * 60_000L
                ms -= if (zone[0] == '+') offset else -offset
            }
            ms
        }.getOrDefault(0L)

        private fun sessionToJson(session: Session): JSONObject {
            val json = JSONObject()
                .put("token", session.token)
                .put("base", session.base)
                .put("bases", JSONArray(session.bases))
                .put("managedSubId", session.managedSubId ?: JSONObject.NULL)
                .put("lastSyncMs", session.lastSyncMs)
                .put("importedLinkKey", session.importedLinkKey ?: JSONObject.NULL)
            session.snapshot?.let { snap ->
                val snapJson = JSONObject()
                    .put("name", snap.profile.name)
                    .put("email", snap.profile.email)
                    .put("telegramLinked", snap.profile.telegramLinked)
                    .put("trialAvailable", snap.trialAvailable)
                    .put("trialDays", snap.trialDays)
                    .put("trialNeedsTelegram", snap.trialNeedsTelegram)
                    .put("supportUrl", snap.supportUrl)
                snap.plan?.let { plan ->
                    snapJson.put(
                        "plan",
                        JSONObject()
                            .put("endsAtMs", plan.endsAtMs)
                            .put("isTrial", plan.isTrial)
                            .put("deviceCount", plan.deviceCount)
                            .put("dataLimitBytes", plan.dataLimitBytes)
                            .put("dataUsedBytes", plan.dataUsedBytes)
                            .put("configSyncPending", plan.configSyncPending)
                    )
                }
                json.put("snapshot", snapJson)
            }
            return json
        }

        private fun sessionFromJson(json: JSONObject): Session? {
            val token = json.optString("token").ifBlank { return null }
            val snap = json.optJSONObject("snapshot")?.let { s ->
                AccountSnapshot(
                    profile = AccountProfile(
                        name = s.optString("name"),
                        email = s.optString("email"),
                        telegramLinked = s.optBoolean("telegramLinked")
                    ),
                    plan = s.optJSONObject("plan")?.let { p ->
                        AccountPlan(
                            endsAtMs = p.optLong("endsAtMs"),
                            isTrial = p.optBoolean("isTrial"),
                            deviceCount = p.optInt("deviceCount", 1),
                            dataLimitBytes = p.optLong("dataLimitBytes"),
                            dataUsedBytes = p.optLong("dataUsedBytes"),
                            configSyncPending = p.optBoolean("configSyncPending")
                        )
                    },
                    trialAvailable = s.optBoolean("trialAvailable"),
                    trialDays = s.optInt("trialDays"),
                    trialNeedsTelegram = s.optBoolean("trialNeedsTelegram"),
                    supportUrl = s.optString("supportUrl")
                )
            }
            return Session(
                token = token,
                base = json.optString("base"),
                bases = json.optJSONArray("bases")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty(),
                managedSubId = json.optString("managedSubId").takeIf { it.isNotBlank() && it != "null" },
                snapshot = snap,
                lastSyncMs = json.optLong("lastSyncMs"),
                importedLinkKey = json.optString("importedLinkKey").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }
}
