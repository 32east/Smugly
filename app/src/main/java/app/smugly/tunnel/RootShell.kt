package app.smugly.tunnel

import app.smugly.util.AppLog
import java.util.concurrent.TimeUnit

object RootShell {
    private const val TAG = "RootShell"
    private const val PROBE_TIMEOUT_MS = 30_000L

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    @Volatile private var granted: Boolean? = null

    fun cachedAvailability(): Boolean? = granted

    fun available(force: Boolean = false): Boolean {
        if (!force && granted == true) return true
        val result = run("id -u", PROBE_TIMEOUT_MS)
        val ok = result.ok && result.output.trim().lineSequence().lastOrNull() == "0"
        granted = ok
        AppLog.i(TAG, "su probe ok=$ok code=${result.code}")
        return ok
    }

    fun run(script: String, timeoutMs: Long = 15_000L): Result {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            AppLog.w(TAG, "su is not available: ${e.message}")
            return Result(-1, e.message.orEmpty())
        }
        return try {
            process.outputStream.bufferedWriter().use {
                it.write(script)
                it.write("\nexit\n")
            }
            val output = StringBuilder()
            val reader = Thread({
                runCatching { process.inputStream.bufferedReader().forEachLine { line -> output.appendLine(line) } }
            }, "root-shell-read").apply { isDaemon = true }
            reader.start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                AppLog.w(TAG, "su timed out after ${timeoutMs}ms")
                return Result(-2, output.toString())
            }
            reader.join(1_000)
            Result(process.exitValue(), output.toString())
        } catch (e: Exception) {
            process.destroyForcibly()
            Result(-1, e.message.orEmpty())
        }
    }
}
