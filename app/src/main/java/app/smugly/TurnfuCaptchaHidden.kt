package app.smugly

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.smugly.tunnel.TurnfuProcess
import kotlin.random.Random

object TurnfuCaptchaHidden {
    private const val TIMEOUT_MS = 13_000L
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var appContext: Context? = null
    private var bound = false
    private var session = 0
    private val expire = Runnable { finish("error:timeout") }

    fun start(context: Context, uri: String) {
        main.post { open(context.applicationContext, uri) }
    }

    fun cancel() {
        main.post { finish(null) }
    }

    private fun open(context: Context, uri: String) {
        finish(null)
        if (!TurnfuCaptchaWeb.isVkCaptchaUri(uri)) {
            TurnfuProcess.submitCaptcha("error:bad-uri")
            return
        }
        val view = runCatching { WebView(context) }.getOrElse {
            TurnfuProcess.log("captcha: hidden WebView unavailable: ${it.message}")
            TurnfuProcess.submitCaptcha("error:webview")
            return
        }
        val id = ++session
        appContext = context
        bound = TurnfuProcess.physicalNetwork(context)?.let { network ->
            context.getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(network)
        } == true
        TurnfuCaptchaWeb.prepare(view, Bridge(id), auto = true)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                TurnfuCaptchaWeb.inject(view, auto = true)
                url?.let(TurnfuCaptchaWeb::successToken)?.let { token -> finish(token) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                TurnfuCaptchaWeb.inject(view, auto = true)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                TurnfuCaptchaWeb.successToken(request.url.toString())?.let { token -> finish(token) }
                return false
            }
        }
        val metrics = context.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        view.onResume()
        view.resumeTimers()
        web = view
        main.postDelayed(expire, TIMEOUT_MS)
        TurnfuProcess.log("captcha: hidden WebView started")
        view.loadUrl(uri)
    }

    private fun finish(result: String?) {
        val view = web ?: return
        web = null
        main.removeCallbacks(expire)
        result?.let {
            TurnfuProcess.log("captcha: hidden WebView finished: ${if (it.startsWith("error:")) it else "token"}")
            TurnfuProcess.submitCaptcha(it)
        }
        runCatching {
            view.stopLoading()
            view.destroy()
        }
        if (bound) {
            appContext?.getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
            bound = false
        }
        appContext = null
    }

    private fun touch(view: WebView, cssX: Float, cssY: Float) {
        val density = view.resources.displayMetrics.density
        val x = cssX * density
        val y = cssY * density
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        view.dispatchTouchEvent(down)
        down.recycle()
        main.postDelayed({
            if (web !== view) return@postDelayed
            val up = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x + Random.nextFloat() - 0.5f, y + Random.nextFloat() - 0.5f, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            view.dispatchTouchEvent(up)
            up.recycle()
        }, Random.nextLong(70, 150))
    }

    private class Bridge(private val id: Int) {
        private fun onMain(action: (WebView) -> Unit) {
            main.post {
                val view = web
                if (view != null && session == id) action(view)
            }
        }

        @JavascriptInterface
        fun token(value: String?) {
            val token = value?.trim().orEmpty()
            if (token.isNotEmpty()) onMain { finish(token) }
        }

        @JavascriptInterface
        fun status(status: String?, kind: String?) {
            val code = status.orEmpty().uppercase()
            val type = kind.orEmpty().lowercase()
            val outcome = when {
                code == "ERROR_LIMIT" -> "error:limit"
                code.isNotEmpty() && code != "OK" && (type == "slider" || type == "sound") -> "error:$type"
                code == "BOT" || code == "ERROR_NOT_PASSED" || code == "ERROR_TOKEN_EXPIRED" -> "error:${code.lowercase()}"
                else -> return
            }
            onMain { finish(outcome) }
        }

        @JavascriptInterface
        fun profile(raw: String?) {
            onMain { TurnfuCaptchaWeb.saveProfile(it.context, raw) }
        }

        @JavascriptInterface
        fun tap(x: Float, y: Float) {
            onMain { touch(it, x, y) }
        }

        @JavascriptInterface
        fun fail(reason: String?) {
            val code = reason.orEmpty().filter { it in 'a'..'z' || it == '-' }.take(24).ifEmpty { "page" }
            onMain { finish("error:$code") }
        }

        @JavascriptInterface
        fun seen(state: String?) {
            TurnfuProcess.log("captcha: hidden page ${state.orEmpty().take(40)}")
        }
    }
}
