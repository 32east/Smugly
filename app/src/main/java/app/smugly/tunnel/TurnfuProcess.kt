package app.smugly.tunnel

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import app.smugly.util.AppLog
import app.smugly.TurnfuIpc
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TurnfuProcess {
    private const val TAG = "turnfu"
    private const val BINARY = "libturnfu.so"
    private const val LOG_FILE = "turnfu.log"
    private const val LOG_MAX_BYTES = 2L * 1024 * 1024
    const val GATEWAY = "10.66.67.1"
    const val HTTP_PROXY_PORT = 3128

    data class Request(
        val host: String,
        val port: Int,
        val password: String,
        val callLink: String,
        val workers: Int,
        val netHandle: Long? = null,
        val accountToken: String = ""
    )

    data class TunConfig(val ip: String, val dns: List<String>, val ip6: String? = null)

    interface Listener {
        fun onConfig(config: TunConfig)
        fun onStats(active: Int, up: Long, down: Long)
        fun onCaptcha(uri: String)
        fun onCaptchaAuto(uri: String)
        fun onError(code: String, message: String, fatal: Boolean)
        fun onExit(code: Int)
    }

    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStreamWriter? = null
    @Volatile private var udsName: String = ""
    @Volatile private var lastError: String? = null
    @Volatile private var lastConfig: TunConfig? = null
    @Volatile private var tunAttached = false
    @Volatile var bytesUp = 0L
        private set
    @Volatile var bytesDown = 0L
        private set
    @Volatile var target = ""
        private set
    private val pingLock = Any()
    private val pingResults = java.util.concurrent.LinkedBlockingQueue<Long>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val logLock = Any()
    private var logFile: File? = null
    private var attachConfig: File? = null

    fun isAvailable(context: Context): Boolean = binary(context).canExecute()

    fun isRunning(): Boolean = process?.isAlive == true

    fun isTunnelUp(): Boolean = tunAttached && isRunning()

    fun ping(timeoutMs: Long = 7_000): Result<Int> = runCatching {
        synchronized(pingLock) {
            check(isTunnelUp()) { "turnfu tunnel is not up" }
            pingResults.clear()
            send(TurnfuIpc.command("ping"))
            val ms = pingResults.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("no echo reply in ${timeoutMs}ms")
            check(ms >= 0) { "no echo reply through the tunnel" }
            ms.toInt()
        }
    }

    fun lastError(): String? = lastError

    fun logFile(context: Context): File = File(context.filesDir, LOG_FILE)

    fun parseTunConf(event: JSONObject): TunConfig? = TurnfuIpc.tunnelConfig(event)?.let { TunConfig(it.ip, it.dns, it.ip6) }

    internal fun writeConfiguration(context: Context, config: JSONObject): File {
        val file = File.createTempFile("turnfu-", ".json", context.noBackupFilesDir)
        file.setReadable(false, false)
        file.setWritable(false, false)
        check(file.setReadable(true, true) && file.setWritable(true, true)) { "cannot protect engine configuration" }
        file.writeText(config.toString(), Charsets.UTF_8)
        return file
    }

    @Synchronized
    fun start(context: Context, request: Request, listener: Listener): Result<Unit> = runCatching {
        stop()
        val exe = binary(context)
        require(exe.canExecute()) { "turnfu engine is missing: ${exe.absolutePath}" }
        logFile = logFile(context)
        lastError = null
        lastConfig = null
        tunAttached = false
        bytesUp = 0
        bytesDown = 0
        target = "${request.host}:${request.port}"
        val random = SecureRandom()
        udsName = "smugly_turnfu_" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val peer = if (request.host.contains(':') && !request.host.startsWith("[")) {
            "[${request.host}]:${request.port}"
        } else {
            "${request.host}:${request.port}"
        }
        val config = writeConfiguration(context, TurnfuIpc.configuration(
            request.host, request.port, request.password, request.callLink.split(',').filter { it.isNotBlank() }, request.workers,
            tunSocket = udsName, network = request.netHandle ?: 0L, token = request.accountToken
        ))
        val builder = ProcessBuilder(exe.absolutePath, "--config", config.absolutePath)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        log("start peer=$peer workers=${request.workers}")
        val started = try { builder.start() } catch (error: Exception) { config.delete(); throw error }
        process = started
        stdin = OutputStreamWriter(started.outputStream, Charsets.UTF_8)
        Thread({ pump(started, listener, config) }, "turnfu-stdout").apply { isDaemon = true }.start()
    }

    fun sendTunFd(tun: ParcelFileDescriptor, timeoutMs: Long = 10_000): Boolean {
        val name = udsName
        if (name.isEmpty()) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && isRunning()) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 5_000
                socket.setFileDescriptorsForSend(arrayOf<FileDescriptor>(tun.fileDescriptor))
                socket.outputStream.write(1)
                socket.outputStream.flush()
                val ack = socket.inputStream.read()
                log("tun fd handed over ack=$ack")
                tunAttached = ack == 1
                return ack == 1
            } catch (e: Exception) {
                Thread.sleep(100)
            } finally {
                runCatching { socket.close() }
            }
        }
        log("tun fd hand-over failed")
        return false
    }

    fun setNetwork(handle: Long) {
        log("bind to network $handle")
        send(TurnfuIpc.command("net", "handle", handle))
    }

    fun attachTunCommand(context: Context, iface: String): String? {
        val name = udsName.takeIf { it.isNotEmpty() } ?: return null
        val quote = app.smugly.RootRouting::quote
        attachConfig?.delete()
        val config = writeConfiguration(context, JSONObject().put("mode", "attach_tun").put("tun_name", iface).put("tun_socket", name))
        attachConfig = config
        return "${quote(binary(context).absolutePath)} --config ${quote(config.absolutePath)}"
    }

    fun markTunAttached() {
        tunAttached = isRunning()
    }

    fun physicalNetwork(context: Context): android.net.Network? {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            network to ((if (validated) 2 else 0) + (if (wifi) 1 else 0))
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    fun physicalNetworkHandle(context: Context): Long? = physicalNetwork(context)?.networkHandle

    fun submitCaptcha(result: String) {
        send(TurnfuIpc.command("captcha_result", "token", result.trim()))
    }

    @Synchronized
    fun stop(timeoutMs: Long = 4_000) {
        app.smugly.TurnfuCaptchaHidden.cancel()
        attachConfig?.delete()
        attachConfig = null
        val p = process ?: return
        log("stop")
        send(TurnfuIpc.command("stop"))
        runCatching { stdin?.close() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroy()
            if (!p.waitFor(1_000, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        process = null
        stdin = null
        udsName = ""
        tunAttached = false
    }

    private fun send(command: JSONObject) {
        val writer = stdin ?: return
        runCatching {
            synchronized(writer) {
                writer.write(command.toString())
                writer.write("\n")
                writer.flush()
            }
        }.onFailure { log("stdin write failed: ${it.message}") }
    }

    private fun pump(p: Process, listener: Listener, config: File) {
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line -> handle(line, listener) }
        } catch (e: Exception) {
            log("stdout closed: ${e.message}")
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        config.delete()
        log("exit code=$code")
        if (process === p) {
            process = null
            stdin = null
            tunAttached = false
        }
        listener.onExit(code)
    }

    private fun handle(line: String, listener: Listener) {
        val json = TurnfuIpc.event(line)
        if (json == null) { log(line); return }
        when (val kind = json.optString("t")) {
            "config" -> parseTunConf(json)?.let { cfg ->
                log("configuration ip=${cfg.ip}")
                if (cfg != lastConfig) { lastConfig = cfg; listener.onConfig(cfg) }
            }
            "stats" -> {
                bytesUp = json.optLong("up")
                bytesDown = json.optLong("down")
                listener.onStats(json.optInt("active"), bytesUp, bytesDown)
            }
            "ping" -> pingResults.offer(if (json.has("ms") && !json.isNull("ms")) json.optLong("ms") else -1L)
            "error" -> {
                val code = json.optString("code")
                val message = json.optString("message")
                lastError = "$code: $message"
                log("error $code fatal=${json.optBoolean("fatal")} $message")
                listener.onError(code, message, json.optBoolean("fatal"))
            }
            "captcha" -> {
                val mode = json.optString("mode")
                val uri = json.optString("url")
                log("captcha requested ($mode)")
                if (uri.startsWith("https://")) {
                    if (mode == "auto") listener.onCaptchaAuto(uri) else listener.onCaptcha(uri)
                }
            }
            else -> log("event $kind $json")
        }
    }

    internal fun useLogFile(context: Context) {
        if (logFile == null) logFile = logFile(context)
    }

    internal fun log(message: String) {
        android.util.Log.i(TAG, message)
        AppLog.mirror(android.util.Log.INFO, TAG, message)
        val file = logFile ?: return
        synchronized(logLock) {
            runCatching {
                if (file.length() > LOG_MAX_BYTES) {
                    val old = File(file.parentFile, "$LOG_FILE.1")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText("${stamp.format(Date())} $message\n")
            }
        }
    }

    private fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, BINARY)
}
