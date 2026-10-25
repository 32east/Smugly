package app.smugly

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.smugly.tunnel.TurnfuProcess

class TurnfuCaptchaActivity : Activity() {
    private var web: WebView? = null
    private var done = false
    private var bound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI)
        if (uri.isNullOrBlank() || !TurnfuCaptchaWeb.isVkCaptchaUri(uri)) {
            finish()
            return
        }
        bound = TurnfuProcess.physicalNetwork(this)?.let { network ->
            getSystemService(android.net.ConnectivityManager::class.java)?.bindProcessToNetwork(network)
        } == true
        val view = WebView(this)
        TurnfuCaptchaWeb.prepare(view, Bridge(), auto = false)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                TurnfuCaptchaWeb.inject(view, auto = false)
                url?.let(::checkUrl)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                TurnfuCaptchaWeb.inject(view, auto = false)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                checkUrl(request.url.toString())
                return false
            }
        }
        web = view
        setContentView(view)
        view.loadUrl(uri)
    }

    private fun checkUrl(url: String) {
        TurnfuCaptchaWeb.successToken(url)?.let(::deliver)
    }

    private fun deliver(token: String) {
        if (done) return
        done = true
        TurnfuProcess.submitCaptcha(token)
        finish()
    }

    override fun onDestroy() {
        if (!done) {
            done = true
            TurnfuProcess.submitCaptcha("error:cancelled")
        }
        web?.destroy()
        web = null
        if (bound) getSystemService(android.net.ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun token(value: String?) {
            val token = value?.trim().orEmpty()
            if (token.isNotEmpty()) runOnUiThread { deliver(token) }
        }

        @JavascriptInterface
        fun status(status: String?, kind: String?) {
        }

        @JavascriptInterface
        fun profile(raw: String?) {
            runOnUiThread { TurnfuCaptchaWeb.saveProfile(applicationContext, raw) }
        }
    }

    companion object {
        const val EXTRA_URI = "app.smugly.extra.CAPTCHA_URI"
    }
}
