package com.iptv.tvplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.GestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import kotlinx.coroutines.launch
import com.iptv.tvplayer.server.LocalServer
import com.iptv.tvplayer.proxy.ProxyManager

/**
 * Shared key event bridge between Activity and Compose.
 * Activity-level dispatchKeyEvent guarantees 100% reliable interception
 * regardless of which native View (WebView, SurfaceView) has focus.
 */
object KeyEventBus {
    /** Called on KeyDown. Return true to consume the event. */
    var onKeyDown: ((keyCode: Int) -> Boolean)? = null
    /** Set of key codes currently being consumed (to also eat their KeyUp). */
    val consumedKeys = mutableSetOf<Int>()
}

class MainActivity : ComponentActivity() {
    
    private var localServer: LocalServer? = null
    private var currentUrl = ""
    private var playJob: kotlinx.coroutines.Job? = null
    private lateinit var gestureDetector: GestureDetector

    private val localFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            importLocalPlaylist(it)
        }
    }

    private lateinit var osdContainer: FrameLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var splashImage: ImageView
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var menuOverlay: LinearLayout
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var btnSettingsTop: android.widget.LinearLayout

    private lateinit var categoryAdapter: com.iptv.tvplayer.ui.CategoryAdapter
    private lateinit var channelAdapter: com.iptv.tvplayer.ui.ChannelAdapter

    private var categories = mutableListOf<String>()
    private var channelsMap = mutableMapOf<String, List<com.iptv.tvplayer.data.Channel>>()

    private var playingCategoryIndex = 0
    private var playingChannelIndex = 0
    private var playingUrlIndex = 0

    private val hideMenuRunnable = Runnable { menuOverlay.visibility = View.GONE }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var settingsOverlayMinimal: android.widget.LinearLayout
    private lateinit var btnSettingsBack: android.widget.LinearLayout
    private lateinit var btnSettingsRefresh: android.widget.LinearLayout
    private lateinit var rvSettingsMinimal: RecyclerView
    private lateinit var settingsMinimalAdapter: com.iptv.tvplayer.ui.SettingsMinimalAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure screen stays on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContentView(R.layout.activity_main)

        com.iptv.tvplayer.data.SettingsManager.init(this)
        ProxyManager.init(this)
        
        try {
            localServer = LocalServer(this)
            localServer?.start()
        } catch (e: Exception) {}

        playerContainer = findViewById(R.id.player_container)
        osdContainer = findViewById(R.id.osd_container)
        initNumberSelectOverlay()
        splashImage = findViewById(R.id.splash_image)
        tvStatus = findViewById(R.id.loading_text)
        menuOverlay = findViewById(R.id.menu_overlay)
        rvCategories = findViewById(R.id.rv_categories)
        rvChannels = findViewById(R.id.rv_channels)
        btnSettingsTop = findViewById(R.id.btn_settings_top)

        settingsOverlayMinimal = findViewById(R.id.settings_overlay_minimal)
        btnSettingsBack = findViewById(R.id.btn_settings_back)
        btnSettingsRefresh = findViewById(R.id.btn_settings_refresh)
        rvSettingsMinimal = findViewById(R.id.rv_settings_minimal)

        btnSettingsTop.setOnClickListener {
            menuOverlay.visibility = View.GONE
            settingsOverlayMinimal.visibility = View.VISIBLE
            showSettingsLevel(0)
            rvSettingsMinimal.requestFocus()
        }
        
        // Connect focus up/down between Settings Button and Categories
        btnSettingsTop.nextFocusDownId = R.id.rv_categories
        btnSettingsTop.nextFocusRightId = R.id.rv_channels

        setupRecyclerViews()
        setupSettingsMenu()
        initGestureDetector()
        loadPlaylist()
        checkAppUpdate(manual = false)
    }

    private var currentMenuCategoryIndex = 0

    private lateinit var tvSettingsTitle: android.widget.TextView
    private var settingsLevel = 0 // 0: Root, 1: Sub, 2: EPG, 3: Decoder, 4: Display, 5: Timeout

    private fun showSettingsLevel(level: Int) {
        settingsLevel = level
        val items = mutableListOf<com.iptv.tvplayer.ui.SettingItem>()
        var selectedPos = 0

        when (level) {
            0 -> {
                tvSettingsTitle.text = "系统设置"
                btnSettingsRefresh.visibility = View.GONE
                
                // Get active sub name
                val actSubUrl = com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl
                val actSubName = com.iptv.tvplayer.data.SettingsManager.subscriptions.find { it.url == actSubUrl }?.name ?: "内置节目"
                items.add(com.iptv.tvplayer.ui.SettingItem("订阅源设置", "当前: $actSubName", false, true, "menu_sub"))
                
                val actEpgUrl = com.iptv.tvplayer.data.SettingsManager.activeEpgUrl
                val actEpgName = com.iptv.tvplayer.data.SettingsManager.epgs.find { it.url == actEpgUrl }?.name ?: "默认EPG"
                items.add(com.iptv.tvplayer.ui.SettingItem("EPG源设置", "当前: $actEpgName", false, true, "menu_epg"))
                
                val dec = com.iptv.tvplayer.data.SettingsManager.decoderType
                items.add(com.iptv.tvplayer.ui.SettingItem("解码器", "当前: $dec", false, true, "menu_decoder"))
                
                val aspect = com.iptv.tvplayer.data.SettingsManager.aspectRatio
                items.add(com.iptv.tvplayer.ui.SettingItem("画面显示", "当前: $aspect", false, true, "menu_display"))
                
                val timeout = com.iptv.tvplayer.data.SettingsManager.timeoutSeconds
                items.add(com.iptv.tvplayer.ui.SettingItem("超时时间", "当前: ${timeout}秒", false, true, "menu_timeout"))
                
                items.add(com.iptv.tvplayer.ui.SettingItem("检查更新", "当前版本: ${getCurrentVersionName()}", false, true, "menu_update"))
            }
            1 -> {
                tvSettingsTitle.text = "设置 / 订阅源"
                btnSettingsRefresh.visibility = View.VISIBLE
                
                val subs = com.iptv.tvplayer.data.SettingsManager.subscriptions
                subs.forEachIndexed { index, sub ->
                    val isChecked = (sub.url == com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl)
                    if (isChecked) selectedPos = index
                    val tag = if (sub.url.startsWith("file://")) "本地" else "远程"
                    items.add(com.iptv.tvplayer.ui.SettingItem(sub.name, tag, isChecked, false, sub.url))
                }
                items.add(com.iptv.tvplayer.ui.SettingItem("添加其他订阅源", null, false, false, "add_sub"))
            }
            2 -> {
                tvSettingsTitle.text = "设置 / EPG源"
                btnSettingsRefresh.visibility = View.GONE
                
                val epgs = com.iptv.tvplayer.data.SettingsManager.epgs
                epgs.forEachIndexed { index, epg ->
                    val isChecked = (epg.url == com.iptv.tvplayer.data.SettingsManager.activeEpgUrl)
                    if (isChecked) selectedPos = index
                    items.add(com.iptv.tvplayer.ui.SettingItem(epg.name, null, isChecked, false, epg.url))
                }
                items.add(com.iptv.tvplayer.ui.SettingItem("自定义其他 EPG", null, false, false, "add_epg"))
            }
            3 -> {
                tvSettingsTitle.text = "设置 / 解码器"
                btnSettingsRefresh.visibility = View.GONE
                
                val dec = com.iptv.tvplayer.data.SettingsManager.decoderType
                items.add(com.iptv.tvplayer.ui.SettingItem("MPV 播放器", "推荐", dec == "MPV", false, "MPV"))
                items.add(com.iptv.tvplayer.ui.SettingItem("EXO 播放器", "备用", dec == "EXO", false, "EXO"))
                if (dec == "EXO") selectedPos = 1
            }
            4 -> {
                tvSettingsTitle.text = "设置 / 画面显示"
                btnSettingsRefresh.visibility = View.GONE
                
                val aspect = com.iptv.tvplayer.data.SettingsManager.aspectRatio
                items.add(com.iptv.tvplayer.ui.SettingItem("原始比例", "按源分辨率", aspect == "原始", false, "原始"))
                items.add(com.iptv.tvplayer.ui.SettingItem("拉伸全屏", "铺满屏幕", aspect == "全屏", false, "全屏"))
                if (aspect == "全屏") selectedPos = 1
            }
            5 -> {
                tvSettingsTitle.text = "设置 / 超时时间"
                btnSettingsRefresh.visibility = View.GONE
                
                val timeout = com.iptv.tvplayer.data.SettingsManager.timeoutSeconds
                val options = listOf(5, 10, 15, 30)
                options.forEachIndexed { index, t ->
                    if (t == timeout) selectedPos = index
                    items.add(com.iptv.tvplayer.ui.SettingItem("${t} 秒", null, t == timeout, false, t.toString()))
                }
            }
        }
        
        settingsMinimalAdapter.updateData(items, selectedPos)
    }

    private fun setupSettingsMenu() {
        tvSettingsTitle = findViewById(R.id.tv_settings_title)
        rvSettingsMinimal.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        settingsMinimalAdapter = com.iptv.tvplayer.ui.SettingsMinimalAdapter(
            emptyList(),
            { pos, item ->
                when (settingsLevel) {
                    0 -> {
                        when (item.identifier) {
                            "menu_sub" -> showSettingsLevel(1)
                            "menu_epg" -> showSettingsLevel(2)
                            "menu_decoder" -> showSettingsLevel(3)
                            "menu_display" -> showSettingsLevel(4)
                            "menu_timeout" -> showSettingsLevel(5)
                            "menu_update" -> checkAppUpdate(manual = true)
                        }
                    }
                    1 -> {
                        if (item.identifier == "add_sub") {
                            showAddSubscriptionDialog()
                        } else {
                            com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl = item.identifier
                            showSettingsLevel(1) // refresh checkmarks
                            settingsOverlayMinimal.visibility = View.GONE
                            rvChannels.requestFocus()
                            loadPlaylist()
                        }
                    }
                    2 -> {
                        if (item.identifier == "add_epg") {
                            showAddEpgDialog()
                        } else {
                            com.iptv.tvplayer.data.SettingsManager.activeEpgUrl = item.identifier
                            showSettingsLevel(2)
                            android.widget.Toast.makeText(this, "EPG已切换", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        com.iptv.tvplayer.data.SettingsManager.decoderType = item.identifier
                        showSettingsLevel(3)
                        android.widget.Toast.makeText(this, "解码器已切换为 ${item.identifier}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        com.iptv.tvplayer.data.SettingsManager.aspectRatio = item.identifier
                        showSettingsLevel(4)
                        android.widget.Toast.makeText(this, "画面已切换为 ${item.identifier}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        com.iptv.tvplayer.data.SettingsManager.timeoutSeconds = item.identifier.toInt()
                        showSettingsLevel(5)
                        android.widget.Toast.makeText(this, "超时时间已切换为 ${item.identifier}秒", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            { pos, item ->
                if (settingsLevel == 1 && item.identifier != "add_sub") {
                    showDeleteSubscriptionConfirmDialog(item)
                } else if (settingsLevel == 2 && item.identifier != "add_epg") {
                    showDeleteEpgConfirmDialog(item)
                }
            }
        )
        rvSettingsMinimal.adapter = settingsMinimalAdapter

        btnSettingsBack.setOnClickListener {
            if (settingsLevel > 0) {
                showSettingsLevel(0) // Go back to root
                rvSettingsMinimal.requestFocus()
            } else {
                settingsOverlayMinimal.visibility = View.GONE
                rvChannels.requestFocus()
            }
        }

        btnSettingsRefresh.setOnClickListener {
            settingsOverlayMinimal.visibility = View.GONE
            rvChannels.requestFocus()
            loadPlaylist()
            android.widget.Toast.makeText(this, "正在刷新全部订阅源", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        btnSettingsBack.nextFocusDownId = R.id.rv_settings_minimal
        btnSettingsBack.nextFocusRightId = R.id.btn_settings_refresh
        btnSettingsRefresh.nextFocusLeftId = R.id.btn_settings_back
        btnSettingsRefresh.nextFocusDownId = R.id.rv_settings_minimal
    }

    private fun setupRecyclerViews() {
        rvCategories.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvChannels.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        categoryAdapter = com.iptv.tvplayer.ui.CategoryAdapter(categories) { position ->
            currentMenuCategoryIndex = position
            val cat = categories[position]
            val chList = channelsMap[cat] ?: emptyList()
            channelAdapter.updateData(chList)
            // If the playing channel is in this category, highlight it
            if (position == playingCategoryIndex) {
                channelAdapter.setSelected(playingChannelIndex)
            } else {
                channelAdapter.setSelected(-1)
            }
            resetMenuTimer()
        }
        rvCategories.adapter = categoryAdapter

        channelAdapter = com.iptv.tvplayer.ui.ChannelAdapter(emptyList()) { channel, position ->
            playChannel(currentMenuCategoryIndex, position, 0)
            menuOverlay.visibility = View.GONE
            playerContainer.requestFocus()
        }
        rvChannels.adapter = channelAdapter
    }

    private fun loadPlaylist() {
        splashImage.visibility = View.VISIBLE
        val tvLoadingList = findViewById<android.widget.TextView>(R.id.tv_loading_list)
        tvLoadingList?.visibility = View.VISIBLE
        
        val subscriptionUrl = com.iptv.tvplayer.data.SettingsManager.subscriptionUrlState.value
        
        kotlinx.coroutines.MainScope().launch {
            if (subscriptionUrl.isNotEmpty()) {
                val playlist = com.iptv.tvplayer.data.PlaylistParser.parse(subscriptionUrl)
                if (playlist != null && playlist.categories.isNotEmpty()) {
                    categories.clear()
                    categories.addAll(playlist.categories)
                    channelsMap.clear()
                    channelsMap.putAll(playlist.channelsMap)

                    categoryAdapter.notifyDataSetChanged()
                    
                    if (categories.isNotEmpty()) {
                        val savedCat = com.iptv.tvplayer.data.SettingsManager.lastCategoryName
                        val savedCh = com.iptv.tvplayer.data.SettingsManager.lastChannelName
                        val savedUrlIdx = com.iptv.tvplayer.data.SettingsManager.lastUrlIndex
                        
                        var targetCatIndex = 0
                        var targetChIndex = 0
                        var targetUrlIndex = 0
                        
                        if (savedCat.isNotEmpty() && savedCh.isNotEmpty()) {
                            val catIdx = categories.indexOf(savedCat)
                            if (catIdx != -1) {
                                val chList = channelsMap[savedCat] ?: emptyList()
                                val chIdx = chList.indexOfFirst { it.name == savedCh }
                                if (chIdx != -1) {
                                    targetCatIndex = catIdx
                                    targetChIndex = chIdx
                                    targetUrlIndex = if (savedUrlIdx in 0 until (chList[chIdx].urls.size)) savedUrlIdx else 0
                                }
                            }
                        }
                        
                        val activeCat = categories[targetCatIndex]
                        channelAdapter.updateData(channelsMap[activeCat] ?: emptyList())
                        categoryAdapter.setSelected(targetCatIndex)
                        channelAdapter.setSelected(targetChIndex)
                        
                        // Scroll to selection
                        rvCategories.scrollToPosition(targetCatIndex)
                        rvChannels.scrollToPosition(targetChIndex)
                        
                        playChannel(targetCatIndex, targetChIndex, targetUrlIndex)
                    }
                    tvStatus.visibility = View.GONE
                    
                    // Trigger EPG loading
                    com.iptv.tvplayer.data.EpgManager.activeChannelNames.clear()
                    channelsMap.values.forEach { list -> 
                        list.forEach { com.iptv.tvplayer.data.EpgManager.activeChannelNames.add(it.name) }
                    }
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.iptv.tvplayer.data.EpgManager.loadEpg(this@MainActivity)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            channelAdapter.notifyDataSetChanged()
                        }
                    }
                } else {
                    // Failed to load
                    tvStatus.text = "源加载失败，请按左键进入【设置】切换"
                    tvStatus.visibility = View.VISIBLE
                    
                    // Auto-recover to built-in if we were on a dead link
                    if (subscriptionUrl != "http://129.159.177.216/api/test") {
                        com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl = "http://129.159.177.216/api/test"
                    }
                }
            }
            splashImage.visibility = View.GONE
            tvLoadingList?.visibility = View.GONE
        }
    }

    private var backPressedTime = 0L

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun playChannel(catIndex: Int, chIndex: Int, urlIndex: Int) {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(catIndex) ?: return
        val channelList = channelsMap[cat] ?: return
        val channel = channelList.getOrNull(chIndex) ?: return
        val url = channel.urls.getOrNull(urlIndex) ?: return
        
        playingCategoryIndex = catIndex
        playingChannelIndex = chIndex
        playingUrlIndex = urlIndex
        
        currentUrl = url

        // Save last played info for memory playback
        com.iptv.tvplayer.data.SettingsManager.lastCategoryName = cat
        com.iptv.tvplayer.data.SettingsManager.lastChannelName = channel.name
        com.iptv.tvplayer.data.SettingsManager.lastUrlIndex = urlIndex

        playJob?.cancel()
        playJob = kotlinx.coroutines.MainScope().launch {
            val resolvedUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ProxyManager.resolveUrl(url)
            }
            
            if (!kotlinx.coroutines.isActive) return@launch
            
            val lowerUrl = resolvedUrl.lowercase()
            val isWebView = lowerUrl.startsWith("webview://") || lowerUrl.startsWith("wb://") || lowerUrl.startsWith("mb://")
            
            if (isWebView) {
                com.iptv.tvplayer.player.NativePlayerManager.release()
                
                for (i in playerContainer.childCount - 1 downTo 0) {
                    val child = playerContainer.getChildAt(i)
                    if (child is android.webkit.WebView) {
                        playerContainer.removeView(child)
                        child.destroy()
                    } else {
                        playerContainer.removeView(child)
                    }
                }
                
                val actualUrl = resolvedUrl.substringAfter("://")
                val isMobile = lowerUrl.startsWith("mb://")
                
                val webView = android.webkit.WebView(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    
                    if (isMobile) {
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    } else {
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    }
                    
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript("""
                                (function() {
                                    function forceFullscreen() {
                                        var target = document.querySelector('video');
                                        if (!target) {
                                            var iframes = document.querySelectorAll('iframe');
                                            var maxArea = 0;
                                            for (var i = 0; i < iframes.length; i++) {
                                                var rect = iframes[i].getBoundingClientRect();
                                                var area = rect.width * rect.height;
                                                if (area > maxArea) { maxArea = area; target = iframes[i]; }
                                            }
                                        }
                                        if (target && !target.dataset.fullscreenForced) {
                                            target.dataset.fullscreenForced = 'true';
                                            target.style.setProperty('position', 'fixed', 'important');
                                            target.style.setProperty('top', '0', 'important');
                                            target.style.setProperty('left', '0', 'important');
                                            target.style.setProperty('width', '100vw', 'important');
                                            target.style.setProperty('height', '100vh', 'important');
                                            target.style.setProperty('z-index', '2147483647', 'important');
                                            target.style.setProperty('background-color', '#000', 'important');
                                            target.style.setProperty('border', 'none', 'important');
                                            target.style.setProperty('max-width', 'none', 'important');
                                            target.style.setProperty('max-height', 'none', 'important');
                                            if (target.tagName.toLowerCase() === 'video') {
                                                target.style.setProperty('object-fit', 'contain', 'important');
                                                target.play().catch(e=>{});
                                            }
                                            
                                            var node = target;
                                            while (node && node !== document.body) {
                                                node.style.setProperty('transform', 'none', 'important');
                                                node.style.setProperty('clip', 'auto', 'important');
                                                node.style.setProperty('clip-path', 'none', 'important');
                                                node.style.setProperty('filter', 'none', 'important');
                                                node.style.setProperty('perspective', 'none', 'important');
                                                node.style.setProperty('contain', 'none', 'important');
                                                node.style.setProperty('opacity', '1', 'important');
                                                node.style.setProperty('z-index', '2147483647', 'important');
                                                node.style.setProperty('margin', '0', 'important');
                                                node.style.setProperty('padding', '0', 'important');
                                                node = node.parentElement;
                                            }
                                            document.body.style.setProperty('overflow', 'hidden', 'important');
                                            document.body.style.setProperty('margin', '0', 'important');
                                            document.body.style.setProperty('padding', '0', 'important');
                                            document.body.style.setProperty('background-color', '#000', 'important');
                                        } else if (target && target.tagName.toLowerCase() === 'video') {
                                            target.play().catch(e=>{});
                                        }
                                    }
                                    setInterval(forceFullscreen, 500);
                                    forceFullscreen();
                                })();
                            """.trimIndent(), null)
                        }
                    }
                    
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        private var customView: View? = null
                        private var customViewCallback: CustomViewCallback? = null
 
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (customView != null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                            playerContainer.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                            this@apply.visibility = View.GONE
                        }
 
                        override fun onHideCustomView() {
                            if (customView == null) return
                            playerContainer.removeView(customView)
                            customView = null
                            this@apply.visibility = View.VISIBLE
                            customViewCallback?.onCustomViewHidden()
                        }
                    }
                }
                
                if (!kotlinx.coroutines.isActive) {
                    webView.destroy()
                    return@launch
                }
                
                playerContainer.addView(webView)
                webView.loadUrl(actualUrl)
            } else {
                for (i in playerContainer.childCount - 1 downTo 0) {
                    val child = playerContainer.getChildAt(i)
                    if (child is android.webkit.WebView) {
                        playerContainer.removeView(child)
                        child.destroy()
                    }
                }
                
                val decoderType = com.iptv.tvplayer.data.SettingsManager.decoderTypeState.value
                com.iptv.tvplayer.player.NativePlayerManager.onAutoSwitchRequested = {
                    android.widget.Toast.makeText(this@MainActivity, "连接超时，自动切换下一条线路", android.widget.Toast.LENGTH_SHORT).show()
                    switchUrl(1)
                }
                com.iptv.tvplayer.player.NativePlayerManager.onPlayerBuffering = {
                    runOnUiThread {
                        findViewById<android.widget.ProgressBar>(R.id.buffering_spinner)?.visibility = View.VISIBLE
                    }
                }
                com.iptv.tvplayer.player.NativePlayerManager.onPlayerReady = {
                    runOnUiThread {
                        findViewById<android.widget.ProgressBar>(R.id.buffering_spinner)?.visibility = View.GONE
                        
                        val currentView = com.iptv.tvplayer.player.NativePlayerManager.getCurrentView()
                        for (i in playerContainer.childCount - 1 downTo 0) {
                            val child = playerContainer.getChildAt(i)
                            if (child != currentView) {
                                playerContainer.removeView(child)
                            }
                        }
                    }
                }
                val playerView = if (decoderType == "EXO") {
                    com.iptv.tvplayer.player.NativePlayerManager.createExoPlayer(this@MainActivity, resolvedUrl)
                } else {
                    com.iptv.tvplayer.player.NativePlayerManager.createMPVPlayer(this@MainActivity, resolvedUrl)
                }
                
                if (!kotlinx.coroutines.isActive) {
                    com.iptv.tvplayer.player.NativePlayerManager.release()
                    return@launch
                }
                
                if (playerView.parent == null) {
                    playerContainer.addView(playerView)
                }
            }
        }
    }

    private fun resetMenuTimer() {
        handler.removeCallbacks(hideMenuRunnable)
        handler.postDelayed(hideMenuRunnable, 8000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetMenuTimer()
        
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                    return super.dispatchKeyEvent(event)
                }
                if (menuOverlay.visibility == View.VISIBLE) {
                    menuOverlay.visibility = View.GONE
                }
                val digit = (keyCode - KeyEvent.KEYCODE_0).toString()
                appendNumberInput(digit)
                return true
            }

            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (settingsOverlayMinimal.visibility == View.GONE) {
                        menuOverlay.visibility = View.GONE
                        settingsOverlayMinimal.visibility = View.VISIBLE
                        rvSettingsMinimal.requestFocus()
                        setupSettingsMenu() // Refresh data
                    } else {
                        settingsOverlayMinimal.visibility = View.GONE
                        rvChannels.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (tvNumberSelectOverlay.visibility == View.VISIBLE) {
                        confirmNumberSelection()
                        return true
                    }
                    if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                        return super.dispatchKeyEvent(event)
                    }
                    if (menuOverlay.visibility == View.GONE) {
                        menuOverlay.visibility = View.VISIBLE
                        // Focus on current category and channel
                        currentMenuCategoryIndex = playingCategoryIndex
                        categoryAdapter.setSelected(playingCategoryIndex)
                        rvCategories.scrollToPosition(playingCategoryIndex)
                        
                        val cat = categories.getOrNull(playingCategoryIndex)
                        if (cat != null) {
                            val chList = channelsMap[cat] ?: emptyList()
                            channelAdapter.updateData(chList)
                            channelAdapter.setSelected(playingChannelIndex)
                            rvChannels.scrollToPosition(playingChannelIndex)
                            // Request focus to the channel list
                            rvChannels.post {
                                val v = rvChannels.layoutManager?.findViewByPosition(playingChannelIndex)
                                v?.requestFocus() ?: rvChannels.requestFocus()
                            }
                        }
                        return true
                    } else {
                        // Menu is visible, manually trigger click on focused item
                        val view = window.currentFocus
                        if (view != null && (rvChannels.hasFocus() || rvCategories.hasFocus())) {
                            view.performClick()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (tvNumberSelectOverlay.visibility == View.VISIBLE) {
                        handler.removeCallbacks(runConfirmNumberSelection)
                        tvNumberSelectOverlay.visibility = View.GONE
                        numberInputBuffer = ""
                        return true
                    }
                    if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                        settingsOverlayMinimal.visibility = View.GONE
                        rvChannels.requestFocus()
                        return true
                    }
                    if (menuOverlay.visibility == View.VISIBLE) {
                        menuOverlay.visibility = View.GONE
                        return true
                    } else {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - backPressedTime > 2000) {
                            android.widget.Toast.makeText(this, "再按一次退出应用", android.widget.Toast.LENGTH_SHORT).show()
                            backPressedTime = currentTime
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (settingsOverlayMinimal.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                    if (menuOverlay.visibility == View.VISIBLE) {
                        if (rvChannels.hasFocus()) {
                            rvCategories.scrollToPosition(currentMenuCategoryIndex)
                            val catView = rvCategories.layoutManager?.findViewByPosition(currentMenuCategoryIndex)
                            if (catView != null) catView.requestFocus() else rvCategories.requestFocus()
                            return true
                        }
                    } else {
                        switchUrl(-1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (settingsOverlayMinimal.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                    if (menuOverlay.visibility == View.VISIBLE) {
                        if (rvCategories.hasFocus() || btnSettingsTop.hasFocus()) {
                            val targetIndex = if (currentMenuCategoryIndex == playingCategoryIndex) playingChannelIndex else 0
                            rvChannels.scrollToPosition(targetIndex)
                            val chView = rvChannels.layoutManager?.findViewByPosition(targetIndex)
                            if (chView != null) chView.requestFocus() else rvChannels.requestFocus()
                            return true
                        }
                    } else {
                        switchUrl(1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (settingsOverlayMinimal.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                    if (menuOverlay.visibility == View.GONE) {
                        switchChannel(-1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (settingsOverlayMinimal.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                    if (menuOverlay.visibility == View.GONE) {
                        switchChannel(1)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchChannel(offset: Int) {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(playingCategoryIndex) ?: return
        val channelList = channelsMap[cat] ?: return
        if (channelList.isEmpty()) return
        
        var newChIndex = playingChannelIndex + offset
        var newCatIndex = playingCategoryIndex
        
        if (newChIndex < 0) {
            // Previous category
            newCatIndex = if (playingCategoryIndex > 0) playingCategoryIndex - 1 else categories.size - 1
            val newCat = categories[newCatIndex]
            newChIndex = (channelsMap[newCat]?.size ?: 1) - 1
        } else if (newChIndex >= channelList.size) {
            // Next category
            newCatIndex = if (playingCategoryIndex < categories.size - 1) playingCategoryIndex + 1 else 0
            newChIndex = 0
        }
        
        // Show toast
        val chName = channelsMap[categories[newCatIndex]]?.getOrNull(newChIndex)?.name ?: ""
        android.widget.Toast.makeText(this, "切至: $chName", android.widget.Toast.LENGTH_SHORT).show()
        
        playChannel(newCatIndex, newChIndex, 0)
    }

    private fun switchUrl(offset: Int) {
        if (categories.isEmpty()) return
        val cat = categories.getOrNull(playingCategoryIndex) ?: return
        val channel = channelsMap[cat]?.getOrNull(playingChannelIndex) ?: return
        val urlCount = channel.urls.size
        if (urlCount <= 1) {
            android.widget.Toast.makeText(this, "当前频道只有一条线路", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        var newUrlIndex = playingUrlIndex + offset
        if (newUrlIndex < 0) newUrlIndex = urlCount - 1
        if (newUrlIndex >= urlCount) newUrlIndex = 0
        
        android.widget.Toast.makeText(this, "切换线路: ${newUrlIndex + 1}/$urlCount", android.widget.Toast.LENGTH_SHORT).show()
        playChannel(playingCategoryIndex, playingChannelIndex, newUrlIndex)
    }

    private fun showQrDialog(url: String, title: String) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_qr, null)
        val ivQr = dialogView.findViewById<android.widget.ImageView>(R.id.iv_qr)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_qr_title)
        val tvUrl = dialogView.findViewById<android.widget.TextView>(R.id.tv_qr_url)
        
        tvTitle.text = title
        tvUrl.text = url
        
        try {
            val size = 500
            val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            ivQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .show()
    }

    private fun initGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (settingsOverlayMinimal.visibility == View.VISIBLE) {
                    settingsOverlayMinimal.visibility = View.GONE
                    rvChannels.requestFocus()
                    return true
                }
                if (menuOverlay.visibility == View.VISIBLE) {
                    menuOverlay.visibility = View.GONE
                    return true
                } else {
                    menuOverlay.visibility = View.VISIBLE
                    currentMenuCategoryIndex = playingCategoryIndex
                    categoryAdapter.setSelected(playingCategoryIndex)
                    rvCategories.scrollToPosition(playingCategoryIndex)
                    
                    val cat = categories.getOrNull(playingCategoryIndex)
                    if (cat != null) {
                        val chList = channelsMap[cat] ?: emptyList()
                        channelAdapter.updateData(chList)
                        channelAdapter.setSelected(playingChannelIndex)
                        rvChannels.scrollToPosition(playingChannelIndex)
                        rvChannels.post {
                            val v = rvChannels.layoutManager?.findViewByPosition(playingChannelIndex)
                            v?.requestFocus() ?: rvChannels.requestFocus()
                        }
                    }
                    return true
                }
            }

            override fun onLongPress(e: MotionEvent) {
                if (settingsOverlayMinimal.visibility == View.GONE) {
                    menuOverlay.visibility = View.GONE
                    settingsOverlayMinimal.visibility = View.VISIBLE
                    showSettingsLevel(0)
                    rvSettingsMinimal.requestFocus()
                }
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                val threshold = 100
                val velocityThreshold = 100
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > threshold && Math.abs(velocityX) > velocityThreshold) {
                        if (diffX > 0) {
                            switchUrl(1)
                        } else {
                            switchUrl(-1)
                        }
                        return true
                    }
                } else {
                    if (Math.abs(diffY) > threshold && Math.abs(velocityY) > velocityThreshold) {
                        if (diffY > 0) {
                            switchChannel(-1) // Swipe down -> previous channel
                        } else {
                            switchChannel(1) // Swipe up -> next channel
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun isTouchInsideView(view: View?, event: MotionEvent): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        val l = IntArray(2)
        view.getLocationOnScreen(l)
        val x = event.rawX
        val y = event.rawY
        return x >= l[0] && x <= l[0] + view.width && y >= l[1] && y <= l[1] + view.height
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val touchInMenu = isTouchInsideView(menuOverlay, ev)
        val touchInSettings = isTouchInsideView(settingsOverlayMinimal, ev)
        
        if (touchInMenu || touchInSettings) {
            resetMenuTimer()
            return super.dispatchTouchEvent(ev)
        }
        
        if (gestureDetector.onTouchEvent(ev)) {
            resetMenuTimer()
            return true
        }
        
        return super.dispatchTouchEvent(ev)
    }
    private fun showAddSubscriptionDialog() {
        val context = this
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("添加订阅源")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // Input Name
        val etName = android.widget.EditText(context).apply {
            hint = "订阅源名称 (如: 我的自建源)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etName)

        // Input URL
        val etUrl = android.widget.EditText(context).apply {
            hint = "订阅源网址 (http://... 或 https://...)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etUrl)

        // Divider or spacing
        val space = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                20
            )
        }
        container.addView(space)

        // Layout for action buttons (File picker & LAN upload)
        val actionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }

        // Button: Select Local File
        val btnSelectFile = android.widget.Button(context).apply {
            text = "导入本地列表文件"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(5, 0, 5, 0)
            }
        }
        actionLayout.addView(btnSelectFile)

        // Button: LAN Upload (QR Code)
        val btnLanUpload = android.widget.Button(context).apply {
            text = "扫码/局域网推送"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                setMargins(5, 0, 5, 0)
            }
        }
        actionLayout.addView(btnLanUpload)

        container.addView(actionLayout)
        builder.setView(container)

        builder.setPositiveButton("确定") { _, _ ->
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                val newSub = com.iptv.tvplayer.data.Subscription(name, url)
                val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                currentSubs.add(newSub)
                com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl = url
                
                android.widget.Toast.makeText(context, "添加并激活：$name", android.widget.Toast.LENGTH_SHORT).show()
                
                if (settingsOverlayMinimal.visibility == View.VISIBLE && settingsLevel == 1) {
                    showSettingsLevel(1)
                }
                loadPlaylist()
            } else {
                android.widget.Toast.makeText(context, "名称或URL不能为空", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }

        val alertDialog = builder.create()

        btnSelectFile.setOnClickListener {
            alertDialog.dismiss()
            try {
                localFilePickerLauncher.launch("*/*")
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "无法启动文件选择器: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        btnLanUpload.setOnClickListener {
            alertDialog.dismiss()
            val ip = try {
                val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                android.text.format.Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
            } catch (e: Exception) { "127.0.0.1" }
            showQrDialog("http://$ip:8080", "扫码添加/推送订阅源")
        }

        alertDialog.show()
    }

    private fun showAddEpgDialog() {
        val context = this
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("添加 EPG 源")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // Input Name
        val etName = android.widget.EditText(context).apply {
            hint = "EPG源名称 (如: 我的EPG)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etName)

        // Input URL
        val etUrl = android.widget.EditText(context).apply {
            hint = "EPG链接 (http://.../epg.xml.gz)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
        container.addView(etUrl)

        // Divider or spacing
        val space = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                20
            )
        }
        container.addView(space)

        // Layout for action buttons (LAN upload)
        val actionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }

        // Button: LAN Upload (QR Code)
        val btnLanUpload = android.widget.Button(context).apply {
            text = "扫码/局域网推送"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(5, 0, 5, 0)
            }
        }
        actionLayout.addView(btnLanUpload)

        container.addView(actionLayout)
        builder.setView(container)

        builder.setPositiveButton("确定") { _, _ ->
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                val newEpg = com.iptv.tvplayer.data.Subscription(name, url)
                val currentEpgs = com.iptv.tvplayer.data.SettingsManager.epgs.toMutableList()
                currentEpgs.add(newEpg)
                com.iptv.tvplayer.data.SettingsManager.epgs = currentEpgs
                com.iptv.tvplayer.data.SettingsManager.activeEpgUrl = url
                
                android.widget.Toast.makeText(context, "已添加并切换：$name", android.widget.Toast.LENGTH_SHORT).show()
                
                if (settingsOverlayMinimal.visibility == View.VISIBLE && settingsLevel == 2) {
                    showSettingsLevel(2)
                }
                loadPlaylist()
            } else {
                android.widget.Toast.makeText(context, "名称或URL不能为空", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }

        val alertDialog = builder.create()

        btnLanUpload.setOnClickListener {
            alertDialog.dismiss()
            val ip = try {
                val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                android.text.format.Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
            } catch (e: Exception) { "127.0.0.1" }
            showQrDialog("http://$ip:8080", "扫码添加/推送EPG")
        }

        alertDialog.show()
    }

    private fun importLocalPlaylist(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val content = inputStream.use { it.readBytes() }
                val fileName = "local_playlist_${System.currentTimeMillis()}.txt"
                val targetFile = java.io.File(filesDir, fileName)
                targetFile.writeBytes(content)
                
                var originalName = "本地导入列表"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val displayName = cursor.getString(nameIndex)
                        if (!displayName.isNullOrBlank()) {
                            originalName = displayName.removeSuffix(".txt").removeSuffix(".m3u").removeSuffix(".m3u8")
                        }
                    }
                }
                
                val localUrl = "file://${targetFile.absolutePath}"
                val newSub = com.iptv.tvplayer.data.Subscription(originalName, localUrl)
                
                val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                currentSubs.add(newSub)
                com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl = localUrl
                
                android.widget.Toast.makeText(this, "导入成功，已切换：$originalName", android.widget.Toast.LENGTH_SHORT).show()
                
                if (settingsOverlayMinimal.visibility == View.VISIBLE && settingsLevel == 1) {
                    showSettingsLevel(1)
                }
                loadPlaylist()
            } else {
                android.widget.Toast.makeText(this, "无法读取文件数据", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "导入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    private fun getCurrentVersionName(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    private fun checkAppUpdate(manual: Boolean) {
        kotlinx.coroutines.MainScope().launch {
            try {
                val updateJsonStr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("https://gh-proxy.com/https://raw.githubusercontent.com/sfee1212/moyutv/main/update.json")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    if (connection.responseCode == 200) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else null
                }
                
                if (updateJsonStr != null) {
                    val jsonObj = org.json.JSONObject(updateJsonStr)
                    val serverVersionCode = jsonObj.optInt("versionCode", 0)
                    val serverVersionName = jsonObj.optString("versionName", "1.0")
                    val updateUrl = jsonObj.optString("updateUrl", "")
                    val updateInfo = jsonObj.optString("updateInfo", "发现新版本，请更新")
                    
                    val currentVersionCode = try {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            pInfo.versionCode
                        }
                    } catch (e: Exception) { 1 }

                    if (serverVersionCode > currentVersionCode && updateUrl.isNotEmpty()) {
                        showUpdateDialog(serverVersionName, updateInfo, updateUrl)
                    } else {
                        if (manual) {
                            android.widget.Toast.makeText(this@MainActivity, "当前已是最新版本 ($serverVersionName)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    if (manual) {
                        android.widget.Toast.makeText(this@MainActivity, "暂无更新或获取更新失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (manual) {
                    android.widget.Toast.makeText(this@MainActivity, "网络错误，无法检查更新", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showUpdateDialog(versionName: String, info: String, url: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("发现新版本 $versionName")
            .setMessage(info)
            .setCancelable(true)
            .setPositiveButton("立即更新") { _, _ ->
                checkInstallPermissionAndDownload(url)
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun checkInstallPermissionAndDownload(url: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(this, "请在设置中允许此应用安装未知应用", android.widget.Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        startUpdateDownload(url)
    }

    private fun startUpdateDownload(url: String) {
        val downloadUrl = if ((url.contains("github.com") || url.contains("githubusercontent.com")) && !url.contains("gh-proxy.com")) {
            "https://gh-proxy.com/$url"
        } else {
            url
        }
        val context = this
        val progressDialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }
        val tvProgress = android.widget.TextView(context).apply {
            text = "正在下载更新包: 0%"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 20)
            }
        }
        val progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        progressDialogView.addView(tvProgress)
        progressDialogView.addView(progressBar)

        val progressDialog = android.app.AlertDialog.Builder(context)
            .setTitle("下载更新")
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        progressDialog.show()

        kotlinx.coroutines.MainScope().launch {
            try {
                val apkFile = java.io.File(cacheDir, "update.apk")
                if (apkFile.exists()) apkFile.delete()

                val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var input: java.io.InputStream? = null
                    var output: java.io.OutputStream? = null
                    var conn: java.net.HttpURLConnection? = null
                    try {
                        val connUrl = java.net.URL(downloadUrl)
                        conn = connUrl.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.connect()

                        if (conn.responseCode != 200) return@withContext false
                        val fileLength = conn.contentLength

                        input = conn.inputStream
                        output = java.io.FileOutputStream(apkFile)

                        val data = ByteArray(4096)
                        var total: Long = 0
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val percentage = (total * 100 / fileLength).toInt()
                                runOnUiThread {
                                    progressBar.progress = percentage
                                    tvProgress.text = "正在下载更新包: $percentage%"
                                }
                            }
                            output.write(data, 0, count)
                        }
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    } finally {
                        output?.close()
                        input?.close()
                        conn?.disconnect()
                    }
                }

                progressDialog.dismiss()

                if (success && apkFile.exists()) {
                    installApk(apkFile)
                } else {
                    android.widget.Toast.makeText(context, "下载失败，请检查网络", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                progressDialog.dismiss()
                android.widget.Toast.makeText(context, "下载更新出错: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installApk(file: java.io.File) {
        try {
            val authority = "$packageName.fileprovider"
            val apkUri = androidx.core.content.FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "安装失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeleteSubscriptionConfirmDialog(item: com.iptv.tvplayer.ui.SettingItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要删除订阅源【${item.name}】吗？")
            .setPositiveButton("删除") { _, _ ->
                val currentSubs = com.iptv.tvplayer.data.SettingsManager.subscriptions.toMutableList()
                val targetSub = currentSubs.find { it.url == item.identifier }
                if (targetSub != null) {
                    currentSubs.remove(targetSub)
                    com.iptv.tvplayer.data.SettingsManager.subscriptions = currentSubs
                    
                    // Clean up local file if it is stored in filesDir
                    if (item.identifier.startsWith("file://")) {
                        val path = item.identifier.replace("file://", "")
                        val localFile = java.io.File(path)
                        if (localFile.exists() && path.contains(filesDir.absolutePath)) {
                            localFile.delete()
                        }
                    }

                    // If we deleted the active one, fallback
                    if (com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl == item.identifier) {
                        val fallbackUrl = currentSubs.firstOrNull()?.url ?: "http://129.159.177.216/api/test"
                        com.iptv.tvplayer.data.SettingsManager.activeSubscriptionUrl = fallbackUrl
                        loadPlaylist()
                    }
                    
                    android.widget.Toast.makeText(this, "订阅源已删除", android.widget.Toast.LENGTH_SHORT).show()
                    showSettingsLevel(1) // refresh list
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteEpgConfirmDialog(item: com.iptv.tvplayer.ui.SettingItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要删除EPG源【${item.name}】吗？")
            .setPositiveButton("删除") { _, _ ->
                val currentEpgs = com.iptv.tvplayer.data.SettingsManager.epgs.toMutableList()
                val targetEpg = currentEpgs.find { it.url == item.identifier }
                if (targetEpg != null) {
                    currentEpgs.remove(targetEpg)
                    com.iptv.tvplayer.data.SettingsManager.epgs = currentEpgs
                    
                    // If we deleted the active one, fallback
                    if (com.iptv.tvplayer.data.SettingsManager.activeEpgUrl == item.identifier) {
                        val fallbackUrl = currentEpgs.firstOrNull()?.url ?: "https://gitee.com/taksssss/tv/raw/main/epg/erw.xml.gz"
                        com.iptv.tvplayer.data.SettingsManager.activeEpgUrl = fallbackUrl
                        loadPlaylist()
                    }
                    
                    android.widget.Toast.makeText(this, "EPG源已删除", android.widget.Toast.LENGTH_SHORT).show()
                    showSettingsLevel(2) // refresh list
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private lateinit var tvNumberSelectOverlay: android.widget.TextView
    private var numberInputBuffer = ""
    private val runConfirmNumberSelection = Runnable { confirmNumberSelection() }

    private fun initNumberSelectOverlay() {
        tvNumberSelectOverlay = android.widget.TextView(this).apply {
            visibility = View.GONE
            textSize = 64f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#CC000000"))
                cornerRadius = 32f
            }
            background = gd
            gravity = android.view.Gravity.CENTER
            setPadding(80, 40, 80, 40)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        osdContainer.addView(tvNumberSelectOverlay)
    }

    private fun appendNumberInput(digit: String) {
        handler.removeCallbacks(runConfirmNumberSelection)
        if (numberInputBuffer.length >= 4) return
        numberInputBuffer += digit
        tvNumberSelectOverlay.text = numberInputBuffer
        tvNumberSelectOverlay.visibility = View.VISIBLE
        handler.postDelayed(runConfirmNumberSelection, 2000)
    }

    private fun confirmNumberSelection() {
        handler.removeCallbacks(runConfirmNumberSelection)
        tvNumberSelectOverlay.visibility = View.GONE
        val input = numberInputBuffer
        numberInputBuffer = ""
        if (input.isEmpty()) return
        val channelNum = input.toIntOrNull() ?: return
        if (channelNum <= 0) {
            android.widget.Toast.makeText(this, "无效的频道号", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val flatList = getFlatChannelList()
        if (channelNum > flatList.size) {
            android.widget.Toast.makeText(this, "无此频道，最大频道号为: ${flatList.size}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val (catIdx, chIdx) = flatList[channelNum - 1]
        val cat = categories.getOrNull(catIdx) ?: return
        val chName = channelsMap[cat]?.getOrNull(chIdx)?.name ?: ""
        android.widget.Toast.makeText(this, "数字选台: 切换至第 ${channelNum} 台【$chName】", android.widget.Toast.LENGTH_SHORT).show()
        playChannel(catIdx, chIdx, 0)
    }

    private fun getFlatChannelList(): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        categories.forEachIndexed { catIdx, cat ->
            val chList = channelsMap[cat] ?: emptyList()
            chList.forEachIndexed { chIdx, _ ->
                list.add(Pair(catIdx, chIdx))
            }
        }
        return list
    }

    override fun onDestroy() {
        playJob?.cancel()
        super.onDestroy()
        localServer?.stop()
        com.iptv.tvplayer.player.NativePlayerManager.release()
    }
}
