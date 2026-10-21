package app.smugly.ui

import app.smugly.Config
import app.smugly.ConfigJson
import app.smugly.ConfigProfile
import app.smugly.DnsResolverPool
import app.smugly.GlobalSettings
import app.smugly.defaultConfig
import app.smugly.platform.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * File-backed profiles for desktop (and any JVM host without SharedPreferences).
 * Format matches Android export JSON so profiles can move between platforms.
 */
class FileProfileStore(
    private val dir: File = File(AppPaths.filesDir())
) {
    private val profilesFile get() = File(dir, "profiles.json")
    private val settingsFile get() = File(dir, "settings.json")
    private val activeFile get() = File(dir, "active_profile_id.txt")
    private val subscriptionsFile get() = File(dir, "subscriptions.json")

    fun loadSubscriptions(): List<app.smugly.subscription.Subscription> =
        runCatching {
            subscriptionsFile.takeIf { it.exists() }?.readJsonText().orEmpty()
        }.getOrDefault("").let { app.smugly.subscription.SubscriptionJson.listFromString(it) }

    fun saveSubscriptions(subs: List<app.smugly.subscription.Subscription>) {
        runCatching {
            subscriptionsFile.writeText(app.smugly.subscription.SubscriptionJson.listToString(subs))
        }
    }

    init {
        dir.mkdirs()
        if (!profilesFile.exists()) {
            val starter = ConfigProfile(
                id = UUID.randomUUID().toString(),
                name = "Default",
                config = defaultConfig(mode = Config.Mode.PROXY)
            )
            writeProfiles(listOf(starter))
            activeFile.writeText(starter.id)
        }
        if (!settingsFile.exists()) {
            saveGlobalSettings(defaultGlobalSettings())
        }
    }

    fun loadProfiles(): List<ConfigProfile> {
        val arr = runCatching { JSONArray(profilesFile.readJsonText()) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                add(ConfigJson.profileFromJson(arr.getJSONObject(i)))
            }
        }
    }

    fun loadActiveProfileId(): String? =
        activeFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    fun setActiveProfile(id: String) {
        activeFile.writeText(id)
    }

    fun writeProfiles(profiles: List<ConfigProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(ConfigJson.profileToJson(it)) }
        profilesFile.writeText(arr.toString(2))
    }

    fun saveProfile(profile: ConfigProfile): ConfigProfile {
        val list = loadProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        writeProfiles(list)
        return profile
    }

    fun addProfile(name: String, config: Config): ConfigProfile {
        val profile = ConfigProfile(UUID.randomUUID().toString(), name.ifBlank { "Profile" }, config)
        writeProfiles(loadProfiles() + profile)
        setActiveProfile(profile.id)
        return profile
    }

    /** Returns the profile that is active afterwards, or null once nothing is left. */
    fun deleteProfile(id: String): ConfigProfile? {
        val list = loadProfiles()
        val remaining = list.filterNot { it.id == id }
        writeProfiles(remaining)
        val first = remaining.firstOrNull()
        if (first == null) {
            setActiveProfile("")
            return null
        }
        val active = loadActiveProfileId()
        if (active == id || remaining.none { it.id == active }) {
            setActiveProfile(first.id)
        }
        return first
    }

    fun reorderProfiles(orderedIds: List<String>) {
        val byId = loadProfiles().associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }
        val rest = loadProfiles().filter { it.id !in orderedIds.toSet() }
        writeProfiles(reordered + rest)
    }

    /**
     * Read a JSON file the way every tool on the machine might have written it.
     *
     * A UTF-8 byte-order mark is invisible in an editor and fatal to a JSON parser, and on Windows
     * it is the *default*: PowerShell's `Set-Content -Encoding utf8` and Notepad both add one. The
     * cost of not handling it is silent — the parse fails, defaults are returned, and every
     * setting the user chose is quietly gone. Measured exactly that way while scripting a test.
     */
    private fun java.io.File.readJsonText(): String = readText().removePrefix("\uFEFF")

    fun loadGlobalSettings(): GlobalSettings {
        val json = runCatching { JSONObject(settingsFile.readJsonText()) }.getOrNull()
            ?: run {
                // Losing every setting in silence is worse than any parse error. It looks exactly
                // like the app "forgetting" what the user chose, with nothing anywhere saying why.
                app.smugly.platform.PlatformLog.log(
                    app.smugly.platform.LogLevel.WARN,
                    "FileProfileStore",
                    "settings.json could not be parsed (${settingsFile.absolutePath}); " +
                        "falling back to defaults"
                )
                return defaultGlobalSettings()
            }
        return GlobalSettings(
            listenPort = json.optInt("listenPort", 1080),
            mode = runCatching {
                Config.Mode.valueOf(json.optString("mode", Config.Mode.PROXY.name))
            }.getOrDefault(Config.Mode.PROXY),
            fileLogging = json.optBoolean("fileLogging", false),
            trafficNotification = json.optBoolean("trafficNotification", false),
            localSocksAuthEnabled = json.optBoolean("localSocksAuthEnabled", true),
            localSocksUsername = json.optString("localSocksUsername", app.smugly.GlobalSettings.DEFAULT_LOCAL_SOCKS_USERNAME),
            localSocksPassword = json.optString("localSocksPassword", "changeme"),
            language = runCatching {
                app.smugly.AppLanguage.valueOf(json.optString("language", "SYSTEM"))
            }.getOrDefault(app.smugly.AppLanguage.SYSTEM),
            dnsResolverPool = DnsResolverPool.normalize(
                json.optString("dnsResolverPool", DnsResolverPool.DEFAULT_RAW)
            ),
            homeFolderIndex = json.optInt("homeFolderIndex", 0),
            lastFolderId = json.optString("lastFolderId", ""),
            collapsedCategories = app.smugly.CollapsedCategories.decode(
                json.optString("collapsedCategories", "")
            ),
            appHttpProxy = json.optBoolean("appHttpProxy", false),
            perAppMode = runCatching {
                app.smugly.PerAppMode.valueOf(
                    json.optString("perAppMode", app.smugly.PerAppMode.OFF.name)
                )
            }.getOrDefault(app.smugly.PerAppMode.OFF),
            perAppPackages = app.smugly.PerAppPackages.decode(json.optString("perAppPackages", "")),
            autoUpdate = json.optBoolean("autoUpdate", true)
        ).let { base ->
            app.smugly.ExtraSettingsCodec.decode(base) { key ->
                if (json.has(key) && !json.isNull(key)) json.get(key).toString() else null
            }
        }
    }

    fun saveGlobalSettings(settings: GlobalSettings) {
        val json = JSONObject()
            .put("listenPort", settings.listenPort)
            .put("mode", settings.mode.name)
            .put("fileLogging", settings.fileLogging)
            .put("trafficNotification", settings.trafficNotification)
            .put("localSocksAuthEnabled", settings.localSocksAuthEnabled)
            .put("localSocksUsername", settings.localSocksUsername)
            .put("localSocksPassword", settings.localSocksPassword)
            .put("language", settings.language.name)
            .put("dnsResolverPool", settings.dnsResolverPool)
            .put("homeFolderIndex", settings.homeFolderIndex)
            .put("lastFolderId", settings.lastFolderId)
            .put(
                "collapsedCategories",
                app.smugly.CollapsedCategories.encode(settings.collapsedCategories)
            )
            .put("appHttpProxy", settings.appHttpProxy)
            .put("perAppMode", settings.perAppMode.name)
            .put("perAppPackages", app.smugly.PerAppPackages.encode(settings.perAppPackages))
            .put("autoUpdate", settings.autoUpdate)
        app.smugly.ExtraSettingsCodec.encode(settings).forEach { (key, value) -> json.put(key, value) }
        settingsFile.writeText(json.toString(2))
    }
}
