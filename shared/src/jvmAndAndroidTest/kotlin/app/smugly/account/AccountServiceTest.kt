package app.smugly.account

import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.defaultConfig
import app.smugly.subscription.Subscription
import app.smugly.subscription.SubscriptionFetcher
import app.smugly.subscription.SubscriptionRepository
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountServiceTest {

    private class FakeStorage : SubscriptionRepository.Storage {
        var subs = mutableListOf<Subscription>()
        var profiles = mutableListOf<ConfigProfile>()
        private var seq = 0
        override fun loadSubscriptions() = subs.toList()
        override fun saveSubscriptions(subs: List<Subscription>) {
            this.subs = subs.toMutableList()
        }
        override fun loadProfiles() = profiles.toList()
        override fun writeProfiles(profiles: List<ConfigProfile>) {
            this.profiles = profiles.toMutableList()
        }
        override fun baseConfig(): Config = defaultConfig(mode = Config.Mode.PROXY)
        override fun newId(): String = "id-${++seq}"
        override fun nowMs(): Long = 1_000_000L
        override fun profileFromLink(uri: String, name: String): ConfigProfile? = null
    }

    private class FakeApi : AccountHttp.Transport {
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var linked = false
        @Volatile var accountCode = 200
        @Volatile var accountBody: String = ""
        @Volatile var offline = false
        @Volatile var hangingHost: String? = null
        @Volatile var accountDelayMs = 0L
        @Volatile var deadHosts: Set<String> = emptySet()
        @Volatile var mirrorsText: String? = null
        override fun exchange(
            url: String,
            method: String,
            token: String?,
            body: String?,
            route: SubscriptionFetcher.ProxySpec?
        ): Pair<Int, String> {
            if (offline) throw java.io.IOException("timeout")
            val host = url.substringAfter("://").substringBefore('/')
            if (host == hangingHost) {
                Thread.sleep(20_000)
                throw java.net.SocketTimeoutException("Read timed out")
            }
            if (host in deadHosts) throw java.net.ConnectException("Connection refused")
            if (url == AccountService.MIRRORS_URL) {
                calls += "GET mirrors"
                return mirrorsText?.let { 200 to it } ?: (404 to "Not Found")
            }
            val path = url.substringAfter("://").substringAfter('/', "")
            if (path == "api/smugly/account" && accountDelayMs > 0) Thread.sleep(accountDelayMs)
            calls += "$method /$path token=${token != null}"
            return when ("/$path") {
                "/api/smugly/ping" -> 200 to """{"ok":true,"service":"smugly","trial_days":3}"""
                "/api/smugly/link/poll" ->
                    if (linked) 200 to """{"ok":true,"status":"linked","token":"app-token","account":"me@mail.ru"}"""
                    else 200 to """{"ok":true,"status":"pending"}"""
                "/api/smugly/account" -> accountCode to accountBody
                "/api/smugly/web-session" -> 200 to """{"ok":true,"url":"https://panel.example/app?smugly_client=1#oauth_token=x"}"""
                else -> 404 to "<html>not found</html>"
            }
        }
    }

    private lateinit var server: HttpServer
    private lateinit var storage: FakeStorage
    private lateinit var repo: SubscriptionRepository
    private lateinit var api: FakeApi
    private lateinit var service: AccountService
    private var session: String? = null
    @Volatile private var mirrorsStore: String? = null
    private val removedNotices = mutableListOf<Set<String>>()
    private val reissuedNotices = mutableListOf<Set<String>>()
    @Volatile private var subFails = false

    private fun subUrl() = "http://127.0.0.1:${server.address.port}/miniapp/sub/1/tok"

    private fun account(plan: Boolean, url: String = subUrl(), token: String? = null): String {
        val json = JSONObject()
            .put("ok", true)
            .put("account", JSONObject().put("id", 1).put("name", "me@mail.ru").put("email", "me@mail.ru"))
            .put("subscription_url", if (plan) url else "")
            .put("trial", JSONObject().put("available", !plan).put("days", 3).put("needs_telegram", false))
            .put("api_bases", org.json.JSONArray(listOf("https://mirror.example")))
        json.put(
            "subscription",
            if (plan) {
                JSONObject().put("end_date", "2027-04-15T10:00:00Z").put("is_trial", true)
                    .put("device_count", 2).put("data_limit_bytes", 0).put("data_used_bytes", 5)
            } else {
                JSONObject.NULL
            }
        )
        if (token != null) json.put("token", token)
        return json.toString()
    }

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/miniapp/sub/") { ex ->
            if (subFails) {
                ex.sendResponseHeaders(502, -1)
                ex.close()
                return@createContext
            }
            val bytes = """[{"remarks":"Spain","outbounds":[{"tag":"proxy","protocol":"vless"}]},{"remarks":"USA","outbounds":[{"tag":"proxy","protocol":"vless"}]}]"""
                .toByteArray()
            ex.responseHeaders.add("profile-title", "Test VPN")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        storage = FakeStorage()
        repo = SubscriptionRepository(storage)
        api = FakeApi()
        session = null
        mirrorsStore = null
        removedNotices.clear()
        reissuedNotices.clear()
        subFails = false
        service = newService()
    }

    private fun newService() = AccountService(
        object : AccountService.Port {
            override fun loadSession(): String? = session
            override fun saveSession(json: String?) {
                session = json
            }
            override fun repository() = repo
            override fun fetchRoutes(): List<SubscriptionFetcher.ProxySpec?> = listOf(null)
            override fun deviceName() = "Test Phone"
            override fun beforeManagedProfilesRemoved(profileIds: Set<String>) {
                removedNotices += profileIds
            }
            override fun afterManagedAccessReissued(profileIds: Set<String>) {
                reissuedNotices += profileIds
            }
            override fun loadMirrors(): String? = mirrorsStore
            override fun saveMirrors(json: String) {
                mirrorsStore = json
            }
        },
        AccountHttp({ listOf(null) }, api)
    )

    private fun builtInHostsDown() {
        api.deadHosts = AccountService.DEFAULT_BASES.map { it.substringAfter("://") }.toSet()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun signIn() {
        val link = service.beginLink().getOrThrow()
        api.linked = true
        assertIs<AccountLinkPoll.Linked>(service.pollLink(link))
    }

    @Test
    fun linkCarriesOnlyTheHashOfTheSecret() {
        val link = service.beginLink().getOrThrow()
        val id = AccountService.sha256Hex(link.secret)
        assertTrue(link.browserUrl.contains("smugly_link=$id"), link.browserUrl)
        assertFalse(link.browserUrl.contains(link.secret))
        assertTrue(link.browserUrl.contains("smugly_device=Test+Phone"))
        assertEquals(3, service.trialDays())
        assertEquals(AccountLinkPoll.Pending, service.pollLink(link))
        assertFalse(service.isSignedIn())
        api.linked = true
        val linked = service.pollLink(link)
        assertIs<AccountLinkPoll.Linked>(linked)
        assertEquals("me@mail.ru", linked.name)
        assertTrue(service.isSignedIn())
    }

    @Test
    fun activePlanCreatesOneManagedFolder() {
        signIn()
        api.accountBody = account(plan = true)
        val first = service.sync()
        assertIs<AccountSyncResult.Ready>(first)
        assertTrue(first.folderChanged)
        val managed = storage.subs.single()
        assertTrue(managed.managed)
        assertFalse(managed.allowReorder)
        assertEquals("Test VPN", managed.name)
        assertEquals(2, storage.profiles.count { it.subscriptionId == managed.id })
        assertEquals(managed.id, service.managedSubscriptionId())
        assertNotNull(service.snapshot()?.plan)

        val second = service.sync()
        assertIs<AccountSyncResult.Ready>(second)
        assertFalse(second.folderChanged)
        assertEquals(1, storage.subs.size)
    }

    @Test
    fun expiredPlanRemovesOnlyTheManagedFolder() {
        storage.profiles += ConfigProfile("own", "Mine", defaultConfig(mode = Config.Mode.PROXY))
        repo.add("http://127.0.0.1:${server.address.port}/miniapp/sub/9/other")
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        val managedId = service.managedSubscriptionId()!!
        val managedProfiles = storage.profiles.filter { it.subscriptionId == managedId }.map { it.id }.toSet()

        api.accountBody = account(plan = false)
        val result = service.sync()
        assertIs<AccountSyncResult.Ready>(result)
        assertTrue(result.folderChanged)
        assertNull(storage.subs.firstOrNull { it.id == managedId })
        assertEquals(1, storage.subs.size)
        assertTrue(storage.profiles.any { it.id == "own" })
        assertTrue(storage.profiles.none { it.subscriptionId == managedId })
        assertEquals(managedProfiles, removedNotices.last())
        assertNull(service.managedSubscriptionId())
        assertTrue(service.isSignedIn())
    }

    @Test
    fun deletedAccountSignsOutAndRemovesFolder() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        api.accountCode = 401
        api.accountBody = """{"ok":false,"error":"unauthorized","reason":"account_not_found"}"""
        val result = service.sync()
        assertEquals(AccountSyncResult.SignedOut(SignOutReason.ACCOUNT_GONE), result)
        assertFalse(service.isSignedIn())
        assertTrue(storage.subs.none { it.managed })
        assertTrue(storage.profiles.isEmpty())
    }

    @Test
    fun bannedAccountSignsOut() {
        signIn()
        api.accountCode = 403
        api.accountBody = """{"ok":false,"reason":"banned"}"""
        assertEquals(AccountSyncResult.SignedOut(SignOutReason.BANNED), service.sync())
    }

    @Test
    fun networkTroubleNeverDeletesAnything() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        val before = storage.subs.toList()
        api.offline = true
        assertIs<AccountSyncResult.Offline>(service.sync())
        api.offline = false
        api.accountCode = 200
        api.accountBody = "<html>captive portal</html>"
        assertIs<AccountSyncResult.Offline>(service.sync())
        api.accountCode = 401
        api.accountBody = "<html>proxy auth</html>"
        assertIs<AccountSyncResult.Offline>(service.sync())
        assertEquals(before, storage.subs)
        assertTrue(service.isSignedIn())
    }

    @Test
    fun existingImportOfTheSameLinkIsAdoptedNotDuplicated() {
        repo.add(subUrl())
        val imported = storage.subs.single()
        assertFalse(imported.managed)
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        val adopted = storage.subs.single()
        assertEquals(imported.id, adopted.id)
        assertTrue(adopted.managed)
    }

    @Test
    fun sameLinkOnAnotherHostOrWithQueryIsAdopted() {
        val imported = subUrl().replace("127.0.0.1", "localhost") + "?client=smugly"
        repo.add(imported)
        val before = storage.subs.single()
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        val adopted = storage.subs.single()
        assertEquals(before.id, adopted.id)
        assertTrue(adopted.managed)
        assertEquals(imported, adopted.url)
    }

    @Test
    fun oldLinkOfTheSameUserIsAdoptedAndUpdated() {
        repo.add("http://127.0.0.1:${server.address.port}/miniapp/sub/1/oldtoken")
        val before = storage.subs.single()
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        val adopted = storage.subs.single()
        assertEquals(before.id, adopted.id)
        assertEquals(subUrl(), adopted.url)
    }

    @Test
    fun rotatedLinkIsFollowed() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        val id = service.managedSubscriptionId()
        val rotated = "http://127.0.0.1:${server.address.port}/miniapp/sub/1/rotated"
        api.accountBody = account(plan = true, url = rotated)
        val result = service.sync()
        assertIs<AccountSyncResult.Ready>(result)
        assertTrue(result.folderChanged)
        assertEquals(id, service.managedSubscriptionId())
        assertEquals(rotated, storage.subs.single().url)
    }

    @Test
    fun reissuedAccessIsReimportedAndReported() {
        signIn()
        api.accountBody = account(plan = true)
        val first = service.sync()
        assertIs<AccountSyncResult.Ready>(first)
        assertFalse(first.accessReissued)
        val id = service.managedSubscriptionId()!!
        val profileIds = storage.profiles.filter { it.subscriptionId == id }.map { it.id }.toSet()

        val rotated = "http://127.0.0.1:${server.address.port}/miniapp/sub/1/rotated"
        api.accountBody = account(plan = true, url = rotated)
        val result = service.sync()
        assertIs<AccountSyncResult.Ready>(result)
        assertTrue(result.accessReissued)
        assertTrue(result.folderChanged)
        assertEquals(rotated, storage.subs.single().url)
        assertEquals(profileIds, reissuedNotices.single())
        assertEquals(profileIds, storage.profiles.filter { it.subscriptionId == id }.map { it.id }.toSet())

        val again = service.sync()
        assertIs<AccountSyncResult.Ready>(again)
        assertFalse(again.accessReissued)
        assertEquals(1, reissuedNotices.size)
    }

    @Test
    fun failedReimportIsRetriedUntilItLands() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        subFails = true
        api.accountBody = account(plan = true, url = "http://127.0.0.1:${server.address.port}/miniapp/sub/1/rotated")
        val failed = service.sync()
        assertIs<AccountSyncResult.Ready>(failed)
        assertFalse(failed.accessReissued)
        assertTrue(reissuedNotices.isEmpty())

        subFails = false
        val retried = service.sync()
        assertIs<AccountSyncResult.Ready>(retried)
        assertTrue(retried.accessReissued)
        assertEquals(1, reissuedNotices.size)
    }

    @Test
    fun firstAdoptionOfAnOldLinkIsNotAReissue() {
        repo.add("http://127.0.0.1:${server.address.port}/miniapp/sub/1/oldtoken")
        signIn()
        api.accountBody = account(plan = true)
        val result = service.sync()
        assertIs<AccountSyncResult.Ready>(result)
        assertFalse(result.accessReissued)
        assertTrue(reissuedNotices.isEmpty())
    }

    @Test
    fun anotherUsersLinkIsNotAReissue() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        api.accountBody = account(plan = true, url = "http://127.0.0.1:${server.address.port}/miniapp/sub/2/tok")
        val result = service.sync()
        assertIs<AccountSyncResult.Ready>(result)
        assertFalse(result.accessReissued)
        assertTrue(reissuedNotices.isEmpty())
    }

    @Test
    fun refreshedTokenAndMirrorsAreKept() {
        signIn()
        api.accountBody = account(plan = false, token = "fresh-token")
        service.sync()
        assertTrue(session!!.contains("fresh-token"))
        assertTrue(session!!.contains("https://mirror.example"))
    }

    @Test
    fun signedOutAppDropsOrphanManagedFolders() {
        storage.subs += Subscription(id = "orphan", name = "x", url = subUrl(), managed = true)
        storage.profiles += ConfigProfile("p", "p", defaultConfig(mode = Config.Mode.PROXY), subscriptionId = "orphan")
        assertEquals(AccountSyncResult.NotSignedIn, service.sync())
        assertTrue(storage.subs.isEmpty())
        assertTrue(storage.profiles.isEmpty())
    }

    @Test
    fun signOutRemovesFolderAndSession() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        service.signOut()
        assertNull(session)
        assertTrue(storage.subs.isEmpty())
    }

    @Test
    fun hangingPrimaryHostDoesNotHoldUpSignInOrSync() {
        api.hangingHost = AccountService.DEFAULT_BASES.first().substringAfter("://")
        val started = System.currentTimeMillis()
        signIn()
        api.accountBody = account(plan = true)
        assertIs<AccountSyncResult.Ready>(service.sync())
        val spent = System.currentTimeMillis() - started
        assertTrue(spent < 6_000, "took $spent ms")
        assertEquals(1, storage.subs.count { it.managed })
    }

    @Test
    fun signOutDoesNotWaitForARunningSync() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        api.accountDelayMs = 2_500
        var result: AccountSyncResult? = null
        val running = Thread { result = service.sync() }.apply { start() }
        Thread.sleep(300)
        val started = System.currentTimeMillis()
        service.signOut()
        val spent = System.currentTimeMillis() - started
        assertTrue(spent < 1_000, "sign-out took $spent ms")
        running.join()
        assertEquals(AccountSyncResult.NotSignedIn, result)
        assertNull(session)
        assertTrue(storage.subs.none { it.managed })
    }

    @Test
    fun webCabinetUrlComesFromServer() {
        signIn()
        assertEquals("https://panel.example/app?smugly_client=1#oauth_token=x", service.webCabinetUrl().getOrThrow())
    }

    @Test
    fun mirrorListRescuesWhenBuiltInAddressesAreDead() {
        builtInHostsDown()
        api.mirrorsText = "<html>captive portal</html>"
        assertTrue(service.beginLink().isFailure)
        assertNull(mirrorsStore)

        service = newService()
        api.mirrorsText = "# mirrors\nhttps://fresh.example\n"
        val link = service.beginLink().getOrThrow()
        assertEquals("https://fresh.example", link.base)
        assertTrue(mirrorsStore!!.contains("https://fresh.example"))
    }

    @Test
    fun learnedMirrorsOutliveSignOutAndRestart() {
        builtInHostsDown()
        api.mirrorsText = "https://fresh.example"
        signIn()
        service.signOut()
        assertNull(session)

        api.mirrorsText = null
        service = newService()
        assertEquals("https://fresh.example", service.beginLink().getOrThrow().base)
    }

    @Test
    fun managedFolderFollowsTheServerToAnotherHost() {
        signIn()
        api.accountBody = account(plan = true)
        service.sync()
        val moved = subUrl().replace("127.0.0.1", "localhost")
        api.accountBody = account(plan = true, url = moved)
        val result = service.sync()
        assertIs<AccountSyncResult.Ready>(result)
        assertEquals(moved, storage.subs.single().url)
        assertFalse(result.accessReissued)
        assertTrue(reissuedNotices.isEmpty())

        val again = service.sync()
        assertIs<AccountSyncResult.Ready>(again)
        assertFalse(again.folderChanged)
    }

    @Test
    fun parsesMirrorList() {
        val text = """
            # comment
            https://a.example
            b.example:8443   # inline note
            http://plain.example
            https://c.example/path
            https://user@d.example
            <html><body>
            localhost
            https://A.example/
        """.trimIndent()
        assertEquals(listOf("https://a.example", "https://b.example:8443"), AccountService.parseMirrors(text))
    }

    @Test
    fun parsesServerTimes() {
        assertEquals(1807783200000L, AccountService.parseTime("2027-04-15T10:00:00Z"))
        assertEquals(1807783200000L, AccountService.parseTime("2027-04-15T13:00:00+03:00"))
        assertEquals(1807783200000L, AccountService.parseTime("2027-04-15T10:00:00.250Z"))
        assertEquals(0L, AccountService.parseTime("garbage"))
    }
}
