package app.smugly

import org.json.JSONArray
import org.json.JSONObject

object XrayRouting {
    const val DIRECT_TAG = "smugly-direct"
    const val BLOCK_TAG = "smugly-block"
    const val RULE_TAG_PREFIX = "smugly-"

    val RU_DOMAINS: List<String> = listOf(
        "domain:ru",
        "domain:su",
        "domain:xn--p1ai",
        "geosite:category-ru"
    )
    val RU_IPS: List<String> = listOf("geoip:ru")
    val AD_DOMAINS: List<String> = listOf("geosite:category-ads-all")

    fun apply(configJson: String, settings: RoutingSettings): String {
        val root = runCatching { JSONObject(configJson) }.getOrNull() ?: return configJson
        val routing = root.optJSONObject("routing")
        val existing = routing?.optJSONArray("rules")
        val hadOurs = existing != null && (0 until existing.length()).any { i ->
            existing.optJSONObject(i)?.optString("ruleTag").orEmpty().startsWith(RULE_TAG_PREFIX)
        }
        if (!settings.isActive && !hadOurs) return configJson
        val outbounds = root.optJSONArray("outbounds")?.takeIf { it.length() > 0 } ?: return configJson
        val newRules = ArrayList<JSONObject>()
        if (settings.blockAds) {
            val block = ensureOutbound(outbounds, "blackhole", BLOCK_TAG)
            newRules += rule("${RULE_TAG_PREFIX}ads", block).put("domain", JSONArray(AD_DOMAINS))
        }
        if (settings.directRu) {
            val direct = ensureOutbound(outbounds, "freedom", DIRECT_TAG)
            newRules += rule("${RULE_TAG_PREFIX}ru-domain", direct).put("domain", JSONArray(RU_DOMAINS))
            newRules += rule("${RULE_TAG_PREFIX}ru-ip", direct).put("ip", JSONArray(RU_IPS))
        }
        val rules = JSONArray()
        newRules.forEach { rules.put(it) }
        if (existing != null) {
            for (i in 0 until existing.length()) {
                val r = existing.opt(i)
                if (r is JSONObject && r.optString("ruleTag").startsWith(RULE_TAG_PREFIX)) continue
                rules.put(r)
            }
        }
        val target = routing ?: JSONObject().also {
            it.put("domainStrategy", "AsIs")
            root.put("routing", it)
        }
        target.put("rules", rules)
        return root.toString(2)
    }

    private fun rule(tag: String, outboundTag: String): JSONObject =
        JSONObject()
            .put("type", "field")
            .put("ruleTag", tag)
            .put("outboundTag", outboundTag)

    private fun ensureOutbound(outbounds: JSONArray, protocol: String, fallbackTag: String): String {
        for (i in 0 until outbounds.length()) {
            val o = outbounds.optJSONObject(i) ?: continue
            if (i == 0) continue
            if (!o.optString("protocol").equals(protocol, ignoreCase = true)) continue
            if (o.has("proxySettings") || o.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.has("dialerProxy") == true) continue
            if (o.optJSONObject("settings")?.optString("redirect").orEmpty().isNotBlank()) continue
            val tag = o.optString("tag")
            if (tag.isNotBlank()) return tag
        }
        for (i in 0 until outbounds.length()) {
            if (outbounds.optJSONObject(i)?.optString("tag") == fallbackTag) return fallbackTag
        }
        val outbound = JSONObject().put("tag", fallbackTag).put("protocol", protocol)
        if (protocol == "blackhole") {
            outbound.put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
        } else {
            outbound.put("settings", JSONObject())
        }
        outbounds.put(outbound)
        return fallbackTag
    }
}
