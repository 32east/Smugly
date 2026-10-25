package app.smugly.tunnel

import android.content.Context
import app.smugly.Config
import app.smugly.ConfigStore
import app.smugly.TurnfuCall
import app.smugly.TurnfuIpc
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object TurnfuProbe {
    private const val PROBE_WORKERS = 3
    private const val PROBE_TIMEOUT_MS = 75_000L
    private val lock = Any()

    fun measure(context: Context, config: Config): Result<Int> = runCatching {
        val host = config.turnfuHost.trim()
        require(host.isNotEmpty() && config.turnfuPassword.isNotBlank()) { "turnfu profile is incomplete" }
        if (TurnfuProcess.isTunnelUp() && TurnfuProcess.target == "$host:${config.turnfuPort}") return TurnfuProcess.ping()
        val calls = config.turnfuCalls.ifEmpty { listOfNotNull(TurnfuCall.url(ConfigStore.turnfuCallLink(context))) }
        require(calls.isNotEmpty() || config.turnfuToken.isNotEmpty()) { "VK call link is not set" }
        synchronized(lock) { run(context, config, calls) }
    }

    private fun run(context: Context, profile: Config, calls: List<String>): Int {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libturnfu.so")
        require(exe.canExecute()) { "turnfu engine is missing" }
        TurnfuProcess.useLogFile(context)
        val config = TurnfuProcess.writeConfiguration(context, TurnfuIpc.configuration(
            profile.turnfuHost, profile.turnfuPort, profile.turnfuPassword, calls, PROBE_WORKERS,
            probe = true, captcha = "manual", network = TurnfuProcess.physicalNetworkHandle(context) ?: 0L, token = profile.turnfuToken
        ))
        try {
            val process = ProcessBuilder(exe.absolutePath, "--config", config.absolutePath)
                .directory(context.filesDir).redirectErrorStream(true).start()
            val stdin = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
            val result = AtomicLong(Long.MIN_VALUE)
            fun send(command: String, key: String? = null, value: Any? = null) {
                runCatching {
                    synchronized(stdin) {
                        stdin.write(TurnfuIpc.command(command, key, value).toString() + "\n")
                        stdin.flush()
                    }
                }
            }
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        val event = TurnfuIpc.event(line)
                        when (event?.optString("t")) {
                            "ping" -> result.set(if (!event.isNull("ms")) event.optLong("ms") else -1L)
                            "stats" -> Unit
                            "captcha" -> send("captcha_result", "token", "error:probe")
                            else -> TurnfuProcess.log("probe: $line")
                        }
                    }
                }
            }, "turnfu-probe").apply { isDaemon = true; start() }
            try {
                if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    send("stop")
                    if (!process.waitFor(4_000, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                }
                reader.join(2_000)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                runCatching { stdin.close() }
            }
            val ms = result.get()
            check(ms != Long.MIN_VALUE) { "the probe session did not finish" }
            check(ms >= 0) { "no echo reply through the tunnel" }
            return ms.toInt()
        } finally {
            config.delete()
        }
    }
}
