package app.smugly

/**
 * Profile / tunnel configuration — pure multiplatform data model.
 * Persistence is platform-specific ([app.smugly.platform.KeyValueStore]).
 */
data class Config(
    val domain: String,
    val resolverHost: String,
    val resolverPort: Int,
    val resolverMode: ResolverMode,
    val resolverTransport: ResolverTransport,
    val resolverPathMode: ResolverPathMode,
    val listenPort: Int,
    val mode: Mode,
    val authMode: AuthMode,
    val username: String,
    val password: String,
    val dnsQueryType: Int = 16,
    val dnsLabelLength: Int = 57,
    val dnsLabelLengthJitter: Int = 4,
    val maxPollQps: Int = 1400,
    val maxDataQps: Int = 800,
    val maxActiveClients: Int = 40,
    val base64uEncoding: Boolean = false,
    val protocol: TunnelProtocol = TunnelProtocol.SLIPSTREAM,
    val s3Endpoint: String = "",
    val s3Bucket: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3Prefix: String = "s3fu",
    /** The whole s3fu credential: it authenticates and derives the user's object
     *  namespace, so there is no separate login. */
    val s3Psk: String = "",
    val xrayConfigJson: String = "",
    /**
     * Whole client config as TOML, edited as text in the profile screen — the same file
     * the `s3fu --config` CLI reads. Blank = derive one from the fields above, which is
     * what every profile made before this existed does (see [effectiveS3fuToml]).
     */
    val s3fuToml: String = "",
    val xrayManual: Boolean = false,
    val turnfuHost: String = "",
    val turnfuPort: Int = 46000,
    val turnfuPassword: String = "",
    val turnfuWorkers: Int = 20,
    val turnfuMtu: Int = 1200,
    val turnfuCalls: List<String> = emptyList(),
    val turnfuToken: String = ""
) {
    enum class Mode { PROXY, VPN }
    enum class AuthMode { NO_AUTH, LOGIN_PASSWORD }
    enum class ResolverMode { MANUAL, AUTO }
    enum class ResolverTransport { UDP, TCP }
    enum class ResolverPathMode { RECURSIVE, AUTHORITATIVE }
    enum class TunnelProtocol { SLIPSTREAM, S3FU, XRAY, TURNFU }
}

fun Config.needsVpnService(): Boolean =
    mode == Config.Mode.VPN || protocol != Config.TunnelProtocol.SLIPSTREAM

data class ConfigProfile(
    val id: String,
    val name: String,
    val config: Config,
    /**
     * Subscription this profile came from, or null for a hand-made one. This is the grouping key:
     * refreshing a subscription replaces exactly the profiles carrying its id and leaves the rest
     * untouched.
     */
    val subscriptionId: String? = null,
    /**
     * Sub-group inside that subscription's folder (see `SubscriptionCategory`), or null when the
     * panel does not group its servers. Only meaningful together with [subscriptionId].
     */
    val categoryId: String? = null
)

object DnsResolverPool {
    const val LOCAL_SENTINEL = "(local)"
    private const val LOCAL_SENTINEL_LEGACY = "(local dns-resolvers)"
    const val DEFAULT_RAW = LOCAL_SENTINEL
    private val RETIRED = setOf("82.151.127.188", "188.0.190.47", "185.22.235.137", "46.254.19.23")

    fun parse(raw: String): List<String> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()

    /**
     * Split a typed resolver field into hosts. The engine already runs several at once; this is
     * what lets one profile write `1.1.1.1, 8.8.8.8` or one-per-line and mean it.
     */
    fun parseManualHosts(raw: String): List<String> =
        raw.split(',', ';', ' ', '\t', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun isLocalSentinel(entry: String): Boolean =
        entry.equals(LOCAL_SENTINEL, ignoreCase = true) ||
            entry.equals(LOCAL_SENTINEL_LEGACY, ignoreCase = true)

    fun normalize(raw: String): String {
        val lines = raw.lineSequence()
            .filter { it.trim() !in RETIRED }
            .map { line ->
                val trimmed = line.trim()
                if (trimmed.equals(LOCAL_SENTINEL_LEGACY, ignoreCase = true)) LOCAL_SENTINEL else line
            }
            .toList()
        return if (lines.all { it.isBlank() }) DEFAULT_RAW else lines.joinToString("\n")
    }
}

data class GlobalSettings(
    val listenPort: Int,
    val mode: Config.Mode,
    val fileLogging: Boolean,
    val trafficNotification: Boolean,
    val localSocksAuthEnabled: Boolean,
    val localSocksUsername: String,
    val localSocksPassword: String,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val dnsResolverPool: String = DnsResolverPool.DEFAULT_RAW,
    /**
     * Where the "Home" folder sits among the tabs, once it exists. Subscriptions carry their
     * own order in the subscription list, but Home is not one of them and still has to be
     * draggable, so its slot lives here. Home itself is omitted until the user has a local
     * profile (imported or created).
     */
    val homeFolderIndex: Int = 0,
    /**
     * Folder that was open when the app was last used — a subscription id, or blank for Home.
     * Stored by identity rather than by tab number so reordering the tabs cannot land the user
     * in a different folder than the one they left.
     */
    val lastFolderId: String = "",
    /**
     * Categories the user folded away, as `subscriptionId/categoryId`. Kept across restarts: a
     * group the user closed should stay closed, exactly like the folder they left open.
     */
    val collapsedCategories: Set<String> = emptySet(),
    /**
     * Advertise the tunnel's own HTTP proxy to apps (Android 10+ `VpnService.setHttpProxy`).
     *
     * What it buys: a proxy-aware app hands the tunnel a **host name** instead of an address it
     * resolved first. Names that have no DNS behind them — `.onion`, `.i2p` — only reach the
     * routing rules that know what to do with them this way; through the TUN alone the browser
     * never gets past "cannot resolve" and the rules never see the name.
     *
     * Off by default: a system proxy changes how every proxy-aware app on the device connects,
     * and only the Xray tunnel has an HTTP inbound to point them at.
     */
    val appHttpProxy: Boolean = false,
    /**
     * Split tunnelling: whether [perAppPackages] is the list of apps that use the tunnel, the
     * list of apps that bypass it, or nothing at all. Applied to the TUN itself, so it holds
     * for every protocol — see [PerAppFilter].
     */
    val perAppMode: PerAppMode = PerAppMode.OFF,
    /** Packages the user picked. Meaning depends on [perAppMode]; ignored while it is OFF. */
    val perAppPackages: Set<String> = emptySet(),
    /**
     * Look for a newer release on start-up. On by default: the app ships as an APK on GitHub
     * rather than through a store, so nothing else would ever tell the user a fix exists.
     */
    val autoUpdate: Boolean = true,
    val tun: TunSettings = TunSettings(),
    val routing: RoutingSettings = RoutingSettings(),
    val autoConnectOnBoot: Boolean = false,
    val allowLan: Boolean = false,
    val rootMode: RootMode = RootMode.OFF,
    val rootHotspot: Boolean = false
) {
    companion object {
        const val DEFAULT_LOCAL_SOCKS_USERNAME = "smugly"
        const val LEGACY_LOCAL_SOCKS_USERNAME = "slipstream"
    }
}

data class TunSettings(
    val mtu: Int = DEFAULT_MTU,
    val ipv6: Boolean = true,
    val bypassLan: Boolean = false,
    val dnsServers: String = ""
) {
    companion object {
        const val DEFAULT_MTU = 1500
        const val MIN_MTU = 1280
        const val MAX_MTU = 9000
    }
}

data class RoutingSettings(
    val directRu: Boolean = false,
    val blockAds: Boolean = false
) {
    val isActive: Boolean get() = directRu || blockAds
}

/**
 * [GlobalSettings.collapsedCategories] as one stored string, and back. Ids are slugs and UUIDs, so
 * a newline can never appear inside one.
 */
object CollapsedCategories {
    fun encode(ids: Set<String>): String = ids.joinToString("\n")

    fun decode(raw: String?): Set<String> =
        raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

object ExtraSettingsCodec {
    private const val TUN_MTU = "tunMtu"
    private const val TUN_IPV6 = "tunIpv6"
    private const val TUN_BYPASS_LAN = "tunBypassLan"
    private const val TUN_DNS = "tunDnsServers"
    private const val ROUTE_DIRECT_RU = "routeDirectRu"
    private const val ROUTE_BLOCK_ADS = "routeBlockAds"
    private const val AUTO_CONNECT_ON_BOOT = "autoConnectOnBoot"
    private const val ALLOW_LAN = "allowLan"
    private const val ROOT_MODE = "rootMode"
    private const val ROOT_HOTSPOT = "rootHotspot"

    val keys: List<String> = listOf(
        TUN_MTU, TUN_IPV6, TUN_BYPASS_LAN, TUN_DNS,
        ROUTE_DIRECT_RU, ROUTE_BLOCK_ADS, AUTO_CONNECT_ON_BOOT, ALLOW_LAN,
        ROOT_MODE, ROOT_HOTSPOT
    )

    fun encode(s: GlobalSettings): Map<String, String> = mapOf(
        TUN_MTU to s.tun.mtu.toString(),
        TUN_IPV6 to s.tun.ipv6.toString(),
        TUN_BYPASS_LAN to s.tun.bypassLan.toString(),
        TUN_DNS to s.tun.dnsServers,
        ROUTE_DIRECT_RU to s.routing.directRu.toString(),
        ROUTE_BLOCK_ADS to s.routing.blockAds.toString(),
        AUTO_CONNECT_ON_BOOT to s.autoConnectOnBoot.toString(),
        ALLOW_LAN to s.allowLan.toString(),
        ROOT_MODE to s.rootMode.name.lowercase(),
        ROOT_HOTSPOT to s.rootHotspot.toString()
    )

    fun decode(base: GlobalSettings, read: (String) -> String?): GlobalSettings {
        val tunDefaults = TunSettings()
        val routingDefaults = RoutingSettings()
        fun bool(key: String, default: Boolean): Boolean =
            when (read(key)?.trim()?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> default
            }
        return base.copy(
            tun = TunSettings(
                mtu = read(TUN_MTU)?.trim()?.toIntOrNull()
                    ?.takeIf { it in TunSettings.MIN_MTU..TunSettings.MAX_MTU }
                    ?: tunDefaults.mtu,
                ipv6 = bool(TUN_IPV6, tunDefaults.ipv6),
                bypassLan = bool(TUN_BYPASS_LAN, tunDefaults.bypassLan),
                dnsServers = read(TUN_DNS) ?: tunDefaults.dnsServers
            ),
            routing = RoutingSettings(
                directRu = bool(ROUTE_DIRECT_RU, routingDefaults.directRu),
                blockAds = bool(ROUTE_BLOCK_ADS, routingDefaults.blockAds)
            ),
            autoConnectOnBoot = bool(AUTO_CONNECT_ON_BOOT, false),
            allowLan = bool(ALLOW_LAN, false),
            rootMode = RootMode.parse(read(ROOT_MODE)),
            rootHotspot = bool(ROOT_HOTSPOT, false)
        )
    }
}

fun GlobalSettings.needsReconnectToApply(next: GlobalSettings): Boolean =
    mode != next.mode ||
        listenPort != next.listenPort ||
        localSocksAuthEnabled != next.localSocksAuthEnabled ||
        localSocksUsername != next.localSocksUsername ||
        localSocksPassword != next.localSocksPassword ||
        dnsResolverPool != next.dnsResolverPool ||
        appHttpProxy != next.appHttpProxy ||
        tun != next.tun ||
        routing != next.routing ||
        allowLan != next.allowLan ||
        rootMode != next.rootMode ||
        rootHotspot != next.rootHotspot ||
        (rootMode.isActive && (perAppMode != next.perAppMode || perAppPackages != next.perAppPackages))

fun GlobalSettings.resetToDefaults(defaultMode: Config.Mode): GlobalSettings =
    GlobalSettings(
        listenPort = 1080,
        mode = defaultMode,
        fileLogging = false,
        trafficNotification = false,
        localSocksAuthEnabled = true,
        localSocksUsername = localSocksUsername,
        localSocksPassword = localSocksPassword,
        homeFolderIndex = homeFolderIndex,
        lastFolderId = lastFolderId,
        collapsedCategories = collapsedCategories,
        perAppMode = perAppMode,
        perAppPackages = perAppPackages
    )

enum class AppLanguage { SYSTEM, EN, RU }

/** Defaults used when creating a blank profile / CLI template. */
fun defaultConfig(
    listenPort: Int = 1080,
    mode: Config.Mode = Config.Mode.VPN
): Config = Config(
    domain = "",
    resolverHost = "",
    resolverPort = 53,
    resolverMode = Config.ResolverMode.MANUAL,
    resolverTransport = Config.ResolverTransport.TCP,
    resolverPathMode = Config.ResolverPathMode.AUTHORITATIVE,
    listenPort = listenPort,
    mode = mode,
    authMode = Config.AuthMode.NO_AUTH,
    username = "",
    password = ""
)
