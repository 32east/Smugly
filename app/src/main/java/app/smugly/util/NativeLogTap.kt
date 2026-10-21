package app.smugly.util

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Locale

object NativeLogTap {
    private const val TAG = "NativeLogTap"
    private val ENGINE_TAGS = listOf("s3fu", "cdnfu")
    private val LOGCAT_LINE = Regex("""^([VDIWEFA])/([^\s:]+)\s*: ?(.*)$""")
    private val TRACING_LINE = Regex("""^\d{4}-\d{2}-\d{2}T\S+\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+(.*)$""")
    private const val RESTART_MIN_MS = 1_000L
    private const val RESTART_MAX_MS = 30_000L
    private const val HEALTHY_RUN_MS = 60_000L

    private val lock = Any()
    @Volatile private var wanted = false
    private var worker: Thread? = null
    private var process: java.lang.Process? = null

    fun start() {
        synchronized(lock) {
            wanted = true
            if (worker?.isAlive == true) return
            worker = Thread(::loop, "native-log-tap").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            wanted = false
            process?.destroy()
            process = null
        }
    }

    private fun loop() {
        var sinceMs = System.currentTimeMillis()
        var backoffMs = RESTART_MIN_MS
        while (wanted) {
            val startedAt = SystemClock.elapsedRealtime()
            val proc = runCatching { spawn(sinceMs) }
                .onFailure { Log.w(TAG, "logcat spawn failed: ${it.message}") }
                .getOrNull()
            if (proc != null) {
                synchronized(lock) {
                    if (!wanted) {
                        proc.destroy()
                        return
                    }
                    process = proc
                }
                runCatching {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            sinceMs = System.currentTimeMillis()
                            forward(line)
                        }
                    }
                }
                proc.destroy()
                synchronized(lock) { if (process === proc) process = null }
            }
            if (!wanted) return
            if (SystemClock.elapsedRealtime() - startedAt >= HEALTHY_RUN_MS) backoffMs = RESTART_MIN_MS
            Log.w(TAG, "logcat exited; restarting in ${backoffMs}ms")
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                return
            }
            backoffMs = (backoffMs * 2).coerceAtMost(RESTART_MAX_MS)
        }
    }

    private fun spawn(sinceMs: Long): java.lang.Process {
        val cmd = mutableListOf(
            "logcat", "-v", "tag",
            "-T", String.format(Locale.US, "%d.%03d", sinceMs / 1000, sinceMs % 1000),
            "--pid=${Process.myPid()}"
        )
        ENGINE_TAGS.forEach { cmd += "$it:V" }
        cmd += "*:S"
        return ProcessBuilder(cmd).redirectErrorStream(true).start()
    }

    private fun forward(raw: String) {
        val line = LOGCAT_LINE.matchEntire(raw) ?: return
        val tag = line.groupValues[2]
        val body = line.groupValues[3]
        val tracing = TRACING_LINE.matchEntire(body)
        if (tracing == null) {
            AppLog.mirror(logcatPriority(line.groupValues[1]), tag, body)
            return
        }
        val priority = when (tracing.groupValues[1]) {
            "ERROR" -> Log.ERROR
            "WARN" -> Log.WARN
            "INFO" -> Log.INFO
            else -> return
        }
        AppLog.mirror(priority, tag, tracing.groupValues[2])
    }

    private fun logcatPriority(letter: String): Int = when (letter) {
        "E", "F", "A" -> Log.ERROR
        "W" -> Log.WARN
        "D" -> Log.DEBUG
        "V" -> Log.VERBOSE
        else -> Log.INFO
    }
}
