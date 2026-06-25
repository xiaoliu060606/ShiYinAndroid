package com.shiyin.music

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import java.lang.ref.WeakReference
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * 扣扣云音乐播放器 - Android WebView 封装
 * 功能：硬件加速 WebView + MediaSession 原生音频控制
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var mediaSessionManager: MediaSessionManager
    internal lateinit var webAppInterface: WebAppInterface
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var clipboardHelper: ClipboardHelper
    lateinit var quanJuYiChangChuLi: QuanJuYiChangChuLi

    // WakeLock 防止 CPU 休眠
    private var wakeLock: PowerManager.WakeLock? = null

    // 悬浮窗歌词管理器
    lateinit var xuanFuGeCiManager: XuanFuGeCiManager

    /** 查询悬浮窗管理器是否已初始化（供外部安全访问） */
    fun xuanFuGeCiYiChuShiHua(): Boolean = ::xuanFuGeCiManager.isInitialized

    private val meiTiJiangJiJieShouQi = MeiTiJiangJiJieShouQi(this)

    private class MeiTiJiangJiJieShouQi(activity: MainActivity) : BroadcastReceiver() {
        private val activityRef = WeakReference(activity)
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra("command") ?: return
            activityRef.get()?.sendCommandToWeb(command)
        }
    }

    private val yinPinZaoYinJieShouQi = YinPinZaoYinJieShouQi(this)

    private class YinPinZaoYinJieShouQi(activity: MainActivity) : BroadcastReceiver() {
        private val activityRef = WeakReference(activity)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val activity = activityRef.get() ?: return
                val player = activity.webAppInterface.currentPlayer
                if (player != null && player.isPlayingState()) {
                    player.pause()
                } else {
                    activity.sendCommandToWeb("pause")
                }
            }
        }
    }

    private val lanYaDuanKaiJieShouQi = LanYaDuanKaiJieShouQi(this)

    private class LanYaDuanKaiJieShouQi(activity: MainActivity) : BroadcastReceiver() {
        private val activityRef = WeakReference(activity)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                val activity = activityRef.get() ?: return
                val player = activity.webAppInterface.currentPlayer
                if (player != null && player.isPlayingState()) {
                    player.pause()
                    activity.webAppInterface.mediaSessionManager.setPlayStateFromNative(false)
                }
                activity.sendCommandToWeb("pause")
            }
        }
    }

    // 权限请求
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            mediaSessionManager.initNotificationChannel()
        }
    }

    // 悬浮窗权限回调
    internal val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            xuanFuGeCiManager.kaiQi()
            webView.evaluateJavascript(
                "javascript:typeof onXuanFuQuanXianJieGuo==='function'&&onXuanFuQuanXianJieGuo(true)", null
            )
        } else {
            webView.evaluateJavascript(
                "javascript:typeof onXuanFuQuanXianJieGuo==='function'&&onXuanFuQuanXianJieGuo(false)", null
            )
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = FrameLayout(this)
        rootLayout = layout
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 不再全局保持屏幕常亮，由系统正常息屏
        }
        layout.addView(webView)
        setContentView(layout)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        window.decorView.post {
            updateStatusBarIconColor()
        }

        playlistRepository = PlaylistRepository(this)

        mediaSessionManager = MediaSessionManager(this)

        clipboardHelper = ClipboardHelper(this)

        webAppInterface = WebAppInterface(this, mediaSessionManager, clipboardHelper, playlistRepository)
        mediaSessionManager.setGeQuQieHuanManager(webAppInterface.geQuQieHuanManager)

        quanJuYiChangChuLi = QuanJuYiChangChuLi(this)

        xuanFuGeCiManager = XuanFuGeCiManager(this) { webAppInterface.currentPlayer }
        xuanFuGeCiManager.chuShiHua()
        quanJuYiChangChuLi.huiDiao = object : QuanJuYiChangChuLi.QuanJuCuoWuHuiDiao {
            override fun onWangLuoDuanKai() {
                android.util.Log.w("MainActivity", "全局异常处理：网络断开")
            }
            override fun onWangLuoHuiFu() {
                android.util.Log.d("MainActivity", "全局异常处理：网络恢复")
            }
            override fun onBoFangQiBengKui(zhuangTai: QuanJuYiChangChuLi.BaoCunDeBoFangZhuangTai) {
                android.util.Log.w("MainActivity", "全局异常处理：播放器崩溃，尝试恢复 - ${zhuangTai.geQuMing}")
                quanJuYiChangChuLi.tongZhiH5HuiFuBoFang(zhuangTai)
            }
            override fun onFuWuChongQiHuiFu(zhuangTai: QuanJuYiChangChuLi.BaoCunDeBoFangZhuangTai) {
                android.util.Log.d("MainActivity", "全局异常处理：服务重启恢复 - ${zhuangTai.geQuMing}")
                mediaSessionManager.updateMetadata(
                    zhuangTai.geQuMing, zhuangTai.geShou, zhuangTai.fengMian, zhuangTai.zongShiChang, zhuangTai.geQuId
                )
                quanJuYiChangChuLi.tongZhiH5HuiFuBoFang(zhuangTai)
            }
        }

        setupWebView()

        setupBackPressedHandler()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(meiTiJiangJiJieShouQi, IntentFilter("MEDIA_CONTROL_FALLBACK"), Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(yinPinZaoYinJieShouQi, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                registerReceiver(lanYaDuanKaiJieShouQi, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED), Context.RECEIVER_NOT_EXPORTED)
            }
        } else {
            registerReceiver(meiTiJiangJiJieShouQi, IntentFilter("MEDIA_CONTROL_FALLBACK"))
            registerReceiver(yinPinZaoYinJieShouQi, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                registerReceiver(lanYaDuanKaiJieShouQi, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED))
            }
        }

        window.decorView.post {
            playlistRepository.initDefaultPlaylistIfNeeded()
            checkNotificationPermission()
            initWakeLock()
            quanJuYiChangChuLi.qiDongWangLuoJianKong()
            RiZhiGuanLiQi.logInfo("MainActivity", "应用启动完成 - 版本: ${getBanBenXinXi()}")
        }
    }
    
    private fun getBanBenXinXi(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "未知"
        }
    }

    /**
     * 根据系统主题更新状态栏图标颜色
     */
    private fun updateStatusBarIconColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 WindowInsetsController
            val controller = window.insetsController ?: return
            val isDarkMode = (resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            
            if (isDarkMode) {
                // 暗色模式：状态栏图标为浅色
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            } else {
                // 亮色模式：状态栏图标为深色
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 使用 systemUiVisibility
            val view = window.decorView
            val flags = view.systemUiVisibility
            val isDarkMode = (resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            
            if (isDarkMode) {
                // 暗色模式：状态栏图标为浅色
                view.systemUiVisibility = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                // 亮色模式：状态栏图标为深色
                view.systemUiVisibility = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    /**
     * 初始化 WakeLock
     */
    private fun initWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ShiYinMusic::PlaybackWakeLock"
        )
    }

    /**
     * 获取 WakeLock
     */
    fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    /**
     * 释放 WakeLock
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
    
    /**
     * 检查 WakeLock 是否持有
     */
    fun isWakeLockHeld(): Boolean {
        return wakeLock?.isHeld == true
    }

    /**
     * 配置 WebView - 开启硬件加速和优化设置
     */
    /**
     * 异步初始化 WebView，避免阻塞主线程
     */
    private fun setupWebView() {
        webView.apply {
            // 硬件加速已经在 AndroidManifest.xml 中开启
            // 这里设置 WebView 的渲染优化
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // 修复缓存权限问题：显式设置缓存路径到应用私有目录
            setupWebViewCache()

            settings.apply {
                // JavaScript 支持 - 必须启用以支持音乐播放功能
                javaScriptEnabled = true
                domStorageEnabled = true

                // 媒体播放设置 - 关键：不需要用户手势即可播放
                // 这样可以支持后台播放和通知栏控制
                mediaPlaybackRequiresUserGesture = false

                // 允许 JavaScript 自动打开窗口（某些音频播放需要）
                javaScriptCanOpenWindowsAutomatically = true

                // 缓存设置 - 使用默认缓存模式
                cacheMode = WebSettings.LOAD_DEFAULT

                loadsImagesAutomatically = true
                blockNetworkImage = false
                
                // 启用视口元标签支持
                useWideViewPort = true
                loadWithOverviewMode = true

                // 缩放控制 - 禁用用户缩放，保持界面一致性
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false

                // 文本渲染优化
                textZoom = 100
                defaultFontSize = 16
                defaultFixedFontSize = 16
                
                // 允许混合内容（HTTPS页面加载HTTP资源）
                // 与 network_security_config.xml 的明文流量配置保持一致
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // 允许访问文件
                allowFileAccess = true
                allowContentAccess = true

                // 允许 file:// 页面向外部发起跨域请求
                // yunzhiapi.cn 等第三方API无法配置CORS头，必须启用
                allowUniversalAccessFromFileURLs = true
                allowFileAccessFromFileURLs = false
                
                // 数据库支持（DOM存储已兼容）
                databaseEnabled = true
                
                // 启用 WebGL 和 Canvas 硬件加速
                setSupportMultipleWindows(false)

                // 设置 User-Agent，模拟 Chrome 浏览器
                userAgentString = "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                // 允许后台音频播放（关键设置，已在上方配置）
                // mediaPlaybackRequiresUserGesture = false
            }

            // 添加 JavaScript 接口
            addJavascriptInterface(webAppInterface, "AndroidBridge")
            
            // WebView 客户端
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 页面加载完成后注入播放列表数据
                    webView.post {
                        injectPlaylistData()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    android.util.Log.e("WebView", "页面加载错误: ${error?.description} (${error?.errorCode})")
                    // 不再自动重新加载页面，避免死循环
                    // 如果主框架加载失败，用户可以手动刷新或重启应用
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // 如果是外部链接，用浏览器打开，不在WebView中加载
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            startActivity(intent)
                            android.util.Log.d("WebView", "外部链接已跳转到浏览器: $url")
                            return true // 拦截，不在WebView中加载
                        } catch (e: Exception) {
                            android.util.Log.e("WebView", "无法打开外部链接: $url", e)
                            return false
                        }
                    }

                    // 其他链接在WebView中加载
                    return false
                }
            }
            
            // Chrome 客户端（支持全屏视频、文件选择等）
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    // 可以在这里添加进度条
                }

                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    message?.let {
                        val msg = "${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                        when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> {
                                android.util.Log.e("WebView", msg)
                                RiZhiGuanLiQi.logError("WebView", msg)
                            }
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> {
                                android.util.Log.w("WebView", msg)
                                RiZhiGuanLiQi.logWarn("WebView", msg)
                            }
                            else -> {
                                android.util.Log.d("WebView", msg)
                                RiZhiGuanLiQi.logInfo("WebView", msg)
                            }
                        }
                    }
                    return true
                }
            }

            // WebView 焦点监听，控制剪贴板访问
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // 有焦点时，允许JS访问剪贴板
                    evaluateJavascript("if(typeof enableClipboardAccess==='function') enableClipboardAccess();", null)
                } else {
                    // 失去焦点时，禁用JS剪贴板功能
                    evaluateJavascript("if(typeof disableClipboardAccess==='function') disableClipboardAccess();", null)
                }
            }

            // 渐进式加载本地 HTML 文件，避免主线程阻塞
            loadLocalHtmlAsync()
            
            // 获取焦点
            requestFocus(View.FOCUS_DOWN)
        }
    }

    /**
     * 异步加载本地 HTML 文件，避免主线程阻塞
     */
    private fun loadLocalHtmlAsync() {
        // 直接加载，WebView 已初始化完成
        val htmlPath = "file:///android_asset/拾音.html"
        webView.loadUrl(htmlPath)
        android.util.Log.d("WebViewPerformance", "HTML 文件异步加载完成")
    }

    /**
     * 配置 WebView 缓存路径，解决 Android 10+ 缓存权限问题
     * 预创建 WebView 缓存目录结构，避免 Chromium 索引重建错误
     */
    private fun setupWebViewCache() {
        try {
            // 创建 WebView 默认缓存目录结构
            val webViewCacheDir = File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
            val jsCacheDir = File(webViewCacheDir, "js")
            val wasmCacheDir = File(webViewCacheDir, "wasm")
            
            listOf(webViewCacheDir, jsCacheDir, wasmCacheDir).forEach { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            
            // 启用 DOM 存储和数据库支持
            webView.settings.apply {
                cacheMode = WebSettings.LOAD_DEFAULT
                domStorageEnabled = true
                databaseEnabled = true
            }
            
            android.util.Log.d("WebViewCache", "缓存目录已初始化: ${webViewCacheDir.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("WebViewCache", "设置缓存路径失败", e)
        }
    }

    /**
     * 注入播放列表数据到 H5
     */
    private fun injectPlaylistData() {
        val js = "try { var data = JSON.parse(AndroidBridge.loadPlaylist()); window.playlistJsonData = data; if (typeof songIds !== 'undefined' && data.songs && Array.isArray(data.songs)) { songIds = data.songs.map(function(s) { return s.id; }); playlistData = data.songs.map(function(s, i) { return { id: s.id, index: i, name: s.name || '未知歌曲', artist: s.artist || '未知歌手', pic: s.pic || '' }; }); isPlaylistLoaded = true; if (typeof renderPlaylist === 'function') renderPlaylist(); } console.log('[Android] 播放列表已注入，共 ' + (data.songs ? data.songs.length : 0) + ' 首歌曲'); } catch(e) { console.error('[Android] 播放列表注入失败:', e); }"
        webView.evaluateJavascript(js, null)
        // 初始化API配置单例（确保只读取一次 api_config.json）
        ApiPeiZhiDanLi.chuShiHua(this)
        // 注入歌词API配置（Web axios 兜底路径使用）
        zhuRuGeCiApiPeiZhi()
        // 注入搜索API配置（Web axios 兜底路径使用）
        zhuRuSouSuoApiPeiZhi()
        // 注入云智API配置（Web API_SERVERS 使用）
        zhuRuYunZhiApiPeiZhi()
    }

    /**
     * 从 ApiPeiZhiDanLi 读取歌词API配置并注入到 WebView
     * Web 层 fetchLyricsFromAPI 用 window.__LYRIC_API_URL__ / __LYRIC_API_TOKEN__
     */
    private fun zhuRuGeCiApiPeiZhi() {
        try {
            val url = ApiPeiZhiDanLi.huoQuUrl("lyric_api")
            val token = ApiPeiZhiDanLi.huoQuToken("lyric_api")
            val safeUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val safeToken = token.replace("\\", "\\\\").replace("'", "\\'")
            val injectJs = "try{window.__LYRIC_API_URL__='$safeUrl';window.__LYRIC_API_TOKEN__='$safeToken';console.log('[Android] 歌词API配置已注入')}catch(e){console.error('[Android] 歌词API配置注入失败',e)}"
            webView.evaluateJavascript(injectJs, null)
            android.util.Log.d("MainActivity", "歌词API配置已注入: url=$url, tokenConfigured=${token.isNotEmpty() && !token.startsWith("<TBD")}")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "注入歌词API配置失败: ${e.message}")
        }
    }

    /**
     * 从 ApiPeiZhiDanLi 读取搜索API配置并注入到 WebView
     * Web 层 axios 降级搜索用 window.__SEARCH_API_URL__ / __SEARCH_API_TOKEN__
     */
    private fun zhuRuSouSuoApiPeiZhi() {
        try {
            val url = ApiPeiZhiDanLi.huoQuUrl("search_api")
            val token = ApiPeiZhiDanLi.huoQuToken("search_api")
            val safeUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val safeToken = token.replace("\\", "\\\\").replace("'", "\\'")
            val injectJs = "try{window.__SEARCH_API_URL__='$safeUrl';window.__SEARCH_API_TOKEN__='$safeToken';console.log('[Android] 搜索API配置已注入')}catch(e){console.error('[Android] 搜索API配置注入失败',e)}"
            webView.evaluateJavascript(injectJs, null)
            android.util.Log.d("MainActivity", "搜索API配置已注入: url=$url, tokenConfigured=${token.isNotEmpty()}")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "注入搜索API配置失败: ${e.message}")
        }
    }

    /**
     * 从 ApiPeiZhiDanLi 读取云智API配置并注入到 WebView
     * Web 层 API_SERVERS 用 window.__YUNZHI_API_URL__ / __YUNZHI_API_TOKEN__
     */
    private fun zhuRuYunZhiApiPeiZhi() {
        try {
            val url = ApiPeiZhiDanLi.huoQuUrl("yunzhi_api")
            val token = ApiPeiZhiDanLi.huoQuToken("yunzhi_api")
            val safeUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val safeToken = token.replace("\\", "\\\\").replace("'", "\\'")
            val injectJs = "try{window.__YUNZHI_API_URL__='$safeUrl';window.__YUNZHI_API_TOKEN__='$safeToken';console.log('[Android] 云智API配置已注入')}catch(e){console.error('[Android] 云智API配置注入失败',e)}"
            webView.evaluateJavascript(injectJs, null)
            android.util.Log.d("MainActivity", "云智API配置已注入: url=$url, tokenConfigured=${token.isNotEmpty()}")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "注入云智API配置失败: ${e.message}")
        }
        // 无论注入成功与否，都通知 H5 初始化 API_SERVERS
        // 即使 window.__XXX__ 未注入，initApiServers 也会用空字符串初始化，避免 API_SERVERS 永远为空数组
        webView.evaluateJavascript("if(typeof initApiServers==='function'){initApiServers()}else{console.warn('[Android] initApiServers未定义')}", null)
    }

    /**
     * 重新加载播放列表（在保存新歌曲后调用）
     */
    fun reloadPlaylist() {
        val playlistJson = playlistRepository.loadPlaylist() ?: "{\"songs\":[]}"
        webAppInterface.tongBuBoFangLieBiao()
        val escaped = playlistJson.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        val js = "if(window.reloadPlaylistFromAndroid) window.reloadPlaylistFromAndroid(JSON.parse('$escaped'));"
        webView.evaluateJavascript(js, null)
    }

    /**
     * 检查通知权限
     */
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    mediaSessionManager.initNotificationChannel()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // 显示权限说明
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            mediaSessionManager.initNotificationChannel()
        }
    }

    /**
     * 从 URL 加载专辑封面并转换为 Bitmap
     */
    fun loadAlbumArt(url: String, callback: (Bitmap?) -> Unit) {
        if (url.isBlank() || url.startsWith("./")) {
            callback(null)
            return
        }
        Glide.with(applicationContext)
            .asBitmap()
            .load(url)
            .placeholder(R.drawable.ic_default_album)
            .error(R.drawable.ic_default_album)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    callback(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    callback(null)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    callback(null)
                }
            })
    }

    /**
     * 发送控制命令到 WebView
     */
    fun sendCommandToWeb(command: String) {
        val safeCommand = command.replace("'", "\\'")
        val js = "if(window.handleNativeCommand) window.handleNativeCommand('$safeCommand');"
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "sendCommandToWeb失败: ${e.message}")
            }
        }
    }

    /** Activity恢复时重新激活媒体会话 */
    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        mediaSessionManager.setActive(true)
        webAppInterface.tongZhiH5ShengMingZhouQi("resume")
        
        // 关键修复：恢复时同步当前播放状态到H5，避免封面停留在旧状态
        tongBuDangQianBoFangZhuangTai()
    }
    
    /**
     * 同步当前播放状态到H5（解决后台返回后封面不更新）
     */
    private fun tongBuDangQianBoFangZhuangTai() {
        try {
            val player = webAppInterface.currentPlayer
            if (player != null) {
                val isPlaying = player.isPlayingState()
                val position = player.getCurrentPosition()
                val duration = player.getDuration()
                
                // 同步播放状态
                webAppInterface.tongZhiH5BoFangZhuangTaiBianHua(isPlaying)
                
                // 同步当前歌曲信息（歌名、歌手、封面），解决息屏切歌后UI跳回上一首的问题
                // 注意：此方法会保留进度，不重置进度
                webAppInterface.geQuQieHuanManager.tongBuDangQianGeQuZhiH5()
                
                // 同步进度（在歌曲信息同步后再次同步进度，确保进度正确）
                if (duration > 0) {
                    webView.postDelayed({
                        val js = "if(window.onNativeProgressChanged) window.onNativeProgressChanged($position, $duration);"
                        webView.evaluateJavascript(js, null)
                    }, 100)
                }
                
                RiZhiGuanLiQi.logInfo("MainActivity", "恢复同步状态: isPlaying=$isPlaying, position=$position")
            }
        } catch (e: Exception) {
            RiZhiGuanLiQi.logError("MainActivity", "同步播放状态失败: ${e.message}")
        }
    }

    // 当前歌曲信息缓存
    private var currentSongTitle = "扣扣云"
    private var currentSongArtist = "正在播放..."
    private var currentSongCover: String? = null
    private var currentSongDuration = 0L

    /**
     * 发送服务 Intent 的统一方法
     */
    private fun sendServiceIntent(
        action: String,
        title: String = currentSongTitle,
        artist: String = currentSongArtist,
        cover: String? = currentSongCover,
        duration: Long = currentSongDuration,
        playing: Boolean? = null
    ) {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
            putExtra(MediaPlaybackService.EXTRA_TITLE, title)
            putExtra(MediaPlaybackService.EXTRA_ARTIST, artist)
            putExtra(MediaPlaybackService.EXTRA_COVER, cover)
            putExtra(MediaPlaybackService.EXTRA_DURATION, duration)
            playing?.let { putExtra("isPlaying", it) }
        }
        startService(intent)
    }

    /**
     * 启动后台播放服务
     */
    fun startPlaybackService() {
        acquireWakeLock()

        mediaSessionManager.getSessionToken()?.let {
            MediaPlaybackService.setSessionToken(it)
        }

        val intent = Intent(this, MediaPlaybackService::class.java)
        startService(intent)
    }

    /**
     * 更新后台服务的歌曲信息
     */
    fun updateServiceMetadata(title: String, artist: String, cover: String?, duration: Long) {
        currentSongTitle = title
        currentSongArtist = artist
        currentSongCover = cover
        currentSongDuration = duration
    }

    /**
     * 更新后台服务的播放状态
     */
    fun updateServicePlaybackState(playing: Boolean) {
        if (playing) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    /**
     * 停止后台播放服务
     */
    fun stopPlaybackService() {
        // 释放 WakeLock
        releaseWakeLock()

        val intent = Intent(this, MediaPlaybackService::class.java)
        stopService(intent)
    }

    /** 处理新的Intent（通知栏/锁屏控制命令由此进入） */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            if (it.action == "MEDIA_CONTROL") {
                val command = it.getStringExtra("command")
                command?.let { cmd ->
                    when (cmd) {
                        "play" -> {
                            val player = webAppInterface.currentPlayer
                            if (player != null && !player.isPlayingState()) {
                                player.resume()
                                webAppInterface.mediaSessionManager.setPlayStateFromNative(true)
                                webAppInterface.tongZhiH5BoFangZhuangTaiBianHua(true)
                            } else {
                                sendCommandToWeb(cmd)
                            }
                        }
                        "pause" -> {
                            val player = webAppInterface.currentPlayer
                            if (player != null && player.isPlayingState()) {
                                player.pause()
                                webAppInterface.mediaSessionManager.setPlayStateFromNative(false)
                                webAppInterface.tongZhiH5BoFangZhuangTaiBianHua(false)
                            } else {
                                sendCommandToWeb(cmd)
                            }
                        }
                        else -> sendCommandToWeb(cmd)
                    }
                }
            }
        }
    }

    /** Activity暂停时保持WebView运行（确保后台播放继续） */
    override fun onPause() {
        super.onPause()
        webView.resumeTimers()
        mediaSessionManager.setActive(true)
        webAppInterface.tongZhiH5ShengMingZhouQi("pause")
    }

    /** Activity停止时保持WebView定时器运行（确保后台播放继续） */
    override fun onStop() {
        super.onStop()
        webView.resumeTimers()
        webAppInterface.tongZhiH5ShengMingZhouQi("stop")
    }

    // onDestroy 方法已合并到文件末尾

    /**
     * 配置返回键行为
     * 使用 OnBackPressedDispatcher 替代废弃的 onBackPressed()
     */
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // 如果有歌曲正在播放，将应用切换到后台而不是退出
                    if (mediaSessionManager.isPlaying()) {
                        moveTaskToBack(true)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    /**
     * 获取 WebView 实例
     * 供 WebAppInterface 使用
     */
    fun getWebView(): WebView {
        return webView
    }

    /** 获取媒体会话管理器实例 */
    fun getMediaSessionManager(): MediaSessionManager {
        return mediaSessionManager
    }
    
    /**
     * 获取帧布局
     * 用于在 Activity 销毁时清理
     */
    private var rootLayout: FrameLayout? = null
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                android.util.Log.w("MainActivity", "内存紧张，清理缓存")
                webView.clearCache(true)
            }
        }
    }

    /** Activity销毁时释放所有资源（播放器、媒体会话、WebView） */
    override fun onDestroy() {
        super.onDestroy()
        
        RiZhiGuanLiQi.logInfo("MainActivity", "应用销毁 - 开始清理资源")

        try { unregisterReceiver(meiTiJiangJiJieShouQi) } catch (_: Exception) {}
        try { unregisterReceiver(yinPinZaoYinJieShouQi) } catch (_: Exception) {}
        try { unregisterReceiver(lanYaDuanKaiJieShouQi) } catch (_: Exception) {}

        rootLayout?.removeView(webView)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()

        if (::quanJuYiChangChuLi.isInitialized) {
            quanJuYiChangChuLi.shiFang()
        }

        if (::xuanFuGeCiManager.isInitialized) {
            xuanFuGeCiManager.shiFang()
        }

        stopPlaybackService()
        releaseWakeLock()

        webAppInterface.nativeRelease()
        webAppInterface.qingLi()
        webAppInterface.geQuQieHuanManager.zhongZhiYuJiaZai()

        mediaSessionManager.release()

        RiZhiGuanLiQi.logInfo("MainActivity", "应用销毁 - 资源清理完成")
        android.util.Log.d("MainActivity", "Activity 已销毁，资源已清理")
    }
}
