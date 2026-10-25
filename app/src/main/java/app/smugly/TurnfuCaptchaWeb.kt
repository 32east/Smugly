package app.smugly

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.smugly.tunnel.TurnfuProcess
import org.json.JSONObject
import java.io.File

object TurnfuCaptchaWeb {
    const val BRIDGE = "SmuglyCaptcha"
    const val PROFILE_FILE = "vk_profile.json"
    private val VK_DOMAINS = listOf("vk.com", "vk.ru", "vk.me", "vkontakte.ru")
    private val SCRIPT_ORIGINS = VK_DOMAINS.flatMap { listOf("https://$it", "https://*.$it") }.toSet()

    fun isVkCaptchaUri(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        return parsed.scheme == "https" && parsed.port == -1 &&
            VK_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    fun successToken(url: String): String? =
        runCatching { Uri.parse(url).getQueryParameter("success_token") }.getOrNull()?.takeIf { it.isNotBlank() }

    @SuppressLint("SetJavaScriptEnabled")
    fun prepare(view: WebView, bridge: Any, auto: Boolean) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.addJavascriptInterface(bridge, BRIDGE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(view, scripts(auto), SCRIPT_ORIGINS) }
        }
    }

    fun inject(view: WebView, auto: Boolean) {
        view.evaluateJavascript(scripts(auto), null)
    }

    private fun scripts(auto: Boolean): String = if (auto) HOOK + AUTO else HOOK

    fun saveProfile(context: Context, raw: String?): Boolean {
        val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return false
        val device = json.optString("device")
        val fingerprint = json.optString("browser_fp")
        val userAgent = json.optString("user_agent")
        val brands = json.optString("brands")
        val deviceJson = runCatching { JSONObject(device) }.getOrNull() ?: return false
        val sized = listOf("innerWidth", "innerHeight", "screenWidth").all { deviceJson.optDouble(it, 0.0) > 0.0 }
        val fingerprintOk = fingerprint.length in 8..64 && fingerprint.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        val printable = { text: String, limit: Int -> text.isNotEmpty() && text.length <= limit && text.all { it in ' '..'~' } }
        if (!sized || deviceJson.optBoolean("webdriver", false) || !fingerprintOk) return false
        if (!printable(userAgent, 512) || !userAgent.contains("Chrome/") || !printable(brands, 256)) return false
        val profile = JSONObject()
            .put("user_agent", userAgent)
            .put("brands", brands)
            .put("platform", json.optString("platform"))
            .put("mobile", json.optBoolean("mobile"))
            .put("device", device)
            .put("browser_fp", fingerprint)
            .put("saved_at", System.currentTimeMillis() / 1000)
        return runCatching {
            val target = File(context.filesDir, PROFILE_FILE)
            val staging = File(context.filesDir, "$PROFILE_FILE.tmp")
            staging.writeText(profile.toString())
            check(staging.renameTo(target)) { "rename failed" }
            TurnfuProcess.log("captcha: browser profile saved (mobile=${json.optBoolean("mobile")})")
        }.isSuccess
    }

    private const val HOOK = """
(function(){
  if (window.__smuglyCaptchaHook) return;
  var B = window.SmuglyCaptcha;
  if (!B) return;
  window.__smuglyCaptchaHook = 1;
  function scan(t){
    try {
      var s = String(t);
      var token = /"success_token"\s*:\s*"([^"]+)"/.exec(s);
      if (token) { B.token(token[1]); return; }
      var status = /"status"\s*:\s*"([A-Za-z_]+)"/.exec(s);
      var kind = /"show_captcha_type"\s*:\s*"([a-z_]+)"/.exec(s);
      if (status || kind) B.status(status ? status[1] : '', kind ? kind[1] : '');
    } catch (e) {}
  }
  function sniff(url, body){
    try {
      url = String(url || '');
      if (url.indexOf('captchaNotRobot.check') >= 0) window.__smuglyCheckSent = 1;
      if (url.indexOf('captchaNotRobot.componentDone') < 0 || typeof body !== 'string') return;
      var p = new URLSearchParams(body), device = p.get('device'), fp = p.get('browser_fp');
      if (!device || !fp) return;
      var d = navigator.userAgentData || {};
      var brands = (d.brands || []).map(function(b){ return '"' + b.brand + '";v="' + b.version + '"'; }).join(', ');
      B.profile(JSON.stringify({user_agent: navigator.userAgent, brands: brands, platform: d.platform ? '"' + d.platform + '"' : '', mobile: !!d.mobile, device: device, browser_fp: fp}));
    } catch (e) {}
  }
  var f = window.fetch;
  if (f) {
    window.fetch = function(input, init){
      sniff(typeof input === 'string' ? input : (input && input.url), init && init.body);
      return f.apply(this, arguments).then(function(r){
        try { r.clone().text().then(scan); } catch (e) {}
        return r;
      });
    };
  }
  var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url){ this.__smuglyUrl = url; return open.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(body){
    sniff(this.__smuglyUrl, body);
    this.addEventListener('load', function(){ try { scan(this.responseText); } catch (e) {} });
    return send.apply(this, arguments);
  };
  window.addEventListener('message', function(e){
    try { scan(typeof e.data === 'string' ? e.data : JSON.stringify(e.data)); } catch (x) {}
  });
})();
"""

    private const val AUTO = """
(function(){
  if (window.__smuglyCaptchaAuto) return;
  var B = window.SmuglyCaptcha;
  if (!B) return;
  window.__smuglyCaptchaAuto = 1;
  var dueAt = 0, tapped = false, last = '', alertSince = 0;
  document.addEventListener('touchstart', function(e){ B.seen('touch:' + e.isTrusted); }, true);
  function report(state){ if (state !== last) { last = state; B.seen(state); } }
  function point(el){
    var r = el.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    return [r.left + r.width * (0.35 + Math.random() * 0.3), r.top + r.height * (0.35 + Math.random() * 0.3)];
  }
  function tick(){
    if (!document.body) { setTimeout(tick, 200); return; }
    var box = document.getElementById('not-robot-captcha-checkbox');
    var alert = !box && document.querySelector('[role=alert]');
    if (box) report(box.checked ? 'checkbox:checked' : (box.disabled ? 'checkbox:busy' : 'checkbox'));
    else if (alert) report('error');
    else if (document.querySelector('img[src^="data:image"], canvas')) report('picture');
    alertSince = alert ? (alertSince || Date.now()) : 0;
    if (alertSince && Date.now() - alertSince > 2500) { B.fail('page'); return; }
    if (box && !box.disabled && !box.checked && !tapped) {
      if (!dueAt) dueAt = Date.now() + 700 + Math.random() * 900;
      if (Date.now() >= dueAt) {
        var p = point(box.closest('label') || box);
        if (p) {
          tapped = true;
          B.tap(p[0], p[1]);
          setTimeout(function(){
            if (!window.__smuglyCheckSent && document.contains(box) && !box.checked) { B.seen('click-fallback'); box.click(); }
          }, 1800);
        }
      }
    }
    setTimeout(tick, 200);
  }
  tick();
})();
"""
}
