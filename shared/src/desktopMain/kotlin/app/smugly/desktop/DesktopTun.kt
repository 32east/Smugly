package app.smugly.desktop

import app.smugly.PerAppMode
import app.smugly.platform.AppPaths
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import org.json.JSONObject

/**
 * System-wide TUN mode: a virtual adapter owns the default route, so **every** app is tunnelled,
 * not just the ones that bother to read the Windows proxy setting.
 *
 * This is the difference the mode exists for. The system-proxy path only reaches WinINET-aware
 * software — games, Electron apps and anything on raw sockets ignore it and leave in the clear,
 * which is a leak the user cannot see. A TUN has no opt-out.
 *
 * The adapter and the userspace TCP/IP stack on top of it come from sing-box; see [TunConfig] for
 * why that has to be the same process that applies the per-app rules. sing-box hands everything to
 * the engine's own SOCKS5 listener — not to [MixedProxyServer], which is CONNECT-only and would
 * silently drop every UDP flow the adapter captures.
 */
object DesktopTun {
    private const val TAG = "DesktopTun"
    private const val ENGINE = "sing-box"

    /** Creating the adapter and rewriting the routing table both need an administrator token. */
    private const val ADMIN_HINT =
        "TUN mode needs administrator rights: the virtual adapter and the routes it installs are " +
            "privileged operations. Restart Smugly as administrator, or use proxy mode."

    private const val READY_TIMEOUT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 250L

    /** The TUN's own address; its presence on an interface is proof the adapter came up. */
    private const val TUN_HOST_ADDRESS = "172.19.0.1"

    @Volatile private var process: EngineProcess? = null
    @Volatile private var apiPort: Int = 0
    @Volatile private var apiSecret: String = ""
    @Volatile private var rx: Long = 0
    @Volatile private var tx: Long = 0

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Bytes the tunnel has actually carried, from sing-box's own control API.
     *
     * Needed because in TUN mode the local proxy is no longer in the path and its counters would
     * sit at zero forever — a connected tunnel reporting "0 B" reads as broken. Refreshed by
     * [refreshTraffic] rather than on read, so the 1 Hz UI tick does not turn into an HTTP call
     * per repaint.
     */
    fun rxBytes(): Long = rx
    fun txBytes(): Long = tx

    /**
     * Poll the control API for the running totals. Cheap and best-effort: a failure means the
     * numbers stay where they were, never that the tunnel is considered down.
     */
    fun refreshTraffic() {
        val port = apiPort
        if (port == 0 || !isRunning) return
        runCatching {
            val connection = (URL("http://127.0.0.1:$port/connections").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = 800
                    readTimeout = 800
                    setRequestProperty("Authorization", "Bearer $apiSecret")
                }
            try {
                if (connection.responseCode !in 200..299) return
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                rx = json.optLong("downloadTotal", rx)
                tx = json.optLong("uploadTotal", tx)
            } finally {
                connection.disconnect()
            }
        }
    }

    fun logTail(): String = process?.logTail().orEmpty()

    /** TUN mode is Windows-only for now — Linux/macOS need their own adapter and route handling. */
    fun isSupported(): Boolean = WindowsSystemProxy.isWindows

    /**
     * True when this process holds an elevated token.
     *
     * There is no JVM API for it, so ask Windows: an elevated process runs at the *high* integrity
     * level, whose well-known SID is S-1-16-12288. That is a stronger test than looking for
     * membership of Administrators, which is present but *disabled* in a normal token — the exact
     * case where the adapter would fail to be created despite the user being an admin.
     */
    fun isElevated(): Boolean = runCatching {
        val proc = ProcessBuilder("whoami", "/groups")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        out.contains("S-1-16-12288")
    }.getOrElse {
        PlatformLog.log(LogLevel.WARN, TAG, "could not determine elevation: ${it.message}")
        false
    }

    /** sing-box.exe, or null when it has not been placed next to the other engines. */
    fun binary(): File? = EngineBinaries.find(ENGINE)

    /**
     * Wintun's driver DLL. sing-box loads it from its own directory, so that is where it must be —
     * a missing DLL surfaces as a bare "unable to create adapter" with nothing pointing at the
     * cause.
     */
    fun wintun(): File? {
        val exe = binary() ?: return null
        val next = File(exe.parentFile, "wintun.dll")
        return next.takeIf { it.isFile } ?: EngineBinaries.findAsset("wintun.dll")
    }

    /**
     * Everything that has to be true before TUN mode can start, as one message — or null when the
     * machine is ready. Checked up front so the user is told what to fix instead of watching the
     * connect fail.
     */
    fun preflight(): String? = when {
        !isSupported() -> "TUN mode is only available on Windows."
        binary() == null ->
            "engine 'sing-box.exe' not found. Put it in ${File(AppPaths.filesDir(), "engines")} " +
                "or set SMUGLY_ENGINE_DIR."
        wintun() == null ->
            "wintun.dll not found next to sing-box.exe — the TUN adapter cannot be created without it."
        !isElevated() -> ADMIN_HINT
        else -> null
    }

    /**
     * Bring the adapter up in front of an already-running engine.
     *
     * @param socksPort the **engine's** SOCKS5 listener (see [TunConfig] — the local proxy cannot
     *        carry UDP)
     * @param ownProcesses this app's executable name(s), never routed into the tunnel
     */
    fun start(
        socksPort: Int,
        mode: PerAppMode,
        processes: Set<String>,
        ownProcesses: List<String>,
        debugLog: Boolean = false
    ): Result<Unit> = runCatching {
        stop()
        preflight()?.let { error(it) }
        val exe = binary() ?: error("sing-box.exe not found")

        apiPort = freePort()
        apiSecret = newSecret()
        rx = 0
        tx = 0
        val configFile = File(configDir(), "tun.json")
        configFile.writeText(
            TunConfig.build(
                socksPort = socksPort,
                mode = mode,
                processes = processes,
                ownProcesses = ownProcesses,
                logLevel = if (debugLog) "info" else "warn",
                clashApiPort = apiPort,
                clashApiSecret = apiSecret
            )
        )

        val proc = EngineProcess(
            name = ENGINE,
            // `run -c` rather than a directory: one file, one source of truth, and the path is
            // ours so a stale config from another tool can never be picked up.
            command = listOf(exe.absolutePath, "run", "-c", configFile.absolutePath),
            // sing-box resolves wintun.dll relative to its own directory.
            workingDir = exe.parentFile
        )
        proc.reapStale()
        proc.start().getOrThrow()
        process = proc

        if (!waitForAdapter(READY_TIMEOUT_MS) { proc.isAlive }) {
            val tail = proc.logTail().lines().takeLast(12).joinToString("\n")
            proc.stop()
            process = null
            error(
                "the TUN adapter did not come up within ${READY_TIMEOUT_MS / 1000}s.\n" +
                    tail.ifBlank { "(sing-box printed nothing)" }
            )
        }
        PlatformLog.log(
            LogLevel.INFO, TAG,
            "TUN up: adapter=${TunConfig.INTERFACE_NAME} socks=127.0.0.1:$socksPort mode=$mode " +
                "apps=${processes.size}"
        )
    }.onFailure {
        PlatformLog.log(LogLevel.ERROR, TAG, "TUN start failed: ${it.message}")
        stop()
    }

    fun stop() {
        // Killing the process is the whole teardown: Wintun adapters are owned by their creator,
        // so the adapter and every route sing-box installed disappear with it. Nothing to unwind
        // by hand, and nothing left behind if we are killed instead of asked.
        process?.stop()
        process = null
        apiPort = 0
        apiSecret = ""
    }

    /**
     * A port the OS just told us was free, rather than a hardcoded 9090 — that one is the Clash
     * default and is very often already taken by another tool on exactly the machines this app
     * runs on.
     */
    private fun freePort(): Int = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrDefault(0)

    /** Anyone who can reach the control API can rewrite routing, so the token is not decoration. */
    private fun newSecret(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Wait for the adapter to actually carry its address.
     *
     * The process being alive is not the same thing: sing-box starts, *then* creates the adapter
     * and writes routes, and a connect reported as ready before that races every first packet.
     */
    private fun waitForAdapter(timeoutMs: Long, isAlive: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) return false
            if (adapterIsUp()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** True once some interface holds the TUN's address. */
    fun adapterIsUp(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { nic ->
            nic.inetAddresses.asSequence().any {
                it is Inet4Address && it.hostAddress == TUN_HOST_ADDRESS
            }
        }
    }.getOrDefault(false)

    private fun configDir(): File =
        File(AppPaths.filesDir(), "engines").apply { mkdirs() }

    /**
     * This app's own executable name(s), for the never-tunnel list.
     *
     * Packaged, that is `Smugly5.exe`; from a Gradle run it is the JVM launcher, and both spellings
     * of it are included because which one is used depends on how the app was started. Getting this
     * wrong is not a cosmetic bug — it is a routing loop.
     */
    fun ownProcessNames(): List<String> {
        val current = runCatching {
            ProcessHandle.current().info().command().orElse(null)
                ?.substringAfterLast('\\')
                ?.substringAfterLast('/')
        }.getOrNull()
        return listOfNotNull(current, "javaw.exe", "java.exe").distinct()
    }

    /** One line for Diagnostics: what is present and what is missing. */
    fun report(): String = buildString {
        appendLine("tun supported: ${isSupported()}")
        appendLine("sing-box: ${binary()?.absolutePath ?: "NOT FOUND"}")
        appendLine("wintun.dll: ${wintun()?.absolutePath ?: "NOT FOUND"}")
        appendLine("elevated: ${isElevated()}")
        append("adapter up: ${adapterIsUp()}")
    }
}
