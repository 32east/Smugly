package app.smugly

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import app.smugly.net.LocalConnectProxy
import app.smugly.ui.components.TopBar
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyCard
import app.smugly.ui.theme.SmuglyTheme
import java.lang.ref.WeakReference

class CabinetActivity : ComponentActivity() {

    companion object {
        private const val LOG_TAG = "SmuglyCabinet"
        const val EXTRA_URL = "url"
        const val EXTRA_EXTERNAL_LOGIN_URL = "external_login_url"

        @Volatile
        var onWake: (() -> Unit)? = null

        @Volatile
        var onPaid: (() -> Unit)? = null

        @Volatile
        var upstream: () -> LocalConnectProxy.Upstream = { LocalConnectProxy.Upstream.Direct }

        private val proxy by lazy { LocalConnectProxy { upstream() } }

        private var current: WeakReference<CabinetActivity>? = null

        fun isOpen(): Boolean = current?.get()?.let { !it.isFinishing } == true

        fun closeIfOpen() {
            current?.get()?.finish()
        }

        fun forgetSession() {
            closeIfOpen()
            runCatching { WebStorage.getInstance().deleteAllData() }
                .onFailure { Log.w(LOG_TAG, "web storage wipe failed: ${it.message}") }
            runCatching {
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            }.onFailure { Log.w(LOG_TAG, "cookie wipe failed: ${it.message}") }
        }
    }

    private lateinit var webView: WebView
    private var externalLoginUrl: String? = null
    private var progress by mutableStateOf(1f)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        current = WeakReference(this)
        externalLoginUrl = intent.getStringExtra(EXTRA_EXTERNAL_LOGIN_URL)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF111111.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = settings.userAgentString + " SmuglyApp/" + AppVersion.name
            webViewClient = CabinetClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@CabinetActivity.progress = newProgress / 100f
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.i(LOG_TAG, "console ${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                    return true
                }
            }
            setDownloadListener { downloadUrl, _, _, _, _ -> openOutside(Uri.parse(downloadUrl)) }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        routeThroughTunnel {
            if (isFinishing || isDestroyed) return@routeThroughTunnel
            if (savedInstanceState != null) {
                webView.restoreState(savedInstanceState)
            } else {
                webView.loadUrl(url)
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        setContent {
            SmuglyTheme {
                Column(Modifier.fillMaxSize().background(SmuglyBg)) {
                    TopBar(title = t(S.WEB_CABINET), onBack = { finish() })
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        if (progress < 1f) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize(),
                                color = SmuglyAccent,
                                trackColor = SmuglyCard
                            )
                        }
                    }
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun routeThroughTunnel(then: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            then()
            return
        }
        val port = runCatching { proxy.start() }.getOrNull()
        if (port == null) {
            then()
            return
        }
        val config = ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:$port")
            .build()
        runCatching {
            ProxyController.getInstance().setProxyOverride(config, { it.run() }) { runOnUiThread(then) }
        }.onFailure { then() }
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
        if (current == null && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }) {} }
        }
        onWake?.invoke()
    }

    private fun openOutside(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun telegramAppUri(web: Uri): Uri {
        val domain = web.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return web
        return Uri.Builder()
            .scheme("tg")
            .authority("resolve")
            .appendQueryParameter("domain", domain)
            .apply { web.getQueryParameter("start")?.let { appendQueryParameter("start", it) } }
            .build()
    }

    private fun openExternalLogin(request: Uri? = null) {
        val direct = request?.getQueryParameter("url")?.takeIf { it.startsWith("https://") }
        val target = direct ?: externalLoginUrl ?: return
        openOutside(Uri.parse(target))
    }

    private inner class CabinetClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress = 0.05f
            Log.i(LOG_TAG, "page started ${url?.substringBefore('#')}")
        }

        override fun onPageFinished(view: WebView, url: String?) {
            Log.i(LOG_TAG, "page finished ${url?.substringBefore('#')}")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            Log.w(LOG_TAG, "load error ${error.errorCode} ${error.description} ${request.url.host}${request.url.path}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            Log.w(LOG_TAG, "http ${errorResponse.statusCode} ${request.url.host}${request.url.path}")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "smugly" -> {
                    when {
                        uri.host.equals("external-login", ignoreCase = true) -> openExternalLogin(uri)
                        uri.host.equals("paid", ignoreCase = true) -> {
                            onPaid?.invoke()
                            finish()
                        }
                        else -> onWake?.invoke()
                    }
                    true
                }
                "http", "https" -> when (uri.host?.lowercase()) {
                    "accounts.google.com" -> {
                        openExternalLogin()
                        true
                    }
                    "t.me", "telegram.me" -> {
                        if (!openOutside(telegramAppUri(uri))) Log.i(LOG_TAG, "telegram app not installed")
                        true
                    }
                    else -> false
                }
                "intent" -> {
                    val parsed = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                    if (parsed != null) {
                        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                        parsed.component = null
                        parsed.selector = null
                        val started = runCatching { startActivity(parsed) }.isSuccess
                        if (!started) {
                            parsed.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                        }
                    }
                    true
                }
                else -> {
                    openOutside(uri)
                    true
                }
            }
        }
    }
}
