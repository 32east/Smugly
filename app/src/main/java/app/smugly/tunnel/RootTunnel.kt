package app.smugly.tunnel

import android.content.Context
import android.os.SystemClock
import app.smugly.PerAppFilter
import app.smugly.PerAppMode
import app.smugly.RootRouting
import app.smugly.TunSettings
import app.smugly.util.AppLog
import java.io.File

object RootTunnel {
    private const val TAG = "RootTunnel"
    private const val HEV_BINARY = "libhevroot.so"
    private const val START_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 2_000L
    private const val BEAT_TIMEOUT_MS = 12_000L
    private const val SU_READ_EVERY_MS = 6_000L
    private const val SU_TIMEOUT_MS = 5_000L

    data class Request(
        val socksPort: Int,
        val username: String?,
        val password: String?,
        val udpMode: String,
        val mapDns: Boolean,
        val tun: TunSettings,
        val dnsServers: List<String>,
        val hotspot: Boolean,
        val perAppMode: PerAppMode,
        val perAppPackages: Set<String>,
        val attachCommand: String? = null,
        val tunAddressV4: String? = null,
        val tunAddressV6: String? = null,
        val mtu: Int? = null,
        val routeMtuV4: Int? = null
    )

    @Volatile private var active = false
    @Volatile private var hotspotOnly = false
    @Volatile private var ownUidTunnelled = false
    @Volatile private var pendingOwnUidTunnelled = false
    @Volatile private var beating = false
    @Volatile private var lastBeatAt = 0L
    @Volatile private var stats = HevSocks5Tunnel.TrafficStats(0, 0, 0, 0)
    private var generation = 0

    fun isActive(): Boolean = active

    fun ownsTunnel(): Boolean = active && !hotspotOnly

    fun isRunning(): Boolean = active && SystemClock.uptimeMillis() - lastBeatAt < BEAT_TIMEOUT_MS

    fun stats(): HevSocks5Tunnel.TrafficStats = if (ownsTunnel()) stats else HevSocks5Tunnel.TrafficStats(0, 0, 0, 0)

    fun startHotspotOnly(context: Context, vpnIface: String): Result<Unit> {
        if (!RootShell.available()) {
            return Result.failure(IllegalStateException("root access is not available"))
        }
        val app = context.applicationContext
        val dir = File(app.filesDir, "root").apply { mkdirs() }
        val files = Files(dir)
        files.clearStatus()
        val spec = RootRouting.Spec(
            dir = dir.absolutePath,
            hevPath = "",
            hevConfigPath = files.config.absolutePath,
            tun = TunSettings(),
            uidRanges = emptyList(),
            dnsTarget = "",
            hotspot = true,
            vpnIface = vpnIface
        )
        AppLog.i(TAG, "start hotspot sharing through $vpnIface")
        files.script.writeText(RootRouting.daemonScript(spec))
        return launch(files, hotspotOnly = true)
    }

    fun start(context: Context, request: Request): Result<Unit> {
        if (!RootShell.available()) {
            return Result.failure(IllegalStateException("root access is not available"))
        }
        val app = context.applicationContext
        val hev = File(app.applicationInfo.nativeLibraryDir, HEV_BINARY)
        val attached = request.attachCommand != null
        if (!attached && !hev.exists()) {
            return Result.failure(IllegalStateException("tun2socks helper is missing: ${hev.path}"))
        }
        val dir = File(app.filesDir, "root").apply { mkdirs() }
        val files = Files(dir)
        files.clearStatus()

        val ownUid = app.applicationInfo.uid.toLong()
        val plan = PerAppFilter.plan(request.perAppMode, request.perAppPackages, app.packageName, includeSelf = attached)
        val ranges = RootRouting.uidRanges(
            allowed = uidsOf(app, plan.allowed),
            excluded = uidsOf(app, plan.disallowed) + (if (attached) emptyList() else listOf(ownUid))
        )
        val spec = RootRouting.Spec(
            dir = dir.absolutePath,
            hevPath = hev.absolutePath,
            hevConfigPath = files.config.absolutePath,
            tun = request.tun,
            uidRanges = ranges,
            dnsTarget = RootRouting.dnsTarget(
                if (request.mapDns) HevSocks5Tunnel.MAPDNS_ADDRESS else null,
                request.dnsServers
            ),
            hotspot = request.hotspot,
            dnsTcp = !request.mapDns,
            guardUid = ownUid,
            attachCommand = request.attachCommand,
            tunAddressV4 = request.tunAddressV4 ?: RootRouting.TUN_ADDRESS_V4,
            tunAddressV6 = request.tunAddressV6 ?: RootRouting.TUN_ADDRESS_V6,
            tunnelMask = if (attached) RootRouting.UNSELECTED_MASK else RootRouting.PROTECTED_FROM_VPN_MASK,
            mtu = request.mtu,
            routeMtuV4 = request.routeMtuV4
        )
        AppLog.i(
            TAG,
            "start ${if (attached) "attached engine" else "socks=127.0.0.1:${request.socksPort}"} udp=${request.udpMode} mapDns=${request.mapDns} " +
                "dnsTarget=${spec.dnsTarget} ranges=${ranges.size} hotspot=${request.hotspot} " +
                "perApp=${request.perAppMode} ipv6=${request.tun.ipv6} bypassLan=${request.tun.bypassLan}"
        )
        files.script.writeText(RootRouting.daemonScript(spec))
        files.config.writeText(
            HevSocks5Tunnel.buildConfig(
                address = "127.0.0.1",
                port = request.socksPort,
                username = request.username,
                password = request.password,
                udpMode = request.udpMode,
                mapDns = request.mapDns,
                mapDnsAddress = HevSocks5Tunnel.MAPDNS_ADDRESS,
                mtu = request.tun.mtu,
                tunnelName = RootRouting.IFACE,
                ipv6 = request.tun.ipv6
            )
        )
        files.config.setReadable(false, false)
        files.config.setReadable(true, true)
        pendingOwnUidTunnelled = ranges.any { ownUid in it.first..it.last }
        return launch(files, hotspotOnly = false)
    }

    private fun launch(files: Files, hotspotOnly: Boolean): Result<Unit> {
        val mine = startBeating(files)
        val launch = RootShell.run(RootRouting.launchCommand(files.script.absolutePath, files.log.absolutePath))
        if (!launch.ok) {
            stopBeating(mine)
            return Result.failure(IllegalStateException("could not launch the root helper: ${launch.output.trim()}"))
        }

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var status = ""
        var lastSu = 0L
        while (System.currentTimeMillis() < deadline) {
            status = files.readStatus() ?: run {
                val now = System.currentTimeMillis()
                if (now - lastSu >= 1_000L) {
                    lastSu = now
                    RootShell.run("cat ${RootRouting.quote(files.status.absolutePath)}").output.trim()
                } else {
                    ""
                }
            }
            if (status == "ready" || status.startsWith("error")) break
            Thread.sleep(150)
        }
        if (status != "ready") {
            val detail = failureDetail(files)
            AppLog.e(TAG, "root tunnel did not come up status='$status' $detail")
            stopBeating(mine)
            runCatching { RootShell.run(RootRouting.stopCommand(files.script.absolutePath), 20_000L) }
            return Result.failure(IllegalStateException(status.ifBlank { "timeout" } + " " + detail))
        }
        lastBeatAt = SystemClock.uptimeMillis()
        stats = HevSocks5Tunnel.TrafficStats(0, 0, 0, 0)
        this.hotspotOnly = hotspotOnly
        ownUidTunnelled = !hotspotOnly && pendingOwnUidTunnelled
        active = true
        AppLog.i(TAG, "root helper ready hotspotOnly=$hotspotOnly")
        return Result.success(Unit)
    }

    fun stop(context: Context, force: Boolean = false) {
        val wasActive = active
        active = false
        ownUidTunnelled = false
        synchronized(this) { beating = false; generation++ }
        val files = Files(File(context.applicationContext.filesDir, "root"))
        if (!wasActive && !force) return
        if (!files.script.exists()) return
        val result = RootShell.run(RootRouting.stopCommand(files.script.absolutePath), 20_000L)
        AppLog.i(TAG, "root tunnel stopped code=${result.code}")
    }

    fun routesOwnUid(): Boolean = active && ownUidTunnelled

    private fun uidsOf(context: Context, packages: List<String>): Set<Long> =
        packages.mapNotNullTo(mutableSetOf()) { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid.toLong() }.getOrNull()
        }

    private fun failureDetail(files: Files): String {
        val result = RootShell.run(
            "tail -n 12 ${RootRouting.quote(files.log.absolutePath)}; " +
                "tail -n 12 ${RootRouting.quote(files.hevLog.absolutePath)}"
        )
        return result.output.trim().replace('\n', '|').take(600)
    }

    private fun startBeating(files: Files): Int {
        val mine = synchronized(this) {
            beating = true
            ++generation
        }
        files.writeBeat(0)
        lastBeatAt = SystemClock.uptimeMillis()
        Thread({
            var counter = 1L
            var lastSuAt = 0L
            while (beating && generation == mine) {
                files.writeBeat(counter++)
                val parsed = files.readStats()
                if (parsed != null) {
                    if (System.currentTimeMillis() - parsed.modifiedAt < BEAT_TIMEOUT_MS) {
                        lastBeatAt = SystemClock.uptimeMillis()
                    }
                    stats = HevSocks5Tunnel.TrafficStats(0, parsed.tx, 0, parsed.rx)
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastSuAt >= SU_READ_EVERY_MS) {
                        lastSuAt = now
                        val out = RootShell.run(
                            "cat ${RootRouting.quote(files.stats.absolutePath)}; " +
                                "cat ${RootRouting.quote(files.status.absolutePath)}",
                            SU_TIMEOUT_MS
                        ).output.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (out.lastOrNull() == "ready") {
                            lastBeatAt = SystemClock.uptimeMillis()
                            Files.parseStats(out.firstOrNull(), now)?.let {
                                stats = HevSocks5Tunnel.TrafficStats(0, it.tx, 0, it.rx)
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "root-tunnel-beat").apply { isDaemon = true }.start()
        return mine
    }

    private fun stopBeating(mine: Int) {
        synchronized(this) {
            if (generation == mine) beating = false
        }
    }

    private class Files(dir: File) {
        val script = File(dir, "rootd.sh")
        val config = File(dir, "hev.yml")
        val log = File(dir, "rootd.log")
        val hevLog = File(dir, "hev.log")
        val status = File(dir, "status")
        val stats = File(dir, "stats")
        private val beat = File(dir, "alive")

        fun clearStatus() {
            status.delete()
            stats.delete()
        }

        fun writeBeat(counter: Long) {
            runCatching { beat.writeText(counter.toString()) }
        }

        fun readStatus(): String? = runCatching { status.readText().trim() }.getOrNull()

        fun readStats(): ParsedStats? = runCatching {
            parseStats(stats.readText(), stats.lastModified())
        }.getOrNull()

        data class ParsedStats(val rx: Long, val tx: Long, val modifiedAt: Long)

        companion object {
            fun parseStats(text: String?, modifiedAt: Long): ParsedStats? {
                val parts = text?.trim()?.split(' ', '\t')?.filter { it.isNotEmpty() } ?: return null
                if (parts.size < 2) return null
                val rx = parts[0].toLongOrNull() ?: return null
                val tx = parts[1].toLongOrNull() ?: return null
                return ParsedStats(rx, tx, modifiedAt)
            }
        }
    }
}
