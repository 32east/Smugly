package app.smugly.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import app.smugly.tunnel.HevSocks5Tunnel
import app.smugly.RootRouting
import app.smugly.tunnel.RootTunnel
import app.smugly.tunnel.TunnelStack
import app.smugly.tunnel.MiniSlipstreamSocksBridge
import app.smugly.tunnel.S3fuBridge
import app.smugly.tunnel.TurnfuProcess
import app.smugly.tunnel.ResolverListConfig
import app.smugly.tunnel.SlipstreamBridge
import app.smugly.tunnel.XrayBridge
import app.smugly.util.AppLog
import app.smugly.Config
import app.smugly.ConfigStore
import app.smugly.GlobalSettings
import app.smugly.PerAppFilter
import app.smugly.effectiveS3fuToml
import app.smugly.ComposeMainActivity
import app.smugly.R
import app.smugly.ResolverChoice
import app.smugly.ResolverSelector
import app.smugly.S
import app.smugly.TunPlan
import app.smugly.TunSettings
import app.smugly.TurnfuCall
import app.smugly.TurnfuCaptchaActivity
import app.smugly.TurnfuCaptchaHidden
import app.smugly.XrayConfigBuilder
import app.smugly.XrayRouting
import app.smugly.t

class TinyVpnService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var currentConfig: Config? = null
    @Volatile private var currentResolver: ResolverChoice? = null
    private val failedAutoResolvers = linkedSetOf<String>()
    @Volatile private var tunnelActive = false
    @Volatile private var recovering = false
    @Volatile private var stopping = false
    @Volatile private var starting = false
    @Volatile private var lifecycleGeneration = 0
    @Volatile private var destroyed = false
    @Volatile private var lastStartId = 0

    /**
     * Signalled when a stop's native cleanup has finished. A restart waits on this instead of
     * racing it — see [startTunnel].
     */
    @Volatile private var teardownDone: java.util.concurrent.CountDownLatch? = null
    @Volatile private var resolverOptimizerRunning = false
    @Volatile private var turnfuTunIp: String? = null
    @Volatile private var turnfuTunIp6: String? = null
    @Volatile private var turnfuUp = 0L
    @Volatile private var turnfuDown = 0L
    @Volatile private var turnfuActive = 0
    private var turnfuDiagAt = 0L
    private val turnfuRestarts = ArrayDeque<Long>()
    private var rxBase = 0L
    private var txBase = 0L
    private var notificationRxLast = 0L
    private var notificationTxLast = 0L
    private var notificationSampleAt = 0L
    private var notificationTextLast: String? = null
    private var lightDiagLoggedAt = 0L
    private var lightDiagRx = 0L
    private var lightDiagTx = 0L
    private var lightSampleAt = 0L
    private var lightSampleRx = 0L
    private var lightSampleTx = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTunRx = 0L
    private var lastTunTx = 0L
    private var lastBridgeRx = 0L
    private var lastBridgeTx = 0L
    private var lastBridgeFailures = 0L
    private val bridgeFailureWatch = BridgeFailureWatch(
        ACCUMULATED_FAILURE_RECOVERY_TOTAL,
        ACCUMULATED_FAILURE_RECOVERY_WINDOW_MS
    )
    private val stallRatioWatch = StallRatioWatch(
        STALL_RATIO_MIN_REQUEST_BYTES,
        STALL_RATIO_RESPONSE_DIVISOR,
        STALL_RATIO_RECOVERY_WINDOW_MS
    )
    private val detachedThreadWatch = DetachedThreadWatch(
        DETACHED_RESTART_THRESHOLD,
        DETACHED_RESTART_WINDOW_MS
    )
    @Volatile private var processRestartRequested = false
    private var lastProgressAt = 0L
    private var lastHeavyUploadAt = 0L
    private var slowResponseSince = 0L
    private var lowBandwidthSince = 0L
    private var lastTransportSwitchAt = 0L
    private var transportSwitchesThisEpisode = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkSignature = ""
    private var pendingNetworkRecheck: Runnable? = null
    private var readyFalseSince = 0L
    private var lastRecoveryAt = 0L
    private var recoveryCount = 0
    private var recoveryRetryPending: Runnable? = null
    private var recoveryRetryBackoffMs = RECOVERY_RETRY_BASE_MS
    @Volatile private var resolverHealthCheckRunning = false
    private var lastResolverHealthCheckAt = 0L
    private var resolverHealthFailures = 0
    private val diagnostics = object : Runnable {
        override fun run() {
            writeDiagnostics()
            handler.postDelayed(this, DIAGNOSTICS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        configureNativeLogging()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel(intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel(forceForeground: Boolean = false) {
        configureNativeLogging()
        if (starting || tunnelActive) {
            AppLog.w(TAG, "VPN start ignored starting=$starting active=$tunnelActive")
            return
        }
        starting = true
        if (forceForeground || ConfigStore.loadGlobalSettings(this).trafficNotification) {
            startForeground(1, notification(t(S.STATUS_STARTING), "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)"))
        }
        val pendingTeardown = teardownDone
        Thread({
            var generation = -1
            try {
                // A profile switch is stop-then-start, and the stop's native cleanup runs on its
                // own thread. Bumping lifecycleGeneration before that cleanup finishes makes the
                // cleanup skip itself ("newer lifecycle generation active"), leaving the previous
                // hev tunnel, TUN fd and engine alive underneath the new one — traffic trickles and
                // then dies, and only killing the process clears it. So: wait first, bump after.
                if (pendingTeardown != null && !pendingTeardown.await(TEARDOWN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    AppLog.w(TAG, "previous teardown still running after ${TEARDOWN_WAIT_MS}ms; starting anyway")
                }
                if (destroyed) {
                    AppLog.w(TAG, "VPN start dropped: service instance was destroyed")
                    return@Thread
                }
                generation = ++lifecycleGeneration
                stopping = false
                startTunnelWorker(generation)
            } finally {
                if (generation == -1 || lifecycleGeneration == generation) {
                    starting = false
                }
            }
        }, "vpn-start").start()
    }

    /**
     * Re-take the VPN slot before building a tunnel.
     *
     * `Builder.establish()` returns null — not an exception, just null — whenever this
     * package is not the *prepared* VPN app, and Android drops that state when the
     * package is replaced. The user's consent survives (it lives in the ACTIVATE_VPN
     * app-op), so `VpnService.prepare()` re-claims the slot silently and returns null;
     * only a genuinely unconsented app gets an Intent back, and that one does need the
     * activity's confirmation dialog.
     *
     * Doing this here rather than only in the connect flow is what lets the service
     * come back on its own after an app update — otherwise the tunnel is dead until
     * somebody opens the UI and taps Connect, with `establish returned null` as the
     * only clue (live 2026-08-29, after an `adb install -r` over a running tunnel).
     */
    private fun reclaimVpnConsent() {
        try {
            if (VpnService.prepare(this) != null) {
                AppLog.w(TAG, "VPN consent missing — the UI must ask for it before a tunnel can start")
            }
        } catch (e: Throwable) {
            AppLog.w(TAG, "VpnService.prepare failed: ${e.message}")
        }
    }

    private fun startTunnelWorker(generation: Int) {
        if (!ConfigStore.loadGlobalSettings(this).rootMode.isActive) reclaimVpnConsent()
        val rawConfig = ConfigStore.effectiveConfig(this)
        if (rawConfig.protocol == Config.TunnelProtocol.S3FU) {
            startS3fuTunnelWorker(generation, rawConfig)
            return
        }
        if (rawConfig.protocol == Config.TunnelProtocol.XRAY) {
            startXrayTunnelWorker(generation, rawConfig)
            return
        }
        if (rawConfig.protocol == Config.TunnelProtocol.TURNFU) {
            startTurnfuTunnelWorker(generation, rawConfig)
            return
        }
        val config = normalizeAutoConfig(rawConfig)
        currentConfig = config
        tunnelActive = true
        AppLog.i(TAG, "VPN start config=${config.copy(password = if (config.password.isBlank()) "" else "***")}")
        try {
            require(config.domain.isNotBlank()) { "domain is empty" }
            if (config.resolverMode == Config.ResolverMode.MANUAL) {
                require(config.resolverHost.isNotBlank()) { "resolver is empty" }
            }
            bindSocketProtector()
            SlipstreamBridge.dnsQueryType = config.dnsQueryType
            SlipstreamBridge.dnsLabelLength = config.dnsLabelLength
            SlipstreamBridge.dnsLabelLengthJitter = config.dnsLabelLengthJitter
            SlipstreamBridge.maxPollQps = config.maxPollQps
            SlipstreamBridge.maxDataQps = config.maxDataQps
            SlipstreamBridge.base64uEncoding = config.base64uEncoding
            resetTrafficBase()
            failedAutoResolvers.clear()
            var choice = ResolverSelector.chooseFast(this, config, "vpn_start")
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            if (ResolverSelector.shouldSkipTransportValidation(config.resolverMode, choice)) {
                // Fresh cache hit with a previously-validated transport+qtype: skip the full real-data
                // probe matrix (up to ~4 sequential 5 KB downloads, the "10-15s on every start" cost)
                // and reuse the known-good combo directly. If the network quietly changed underneath
                // it, startSlipstreamWithTransportFallback below still catches a hard not-ready within
                // START_READY_TIMEOUT_MS and retries the other transport, and the (now much faster)
                // resolver_silent/no-progress detector catches a stalled resolver within seconds --
                // so skipping the defensive re-probe here no longer trades away correctness the way it
                // would have before those fixes.
                SlipstreamBridge.dnsQueryType = choice.qtype
                AppLog.i(
                    TAG,
                    "transport/qtype validation skipped: fresh cache hit host=${choice.selectedHost} " +
                        "transport=${choice.transport.name.lowercase()} qtype=${choice.qtype} reason=vpn_start"
                )
            } else {
                // Validate the DNS carrier transport before committing (light-dns "auto" style): probe the
                // chosen resolver UDP-first with a real 5 KB download and keep UDP only if data actually
                // flows, else fall back to TCP. This catches "UDP handshakes but is throttled" (e.g. Tele2),
                // which the ready check and the cached/guessed transport cannot.
                choice = ResolverSelector.validateTransport(this, config, choice, "vpn_start")
            }
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            currentResolver = choice
            val bridgePort = config.listenPort
            var slipstreamPort = config.listenPort + 1
            val localSocks = localSocksCredentials()
            val startResult = startSlipstreamWithTransportFallback(config, choice, slipstreamPort, generation)
            choice = startResult.first
            slipstreamPort = startResult.second
            currentResolver = choice
            ResolverSelector.lastConnectedTransport = choice.transport
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            MiniSlipstreamSocksBridge.start(
                listenHost = proxyListenHost(),
                listenPort = bridgePort,
                slipstreamHost = "127.0.0.1",
                slipstreamPort = slipstreamPort,
                dnsHost = choice.selectedHost,
                username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
                localUsername = localSocks.first,
                localPassword = localSocks.second,
                maxActiveClients = config.maxActiveClients
            ).getOrThrow()
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            val global = ConfigStore.loadGlobalSettings(this)
            bringUpTun(
                global = global,
                dnsServers = TunPlan.dnsServers(global.tun.dnsServers),
                socksPort = bridgePort,
                username = localSocks.first,
                password = localSocks.second,
                udpMode = "tcp",
                mapDns = false
            )
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            maybeUpdateTrafficNotification(0, 0, force = true)
            AppLog.i(
                TAG,
                "VPN connected resolver=${choice.selectedHost}:${choice.port} source=${choice.source} " +
                    "transport=${choice.transport.name.lowercase()} " +
                    "pathMode=${config.resolverPathMode.name.lowercase()} " +
                    "qnameMtu=${if (choice.qnameMtu > 0) choice.qnameMtu else "max"} " +
                    "tested=${choice.testedCount} alive=${choice.aliveCount} skipped=${choice.skippedCount}"
            )
            handler.removeCallbacks(diagnostics)
            handler.post(diagnostics)
            startBackgroundResolverOptimization(config, choice)
            registerNetworkChangeWatch()
        } catch (e: Throwable) {
            AppLog.e(TAG, "VPN start failed", e)
            lastFailureAt = SystemClock.elapsedRealtime()
            runCatching { TunnelStack.stop(this) }
            runCatching { MiniSlipstreamSocksBridge.stop() }
            runCatching { SlipstreamBridge.stopClient() }
            runCatching { tunFd?.close() }
            tunFd = null
            SlipstreamBridge.setVpnService(null)
            tunnelActive = false
            stopTunnel()
        }
    }

    /**
     * S3FU protocol path: no DNS resolver machinery. Start the native s3fu SOCKS5
     * client, then layer the existing tun2socks (hev) onto it exactly as the DNS
     * path does. The app already excludes its own package from the VPN, so the
     * client's TLS to the S3 endpoint travels outside the tunnel unaided.
     */
    private fun startS3fuTunnelWorker(generation: Int, config: Config) {
        currentConfig = config
        tunnelActive = true
        AppLog.i(
            TAG,
            "S3FU start endpoint=${config.s3Endpoint} bucket=${config.s3Bucket} " +
                "prefix=${config.s3Prefix}"
        )
        try {
            require(config.s3Endpoint.isNotBlank()) { "S3 endpoint is empty" }
            require(config.s3Bucket.isNotBlank()) { "S3 bucket is empty" }
            require(config.s3AccessKey.isNotBlank()) { "S3 access key is empty" }
            require(config.s3SecretKey.isNotBlank()) { "S3 secret key is empty" }
            require(config.s3Psk.isNotBlank()) { "PSK is empty" }
            resetTrafficBase()

            val socksPort = config.listenPort
            val localSocks = localSocksCredentials()
            val caPath = ensureCaBundle()
            // The profile carries the whole config file; a profile from an older build
            // has none, and effectiveS3fuToml() derives one from its fields.
            S3fuBridge.startClient(
                configToml = config.effectiveS3fuToml(),
                socksListen = "${proxyListenHost()}:$socksPort",
                caFile = caPath,
                username = localSocks.first,
                password = localSocks.second
            ).getOrThrow()
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            // The SOCKS listener binds asynchronously inside the native runtime; wait for the
            // port to accept before wiring the TUN, else the first flows race the bind.
            if (!waitForLocalPort(socksPort, S3FU_SOCKS_READY_TIMEOUT_MS) { S3fuBridge.isRunning() }) {
                error("s3fu SOCKS proxy did not come up on 127.0.0.1:$socksPort")
            }
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            // Raw DNS UDP to 1.1.1.1 through S3 is lossy (Telegram
            // has IPs cached, browsers do not) so the tab never resolves.
            // s3fu implements standard SOCKS5 UDP ASSOCIATE (no auth) -> hev udp='udp'.
            // mapdns restores hostnames on CONNECT; QUIC is rejected in hev.
            bringUpTun(
                global = ConfigStore.loadGlobalSettings(this),
                dnsServers = listOf(HevSocks5Tunnel.MAPDNS_ADDRESS),
                socksPort = socksPort,
                username = localSocks.first,
                password = localSocks.second,
                udpMode = "udp",
                mapDns = true
            )
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            maybeUpdateTrafficNotification(0, 0, force = true)
            AppLog.i(TAG, "S3FU VPN connected socks=127.0.0.1:$socksPort")
            handler.removeCallbacks(diagnostics)
            handler.post(diagnostics)
        } catch (e: Throwable) {
            AppLog.e(TAG, "S3FU VPN start failed", e)
            lastFailureAt = SystemClock.elapsedRealtime()
            runCatching { TunnelStack.stop(this) }
            runCatching { S3fuBridge.stopClient() }
            runCatching { tunFd?.close() }
            tunFd = null
            tunnelActive = false
            stopTunnel()
        }
    }


    /**
     * XRAY protocol path: the profile is a literal Xray JSON config. Start the
     * core, then layer the existing tun2socks (hev) onto the SOCKS inbound it
     * exposes -- structurally identical to the S3FU path. The app already excludes
     * its own package from the VPN, so Xray's outbound connections travel outside
     * the tunnel without needing per-socket protect().
     */
    private fun startXrayTunnelWorker(generation: Int, config: Config) {
        currentConfig = config
        tunnelActive = true
        try {
            require(config.xrayConfigJson.isNotBlank()) { "Xray config is empty" }
            XrayBridge.init(this)
            require(XrayBridge.isLoaded()) { "libxray failed to load" }
            // Already a worker thread, so it is safe to wait here: a config with geoip: /
            // geosite: routing rules will not even parse until those files are on disk.
            XrayBridge.ensureGeoAssets(this)
            resetTrafficBase()

            // The stored JSON may have been written when the local port was something
            // else (or pasted from elsewhere with an inbound we can't reach), so pin
            // the SOCKS inbound to the port the TUN bridge is about to dial.
            val socksPort = config.listenPort
            // The HTTP proxy apps are handed sits one port up, the way every other client lays it
            // out (10808 SOCKS / 10809 HTTP). Only added when it is actually going to be
            // advertised — an inbound nobody dials is just an open port.
            val global = ConfigStore.loadGlobalSettings(this)
            val appProxy = global.appHttpProxy && !global.rootMode.isActive &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val listen = XrayConfigBuilder.listenAddress(global.allowLan)
            // A config that already publishes an HTTP inbound keeps its own port — that one is
            // the user's decision and may be written down elsewhere. Only a config without one
            // gets ours, one above the SOCKS port the way every other client lays it out.
            val httpPort = XrayConfigBuilder.httpPortOf(config.xrayConfigJson) ?: (socksPort + 1)
            val localSocks = localSocksCredentials()
            val configJson = XrayConfigBuilder.withSocksPort(
                config.xrayConfigJson,
                socksPort,
                listen,
                localSocks.first,
                localSocks.second
            )
                .let { if (appProxy) XrayConfigBuilder.withHttpPort(it, httpPort, listen) else it }
                .let { XrayRouting.apply(it, global.routing) }
            AppLog.i(
                TAG,
                "XRAY start server=${XrayConfigBuilder.describeServer(configJson) ?: "?"} " +
                    "socks=$listen:$socksPort core=${XrayBridge.version() ?: "?"} " +
                    "directRu=${global.routing.directRu} blockAds=${global.routing.blockAds}"
            )

            XrayBridge.startClient(configJson).getOrThrow()
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            // Xray binds its inbounds asynchronously; wait for the listener before
            // wiring the TUN, else the first flows race the bind.
            if (!waitForLocalPort(socksPort, XRAY_SOCKS_READY_TIMEOUT_MS) { XrayBridge.isRunning() }) {
                error(
                    XrayBridge.lastError()
                        ?: "Xray SOCKS inbound did not come up on 127.0.0.1:$socksPort"
                )
            }
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            bringUpTun(
                global = global,
                dnsServers = TunPlan.dnsServers(global.tun.dnsServers),
                socksPort = socksPort,
                username = localSocks.first,
                password = localSocks.second,
                udpMode = "udp",
                mapDns = false
            ) { builder ->
                if (appProxy) {
                    // Proxy-aware apps now send `CONNECT host:port` instead of dialling an address
                    // they resolved themselves, so the host name survives all the way to the routing
                    // rules. That is what makes `.onion` / `.i2p` reachable: nothing resolves those,
                    // so through the TUN alone the browser never even opens a connection.
                    runCatching {
                        builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", httpPort))
                        AppLog.i(TAG, "XRAY app http proxy 127.0.0.1:$httpPort")
                    }.onFailure { AppLog.w(TAG, "setHttpProxy failed: ${it.message}") }
                }
            }

            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            maybeUpdateTrafficNotification(0, 0, force = true)
            AppLog.i(TAG, "XRAY VPN connected socks=127.0.0.1:$socksPort")
            handler.removeCallbacks(diagnostics)
            handler.post(diagnostics)
        } catch (e: Throwable) {
            AppLog.e(TAG, "XRAY VPN start failed", e)
            lastFailureAt = SystemClock.elapsedRealtime()
            runCatching { TunnelStack.stop(this) }
            runCatching { XrayBridge.stopClient() }
            runCatching { tunFd?.close() }
            tunFd = null
            tunnelActive = false
            stopTunnel()
        }
    }

    private fun startTurnfuTunnelWorker(generation: Int, config: Config) {
        currentConfig = config
        tunnelActive = true
        val callLink = config.turnfuCalls.joinToString(",").ifBlank { if (config.turnfuToken.isEmpty()) ConfigStore.turnfuCallLink(this) else "" }
        AppLog.i(
            TAG,
            "TURNFU start peer=${config.turnfuHost}:${config.turnfuPort} workers=${config.turnfuWorkers} " +
                "mtu=${config.turnfuMtu} call=${TurnfuCall.hash(callLink)?.take(6) ?: "none"}"
        )
        try {
            require(config.turnfuHost.isNotBlank()) { "server is empty" }
            require(config.turnfuPassword.isNotBlank()) { "password is empty" }
            require(callLink.isNotBlank() || config.turnfuToken.isNotEmpty()) { "VK call link is empty" }
            require(TurnfuProcess.isAvailable(this)) { "turnfu engine is missing" }
            resetTrafficBase()
            turnfuTunIp = null
            turnfuTunIp6 = null
            turnfuUp = 0
            turnfuDown = 0
            turnfuActive = 0
            turnfuDiagAt = 0
            synchronized(turnfuRestarts) { turnfuRestarts.clear() }
            val configured = java.util.concurrent.CountDownLatch(1)
            val first = java.util.concurrent.atomic.AtomicReference<TurnfuProcess.TunConfig?>(null)
            startTurnfuEngine(generation, config, callLink) { cfg ->
                if (first.compareAndSet(null, cfg)) {
                    configured.countDown()
                    true
                } else {
                    false
                }
            }.getOrThrow()
            val deadline = SystemClock.elapsedRealtime() + TURNFU_CONFIG_TIMEOUT_MS
            while (!configured.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
                if (!TurnfuProcess.isRunning()) error(TurnfuProcess.lastError() ?: "turnfu engine exited")
                if (SystemClock.elapsedRealtime() > deadline) error("the server sent no tunnel config")
            }
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            attachTurnfu(config, first.get() ?: error("no tunnel config"))
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            maybeUpdateTrafficNotification(0, 0, force = true)
            AppLog.i(TAG, "TURNFU VPN connected ip=$turnfuTunIp root=${RootTunnel.ownsTunnel()}")
            handler.removeCallbacks(diagnostics)
            handler.post(diagnostics)
            registerNetworkChangeWatch()
        } catch (e: Throwable) {
            AppLog.e(TAG, "TURNFU VPN start failed", e)
            lastFailureAt = SystemClock.elapsedRealtime()
            runCatching { TurnfuProcess.stop() }
            runCatching { tunFd?.close() }
            tunFd = null
            turnfuTunIp = null
            turnfuTunIp6 = null
            tunnelActive = false
            stopTunnel()
        }
    }

    private fun startTurnfuEngine(
        generation: Int,
        config: Config,
        callLink: String,
        onFirstConfig: (TurnfuProcess.TunConfig) -> Boolean
    ): Result<Unit> {
        val request = TurnfuProcess.Request(
            host = config.turnfuHost.trim(),
            port = config.turnfuPort.coerceIn(1, 65535),
            password = config.turnfuPassword.trim(),
            callLink = TurnfuCall.url(callLink) ?: callLink,
            workers = config.turnfuWorkers.coerceIn(5, 100),
            netHandle = TurnfuProcess.physicalNetworkHandle(this),
            accountToken = config.turnfuToken
        )
        val listener = object : TurnfuProcess.Listener {
            private fun current() = !destroyed && tunnelActive && lifecycleGeneration == generation &&
                currentConfig?.protocol == Config.TunnelProtocol.TURNFU

            override fun onConfig(config: TurnfuProcess.TunConfig) {
                if (onFirstConfig(config) || !current()) return
                Thread({ reattachTurnfuTun(generation, config) }, "turnfu-reattach").start()
            }

            override fun onStats(active: Int, up: Long, down: Long) {
                turnfuActive = active
                turnfuUp = up
                turnfuDown = down
            }

            override fun onCaptcha(uri: String) {
                if (current()) handler.post { showTurnfuCaptcha(uri) }
            }

            override fun onCaptchaAuto(uri: String) {
                if (current()) TurnfuCaptchaHidden.start(this@TinyVpnService, uri) else TurnfuProcess.submitCaptcha("error:inactive")
            }

            override fun onError(code: String, message: String, fatal: Boolean) {
                if (fatal) AppLog.w(TAG, "turnfu fatal error $code: $message")
            }

            override fun onExit(code: Int) {
                if (!current()) return
                AppLog.w(TAG, "turnfu engine exited code=$code while the tunnel was up")
                restartTurnfuEngine(generation, config, callLink)
            }
        }
        return TurnfuProcess.start(this, request, listener)
    }

    private fun restartTurnfuEngine(generation: Int, config: Config, callLink: String) {
        val now = SystemClock.elapsedRealtime()
        val allowed = synchronized(turnfuRestarts) {
            while (turnfuRestarts.isNotEmpty() && now - turnfuRestarts.first() > TURNFU_RESTART_WINDOW_MS) {
                turnfuRestarts.removeFirst()
            }
            if (turnfuRestarts.size >= TURNFU_MAX_RESTARTS) {
                false
            } else {
                turnfuRestarts.addLast(now)
                true
            }
        }
        if (!allowed) {
            AppLog.e(TAG, "turnfu engine keeps exiting; stopping the tunnel")
            handler.post { stopTunnel() }
            return
        }
        Thread({
            Thread.sleep(TURNFU_RESTART_DELAY_MS)
            if (destroyed || !tunnelActive || lifecycleGeneration != generation) return@Thread
            AppLog.i(TAG, "turnfu engine restart #${synchronized(turnfuRestarts) { turnfuRestarts.size }}")
            startTurnfuEngine(generation, config, callLink) { false }
                .onFailure {
                    AppLog.e(TAG, "turnfu engine restart failed", it)
                    handler.post { stopTunnel() }
                }
        }, "turnfu-restart").start()
    }

    private fun reattachTurnfuTun(generation: Int, cfg: TurnfuProcess.TunConfig) {
        if (!tunnelActive || lifecycleGeneration != generation) return
        if (RootTunnel.ownsTunnel()) {
            AppLog.i(TAG, "turnfu engine asked for the TUN again ip=${cfg.ip} (root)")
            runCatching { attachTurnfu(currentConfig ?: return, cfg) }
                .onFailure { AppLog.e(TAG, "turnfu root re-attach failed", it) }
            return
        }
        val existing = tunFd
        val sent = if (existing != null && cfg.ip == turnfuTunIp && cfg.ip6 == turnfuTunIp6) {
            AppLog.i(TAG, "turnfu engine asked for the TUN again ip=${cfg.ip}")
            TurnfuProcess.sendTunFd(existing)
        } else {
            AppLog.i(TAG, "turnfu tunnel address changed ${turnfuTunIp ?: "-"} ${turnfuTunIp6 ?: "-"} -> ${cfg.ip} ${cfg.ip6 ?: "-"}")
            runCatching { attachTurnfuTun(currentConfig ?: return, cfg) }
                .onFailure { AppLog.e(TAG, "turnfu TUN re-attach failed", it) }
                .isSuccess
        }
        if (!sent) AppLog.w(TAG, "turnfu TUN hand-over failed after reconfig")
    }

    private fun attachTurnfu(config: Config, cfg: TurnfuProcess.TunConfig) {
        if (ConfigStore.loadGlobalSettings(this).rootMode.isActive) attachTurnfuRoot(config, cfg) else attachTurnfuTun(config, cfg)
    }

    private fun attachTurnfuRoot(config: Config, cfg: TurnfuProcess.TunConfig) {
        val global = ConfigStore.loadGlobalSettings(this)
        val dns = if (global.tun.dnsServers.isBlank() && cfg.dns.isNotEmpty()) cfg.dns else TunPlan.dnsServers(global.tun.dnsServers)
        val attach = TurnfuProcess.attachTunCommand(this, RootRouting.IFACE) ?: error("turnfu engine is not running")
        val ip6 = cfg.ip6?.takeIf { global.tun.ipv6 }
        val mtu = config.turnfuMtu.coerceIn(576, 1500)
        val linkMtu = turnfuLinkMtu(mtu, ip6)
        RootTunnel.start(
            this,
            RootTunnel.Request(
                socksPort = 0,
                username = null,
                password = null,
                udpMode = "udp",
                mapDns = false,
                tun = global.tun.copy(ipv6 = ip6 != null),
                dnsServers = dns,
                hotspot = global.rootHotspot,
                perAppMode = global.perAppMode,
                perAppPackages = global.perAppPackages,
                attachCommand = attach,
                tunAddressV4 = cfg.ip,
                tunAddressV6 = ip6,
                mtu = linkMtu,
                routeMtuV4 = mtu.takeIf { it < linkMtu }
            )
        ).exceptionOrNull()?.let {
            handler.post { Toast.makeText(this, t(S.TOAST_ROOT_START_FAILED), Toast.LENGTH_LONG).show() }
            throw it
        }
        TurnfuProcess.markTunAttached()
        turnfuTunIp = cfg.ip
        turnfuTunIp6 = ip6
        AppLog.i(TAG, "turnfu root tun ip=${cfg.ip} ip6=${ip6 ?: "-"} mtu=$linkMtu v4RouteMtu=$mtu")
    }

    private fun turnfuLinkMtu(mtu: Int, ip6: String?): Int =
        if (ip6 != null) mtu.coerceAtLeast(TunSettings.MIN_MTU) else mtu

    private fun attachTurnfuTun(config: Config, cfg: TurnfuProcess.TunConfig) {
        val global = ConfigStore.loadGlobalSettings(this)
        val dns = if (global.tun.dnsServers.isBlank() && cfg.dns.isNotEmpty()) cfg.dns else TunPlan.dnsServers(global.tun.dnsServers)
        val previous = tunFd
        val ip6 = cfg.ip6?.takeIf { global.tun.ipv6 }
        val fd = establishTurnfuTun(global.tun, cfg.ip, ip6, dns, config.turnfuMtu.coerceIn(576, 1500))
        tunFd = fd
        turnfuTunIp = cfg.ip
        turnfuTunIp6 = ip6
        if (!TurnfuProcess.sendTunFd(fd)) error(TurnfuProcess.lastError() ?: "the turnfu engine did not take the TUN")
        if (previous != null && previous !== fd) runCatching { previous.close() }
    }

    private fun establishTurnfuTun(tun: TunSettings, ip: String, ip6: String?, dnsServers: List<String>, mtu: Int): ParcelFileDescriptor {
        val v4 = TunPlan.ipv4Routes(tun)
        val v6 = TunPlan.ipv6Routes(tun)
        val linkMtu = turnfuLinkMtu(mtu, ip6)
        val builder = Builder()
            .setSession(t(S.APP_NAME))
            .setMtu(linkMtu)
            .addAddress(ip, 32)
        if (ip6 != null) builder.addAddress(ip6, 128)
        v4.forEach { builder.addRoute(it.address, it.prefix) }
        if (v6.isNotEmpty()) v6.forEach { builder.addRoute(it.address, it.prefix) } else builder.addRoute("::", 0)
        var dnsAdded = 0
        dnsServers.filter { it.contains('.') }.forEach { dns ->
            runCatching { builder.addDnsServer(dns) }
                .onSuccess { dnsAdded++ }
                .onFailure { AppLog.w(TAG, "addDnsServer($dns) failed: ${it.message}") }
        }
        if (dnsAdded == 0) TunPlan.DEFAULT_DNS.forEach { builder.addDnsServer(it) }
        applyAppFilter(builder, includeSelf = true)
        if (ConfigStore.loadGlobalSettings(this).appHttpProxy && Build.VERSION.SDK_INT >= 29) {
            runCatching {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(TurnfuProcess.GATEWAY, TurnfuProcess.HTTP_PROXY_PORT))
            }.onFailure { AppLog.w(TAG, "turnfu setHttpProxy failed: ${it.message}") }
        }
        AppLog.i(TAG, "turnfu tun ip=$ip ip6=${ip6 ?: "-"} mtu=$linkMtu v4Routes=${v4.size} v6Routes=${v6.size} dns=${dnsServers.joinToString(",")}")
        return builder.establish() ?: error("VpnService.Builder.establish returned null")
    }

    private fun showTurnfuCaptcha(uri: String) {
        val intent = Intent(this, TurnfuCaptchaActivity::class.java)
            .putExtra(TurnfuCaptchaActivity.EXTRA_URI, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure { AppLog.w(TAG, "captcha activity start failed: ${it.message}") }
        val pi = PendingIntent.getActivity(
            this,
            TURNFU_CAPTCHA_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(t(S.TURNFU_CAPTCHA_TITLE))
            .setContentText(t(S.TURNFU_CAPTCHA_NOTIFICATION))
            .setSmallIcon(R.drawable.ic_stat_smugly)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(TURNFU_CAPTCHA_TIMEOUT_MS)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(TURNFU_CAPTCHA_NOTIFICATION_ID, notification)
        }
    }

    private fun writeTurnfuDiagnostics() {
        maybeUpdateTrafficNotification(turnfuDown, turnfuUp)
        val running = TurnfuProcess.isRunning()
        val now = SystemClock.elapsedRealtime()
        val interval = if (isScreenInteractive()) LIGHT_DIAG_ACTIVE_INTERVAL_MS else LIGHT_DIAG_SCREEN_OFF_INTERVAL_MS
        if (turnfuDiagAt == 0L || now - turnfuDiagAt >= interval) {
            turnfuDiagAt = now
            AppLog.i(
                TAG,
                "diag[turnfu] running=$running ip=${turnfuTunIp ?: "-"} streams=$turnfuActive " +
                    "total down=${formatBytes(turnfuDown)} up=${formatBytes(turnfuUp)}"
            )
        }
    }

    /** Lightweight diagnostics for the XRAY path (no DNS/resolver/bridge machinery). */
    private fun writeXrayDiagnostics() {
        val hev = TunnelStack.stats()
        maybeUpdateTrafficNotification(hev.rxBytes, hev.txBytes)
        val running = XrayBridge.isRunning()
        lightDiagnosticsLine("xray", running, hev)?.let { line ->
            // queryStats RESETS the counters, so these are per-interval deltas.
            val up = XrayBridge.queryStats(XrayConfigBuilder.PROXY_TAG, XrayBridge.UPLINK)
            val down = XrayBridge.queryStats(XrayConfigBuilder.PROXY_TAG, XrayBridge.DOWNLINK)
            AppLog.i(TAG, "$line proxyUp=${formatBytes(up)} proxyDown=${formatBytes(down)}")
        }
        if (!running && tunnelActive) {
            AppLog.w(TAG, "xray core stopped while tunnel active; lastError=${XrayBridge.lastError() ?: "none"}")
        }
    }

    /**
     * Poll-connect to a local TCP port until it accepts or the timeout elapses.
     * [isAlive] reports whether the native side that should be opening the port is
     * still up, so a crashed engine aborts the wait instead of burning the timeout.
     */
    /**
     * Apply split tunnelling to a TUN that is about to be established.
     *
     * Every protocol path calls this instead of excluding our own package by hand, which is why
     * the feature is protocol-agnostic: the filter lives on the TUN, not in any engine, so
     * Slipstream, S3FU and Xray all get it from the one call. [PerAppFilter] decides
     * which of the two mutually exclusive lists to use — mixing them makes `establish()` throw.
     *
     * Each package is added on its own: `addAllowed/DisallowedApplication` throws
     * `NameNotFoundException` for a package that has since been uninstalled, and one stale entry
     * in a saved list must not take the whole tunnel down with it.
     */
    private fun proxyListenHost(): String =
        XrayConfigBuilder.listenAddress(ConfigStore.loadGlobalSettings(this).allowLan)

    private fun establishTun(
        tun: TunSettings,
        dnsServers: List<String>,
        extra: (Builder) -> Unit = {}
    ): ParcelFileDescriptor {
        val v4 = TunPlan.ipv4Routes(tun)
        val v6 = TunPlan.ipv6Routes(tun)
        val builder = Builder()
            .setSession(t(S.APP_NAME))
            .setMtu(TunPlan.mtu(tun))
            .addAddress("10.255.0.2", 32)
        if (v6.isNotEmpty()) builder.addAddress("fd00::2", 128)
        v4.forEach { builder.addRoute(it.address, it.prefix) }
        v6.forEach { builder.addRoute(it.address, it.prefix) }
        var dnsAdded = 0
        dnsServers.forEach { dns ->
            if (!tun.ipv6 && !dns.contains('.')) return@forEach
            runCatching { builder.addDnsServer(dns) }
                .onSuccess { dnsAdded++ }
                .onFailure { AppLog.w(TAG, "addDnsServer($dns) failed: ${it.message}") }
        }
        if (dnsAdded == 0) TunPlan.DEFAULT_DNS.forEach { builder.addDnsServer(it) }
        applyAppFilter(builder)
        extra(builder)
        AppLog.i(
            TAG,
            "tun mtu=${TunPlan.mtu(tun)} ipv6=${tun.ipv6} bypassLan=${tun.bypassLan} " +
                "v4Routes=${v4.size} v6Routes=${v6.size} dns=${dnsServers.joinToString(",")}"
        )
        return builder.establish() ?: error("VpnService.Builder.establish returned null")
    }

    private fun bindSocketProtector() {
        val root = ConfigStore.loadGlobalSettings(this).rootMode.isActive
        SlipstreamBridge.setVpnService(if (root) null else this)
        SlipstreamBridge.proxyOnlyMode = root
    }

    private fun bringUpTun(
        global: GlobalSettings,
        dnsServers: List<String>,
        socksPort: Int,
        username: String?,
        password: String?,
        udpMode: String,
        mapDns: Boolean,
        extra: (Builder) -> Unit = {}
    ) {
        if (global.rootMode.isActive) {
            val started = RootTunnel.start(
                this,
                RootTunnel.Request(
                    socksPort = socksPort,
                    username = username,
                    password = password,
                    udpMode = udpMode,
                    mapDns = mapDns,
                    tun = global.tun,
                    dnsServers = dnsServers,
                    hotspot = global.rootHotspot,
                    perAppMode = global.perAppMode,
                    perAppPackages = global.perAppPackages
                )
            )
            started.exceptionOrNull()?.let {
                handler.post { Toast.makeText(this, t(S.TOAST_ROOT_START_FAILED), Toast.LENGTH_LONG).show() }
                throw it
            }
            return
        }
        tunFd = establishTun(global.tun, dnsServers, extra)
        HevSocks5Tunnel.start(
            tunFd = tunFd ?: error("TUN fd is null"),
            socksAddress = "127.0.0.1",
            socksPort = socksPort,
            username = username,
            password = password,
            udpMode = udpMode,
            mapDns = mapDns,
            mtu = TunPlan.mtu(global.tun)
        ).getOrThrow()
        if (global.rootHotspot) shareThroughHotspot()
    }

    private fun shareThroughHotspot() {
        val iface = systemVpnInterface()
        if (iface == null) {
            AppLog.w(TAG, "hotspot sharing skipped: no VPN interface found")
            return
        }
        RootTunnel.startHotspotOnly(this, iface).exceptionOrNull()?.let {
            AppLog.w(TAG, "hotspot sharing failed: ${it.message}")
            handler.post { Toast.makeText(this, t(S.TOAST_ROOT_START_FAILED), Toast.LENGTH_LONG).show() }
        }
    }

    private fun systemVpnInterface(): String? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        repeat(30) {
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val vpn = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (vpn) cm.getLinkProperties(network)?.interfaceName?.let { return it }
            }
            Thread.sleep(100)
        }
        return null
    }

    private fun applyAppFilter(builder: Builder, includeSelf: Boolean = false) {
        val settings = ConfigStore.loadGlobalSettings(this)
        val plan = PerAppFilter.plan(settings.perAppMode, settings.perAppPackages, packageName, includeSelf)
        if (plan.fellBackToOff) {
            AppLog.w(
                TAG,
                "per-app mode=${settings.perAppMode} has no packages selected; " +
                    "routing all apps rather than establishing a TUN nothing uses"
            )
        }
        var applied = 0
        var missing = 0
        plan.allowed.forEach { pkg ->
            runCatching { builder.addAllowedApplication(pkg) }
                .onSuccess { applied++ }
                .onFailure {
                    missing++
                    AppLog.w(TAG, "addAllowedApplication($pkg) failed: ${it.message}")
                }
        }
        plan.disallowed.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
                .onSuccess { applied++ }
                .onFailure {
                    missing++
                    AppLog.w(TAG, "addDisallowedApplication($pkg) failed: ${it.message}")
                }
        }
        AppLog.i(
            TAG,
            "per-app filter mode=${settings.perAppMode} " +
                "allowed=${plan.allowed.size} disallowed=${plan.disallowed.size} " +
                "applied=$applied missing=$missing"
        )
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Long, isAlive: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!tunnelActive) return false
            val ok = runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    true
                }
            }.getOrDefault(false)
            if (ok) return true
            if (!isAlive()) return false // native side died -> stop waiting
            Thread.sleep(100)
        }
        return false
    }

    /** Copy the bundled CA bundle (assets/cacert.pem) into files/ once; return its path. */
    private fun ensureCaBundle(): String {
        val out = java.io.File(filesDir, "cacert.pem")
        if (!out.exists() || out.length() == 0L) {
            runCatching {
                assets.open("cacert.pem").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { AppLog.e(TAG, "cacert.pem copy failed", it) }
        }
        return if (out.exists()) out.absolutePath else ""
    }

    /** Lightweight diagnostics for the S3FU path (no DNS/resolver/bridge machinery). */
    private fun writeS3fuDiagnostics() {
        val hev = TunnelStack.stats()
        maybeUpdateTrafficNotification(hev.rxBytes, hev.txBytes)
        val running = S3fuBridge.isRunning()
        lightDiagnosticsLine("s3fu", running, hev)?.let { AppLog.i(TAG, it) }
        if (!running && tunnelActive) {
            AppLog.w(TAG, "s3fu client stopped while tunnel active; lastError=${S3fuBridge.lastError() ?: "none"}")
        }
    }


    private fun startSlipstreamWithTransportFallback(
        config: Config,
        choice: ResolverChoice,
        slipstreamPort: Int,
        generation: Int
    ): Pair<ResolverChoice, Int> {
        var port = startSlipstreamClientEscapingWedge(config, choice, slipstreamPort, "vpn_start")
        if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
        // config.dnsQueryType (not choice.qtype, which stays 0/unset in MANUAL mode -- see
        // ResolverSelector.chooseFast) is the actual qtype SlipstreamBridge gets configured with.
        val readyTimeout = readyTimeoutMs(START_READY_TIMEOUT_MS, config.dnsQueryType)
        if (waitForSlipstreamReady(readyTimeout)) {
            if (config.resolverMode == Config.ResolverMode.AUTO && choice.selectedHost.isNotBlank()) {
                ResolverSelector.rememberTransport(this, config, choice.selectedHost, choice.transport)
            }
            return choice to port
        }
        if (config.resolverMode != Config.ResolverMode.AUTO) {
            error("slipstream not ready after ${readyTimeout}ms resolver=${choice.selectedHost}:${choice.port}")
        }
        val altTransport = if (choice.transport == Config.ResolverTransport.UDP) {
            Config.ResolverTransport.TCP
        } else {
            Config.ResolverTransport.UDP
        }
        AppLog.w(
            TAG,
            "slipstream not ready with transport=${choice.transport.name.lowercase()}; retrying with " +
                "transport=${altTransport.name.lowercase()} resolver=${choice.selectedHost}:${choice.port}"
        )
        runCatching { SlipstreamBridge.stopClient() }
        if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
        val altChoice = choice.copy(transport = altTransport)
        port = startSlipstreamClientEscapingWedge(config, altChoice, port, "vpn_start:fallback")
        if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
        require(waitForSlipstreamReady(readyTimeoutMs(START_READY_TIMEOUT_MS, config.dnsQueryType))) {
            "slipstream not ready after transport fallback resolver=${altChoice.selectedHost}:${altChoice.port} " +
                "triedTransports=${choice.transport.name.lowercase()},${altTransport.name.lowercase()}"
        }
        // The cached decision was wrong for this network — record the transport that actually came
        // up, but drop its "measured" mark so the next start runs the real probe matrix again
        // instead of trusting a guess for another day.
        ResolverSelector.rememberTransport(
            this, config, altChoice.selectedHost, altTransport, resetValidation = true
        )
        return altChoice to port
    }

    private fun normalizeAutoConfig(config: Config): Config {
        if (config.resolverMode != Config.ResolverMode.AUTO) return config
        val local = ResolverSelector.preferredLocalResolver(this).orEmpty()
        if (local.isBlank() || local == config.resolverHost) return config
        AppLog.i(TAG, "AUTO resolverHost refreshed from active network old=${config.resolverHost.ifBlank { "-" }} new=$local")
        return config.copy(resolverHost = local)
    }

    private fun localSocksCredentials(): Pair<String?, String?> {
        val global = ConfigStore.loadGlobalSettings(this)
        return if (global.localSocksAuthEnabled) {
            global.localSocksUsername to global.localSocksPassword
        } else {
            null to null
        }
    }

    private fun isAuthoritativeResolverPath(config: Config): Boolean =
        config.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE

    private fun stopTunnel() {
        ResolverSelector.cancelActiveProbes("vpn_stop")
        unregisterNetworkChangeWatch()
        val generation = ++lifecycleGeneration
        if (
            !starting &&
            !tunnelActive &&
            tunFd == null &&
            !TunnelStack.isRunning() &&
            !MiniSlipstreamSocksBridge.isRunning() &&
            !SlipstreamBridge.isRunning() &&
            !S3fuBridge.isRunning() &&
            !TurnfuProcess.isRunning() &&
            !XrayBridge.isRunning()
        ) {
            AppLog.i(TAG, "VPN already stopped")
            return
        }
        if (stopping) {
            AppLog.w(TAG, "VPN stop already running")
            return
        }
        stopping = true
        starting = false
        AppLog.i(TAG, "VPN stop")
        tunnelActive = false
        currentConfig = null
        currentResolver = null
        ResolverSelector.lastConnectedTransport = null
        resolverOptimizerRunning = false
        failedAutoResolvers.clear()
        lastTransportSwitchAt = 0
        transportSwitchesThisEpisode = 0
        readyFalseSince = 0
        handler.removeCallbacks(diagnostics)
        val done = java.util.concurrent.CountDownLatch(1)
        teardownDone = done
        Thread({
            try {
                if (lifecycleGeneration == generation) {
                    // Order matters: tear down the SOCKS backend + the TUN fd FIRST, then hev.
                    // hev.stop() drains active sessions before returning; if s3fu is still
                    // serving, its UDP associations stay alive and hev waits out the full
                    // udp-read-write-timeout (~60s) -- the UI sits on "Disconnecting" for a minute
                    // when switching protocols. Killing the backend + closing the TUN first makes
                    // hev's relays fail immediately so its stop returns promptly.
                    TunnelStack.stopRoot(this)
                    runCatching { SlipstreamBridge.stopClient() }
                        .onFailure { AppLog.w(TAG, "native stop failed: ${it.message}") }
                    runCatching { S3fuBridge.stopClient() }
                        .onFailure { AppLog.w(TAG, "s3fu stop failed: ${it.message}") }
                    runCatching { XrayBridge.stopClient() }
                        .onFailure { AppLog.w(TAG, "xray stop failed: ${it.message}") }
                    runCatching { TurnfuProcess.stop() }
                        .onFailure { AppLog.w(TAG, "turnfu stop failed: ${it.message}") }
                    turnfuTunIp = null
                    turnfuTunIp6 = null
                    runCatching { MiniSlipstreamSocksBridge.stop() }
                        .onFailure { AppLog.w(TAG, "bridge stop failed: ${it.message}") }
                    runCatching { tunFd?.close() }
                        .onFailure { AppLog.w(TAG, "tun close failed: ${it.message}") }
                    tunFd = null
                    runCatching { HevSocks5Tunnel.stop() }
                        .onFailure { AppLog.w(TAG, "hev stop failed: ${it.message}") }
                    SlipstreamBridge.setVpnService(null)
                    AppLog.i(TAG, "VPN stop cleanup finished")
                } else {
                    AppLog.i(TAG, "VPN stop cleanup skipped: newer lifecycle generation active")
                }
            } finally {
                // Release a waiting restart before stopSelf: when a restart is pending the block
                // below is skipped (its generation is stale), so this must not depend on it.
                done.countDown()
                handler.post {
                    if (lifecycleGeneration == generation && !starting && !tunnelActive) {
                        stopping = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(lastStartId)
                    }
                }
            }
        }, "vpn-stop-cleanup").start()
    }

    private fun resetTrafficBase() {
        val uid = applicationInfo.uid
        rxBase = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0
        txBase = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0
        notificationRxLast = 0
        notificationTxLast = 0
        notificationSampleAt = 0
        notificationTextLast = null
        lastRx = 0
        lastTx = 0
        lastTunRx = 0
        lastTunTx = 0
        lightDiagLoggedAt = 0
        lightDiagRx = 0
        lightDiagTx = 0
        lightSampleAt = 0
        lightSampleRx = 0
        lightSampleTx = 0
        lastBridgeRx = 0
        lastBridgeTx = 0
        lastBridgeFailures = 0
        bridgeFailureWatch.reset()
        stallRatioWatch.reset()
        detachedThreadWatch.reset()
        lastProgressAt = System.currentTimeMillis()
        lastHeavyUploadAt = 0
        slowResponseSince = 0
        lowBandwidthSince = 0
        lastResolverHealthCheckAt = 0
        resolverHealthFailures = 0
    }

    private fun writeDiagnostics() {
        if (RootTunnel.ownsTunnel() && !RootTunnel.isRunning()) {
            AppLog.e(TAG, "root tunnel stopped answering; stopping")
            stopTunnel()
            return
        }
        if (currentConfig?.protocol == Config.TunnelProtocol.S3FU) {
            writeS3fuDiagnostics()
            return
        }
        if (currentConfig?.protocol == Config.TunnelProtocol.XRAY) {
            writeXrayDiagnostics()
            return
        }
        if (currentConfig?.protocol == Config.TunnelProtocol.TURNFU) {
            writeTurnfuDiagnostics()
            return
        }
        val uid = applicationInfo.uid
        val rx = (TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0) - rxBase
        val tx = (TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0) - txBase
        val hev = TunnelStack.stats()
        val bridge = MiniSlipstreamSocksBridge.stats()
        val notificationRx = if (TunnelStack.isRunning()) hev.rxBytes else bridge.rxBytes
        val notificationTx = if (TunnelStack.isRunning()) hev.txBytes else bridge.txBytes
        maybeUpdateTrafficNotification(notificationRx, notificationTx)
        val running = SlipstreamBridge.isRunning()
        val ready = SlipstreamBridge.isReady()
        val now = System.currentTimeMillis()
        if (running && !ready) {
            if (readyFalseSince == 0L) readyFalseSince = now
        } else {
            readyFalseSince = 0
        }
        val appRxDelta = (rx - lastRx).coerceAtLeast(0)
        val appTxDelta = (tx - lastTx).coerceAtLeast(0)
        val appProgressed = appRxDelta > 0 || appTxDelta > 0
        val requestBytesDelta = (hev.txBytes - lastTunTx).coerceAtLeast(0)
        val responseBytesDelta = (hev.rxBytes - lastTunRx).coerceAtLeast(0) + (bridge.rxBytes - lastBridgeRx).coerceAtLeast(0)
        val bridgeTxDelta = (bridge.txBytes - lastBridgeTx).coerceAtLeast(0)
        val failureTotal = bridge.connectFail + bridge.dnsFail
        val failureDelta = (failureTotal - lastBridgeFailures).coerceAtLeast(0)
        if (bridgeTxDelta >= HEAVY_UPLOAD_DELTA_BYTES) {
            lastHeavyUploadAt = now
        }
        val uploadGraceActive = lastHeavyUploadAt != 0L && now - lastHeavyUploadAt <= HEAVY_UPLOAD_GRACE_MS
        val usefulProgressed = responseBytesDelta > 0
        if (usefulProgressed) {
            lastProgressAt = now
        }
        val activeBridge = bridge.activeClients > 0 || bridge.clientSockets > 0 || bridge.remoteSockets > 0
        val outboundPressure = requestBytesDelta >= SLOW_RESPONSE_REQUEST_DELTA_BYTES ||
            bridgeTxDelta >= SLOW_RESPONSE_BRIDGE_TX_DELTA_BYTES ||
            appTxDelta >= SLOW_RESPONSE_APP_TX_DELTA_BYTES
        val slowResponse = running && ready && activeBridge && outboundPressure &&
            responseBytesDelta in 1 until SLOW_RESPONSE_MAX_DELTA_BYTES
        val lowBandwidth = running && ready && activeBridge &&
            (outboundPressure || bridge.activeClients >= LOW_BANDWIDTH_ACTIVE_CLIENTS) &&
            responseBytesDelta in SLOW_RESPONSE_MAX_DELTA_BYTES until LOW_BANDWIDTH_MAX_DELTA_BYTES
        if (slowResponse) {
            if (slowResponseSince == 0L) slowResponseSince = now
        } else if (!activeBridge || responseBytesDelta >= SLOW_RESPONSE_CLEAR_DELTA_BYTES || responseBytesDelta == 0L) {
            slowResponseSince = 0
        }
        if (lowBandwidth) {
            if (lowBandwidthSince == 0L) lowBandwidthSince = now
        } else if (!activeBridge || responseBytesDelta >= LOW_BANDWIDTH_CLEAR_DELTA_BYTES || responseBytesDelta == 0L) {
            lowBandwidthSince = 0
        }
        // Real throughput returned -> the current transport is delivering, so end the transport-switch
        // "episode" (let a future degradation start fresh with its full switch budget).
        if (responseBytesDelta >= LOW_BANDWIDTH_CLEAR_DELTA_BYTES) {
            transportSwitchesThisEpisode = 0
        }
        if (appProgressed || requestBytesDelta > 0 || bridgeTxDelta > 0 || usefulProgressed || failureDelta > 0) {
            lastRx = rx
            lastTx = tx
            lastTunRx = hev.rxBytes
            lastTunTx = hev.txBytes
            lastBridgeRx = bridge.rxBytes
            lastBridgeTx = bridge.txBytes
            lastBridgeFailures = failureTotal
        }
        updateBridgeFailureWatch(now, running, ready, failureTotal)
        updateStallRatioWatch(now, running, ready, uploadGraceActive, requestBytesDelta, responseBytesDelta)
        liveDiag = LiveDiag(
            resolverHost = currentResolver?.selectedHost.orEmpty(),
            resolverPort = currentResolver?.port ?: 0,
            qnameMtu = currentResolver?.qnameMtu ?: 0,
            dnsTested = currentResolver?.testedCount ?: 0,
            dnsAlive = currentResolver?.aliveCount ?: 0,
            dnsSkipped = currentResolver?.skippedCount ?: 0,
            recovering = recovering,
            recoveries = recoveryCount,
            lastProgressMs = now - lastProgressAt,
            readyFalseMs = if (readyFalseSince == 0L) 0 else now - readyFalseSince,
            slowResponseMs = if (slowResponseSince == 0L) 0 else now - slowResponseSince,
            lowBandwidthMs = if (lowBandwidthSince == 0L) 0 else now - lowBandwidthSince,
            networkSignature = lastNetworkSignature
        )
        AppLog.i(
            TAG,
            "diag running=$running ready=$ready hevRunning=${TunnelStack.isRunning()} " +
                "port=${SlipstreamBridge.port()} appRx=$rx appTx=$tx " +
                "tunRx=${hev.rxBytes} tunTx=${hev.txBytes} " +
                "bridgeRx=${bridge.rxBytes} bridgeTx=${bridge.txBytes} " +
                "connectOk=${bridge.connectOk} connectFail=${bridge.connectFail} dnsOk=${bridge.dnsOk} dnsFail=${bridge.dnsFail} " +
                "bridgeActive=${bridge.activeClients} bridgeClients=${bridge.clientSockets} bridgeRemotes=${bridge.remoteSockets} " +
                "bridgeHalfClosed=${bridge.halfClosedClients} bridgeThreads=${bridge.threads} " +
                "transport=${currentResolver?.transport?.name?.lowercase() ?: currentConfig?.resolverTransport?.name?.lowercase() ?: "unknown"} " +
                "qtype=${SlipstreamBridge.dnsQueryType} " +
                "pathMode=${currentConfig?.resolverPathMode?.name?.lowercase() ?: "unknown"} " +
                "resolver=${currentResolver?.selectedHost ?: "none"}:${currentResolver?.port ?: 0} " +
                "qnameMtu=${currentResolver?.qnameMtu?.takeIf { it > 0 } ?: "max"} " +
                "dnsTested=${currentResolver?.testedCount ?: 0} dnsAlive=${currentResolver?.aliveCount ?: 0} " +
                "dnsSkipped=${currentResolver?.skippedCount ?: 0} " +
                "deltaReq=$requestBytesDelta deltaResp=$responseBytesDelta deltaBridgeTx=$bridgeTxDelta deltaFail=$failureDelta " +
                "lastProgressMs=${now - lastProgressAt} readyFalseMs=${if (readyFalseSince == 0L) 0 else now - readyFalseSince} " +
                "slowResponseMs=${if (slowResponseSince == 0L) 0 else now - slowResponseSince} " +
                "lowBandwidthMs=${if (lowBandwidthSince == 0L) 0 else now - lowBandwidthSince} " +
                "heavyUploadAgoMs=${if (lastHeavyUploadAt == 0L) -1 else now - lastHeavyUploadAt} " +
                "recovering=$recovering recoveries=$recoveryCount lastError=${SlipstreamBridge.lastError() ?: "none"}"
        )
        maybeCheckResolverHealth(now, running, ready)
        if (slowResponse && now - slowResponseSince > SLOW_RESPONSE_RECOVERY_MS) {
            AppLog.w(
                TAG,
                "traffic slow-response for ${now - slowResponseSince}ms while native ready " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta bridgeTxDelta=$bridgeTxDelta " +
                    "appTxDelta=$appTxDelta uploadGrace=$uploadGraceActive"
            )
        }
        if (slowResponse && lowBandwidthSince != 0L && now - lowBandwidthSince > LOW_BANDWIDTH_RECOVERY_MS) {
            AppLog.w(
                TAG,
                "traffic low-bandwidth degraded into slow-response for ${now - lowBandwidthSince}ms " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta bridgeTxDelta=$bridgeTxDelta " +
                    "active=${bridge.activeClients} uploadGrace=$uploadGraceActive"
            )
        }
        if (lowBandwidth && now - lowBandwidthSince > LOW_BANDWIDTH_RECOVERY_MS) {
            AppLog.w(
                TAG,
                "traffic low-bandwidth for ${now - lowBandwidthSince}ms while native ready " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta bridgeTxDelta=$bridgeTxDelta " +
                    "active=${bridge.activeClients} uploadGrace=$uploadGraceActive"
            )
        }
        // NOTE: the runtime low-bandwidth "transport self-correction" was REMOVED here. It fired on any
        // sustained low-throughput stretch (common during normal browsing and file downloads over this
        // inherently slow DNS carrier) and, after being changed to re-validate rather than blind-flip,
        // each firing became a ~15-20s DNS-probing teardown of a working tunnel -- so on a slow network
        // (e.g. Tele2) it looped: dip -> re-probe -> pick the same transport -> dip -> re-probe, leaving
        // the app "stuck in DNS probing" repeatedly. Transport selection is now handled correctly at
        // connect time (validateTransport, with a throughput gate) and on network change, which covers
        // the "ready-but-throttled transport" case without tearing down a live session. A genuine total
        // stall is still caught by the traffic_no_response / bridge-failure recovery paths below.
        if (running && ready && failureDelta >= FAILURE_STORM_RECOVERY_DELTA) {
            AppLog.w(
                TAG,
                "bridge failure storm delta=$failureDelta active=${bridge.activeClients} " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta uploadGrace=$uploadGraceActive"
            )
            if (now - lastRecoveryAt > FAILURE_STORM_RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("bridge_failure_storm_${failureDelta}")
            }
        }
        if (running && ready && !uploadGraceActive && requestBytesDelta > 8192 && responseBytesDelta == 0L && now - lastProgressAt > TRAFFIC_RECOVERY_MS) {
            AppLog.w(TAG, "traffic stalled for ${now - lastProgressAt}ms while native ready requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta")
            if (now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("traffic_no_response_${now - lastProgressAt}ms")
            }
        }
        if (
            running && ready && appProgressed &&
            requestBytesDelta == 0L && responseBytesDelta == 0L && bridgeTxDelta == 0L &&
            now - lastProgressAt > TRANSPORT_SILENCE_RECOVERY_MS
        ) {
            AppLog.w(
                TAG,
                "transport silent for ${now - lastProgressAt}ms while app traffic changed " +
                    "appRxDelta=$appRxDelta appTxDelta=$appTxDelta"
            )
        }
        if (running && ready && bridge.connectOk == 0L && failureTotal >= STARTUP_FAILURE_RECOVERY_TOTAL) {
            AppLog.w(
                TAG,
                "startup bridge failures before first successful CONNECT: total=$failureTotal delta=$failureDelta " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta"
            )
            if (now - lastRecoveryAt > STARTUP_RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("bridge_failures_startup_${failureTotal}")
            }
        }
        if (running && ready && failureDelta >= FAILURE_RECOVERY_DELTA && now - lastProgressAt > TRAFFIC_RECOVERY_MS) {
            AppLog.w(TAG, "bridge failures increased by $failureDelta without useful response for ${now - lastProgressAt}ms")
            if (now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("bridge_failures_${failureDelta}_no_response")
            }
        }
        if (running && !ready && readyFalseSince != 0L) {
            val downFor = now - readyFalseSince
            val afterHeavyUpload = lastHeavyUploadAt != 0L && now - lastHeavyUploadAt < HEAVY_UPLOAD_GRACE_MS
            val threshold = if (afterHeavyUpload) READY_RECOVERY_AFTER_UPLOAD_MS else READY_RECOVERY_MS
            AppLog.w(TAG, "native client not ready for ${downFor}ms threshold=${threshold}ms afterHeavyUpload=$afterHeavyUpload")
            if (downFor > threshold && now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("native_not_ready_${downFor}ms")
            }
        }
        if (!running && TunnelStack.isRunning()) {
            AppLog.e(TAG, "tun2socks is running but Slipstream native client is down")
            runCatching { MiniSlipstreamSocksBridge.stop() }
                .onFailure { AppLog.w(TAG, "bridge emergency stop failed after native down: ${it.message}") }
            if (now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("native_not_running")
            }
        }
    }

    private fun updateBridgeFailureWatch(now: Long, running: Boolean, ready: Boolean, failureTotal: Long) {
        val fired = bridgeFailureWatch.tick(
            now, running, ready, recovering, failureTotal, lastRecoveryAt, RECOVERY_COOLDOWN_MS
        ) ?: return
        AppLog.w(
            TAG,
            "bridge accumulated failures total=$failureTotal accumulated=${fired.accumulated} " +
                "windowMs=${fired.windowMs}; restarting path"
        )
        restartSlipstreamPath("bridge_failures_accumulated_${fired.accumulated}")
    }

    private fun updateStallRatioWatch(
        now: Long,
        running: Boolean,
        ready: Boolean,
        uploadGraceActive: Boolean,
        requestBytesDelta: Long,
        responseBytesDelta: Long
    ) {
        val fired = stallRatioWatch.tick(
            now, running, ready, recovering, uploadGraceActive,
            requestBytesDelta, responseBytesDelta, lastRecoveryAt, RECOVERY_COOLDOWN_MS
        ) ?: return
        AppLog.w(
            TAG,
            "traffic starved (downstream trickle) for ${fired.windowMs}ms while native ready " +
                "requestDelta=${fired.requestBytesDelta} responseDelta=${fired.responseBytesDelta}; restarting path"
        )
        restartSlipstreamPath("traffic_starved_${fired.windowMs}ms")
    }

    private fun maybeCheckResolverHealth(now: Long, running: Boolean, ready: Boolean) {
        val config = currentConfig ?: return
        val resolver = currentResolver ?: return
        if (config.resolverMode != Config.ResolverMode.AUTO) return
        if (!tunnelActive || !running || !ready || recovering || resolverHealthCheckRunning) return
        if (now - lastResolverHealthCheckAt < RESOLVER_HEALTH_CHECK_INTERVAL_MS) return
        // Don't count a health failure (which rotates + condemns the resolver) when the phone has no
        // usable network — the resolver is fine, the network is gone. Wait for it to come back.
        if (!ResolverSelector.hasUsableNetwork(this)) return
        val host = resolver.selectedHost.takeIf { it.isNotBlank() } ?: return
        val port = resolver.port
        resolverHealthCheckRunning = true
        lastResolverHealthCheckAt = now
        Thread({
            val ok = ResolverSelector.isResolverReachable(host, port)
            if (ok) {
                if (resolverHealthFailures != 0) {
                    AppLog.i(TAG, "resolver health restored host=$host:$port failures=$resolverHealthFailures")
                }
                resolverHealthFailures = 0
            } else {
                resolverHealthFailures += 1
                AppLog.w(
                    TAG,
                    "resolver health failed host=$host:$port failures=$resolverHealthFailures/$RESOLVER_HEALTH_FAILURES_BEFORE_ROTATE"
                )
                if (
                    shouldRotateOnResolverHealthFailure(
                        resolverHealthFailures,
                        RESOLVER_HEALTH_FAILURES_BEFORE_ROTATE,
                        tunnelActive,
                        recovering,
                        System.currentTimeMillis(),
                        lastRecoveryAt,
                        RESOLVER_HEALTH_RECOVERY_COOLDOWN_MS
                    )
                ) {
                    restartSlipstreamPath("resolver_unreachable_${host}_${resolverHealthFailures}")
                }
            }
            resolverHealthCheckRunning = false
        }, "resolver-health").start()
    }

    private fun restartSlipstreamPath(reason: String) {
        restartSlipstreamPath(reason, forcedChoice = null)
    }

    internal fun isAddressInUse(e: Throwable): Boolean =
        e.message?.contains("Address already in use", ignoreCase = true) == true ||
            e.message?.contains("os error 98") == true

    /// Grab a currently-free localhost TCP port by binding a throwaway socket to :0. Used to dodge
    /// the EADDRINUSE wedge where a detached native accept thread is still holding the fixed
    /// slipstream listen port after stopClient() gave up joining it. Returns [fallback] if the probe
    /// itself fails (nothing lost — the caller was going to use that port anyway).
    internal fun findFreeLocalPort(fallback: Int): Int =
        runCatching {
            java.net.ServerSocket().use { sock ->
                sock.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                sock.localPort
            }
        }.getOrDefault(fallback)

    /// Start the native client on [preferredPort]; if the bind fails with EADDRINUSE (a previous
    /// client's detached thread is still holding the port past the native STOP_JOIN_TIMEOUT), retry
    /// once on a fresh free port instead of looping on the wedged one. Returns the port actually used
    /// so the caller can point the SOCKS bridge at it. Throws the start error if it isn't EADDRINUSE,
    /// or if the fresh-port retry also fails. Used by both the initial start and recovery paths.
    private fun startSlipstreamClientEscapingWedge(
        config: Config,
        choice: ResolverChoice,
        preferredPort: Int,
        tag: String
    ): Int {
        var port = preferredPort
        var err = SlipstreamBridge.startClient(
            config.domain,
            ResolverListConfig(choice.hosts, choice.port, isAuthoritativeResolverPath(config)),
            port,
            choice.qnameMtu,
            choice.transport.name.lowercase()
        ).exceptionOrNull()
        if (err != null && isAddressInUse(err)) {
            // EADDRINUSE here means the previous client's native thread never died (it's still
            // holding the listen port past STOP_JOIN_TIMEOUT) -- i.e. a detached, likely
            // CPU-burning thread. Rebinding on a fresh port works around the port conflict but not
            // the leaked core; if these pile up, escalate to a clean process restart.
            if (detachedThreadWatch.onIncident(System.currentTimeMillis())) {
                requestCleanProcessRestart("$tag:eaddrinuse_x${detachedThreadWatch.countInWindow()}")
            }
            runCatching { SlipstreamBridge.stopClient() }
            val fresh = findFreeLocalPort(port)
            if (fresh != port) {
                AppLog.w(TAG, "$tag slipstream port $port wedged (EADDRINUSE); retrying on fresh port $fresh")
                port = fresh
                Thread.sleep(150)
                err = SlipstreamBridge.startClient(
                    config.domain,
                    ResolverListConfig(choice.hosts, choice.port, isAuthoritativeResolverPath(config)),
                    port,
                    choice.qnameMtu,
                    choice.transport.name.lowercase()
                ).exceptionOrNull()
            }
        }
        if (err != null) throw err
        return port
    }

    /// Last-resort escalation for the detached-native-thread spiral: a wedged native thread stuck
    /// in a synchronous native call (e.g. the picoquic reassembly-splay infinite loop) can never
    /// observe the shutdown/stale signal, so it burns a CPU core forever and no amount of
    /// fresh-port rebinding reclaims it. The only thing that actually reaps such a thread is the
    /// death of its process. Schedule a relaunch (belt) and kill our own process (suspenders); the
    /// START_STICKY service is brought back by the system, and the sticky-restart null-intent path
    /// re-runs startTunnel() in a fresh process with the burned cores freed.
    private fun requestCleanProcessRestart(reason: String) {
        if (processRestartRequested) return
        processRestartRequested = true
        AppLog.e(
            TAG,
            "detached-native-thread spiral ($reason): killing process for a clean restart to " +
                "reclaim CPU cores burned by wedged native threads"
        )
        // Belt: an explicit alarm-driven relaunch so we don't rely solely on START_STICKY.
        runCatching {
            val intent = Intent(this, TinyVpnService::class.java)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, RESTART_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(this, RESTART_REQUEST_CODE, intent, flags)
            }
            getSystemService(AlarmManager::class.java)?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + PROCESS_RESTART_DELAY_MS,
                pi
            )
        }.onFailure { AppLog.w(TAG, "restart alarm scheduling failed: ${it.message}") }
        // Tear down what we cleanly can, then kill the process (this also takes the wedged native
        // thread down with it). START_STICKY relaunches the service.
        runCatching { TunnelStack.stop(this) }
        runCatching { MiniSlipstreamSocksBridge.stop() }
        Process.killProcess(Process.myPid())
    }

    /// Schedule a delayed recovery retry with capped exponential backoff. Used when a recovery
    /// attempt fails outright or is deferred because there is no usable network yet, so the tunnel
    /// doesn't stay dark waiting for an unrelated diagnostic trigger. Only one retry is ever pending
    /// (coalesced). The backoff resets to base on the next successful recovery.
    private fun scheduleRecoveryRetry(reason: String) {
        if (!tunnelActive) return
        recoveryRetryPending?.let { handler.removeCallbacks(it) }
        val delay = recoveryRetryBackoffMs
        recoveryRetryBackoffMs = (recoveryRetryBackoffMs * 2).coerceAtMost(RECOVERY_RETRY_MAX_MS)
        val r = Runnable {
            recoveryRetryPending = null
            if (!tunnelActive || recovering) return@Runnable
            if (!ResolverSelector.hasUsableNetwork(this)) {
                // Still offline — keep waiting (the ConnectivityManager onAvailable callback will
                // also re-trigger). Don't probe/condemn resolvers on a dead network.
                AppLog.w(TAG, "recovery retry deferred: still no usable network reason=$reason")
                scheduleRecoveryRetry(reason)
                return@Runnable
            }
            restartSlipstreamPath(reason)
        }
        recoveryRetryPending = r
        handler.postDelayed(r, delay)
        AppLog.w(TAG, "recovery retry scheduled in ${delay}ms reason=$reason")
    }

    private fun configureNativeLogging() {
        val path = if (AppLog.isFileLoggingEnabled(this)) AppLog.file(this).absolutePath else ""
        SlipstreamBridge.setLogFilePath(path)
        HevSocks5Tunnel.setCrashLogPath(AppLog.crashFile(this).absolutePath)
    }

    private fun registerNetworkChangeWatch() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        lastNetworkSignature = ResolverSelector.networkSignature(this)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkRecheck()
            override fun onLost(network: Network) = scheduleNetworkRecheck()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
                scheduleNetworkRecheck()
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb; AppLog.i(TAG, "network-change watch registered sig='$lastNetworkSignature'") }
            .onFailure { AppLog.w(TAG, "network-change watch register failed: ${it.message}") }
    }

    private fun unregisterNetworkChangeWatch() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        pendingNetworkRecheck?.let { handler.removeCallbacks(it) }
        pendingNetworkRecheck = null
        recoveryRetryPending?.let { handler.removeCallbacks(it) }
        recoveryRetryPending = null
    }

    private fun scheduleNetworkRecheck() {
        // Network callbacks fire in bursts during a switch; debounce and act once it settles.
        pendingNetworkRecheck?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingNetworkRecheck = null
            if (!tunnelActive || recovering) return@Runnable
            val config = currentConfig ?: return@Runnable
            if (config.protocol == Config.TunnelProtocol.TURNFU) {
                TurnfuProcess.physicalNetworkHandle(this)?.let { TurnfuProcess.setNetwork(it) }
                return@Runnable
            }
            if (config.resolverMode != Config.ResolverMode.AUTO) return@Runnable
            val sig = ResolverSelector.networkSignature(this)
            if (sig.isBlank() || sig == lastNetworkSignature) return@Runnable
            AppLog.w(TAG, "underlying network changed '$lastNetworkSignature' -> '$sig'; re-selecting resolver+transport")
            lastNetworkSignature = sig
            if (System.currentTimeMillis() - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("network_changed")
            }
        }
        pendingNetworkRecheck = r
        handler.postDelayed(r, NETWORK_CHANGE_DEBOUNCE_MS)
    }

    private fun restartSlipstreamPath(reason: String, forcedChoice: ResolverChoice?) {
        val config = currentConfig ?: run {
            AppLog.e(TAG, "recovery skipped: config is missing reason=$reason")
            return
        }
        if (!tunnelActive) {
            AppLog.w(TAG, "recovery skipped: tunnel is not active reason=$reason")
            return
        }
        if (recovering) {
            AppLog.w(TAG, "recovery already running reason=$reason")
            return
        }
        recovering = true
        lastRecoveryAt = System.currentTimeMillis()
        // A fresh recovery (from any trigger) supersedes a scheduled backoff retry.
        recoveryRetryPending?.let { handler.removeCallbacks(it) }
        recoveryRetryPending = null
        recoveryCount += 1
        val recoveryId = recoveryCount
        AppLog.w(TAG, "recovery#$recoveryId start reason=$reason")
        Thread({
            val previousChoice = currentResolver
            try {
                if (config.resolverMode == Config.ResolverMode.AUTO &&
                    !ResolverSelector.hasUsableNetwork(this)
                ) {
                    // No underlying network right now (SIM/Wi-Fi dropped mid-switch). Every resolver
                    // probe would fail and wrongly mark healthy resolvers bad, and restarting the
                    // native client is pointless with no transport. Keep the current path intact and
                    // wait for the network to return — the ConnectivityManager callback re-triggers,
                    // and the backoff retry is the safety net.
                    AppLog.w(TAG, "recovery#$recoveryId deferred: no usable network reason=$reason")
                    scheduleRecoveryRetry(reason)
                    return@Thread
                }
                val bridgePort = config.listenPort
                var slipstreamPort = config.listenPort + 1
                val lastNativeError = SlipstreamBridge.lastError().orEmpty()
                val currentHostForRetry = currentResolver?.selectedHost?.takeIf { it.isNotBlank() }
                val currentAlreadyFailed =
                    currentHostForRetry != null && currentHostForRetry in failedAutoResolvers
                val cls = classifyRecoveryReason(
                    reason, lastNativeError, config.resolverMode,
                    hasCurrentResolver = currentResolver != null,
                    currentResolverAlreadyFailed = currentAlreadyFailed
                )
                val isNativeNoProgress = cls.isNativeNoProgress
                val fastPathRecovery = cls.fastPathRecovery
                val failureStormRecovery = cls.failureStormRecovery
                val resolverUnreachableRecovery = cls.resolverUnreachableRecovery
                val transportSwitchRecovery = cls.transportSwitchRecovery
                val networkChangedRecovery = cls.networkChangedRecovery
                val bridgeFailureRecovery = cls.bridgeFailureRecovery
                val nativeDownFastRecovery = cls.nativeDownFastRecovery
                val autoFastRecovery = cls.autoFastRecovery
                val reuseCurrentResolver = cls.reuseCurrentResolver
                val rotateResolver = cls.rotateResolver
                if (isNativeNoProgress) {
                    AppLog.w(TAG, "recovery#$recoveryId native no-progress; reusing current resolver without auto DNS probe: $lastNativeError")
                }
                runCatching { MiniSlipstreamSocksBridge.stop() }
                    .onFailure { AppLog.w(TAG, "recovery#$recoveryId bridge early stop failed: ${it.message}") }
                runCatching { SlipstreamBridge.stopClient() }
                    .onFailure { AppLog.w(TAG, "recovery#$recoveryId native early stop failed: ${it.message}") }
                currentResolver?.selectedHost
                    ?.takeIf { rotateResolver && config.resolverMode == Config.ResolverMode.AUTO && it.isNotBlank() }
                    ?.let {
                        failedAutoResolvers += it
                        AppLog.w(TAG, "recovery#$recoveryId mark resolver bad for this session: $it reason=$reason")
                    }
                if (!rotateResolver && failedAutoResolvers.isNotEmpty()) {
                    AppLog.w(TAG, "recovery#$recoveryId keep resolver sticky for reason=$reason; clearing failed auto resolvers=${failedAutoResolvers.joinToString()}")
                    failedAutoResolvers.clear()
                }
                val quickChoice = if (forcedChoice == null && autoFastRecovery) quickAutoRecoveryChoice(config, recoveryId, reason) else null
                val choice = forcedChoice ?: quickChoice ?: if (transportSwitchRecovery && currentResolver != null) {
                    // Sustained low-bandwidth: RE-VALIDATE (probe transports + qtype) and keep whatever
                    // actually performs, instead of blindly flipping the transport. Blind flipping caused
                    // tcp<->udp flapping on Tele2 (where udp+HTTPS doesn't work), which shredded
                    // throughput; re-validation re-picks the working transport and stops the flap.
                    AppLog.w(TAG, "recovery#$recoveryId re-validating transport/qtype for ${currentResolver!!.selectedHost}:${currentResolver!!.port} reason=$reason")
                    SlipstreamBridge.dnsQueryType = config.dnsQueryType
                    SlipstreamBridge.dnsLabelLength = config.dnsLabelLength
                    SlipstreamBridge.dnsLabelLengthJitter = config.dnsLabelLengthJitter
                    SlipstreamBridge.maxPollQps = config.maxPollQps
                    SlipstreamBridge.maxDataQps = config.maxDataQps
                    SlipstreamBridge.base64uEncoding = config.base64uEncoding
                    ResolverSelector.validateTransport(this, config, currentResolver!!, "recovery#$recoveryId:$reason")
                } else if (networkChangedRecovery) {
                    // Fresh network: forget the previous network's failed resolvers, pick a resolver for
                    // the current network, and re-run transport/qtype validation for it.
                    failedAutoResolvers.clear()
                    val fresh = ResolverSelector.chooseFast(this, config, "recovery#$recoveryId:$reason", failedAutoResolvers)
                    AppLog.w(TAG, "recovery#$recoveryId network changed; fresh resolver ${fresh.selectedHost}:${fresh.port}; re-validating transport/qtype")
                    SlipstreamBridge.dnsQueryType = config.dnsQueryType
                    SlipstreamBridge.dnsLabelLength = config.dnsLabelLength
                    SlipstreamBridge.dnsLabelLengthJitter = config.dnsLabelLengthJitter
                    SlipstreamBridge.maxPollQps = config.maxPollQps
                    SlipstreamBridge.maxDataQps = config.maxDataQps
                    SlipstreamBridge.base64uEncoding = config.base64uEncoding
                    ResolverSelector.validateTransport(this, config, fresh, "recovery#$recoveryId:$reason")
                } else if (reuseCurrentResolver && currentResolver != null) {
                    AppLog.w(TAG, "recovery#$recoveryId reusing resolver ${currentResolver!!.selectedHost}:${currentResolver!!.port} reason=$reason")
                    currentResolver!!
                } else {
                    // Cheap next-best failover: pick the next-best resolver from the cached ranked
                    // list using only a TCP-reachability probe (no slipstream speed-probe / probe
                    // QUIC clients, which were the expensive, device-loading part of the old path).
                    // Falls back to a full speed-probe rescan only if the cache yields nothing usable.
                    runCatching {
                        ResolverSelector.chooseFast(
                            this,
                            config,
                            "recovery#$recoveryId:$reason",
                            failedAutoResolvers
                        )
                    }.getOrElse { fastErr ->
                        AppLog.w(
                            TAG,
                            "recovery#$recoveryId chooseFast found no cached resolver (${fastErr.message}); " +
                                "falling back to full speed-probe rescan"
                        )
                        try {
                            ResolverSelector.choose(
                                this,
                                config,
                                "recovery#$recoveryId:$reason:rescan",
                                failedAutoResolvers
                            )
                        } catch (rescanErr: Throwable) {
                            // Total exhaustion: every local + cached resolver failed this session.
                            // Clear the failed set so the next tick retries from scratch instead of
                            // staying dark forever (the safety valve the old quickAutoRecovery clear
                            // used to provide, now applied only at true exhaustion).
                            if (failedAutoResolvers.isNotEmpty()) {
                                AppLog.w(
                                    TAG,
                                    "recovery#$recoveryId all resolvers exhausted; clearing failed set to retry: " +
                                        failedAutoResolvers.joinToString()
                                )
                                failedAutoResolvers.clear()
                            }
                            throw rescanErr
                        }
                    }
                }
                currentResolver = choice
                ResolverSelector.lastConnectedTransport = choice.transport
                val localSocks = localSocksCredentials()
                Thread.sleep(if (fastPathRecovery || nativeDownFastRecovery) 150 else 750)
                if (!tunnelActive) {
                    AppLog.w(TAG, "recovery#$recoveryId cancelled: tunnel stopped")
                    return@Thread
                }
                bindSocketProtector()
                // Do NOT reset dnsQueryType here: keep the qtype that transport/qtype validation locked
                // in at connect (e.g. a TXT fallback on a resolver that can't forward SVCB). A network
                // change re-runs validation via startTunnelWorker, which re-picks the qtype.
                // Rebind on a fresh free port if the previous client's detached thread is still wedged
                // on slipstreamPort (EADDRINUSE) — bridgePort (what tun2socks targets) is unchanged,
                // only the internal bridge→native hop moves. Same helper the initial-start path uses.
                slipstreamPort = startSlipstreamClientEscapingWedge(config, choice, slipstreamPort, "recovery#$recoveryId")
                val recoveryReadyTimeout = readyTimeoutMs(RECOVERY_READY_TIMEOUT_MS, config.dnsQueryType)
                if (!waitForSlipstreamReady(recoveryReadyTimeout)) {
                    runCatching { SlipstreamBridge.stopClient() }
                    // A transport switch that fails to become ready is a transport problem, not a dead
                    // resolver -- don't condemn the resolver in that case.
                    if (config.resolverMode == Config.ResolverMode.AUTO && choice.selectedHost.isNotBlank() &&
                        !transportSwitchRecovery
                    ) {
                        failedAutoResolvers += choice.selectedHost
                        AppLog.w(
                            TAG,
                            "recovery#$recoveryId resolver not ready; marked bad: " +
                                "${choice.selectedHost}:${choice.port}"
                        )
                    }
                    throw IllegalStateException(
                        "slipstream not ready after ${recoveryReadyTimeout}ms " +
                            "resolver=${choice.selectedHost}:${choice.port}"
                    )
                }
                MiniSlipstreamSocksBridge.start(
                    listenHost = proxyListenHost(),
                    listenPort = bridgePort,
                    slipstreamHost = "127.0.0.1",
                    slipstreamPort = slipstreamPort,
                    dnsHost = choice.selectedHost,
                    username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                    password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
                    localUsername = localSocks.first,
                    localPassword = localSocks.second,
                    maxActiveClients = config.maxActiveClients
                ).getOrThrow()
                readyFalseSince = 0
                slowResponseSince = 0
                lowBandwidthSince = 0
                resolverHealthFailures = 0
                lastResolverHealthCheckAt = System.currentTimeMillis()
                lastProgressAt = System.currentTimeMillis()
                if (transportSwitchRecovery && config.resolverMode == Config.ResolverMode.AUTO &&
                    choice.selectedHost.isNotBlank()
                ) {
                    // Persist the switched-to transport so the next connect on this network profile
                    // starts on it directly instead of re-guessing the throttled one.
                    ResolverSelector.rememberTransport(this, config, choice.selectedHost, choice.transport)
                    AppLog.w(
                        TAG,
                        "recovery#$recoveryId remembered switched transport=${choice.transport.name.lowercase()} " +
                            "for ${choice.selectedHost}"
                    )
                }
                // Recovered: cancel any pending backoff retry and reset the backoff window.
                recoveryRetryPending?.let { handler.removeCallbacks(it) }
                recoveryRetryPending = null
                recoveryRetryBackoffMs = RECOVERY_RETRY_BASE_MS
                AppLog.w(
                    TAG,
                    "recovery#$recoveryId done ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()} " +
                        "resolver=${choice.selectedHost}:${choice.port} tested=${choice.testedCount} " +
                        "qnameMtu=${if (choice.qnameMtu > 0) choice.qnameMtu else "max"} " +
                        "alive=${choice.aliveCount} skipped=${choice.skippedCount}"
                )
            } catch (e: Throwable) {
                AppLog.e(TAG, "recovery#$recoveryId failed", e)
                if (forcedChoice != null && previousChoice != null && tunnelActive) {
                    restoreResolverAfterFailedUpgrade(config, previousChoice, recoveryId)
                } else if (tunnelActive) {
                    // Don't leave the tunnel dark after a failed recovery — reschedule with backoff
                    // instead of waiting for an unrelated diagnostic trigger to fire.
                    scheduleRecoveryRetry(reason)
                }
            } finally {
                recovering = false
            }
        }, "slipstream-recovery").start()
    }

    private fun restoreResolverAfterFailedUpgrade(config: Config, choice: ResolverChoice, recoveryId: Int) {
        runCatching {
            val bridgePort = config.listenPort
            val slipstreamPort = config.listenPort + 1
            val localSocks = localSocksCredentials()
            AppLog.w(
                TAG,
                "recovery#$recoveryId restoring previous resolver " +
                    "${choice.selectedHost}:${choice.port} after failed speed upgrade"
            )
            runCatching { MiniSlipstreamSocksBridge.stop() }
            runCatching { SlipstreamBridge.stopClient() }
            bindSocketProtector()
            SlipstreamBridge.startClient(
                config.domain,
                ResolverListConfig(choice.hosts, choice.port, isAuthoritativeResolverPath(config)),
                slipstreamPort,
                choice.qnameMtu,
                choice.transport.name.lowercase()
            ).getOrThrow()
            if (!waitForSlipstreamReady(readyTimeoutMs(RECOVERY_READY_TIMEOUT_MS, config.dnsQueryType))) {
                throw IllegalStateException(
                    "previous resolver not ready after failed speed upgrade: " +
                        "${choice.selectedHost}:${choice.port}"
                )
            }
            MiniSlipstreamSocksBridge.start(
                listenHost = proxyListenHost(),
                listenPort = bridgePort,
                slipstreamHost = "127.0.0.1",
                slipstreamPort = slipstreamPort,
                dnsHost = choice.selectedHost,
                username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
                localUsername = localSocks.first,
                localPassword = localSocks.second,
                maxActiveClients = config.maxActiveClients
            ).getOrThrow()
            currentResolver = choice
            ResolverSelector.lastConnectedTransport = choice.transport
            readyFalseSince = 0
            slowResponseSince = 0
            lowBandwidthSince = 0
            resolverHealthFailures = 0
            lastResolverHealthCheckAt = System.currentTimeMillis()
            lastProgressAt = System.currentTimeMillis()
            AppLog.w(
                TAG,
                "recovery#$recoveryId restored previous resolver ${choice.selectedHost}:${choice.port} " +
                    "ready=${SlipstreamBridge.isReady()}"
            )
        }.onFailure {
            AppLog.e(TAG, "recovery#$recoveryId failed to restore previous resolver", it)
        }
    }

    private fun startBackgroundResolverOptimization(config: Config, initialChoice: ResolverChoice) {
        // Trimmed "до лучших времён": no background throughput-ranking speed probe / resolver upgrade.
        // Resolver stays whatever the operator-only reachability pick chose; transport/qtype auto stays.
        if (!RESOLVER_SPEED_OPTIMIZER_ENABLED) return
        if (config.resolverMode != Config.ResolverMode.AUTO) return
        if (resolverOptimizerRunning) return
        resolverOptimizerRunning = true
        Thread({
            try {
                Thread.sleep(BACKGROUND_RESOLVER_PROBE_DELAY_MS)
                if (!tunnelActive || !SlipstreamBridge.isRunning() || recovering) return@Thread
                AppLog.i(TAG, "background resolver download probe start current=${initialChoice.selectedHost}:${initialChoice.port}")
                val best = ResolverSelector.chooseFastestByDownload(
                    this,
                    config,
                    "background_speed",
                    failedAutoResolvers
                ) ?: run {
                    AppLog.w(TAG, "background resolver speed probe found no usable resolver")
                    return@Thread
                }
                val current = currentResolver
                if (!tunnelActive || current == null) return@Thread
                if (best.selectedHost == current.selectedHost && best.port == current.port) {
                    AppLog.i(
                        TAG,
                        "background resolver download probe kept current=${current.selectedHost}:${current.port} " +
                            "totalMs=${best.latencyMs}"
                    )
                    return@Thread
                }
                if (recovering || System.currentTimeMillis() - lastRecoveryAt <= BACKGROUND_RESOLVER_SWITCH_COOLDOWN_MS) {
                    AppLog.w(TAG, "background resolver switch skipped: recovery busy/cooling down best=${best.selectedHost}:${best.port}")
                    return@Thread
                }
                AppLog.w(
                    TAG,
                    "background resolver download upgrade ${current.selectedHost}:${current.port} -> " +
                        "${best.selectedHost}:${best.port} totalMs=${best.latencyMs}"
                )
                restartSlipstreamPath("resolver_speed_upgrade_${best.selectedHost}_${best.latencyMs}ms", best)
            } catch (e: Throwable) {
                AppLog.e(TAG, "background resolver download probe failed", e)
            } finally {
                resolverOptimizerRunning = false
            }
        }, "resolver-speed-optimizer").start()
    }

    private fun quickAutoRecoveryChoice(config: Config, recoveryId: Int, reason: String): ResolverChoice? {
        if (config.resolverMode != Config.ResolverMode.AUTO) return null
        val current = currentResolver
        val currentHost = current?.selectedHost.orEmpty()
        val local = ResolverSelector.localDefaultResolvers(this)
        val preferred = buildList {
            local.forEach { host ->
                if (host != currentHost && host !in failedAutoResolvers) add(host)
            }
            current?.hosts.orEmpty().forEach { host ->
                if (host !in failedAutoResolvers) add(host)
            }
            if (currentHost.isNotBlank() && currentHost !in failedAutoResolvers) add(currentHost)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (preferred.isEmpty()) {
            // Every local (DHCP) + current-connection resolver is already marked bad this session.
            // Previously this cleared the failed set and re-cycled the same operator DNS servers, which
            // caused an oscillation between them without ever trying the known-good resolvers in the
            // speed-probed cache. Instead return null so the caller escalates to chooseFast(), which
            // draws the next-best resolver from the full cache. The failed set is preserved (and only
            // cleared on true total exhaustion, in the rotate branch's rescan fallback).
            AppLog.w(
                TAG,
                "recovery#$recoveryId auto-fast local candidates exhausted; escalating to cached resolver pool " +
                    "failed=${failedAutoResolvers.joinToString()}"
            )
            return null
        }

        val ordered = buildList {
            preferred.forEach { add(it) }
            local.forEach { host ->
                add(host)
            }
            current?.hosts.orEmpty().forEach { add(it) }
            if (currentHost.isNotBlank()) add(currentHost)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
        val selected = ordered.firstOrNull() ?: return null
        AppLog.w(
            TAG,
            "recovery#$recoveryId auto-fast resolver selected=$selected:${config.resolverPort} " +
                "hosts=${ordered.joinToString()} local=${local.joinToString()} current=${currentHost.ifBlank { "-" }} " +
                "failed=${failedAutoResolvers.joinToString()} reason=$reason"
        )
        return ResolverChoice(
            hosts = ordered,
            port = config.resolverPort,
            selectedHost = selected,
            source = "auto-fast",
            qnameMtu = current?.qnameMtu ?: 0,
            testedCount = 0,
            aliveCount = ordered.size,
            skippedCount = failedAutoResolvers.size,
            transport = current?.transport ?: Config.ResolverTransport.UDP
        )
    }

    private fun waitForSlipstreamReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!tunnelActive || !SlipstreamBridge.isRunning()) return false
            if (SlipstreamBridge.isReady()) return true
            Thread.sleep(100)
        }
        return SlipstreamBridge.isReady()
    }

    private fun maybeUpdateTrafficNotification(rx: Long, tx: Long, force: Boolean = false) {
        if (!ConfigStore.loadGlobalSettings(this).trafficNotification) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            return
        }
        val now = System.currentTimeMillis()
        val firstSample = notificationSampleAt == 0L
        val elapsedMs = (now - notificationSampleAt).takeIf { !firstSample && it > 0 } ?: 1000L
        val downRate = if (firstSample) 0L else ((rx - notificationRxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        val upRate = if (firstSample) 0L else ((tx - notificationTxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        notificationRxLast = rx
        notificationTxLast = tx
        notificationSampleAt = now
        if (!force && !isScreenInteractive()) return
        val text = "↓ ${formatBytes(rx)} (${formatRate(downRate)})   ↑ ${formatBytes(tx)} (${formatRate(upRate)})"
        if (!force && text == notificationTextLast) return
        notificationTextLast = text
        startForeground(1, notification(t(S.STATUS_CONNECTED), text))
    }

    private fun isScreenInteractive(): Boolean =
        runCatching { getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() ?: true

    private fun lightDiagnosticsLine(
        engine: String,
        running: Boolean,
        hev: HevSocks5Tunnel.TrafficStats
    ): String? {
        val now = SystemClock.elapsedRealtime()
        val sampleElapsed = now - lightSampleAt
        val rates = if (lightSampleAt == 0L || sampleElapsed <= 0) {
            ""
        } else {
            val down = ((hev.rxBytes - lightSampleRx).coerceAtLeast(0) * 1000L) / sampleElapsed
            val up = ((hev.txBytes - lightSampleTx).coerceAtLeast(0) * 1000L) / sampleElapsed
            " down=${formatRate(down)} up=${formatRate(up)}"
        }
        lightSampleAt = now
        lightSampleRx = hev.rxBytes
        lightSampleTx = hev.txBytes
        val first = lightDiagLoggedAt == 0L
        val elapsed = now - lightDiagLoggedAt
        val moved = hev.rxBytes != lightDiagRx || hev.txBytes != lightDiagTx
        val interval = if (isScreenInteractive()) {
            LIGHT_DIAG_ACTIVE_INTERVAL_MS
        } else {
            LIGHT_DIAG_SCREEN_OFF_INTERVAL_MS
        }
        val due = first ||
            (moved && elapsed >= interval - LIGHT_DIAG_JITTER_MS) ||
            elapsed >= LIGHT_DIAG_IDLE_INTERVAL_MS - LIGHT_DIAG_JITTER_MS
        if (!due) return null
        lightDiagLoggedAt = now
        lightDiagRx = hev.rxBytes
        lightDiagTx = hev.txBytes
        return "diag[$engine] running=$running hev=${TunnelStack.isRunning()}$rates " +
            "total down=${formatBytes(hev.rxBytes)} up=${formatBytes(hev.txBytes)}"
    }

    private fun notification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ComposeMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_smugly)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun formatBytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1024L * 1024L -> "${v / 1024L / 1024L} MiB"
            v >= 1024L -> "${v / 1024L} KiB"
            else -> "$v B"
        }
    }

    private fun formatRate(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1024L * 1024L -> "${v / 1024L / 1024L} MiB/s"
            v >= 1024L -> "${v / 1024L} KiB/s"
            else -> "$v B/s"
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, t(S.APP_NAME), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        @Volatile var lastFailureAt = 0L
        const val ACTION_START = "app.smugly.START"
        const val ACTION_STOP = "app.smugly.STOP"
        const val EXTRA_FOREGROUND = "app.smugly.extra.FOREGROUND"

        // Snapshot of the rich per-tick diagnostics already computed for the logcat "diag" line,
        // additionally exposed here so the in-app Diagnostics screen can show it (not just logcat).
        data class LiveDiag(
            val resolverHost: String = "",
            val resolverPort: Int = 0,
            val qnameMtu: Int = 0,
            val dnsTested: Int = 0,
            val dnsAlive: Int = 0,
            val dnsSkipped: Int = 0,
            val recovering: Boolean = false,
            val recoveries: Int = 0,
            val lastProgressMs: Long = 0,
            val readyFalseMs: Long = 0,
            val slowResponseMs: Long = 0,
            val lowBandwidthMs: Long = 0,
            val networkSignature: String = ""
        )

        @Volatile
        var liveDiag: LiveDiag = LiveDiag()
            private set
        private const val CHANNEL = "smugly"
        private const val TAG = "TinyVpnService"
        private const val DIAGNOSTICS_INTERVAL_MS = 5_000L
        private const val LIGHT_DIAG_ACTIVE_INTERVAL_MS = 15_000L
        private const val LIGHT_DIAG_SCREEN_OFF_INTERVAL_MS = 60_000L
        private const val LIGHT_DIAG_IDLE_INTERVAL_MS = 300_000L
        private const val LIGHT_DIAG_JITTER_MS = 250L
        private const val READY_RECOVERY_MS = 3_000L
        private const val READY_RECOVERY_AFTER_UPLOAD_MS = 3_000L
        private const val TRAFFIC_RECOVERY_MS = 15_000L
        private const val TRANSPORT_SILENCE_RECOVERY_MS = 8_000L
        private const val SLOW_RESPONSE_RECOVERY_MS = 12_000L
        private const val SLOW_RESPONSE_MAX_DELTA_BYTES = 2L * 1024L
        private const val SLOW_RESPONSE_CLEAR_DELTA_BYTES = 16L * 1024L
        private const val SLOW_RESPONSE_REQUEST_DELTA_BYTES = 1024L
        private const val SLOW_RESPONSE_BRIDGE_TX_DELTA_BYTES = 1024L
        private const val SLOW_RESPONSE_APP_TX_DELTA_BYTES = 4L * 1024L
        private const val LOW_BANDWIDTH_RECOVERY_MS = 45_000L
        private const val LOW_BANDWIDTH_MAX_DELTA_BYTES = 8L * 1024L
        private const val LOW_BANDWIDTH_CLEAR_DELTA_BYTES = 32L * 1024L
        private const val LOW_BANDWIDTH_ACTIVE_CLIENTS = 10
        // Runtime transport self-correction (auto udp<->tcp switch when the chosen carrier is ready but
        // throttled to near-uselessness). Reacts far sooner than LOW_BANDWIDTH_RECOVERY_MS (45s) because
        // a wrong transport never self-heals; bounded per episode to prevent flapping.
        private const val TRANSPORT_SWITCH_LOW_BW_MS = 15_000L
        private const val TRANSPORT_SWITCH_COOLDOWN_MS = 18_000L
        private const val MAX_TRANSPORT_SWITCHES_PER_EPISODE = 2
        // Debounce for underlying-network (SIM/Wi-Fi) change before re-selecting the resolver.
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 4_000L
        private const val RECOVERY_COOLDOWN_MS = 5_000L
        // Auto-DNS trim: background throughput-ranking resolver optimizer is off "до лучших времён".
        private const val RESOLVER_SPEED_OPTIMIZER_ENABLED = false
        // Backoff for re-attempting a recovery that failed or was deferred (e.g. no network yet),
        // so the tunnel doesn't stay dark until an unrelated diagnostic trigger happens to fire.
        private const val RECOVERY_RETRY_BASE_MS = 3_000L
        private const val RECOVERY_RETRY_MAX_MS = 60_000L
        private const val FAILURE_RECOVERY_DELTA = 8L
        private const val FAILURE_STORM_RECOVERY_DELTA = 12L
        private const val FAILURE_STORM_RECOVERY_COOLDOWN_MS = 3_000L
        private const val STARTUP_FAILURE_RECOVERY_TOTAL = 6L
        private const val STARTUP_RECOVERY_COOLDOWN_MS = 2_000L
        private const val RESOLVER_HEALTH_CHECK_INTERVAL_MS = 15_000L
        private const val RESOLVER_HEALTH_FAILURES_BEFORE_ROTATE = 2
        private const val RESOLVER_HEALTH_RECOVERY_COOLDOWN_MS = 5_000L
        private const val ACCUMULATED_FAILURE_RECOVERY_TOTAL = 24L
        private const val ACCUMULATED_FAILURE_RECOVERY_WINDOW_MS = 20_000L
        // Half-silent-degradation detector (StallRatioWatch): fires when the tunnel is ready and
        // the app keeps sending upstream requests but downstream comes back as a useless trickle
        // (requestDelta >= 8x responseDelta) for a sustained window. Fills the gap where a thin
        // non-zero response stream keeps lastProgressAt fresh (so traffic_no_response never fires)
        // while failures stay too sparse for the storm/accumulated thresholds. Observed live on
        // Tele2 TCP: deltaReq≈40k vs deltaResp≈3k for 100+s with recovering=false.
        private const val STALL_RATIO_MIN_REQUEST_BYTES = 8_192L
        private const val STALL_RATIO_RESPONSE_DIVISOR = 8L
        private const val STALL_RATIO_RECOVERY_WINDOW_MS = 25_000L
        private const val HEAVY_UPLOAD_DELTA_BYTES = 256L * 1024L
        private const val HEAVY_UPLOAD_GRACE_MS = 120_000L
        private const val BACKGROUND_RESOLVER_PROBE_DELAY_MS = 1_500L
        private const val BACKGROUND_RESOLVER_SWITCH_COOLDOWN_MS = 2_000L
        private const val START_READY_TIMEOUT_MS = 8_000L
        private const val RECOVERY_READY_TIMEOUT_MS = 5_000L

        // Answer-type payload density varies a lot (crates/slipstream-dns's codec): TXT/HTTPS/NULL carry
        // the handshake near-losslessly in one record, but A/AAAA/CNAME/MX/SRV need multiple answer
        // records per exchange (as little as ~25% of TXT's bytes/round-trip for A), so completing the
        // same handshake takes proportionally more DNS round trips -- and therefore more wall-clock time,
        // especially through a real recursive resolver hop (authoritative path mode) rather than a direct
        // query. Scale the ready-timeout by qtype so picking a less-suspicious/less-dense type doesn't
        // make an otherwise-healthy, just-slower connection look like a hard failure.
        private fun readyTimeoutMs(baseMs: Long, qtype: Int): Long = when (qtype) {
            1 -> baseMs * 3 // A: ~25% density
            5, 28 -> baseMs * 2 // CNAME, AAAA: ~57% density
            15, 33 -> baseMs * 2 // MX, SRV: ~53% density
            else -> baseMs // TXT (16), HTTPS (65), NULL (10): near-100% density, baseline
        }
        // Detached-native-thread spiral -> clean process restart. One or two EADDRINUSE incidents
        // are handled by the fresh-port escape; 3 within 2 minutes is a genuine spiral of leaked,
        // CPU-burning native threads, at which point a process restart (the only thing that reaps
        // a wedged native thread) is cheaper than accumulating burned cores.
        private const val DETACHED_RESTART_THRESHOLD = 3
        private const val DETACHED_RESTART_WINDOW_MS = 120_000L
        private const val PROCESS_RESTART_DELAY_MS = 1_500L
        private const val RESTART_REQUEST_CODE = 4711
        /** How long a restart waits for the previous tunnel's native cleanup to finish. */
        private const val TEARDOWN_WAIT_MS = 10_000L
        private const val S3FU_SOCKS_READY_TIMEOUT_MS = 8_000L
        private const val TURNFU_CONFIG_TIMEOUT_MS = 180_000L
        private const val TURNFU_RESTART_DELAY_MS = 3_000L
        private const val TURNFU_RESTART_WINDOW_MS = 10 * 60_000L
        private const val TURNFU_MAX_RESTARTS = 5
        private const val TURNFU_CAPTCHA_TIMEOUT_MS = 120_000L
        private const val TURNFU_CAPTCHA_NOTIFICATION_ID = 4710
        private const val XRAY_SOCKS_READY_TIMEOUT_MS = 8_000L
    }
}
