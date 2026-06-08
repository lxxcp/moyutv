package com.iptv.tvplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

data class Subscription(val name: String, val url: String)

object SettingsManager {
    private const val PREF_NAME = "iptv_settings"
    private const val KEY_DECODER = "decoder_type"
    private const val KEY_SUBSCRIPTION_LIST = "subscription_list"
    private const val KEY_EPG_LIST = "epg_list"
    private const val KEY_ASPECT_RATIO = "aspect_ratio"
    private const val KEY_TIMEOUT = "timeout_seconds"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private const val KEY_USER_AGENT = "user_agent"
    private const val KEY_HEADERS = "headers"
    private const val KEY_LAST_PLAYED_URL = "last_played_url"
    private const val KEY_ACTIVE_SUBSCRIPTION = "active_subscription"
    private const val KEY_ACTIVE_EPG = "active_epg"
    private const val KEY_FAVORITES = "favorites"

    private lateinit var prefs: SharedPreferences

    var localPlaylistPath = ""

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        localPlaylistPath = java.io.File(context.filesDir, "custom_playlist.txt").absolutePath

        // Migrate old default URL https://dv.tvbj.cc.cd/test.txt to http://129.159.177.216/api/test
        val currentJson = prefs.getString(KEY_SUBSCRIPTION_LIST, "") ?: ""
        if (currentJson.contains("https://dv.tvbj.cc.cd/test.txt")) {
            val updatedJson = currentJson.replace("https://dv.tvbj.cc.cd/test.txt", "http://129.159.177.216/api/test")
            prefs.edit().putString(KEY_SUBSCRIPTION_LIST, updatedJson).apply()
        }
        val activeSub = prefs.getString(KEY_ACTIVE_SUBSCRIPTION, "") ?: ""
        if (activeSub == "https://dv.tvbj.cc.cd/test.txt") {
            prefs.edit().putString(KEY_ACTIVE_SUBSCRIPTION, "http://129.159.177.216/api/test").apply()
        }

        // Check if we need to migrate old single url to the new list format
        val oldSub = prefs.getString("subscription_url", "") ?: ""
        if (oldSub.isNotEmpty() && subscriptions.isEmpty()) {
            subscriptions = listOf(Subscription("默认接口", oldSub))
            prefs.edit().remove("subscription_url").apply()
        }

        // Migrate old default list
        val currentJson = prefs.getString(KEY_SUBSCRIPTION_LIST, "") ?: ""
        if (currentJson == "[{\"name\":\"默认直播(IPv6)\",\"url\":\"https://live.fanmingming.com/tv/m3u/ipv6.m3u\"},{\"name\":\"默认直播(备用)\",\"url\":\"https://t.freetv.fun/m3u/playlist_all_original.m3u\"}]") {
            prefs.edit().remove(KEY_SUBSCRIPTION_LIST).apply()
        }

        // Migrate old default EPG URL
        val currentEpgJson = prefs.getString(KEY_EPG_LIST, "") ?: ""
        if (currentEpgJson.contains("https://epg.112114.xyz/pp.xml.gz") || currentEpgJson.contains("112114.xyz")) {
            prefs.edit().remove(KEY_EPG_LIST).apply()
            prefs.edit().remove(KEY_ACTIVE_EPG).apply()
        }
        
        // Initialize reactive states
        decoderTypeState.value = decoderType
        subscriptionUrlState.value = subscriptionUrl
        activeEpgUrlState.value = activeEpgUrl
        aspectRatioState.value = aspectRatio
        timeoutSecondsState.value = timeoutSeconds
        autoReconnectState.value = autoReconnect
    }

    val decoderTypeState = mutableStateOf("MPV")
    var decoderType: String
        get() = prefs.getString(KEY_DECODER, "MPV") ?: "MPV"
        set(value) {
            prefs.edit().putString(KEY_DECODER, value).apply()
            decoderTypeState.value = value
        }

    var subscriptions: List<Subscription>
        get() {
            val defaultJsonStr = "[{\"name\":\"内置节目\",\"url\":\"http://129.159.177.216/api/test\"}]"
            var jsonStr = prefs.getString(KEY_SUBSCRIPTION_LIST, defaultJsonStr) ?: defaultJsonStr
            if (jsonStr == "[]") jsonStr = defaultJsonStr
            val list = mutableListOf<Subscription>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.getString("name")
                    // Filter out old unused defaults
                    if (name == "网络列表(备用)" || name == "本地列表(扫码上传)") {
                        continue
                    }
                    list.add(Subscription(name, obj.getString("url")))
                }
            } catch (e: Exception) { }
            if (list.isEmpty()) {
                list.add(Subscription("内置节目", "http://129.159.177.216/api/test"))
            }
            return list
        }
        set(value) {
            val array = JSONArray()
            value.forEach { 
                val obj = JSONObject()
                obj.put("name", it.name)
                obj.put("url", it.url)
                array.put(obj)
            }
            prefs.edit().putString(KEY_SUBSCRIPTION_LIST, array.toString()).apply()
        }
        
    // The active subscription URL
    var activeSubscriptionUrl: String
        get() {
            val url = prefs.getString(KEY_ACTIVE_SUBSCRIPTION, "") ?: ""
            // Auto-recover if the user was stuck on the old dead default URL
            if (url == "https://t.freetv.fun/m3u/playlist_all_original.m3u" || url == "https://dv.tvbj.cc.cd/test.txt") {
                return "http://129.159.177.216/api/test"
            }
            return url
        }
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_SUBSCRIPTION, value).apply()
            subscriptionUrlState.value = subscriptionUrl
        }

    val subscriptionUrlState = mutableStateOf("")
    val subscriptionUrl: String
        get() {
            val active = activeSubscriptionUrl
            if (active.isNotEmpty()) return active
            return subscriptions.firstOrNull()?.url ?: ""
        }

    var epgs: List<Subscription>
        get() {
            val defaultJsonStr = "[{\"name\":\"默认EPG\",\"url\":\"https://gitee.com/taksssss/tv/raw/main/epg/erw.xml.gz\"}]"
            var jsonStr = prefs.getString(KEY_EPG_LIST, defaultJsonStr) ?: defaultJsonStr
            if (jsonStr == "[]") jsonStr = defaultJsonStr
            val list = mutableListOf<Subscription>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(Subscription(obj.getString("name"), obj.getString("url")))
                }
            } catch (e: Exception) { }
            return list
        }
        set(value) {
            val array = JSONArray()
            value.forEach { 
                val obj = JSONObject()
                obj.put("name", it.name)
                obj.put("url", it.url)
                array.put(obj)
            }
            prefs.edit().putString(KEY_EPG_LIST, array.toString()).apply()
        }

    var activeEpgUrl: String
        get() = prefs.getString(KEY_ACTIVE_EPG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_EPG, value).apply()
            activeEpgUrlState.value = epgUrl
        }

    val activeEpgUrlState = mutableStateOf("")
    val epgUrl: String
        get() {
            val active = activeEpgUrl
            if (active.isNotEmpty()) return active
            return epgs.firstOrNull()?.url ?: ""
        }

    val aspectRatioState = mutableStateOf("原始")
    var aspectRatio: String
        get() = prefs.getString(KEY_ASPECT_RATIO, "原始") ?: "原始"
        set(value) {
            prefs.edit().putString(KEY_ASPECT_RATIO, value).apply()
            aspectRatioState.value = value
        }

    val timeoutSecondsState = mutableStateOf(15)
    var timeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT, 15)
        set(value) {
            prefs.edit().putInt(KEY_TIMEOUT, value).apply()
            timeoutSecondsState.value = value
        }
        
    val autoReconnectState = mutableStateOf(false)
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
            autoReconnectState.value = value
        }

    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
        set(value) = prefs.edit().putString(KEY_USER_AGENT, value).apply()

    var headers: String
        get() = prefs.getString(KEY_HEADERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HEADERS, value).apply()

    var lastPlayedUrl: String
        get() = prefs.getString(KEY_LAST_PLAYED_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PLAYED_URL, value).apply()

    var lastCategoryName: String
        get() = prefs.getString("last_category_name", "") ?: ""
        set(value) = prefs.edit().putString("last_category_name", value).apply()

    var lastChannelName: String
        get() = prefs.getString("last_channel_name", "") ?: ""
        set(value) = prefs.edit().putString("last_channel_name", value).apply()

    var lastUrlIndex: Int
        get() = prefs.getInt("last_url_index", 0)
        set(value) = prefs.edit().putInt("last_url_index", value).apply()

    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    fun toggleFavorite(url: String): Boolean {
        val current = favorites.toMutableSet()
        val isAdded = if (current.contains(url)) {
            current.remove(url)
            false
        } else {
            current.add(url)
            true
        }
        favorites = current
        return isAdded
    }
}
