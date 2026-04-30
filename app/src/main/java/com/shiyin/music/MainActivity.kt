package com.shiyin.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import java.io.File
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
    private lateinit var webAppInterface: WebAppInterface
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var clipboardHelper: ClipboardHelper

    // WakeLock 防止 CPU 休眠
    private var wakeLock: PowerManager.WakeLock? = null

    // 权限请求
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            mediaSessionManager.initNotificationChannel()
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 创建 WebView 布局
        val layout = FrameLayout(this)
        rootLayout = layout
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 保持屏幕常亮，防止播放时休眠
            keepScreenOn = true
        }
        layout.addView(webView)
        setContentView(layout)
        
        // 立即设置状态栏透明（不等待 decorView.post）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        // 延迟设置状态栏图标颜色，确保 DecorView 已初始化
        window.decorView.post {
            updateStatusBarIconColor()
        }

        // 初始化播放列表存储
        playlistRepository = PlaylistRepository(this)
        playlistRepository.initDefaultPlaylistIfNeeded()

        // 初始化 MediaSession
        mediaSessionManager = MediaSessionManager(this)

        // 初始化剪贴板帮助类
        clipboardHelper = ClipboardHelper(this)

        // 初始化 WebView Bridge
        webAppInterface = WebAppInterface(this, mediaSessionManager, clipboardHelper, playlistRepository)

        // 延迟初始化 WebView，避免阻塞主线程
        initWebViewAsync()

        // 配置返回键行为
        setupBackPressedHandler()

        // 检查通知权限（Android 13+）
        checkNotificationPermission()

        // 初始化 WakeLock
        initWakeLock()
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
            val view = window.decorView ?: return
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
                it.acquire()
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
     * 配置 WebView - 开启硬件加速和优化设置
     */
    /**
     * 异步初始化 WebView，避免阻塞主线程
     */
    private fun initWebViewAsync() {
        // 直接在主线程初始化，无需延迟
        setupWebView()
    }

    /**
     * 配置 WebView - 开启硬件加速和优化设置
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

                // 关键修复：关闭危险的跨域访问，防止 XSS 攻击
                // 原设置 allowUniversalAccessFromFileURLs = true 存在安全风险
                // 现已关闭，如果遇到 CORS 问题，需要在服务器端配置 CORS 头
                allowUniversalAccessFromFileURLs = false
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
                        val msg = "[WebView] ${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                        when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> android.util.Log.e("WebView", msg)
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> android.util.Log.w("WebView", msg)
                            else -> android.util.Log.d("WebView", msg)
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
     * 使用现代缓存策略，避免使用废弃的 API
     */
    private fun setupWebViewCache() {
        try {
            // 创建应用私有缓存目录
            val cacheDir = File(cacheDir, "webview_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            // 启用 DOM 存储和数据库支持
            webView.settings.apply {
                cacheMode = WebSettings.LOAD_DEFAULT
                domStorageEnabled = true
                databaseEnabled = true
            }
            
            android.util.Log.d("WebViewCache", "缓存路径已设置: ${cacheDir.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("WebViewCache", "设置缓存路径失败", e)
        }
    }

    /**
     * 注入播放列表数据到 H5
     */
    private fun injectPlaylistData() {
        val playlistJson = playlistRepository.loadPlaylist() ?: "{\"songs\":[]}"
        val js = """
            (function() {
                window.playlistJsonData = $playlistJson;
                console.log('[Android] 播放列表已注入，共 ' + ($playlistJson.songs?.length || 0) + ' 首歌曲');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 重新加载播放列表（在保存新歌曲后调用）
     */
    fun reloadPlaylist() {
        val playlistJson = playlistRepository.loadPlaylist() ?: "{\"songs\":[]}"
        val js = "if(window.reloadPlaylistFromAndroid) window.reloadPlaylistFromAndroid(${playlistJson});"
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
        Glide.with(this)
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
        // 转义单引号防止JS注入
        val safeCommand = command.replace("'", "\\'")
        val js = "if(window.handleNativeCommand) window.handleNativeCommand('$safeCommand');"
        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    override fun onResume() {
        super.onResume()
        // WebView 恢复
        webView.resumeTimers()
        mediaSessionManager.setActive(true)
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

        sendServiceIntent(
            action = MediaPlaybackService.ACTION_UPDATE_METADATA,
            playing = true
        )
    }

    /**
     * 更新后台服务的歌曲信息
     */
    fun updateServiceMetadata(title: String, artist: String, cover: String?, duration: Long) {
        currentSongTitle = title
        currentSongArtist = artist
        currentSongCover = cover
        currentSongDuration = duration

        sendServiceIntent(
            action = MediaPlaybackService.ACTION_UPDATE_METADATA,
            title = title,
            artist = artist,
            cover = cover,
            duration = duration,
            playing = true
        )
    }

    /**
     * 更新后台服务的播放状态
     */
    fun updateServicePlaybackState(playing: Boolean) {
        sendServiceIntent(
            action = MediaPlaybackService.ACTION_UPDATE_METADATA,
            playing = playing
        )
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 处理媒体控制命令（从通知栏/锁屏）
        intent?.let {
            if (it.action == "MEDIA_CONTROL") {
                val command = it.getStringExtra("command")
                command?.let { cmd ->
                    sendCommandToWeb(cmd)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 关键：不暂停 WebView，确保后台播放继续
        // 保持 timers 运行，否则 JavaScript 会停止
        webView.resumeTimers()
        mediaSessionManager.setActive(true)
    }

    override fun onStop() {
        super.onStop()
        // 进入后台时，确保 timers 继续运行
        webView.resumeTimers()
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
    
    /**
     * 获取帧布局
     * 用于在 Activity 销毁时清理
     */
    private var rootLayout: FrameLayout? = null
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 停止后台播放服务
        stopPlaybackService()
        
        // 释放 WakeLock
        releaseWakeLock()
        
        // 释放原生音频播放器
        webAppInterface.nativeRelease()
        
        // 释放媒体会话
        mediaSessionManager.release()
        
        // 销毁 WebView 避免内存泄漏
        rootLayout?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
        
        android.util.Log.d("MainActivity", "Activity 已销毁")
    }
}
