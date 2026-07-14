package com.shiyin.music

import android.webkit.JavascriptInterface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebView JavaScript 接口
 * 实现 H5 与 Android 原生之间的双向通信
 */
class WebAppInterface(
    private val context: MainActivity,
    val mediaSessionManager: MediaSessionManager,
    private val clipboardHelper: ClipboardHelper,
    private val playlistRepository: PlaylistRepository
) {

    private var nativeAudioPlayer: NativeAudioPlayer? = null
    val currentPlayer: NativeAudioPlayer? get() = nativeAudioPlayer
    private val contextRef = java.lang.ref.WeakReference(context)

    // 搜索API配置（从 ApiPeiZhiDanLi 懒加载）
    private var searchApiUrl: String = ""
    private var searchApiToken: String = ""
    private var searchConfigLoaded = false

    private fun jiaZaiSouSuoPeiZhi() {
        if (searchConfigLoaded) return
        try {
            searchApiUrl = ApiPeiZhiDanLi.huoQuUrl("search_api")
            searchApiToken = ApiPeiZhiDanLi.huoQuToken("search_api")
            searchConfigLoaded = true
            android.util.Log.d("WebAppInterface", "搜索API配置加载: tokenConfigured=${searchApiToken.isNotEmpty()}")
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "加载搜索API配置失败: ${e.message}，下次调用将重试")
            // 不设置 searchConfigLoaded=true，允许后续重试
        }
    }

    private var currentSongInfo: SongInfo? = null

    private var dangQianBoFangLieBiao = mutableListOf<String>()
    private var dangQianBoFangSuoYin = 0
    private var dangQianBoFangMoShi = 0
    private val handler = Handler(Looper.getMainLooper())

    private val geQuZiYuanHuanCun = GeQuZiYuanHuanCun(context)
    val geQuQieHuanManager = GeQuQieHuanManager(context, mediaSessionManager, geQuZiYuanHuanCun)
    private val geCiHuanCun = GeCiHuanCun(context)
    val dingShiGuanBiManager = DingShiGuanBiManager()
    private val riZhiGuanLiQi = RiZhiGuanLiQi(context)
    private val yinYuanPeiZhi = YinYuanPeiZhi(context)

    init {
        val banBen = try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionName} (${pkg.versionCode})"
        } catch (e: Exception) { "未知" }
        val sheBei = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        RiZhiGuanLiQi.chuShiHua(banBen, sheBei)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 设置播放模式（从 H5 接收）
     * @param mode 播放模式：0=顺序播放, 1=单曲循环, 2=随机播放
     */
    @JavascriptInterface
    fun setPlayMode(mode: String) {
        val moShi = mode.toIntOrNull() ?: 0
        mediaSessionManager.setPlayMode(moShi)
        try {
            geQuQieHuanManager.gengXinBoFangSuoYin(
                geQuQieHuanManager.huoQuDangQianSuoYin(), moShi
            )
        } catch (e: Exception) {
            android.util.Log.w("WebAppInterface", "同步播放模式失败: ${e.message}")
        }
        android.util.Log.d("ShiYinSync", "[播放模式] H5→Native: mode=$moShi")
    }

    /**
     * 接收播放状态变化
     * @param stateJson JSON 格式：{"isPlaying": true, "position": 0, "duration": 300000}
     */
    @JavascriptInterface
    fun onPlayStateChanged(stateJson: String) {
        try {
            val json = JSONObject(stateJson)
            val isPlaying = json.optBoolean("isPlaying", false)
            val position = json.optLong("position", 0)
            val duration = json.optLong("duration", 0)

            // 更新 MediaSession 播放状态
            mediaSessionManager.apply {
                // 只在第一次播放时激活 MediaSession，避免循环触发
                if (isPlaying) {
                    setActive(true)
                }
                setPlayState(isPlaying)
                updateProgress(position, duration)
            }

            // 启动后台服务（只启动一次，保持后台播放）
            if (isPlaying) {
                context.startPlaybackService()
            } else {
                context.updateServicePlaybackState(false)
            }

        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "[播放状态] H5→Native 解析失败: ${e.message}")
            RiZhiGuanLiQi.logError("WebAppInterface", "播放状态解析失败: ${e.message}, 输入: $stateJson")
        }
    }

    /**
     * 接收歌曲信息变化
     * @param infoJson JSON 格式：{"id": "123", "title": "歌名", "artist": "歌手", "cover": "url", "duration": 300000}
     */
    @JavascriptInterface
    fun onSongInfoChanged(infoJson: String) {
        try {
            val json = JSONObject(infoJson)
            val songInfo = SongInfo(
                id = json.optString("id", ""),
                title = json.optString("title", "未知歌曲"),
                artist = json.optString("artist", "未知歌手"),
                album = json.optString("album", ""),
                cover = json.optString("cover", ""),
                duration = json.optLong("duration", 0)
            )

            currentSongInfo = songInfo

            // 同时设置切歌管理器的兜底歌曲信息（nativeLoadAndPlay路径需要）
            geQuQieHuanManager.sheZhiWanBuGeQuInfo(
                name = songInfo.title,
                artist = songInfo.artist,
                pic = songInfo.cover,
                id = songInfo.id
            )

            // 更新 MediaSession 元数据
            mediaSessionManager.updateMetadata(
                title = songInfo.title,
                artist = songInfo.artist,
                albumArtUrl = songInfo.cover,
                durationMs = songInfo.duration,
                songId = songInfo.id
            )

            // 更新后台服务的歌曲信息（通知栏显示）
            context.updateServiceMetadata(
                title = songInfo.title,
                artist = songInfo.artist,
                cover = songInfo.cover,
                duration = songInfo.duration
            )

        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "[歌曲信息] H5→Native 解析失败: ${e.message}")
        }
    }

    /**
     * 接收播放进度变化
     * 接收播放进度变化
     * @param currentTime 当前播放位置（毫秒）
     * @param duration 总时长（毫秒）
     */
    @JavascriptInterface
    fun onProgressChanged(currentTime: Long, duration: Long) {
        mediaSessionManager.updateProgress(currentTime, duration)
    }

    /**
     * 原生向 H5 请求控制
     * @param dongZuo 控制动作：play, pause, previous, next, seek
     * @return 是否成功发送
     */
    @JavascriptInterface
    fun requestControl(dongZuo: String): Boolean {
        // 这个方法实际上不会从 H5 调用，
        // 而是 MainActivity 通过 sendCommandToWeb 发送命令到 H5
        return true
    }

    /**
     * 检查原生功能是否可用
     */
    @JavascriptInterface
    fun isNativeReady(): Boolean {
        return true
    }

    /**
     * 获取原生版本信息
     */
    @JavascriptInterface
    fun getNativeVersion(): String {
        return "1.0.0"
    }

    /**
     * 接收 H5 推送的当前歌词行（用于通知栏和悬浮窗显示歌词）
     * @param geCi 当前歌词文本，空字符串表示无歌词
     */
    @JavascriptInterface
    fun updateGeCiHang(geCi: String) {
        handler.post {
            mediaSessionManager.updateGeCi(geCi)
            // 悬浮窗歌词由 XuanFuGeCiManager 原生 Handler 独立刷新，
            // 避免 WebView 后台时 JS 定时器受限导致不同步
        }
    }

    /**
     * 保存完整播放列表
     * @param playlistJson 播放列表 JSON 字符串
     * @return 是否保存成功
     */
    @JavascriptInterface
    fun savePlaylist(playlistJson: String): Boolean {
        val result = playlistRepository.savePlaylist(playlistJson)
        if (result) {
            tongBuBoFangLieBiao()
        }
        return result
    }

    @JavascriptInterface
    fun loadPlaylist(): String {
        return playlistRepository.loadPlaylist() ?: "{\"songs\":[]}"
    }

    @JavascriptInterface
    fun addSong(songJson: String): Boolean {
        android.util.Log.d("WebAppInterface", "addSong 被调用: $songJson")
        val result = playlistRepository.addSong(songJson)
        android.util.Log.d("WebAppInterface", "addSong 返回结果: $result")
        if (result) {
            tongBuBoFangLieBiao()
        }
        return result
    }

    @JavascriptInterface
    fun removeSong(songId: String): Boolean {
        val result = playlistRepository.removeSong(songId)
        if (result) {
            tongBuBoFangLieBiao()
        }
        return result
    }

    /**
     * 获取播放列表存储路径
     */
    @JavascriptInterface
    fun getPlaylistPath(): String {
        return "file://${playlistRepository.getPlaylistFilePath()}"
    }

    // ==================== 原生音频播放接口 ====================

    /**
     * 更新播放索引和播放模式（供H5调用，用于后台切歌时确定当前播放位置和模式）
     * @param suoYin 当前播放索引
     * @param boFangMoShi 播放模式：0=顺序播放, 1=单曲循环, 2=随机播放
     */
    @JavascriptInterface
    fun gengXinBoFangSuoYin(suoYin: String, boFangMoShi: String) {
        val suoYinInt = suoYin.toIntOrNull() ?: 0
        val moShiInt = boFangMoShi.toIntOrNull() ?: 0
        dangQianBoFangSuoYin = suoYinInt
        dangQianBoFangMoShi = moShiInt
        try {
            geQuQieHuanManager.gengXinBoFangSuoYin(suoYinInt, moShiInt)
        } catch (e: Exception) {
            android.util.Log.w("WebAppInterface", "同步索引失败: ${e.message}")
        }
    }

    /**
     * 更新播放列表ID顺序（供H5调用，用于后台切歌时确定下一首）
     */
    @JavascriptInterface
    fun gengXinBoFangLieBiao(geQuIds: String) {
        try {
            val xinLieBiao = geQuIds.split(",").filter { it.isNotEmpty() }.toMutableList()
            if (xinLieBiao == dangQianBoFangLieBiao) return
            dangQianBoFangLieBiao = xinLieBiao
            android.util.Log.d("WebAppInterface", "更新播放列表: ${dangQianBoFangLieBiao.size}首")
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "更新播放列表失败: ${e.message}")
        }
    }

    @JavascriptInterface
    fun gengXinBoFangLieBiaoXiangQing(geQuLieBiaoJson: String) {
        geQuQieHuanManager.gengXinBoFangLieBiaoXiangQing(geQuLieBiaoJson)
    }

    fun tongBuBoFangLieBiao() {
        try {
            val playlistJson = playlistRepository.loadPlaylist() ?: return
            val jsonObj = org.json.JSONObject(playlistJson)
            val songsArray = jsonObj.getJSONArray("songs")
            // 关键修复：songsArray.toString() 会将 / 转义为 \/，需要还原
            val songsJson = songsArray.toString().replace("\\/", "/")
            geQuQieHuanManager.gengXinBoFangLieBiaoXiangQing(songsJson)
            val ids = mutableListOf<String>()
            for (i in 0 until songsArray.length()) {
                ids.add(songsArray.getJSONObject(i).getString("id"))
            }
            dangQianBoFangLieBiao = ids
            android.util.Log.d("WebAppInterface", "同步GeQuQieHuanManager: ${ids.size}首")
        } catch (e: Exception) {
            android.util.Log.w("WebAppInterface", "同步播放列表失败: ${e.message}")
        }
    }

    @JavascriptInterface
    fun yuanShengQieGe(fangXiang: String): Boolean {
        RiZhiGuanLiQi.logInfo("WebAppInterface", "H5请求原生切歌: $fangXiang")
        val result = geQuQieHuanManager.qieGe(fangXiang)
        RiZhiGuanLiQi.logInfo("WebAppInterface", "原生切歌结果: $result")
        return result
    }

    /**
     * H5获取到URL后回传给原生播放
     * @param geQuId 歌曲ID
     * @param url 歌曲URL
     * @param suoYin 歌曲索引
     */
    @JavascriptInterface
    fun nativeSetSongUrl(geQuId: String, url: String, suoYin: Int) {
        android.util.Log.d("WebAppInterface", "H5回传URL: id=$geQuId, url=$url, index=$suoYin")
        geQuQieHuanManager.onH5UrlResponse(geQuId, url, suoYin)
    }

    /**
     * 后台切歌：在Native层直接处理切到下一首
     * 当WebView回调失败时使用缓存URL直接播放
     */
    fun houTaiQieGe(): Boolean {
        return geQuQieHuanManager.qieGe("next")
    }

    /**
     * 初始化原生音频播放器
     * @return 是否初始化成功
     */
    @JavascriptInterface
    fun initNativePlayer(): Boolean {
        android.util.Log.d("WebAppInterface", "[初始化] initNativePlayer 被调用")
        return try {
            nativeAudioPlayer?.release()
            nativeAudioPlayer = null

            var mediaSession = mediaSessionManager.getMediaSession()
            if (mediaSession == null) {
                mediaSessionManager.chuShiHua()
                mediaSession = mediaSessionManager.getMediaSession()
            }
            if (mediaSession == null) {
                android.util.Log.e("WebAppInterface", "[初始化] MediaSession 为空，失败")
                return false
            }
            
            nativeAudioPlayer = NativeAudioPlayer(context, mediaSession).apply {
                android.util.Log.d("WebAppInterface", "[初始化] NativeAudioPlayer 创建成功")
                mediaSessionManager.setNativeAudioPlayer(this)
                geQuQieHuanManager.sheZhiBoFangQi(this)
                
                callback = object : NativeAudioPlayer.AudioPlayerCallback {
                    override fun onPrepared(duration: Long) {
                        android.util.Log.d("WebAppInterface", "[回调] onPrepared: duration=${duration}ms")
                        if (context.isFinishing || context.isDestroyed) return
                        context.runOnUiThread {
                            geQuQieHuanManager.gengXinBoFangShiChang(duration)
                            try {
                                context.getWebView().evaluateJavascript(
                                    "javascript:onNativePlayerPrepared($duration)",
                                    null
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("WebAppInterface", "onPrepared回调失败: ${e.message}")
                            }
                        }
                    }

                    override fun onProgressChanged(position: Long, duration: Long) {
                        mediaSessionManager.updateProgress(position, duration)
                        if (context.isFinishing || context.isDestroyed) return
                        context.runOnUiThread {
                            try {
                                context.getWebView().evaluateJavascript(
                                    "javascript:onNativeProgressChanged($position, $duration)",
                                    null
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("WebAppInterface", "onProgressChanged回调失败: ${e.message}")
                            }
                        }
                    }

                    override fun onCompletion() {
                        android.util.Log.d("ShiYinSync", "[播放完成] Native→H5: 歌曲播放完成")
                        
                        if (dingShiGuanBiManager.shiFouYiSheZhi() && dingShiGuanBiManager.shiFouBoWanZaiTing()) {
                            android.util.Log.d("DingShiGuanBi", "播完当前再停模式，执行定时暂停")
                            geQuQieHuanManager.zhongZhiYuJiaZai()
                            dingShiGuanBiManager.zaiGeQuBoWanShiJianCha()
                            return
                        }
                        
                        if (context.isFinishing || context.isDestroyed) {
                            geQuQieHuanManager.zhongZhiYuJiaZai()
                            mediaSessionManager.notifySongCompletion()
                            return
                        }
                        // 优先使用原生切歌（预加载缓存还在，不依赖 WebView JS，息屏也能工作）
                        val qieGeChengGong = geQuQieHuanManager.qieGe("next")
                        // 清理预加载数据，但**不释放切歌锁，不移除超时定时器**。
                        // 切歌锁由 qieGe() 内部在切歌完成(boFang)或超时(15s)时自动释放。
                        // 此处释放锁会导致异步H5请求期间另一个切歌进入，在随机模式下选到不同歌曲。
                        geQuQieHuanManager.qingChuYuJiaZaiShuJu()
                        android.util.Log.d("WebAppInterface", "[播放完成] 原生切歌结果: ${if (qieGeChengGong) "成功" else "失败（无缓存或H5未响应）"}")
                        if (!qieGeChengGong) {
                            // 原生切歌失败时通知H5尝试（作为兜底）
                            context.runOnUiThread {
                                try {
                                    context.getWebView().evaluateJavascript(
                                        "javascript:onNativePlaybackComplete()", null
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.w("WebAppInterface", "onCompletion回调失败: ${e.message}")
                                }
                            }
                        }
                        // 2秒后检查是否已播放，未播放则重试原生切歌（息屏时H5 fallback可能不执行）
                        handler.postDelayed({
                            val ctx = contextRef.get()
                            if (ctx == null || ctx.isFinishing || ctx.isDestroyed) return@postDelayed
                            val player = nativeAudioPlayer
                            if (player != null && !player.isPlayingState()) {
                                android.util.Log.w("WebAppInterface", "[播放完成] 2s后仍未播放，重试原生切歌")
                                geQuQieHuanManager.qieGe("next")
                            }
                        }, 2000)
                    }

                    override fun onError(error: String) {
                        mediaSessionManager.setPlayStateFromNative(false)
                        RiZhiGuanLiQi.logError("WebAppInterface", "播放错误: $error")
                        
                        // 播放失败时由H5端处理自动下一首
                        handler.postDelayed({
                            val ctx = contextRef.get()
                            if (ctx == null || ctx.isFinishing || ctx.isDestroyed) return@postDelayed
                            val player = nativeAudioPlayer
                            if (player != null && !player.isPlayingState()) {
                                android.util.Log.w("ShiYinSync", "[播放错误] 自动切歌到下一首")
                                try {
                                    ctx.getWebView().evaluateJavascript(
                                        "javascript:if(typeof playNextSong==='function') playNextSong();",
                                        null
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.w("WebAppInterface", "错误后自动下一首失败: ${e.message}")
                                }
                            }
                        }, 1500)
                        
                        if (context.isFinishing || context.isDestroyed) return
                        context.runOnUiThread {
                            try {
                                val safeError = error.replace("\\", "\\\\")
                                    .replace("'", "\\'")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n")
                                    .replace("\r", "\\r")
                                context.getWebView().evaluateJavascript(
                                    "javascript:onNativePlayerError('$safeError')",
                                    null
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("WebAppInterface", "onError回调失败: ${e.message}")
                            }
                        }
                    }

                    override fun onPlayStateChanged(isPlaying: Boolean) {
                        android.util.Log.d("ShiYinSync", "[播放状态] Native→H5: $isPlaying")
                        mediaSessionManager.setPlayStateFromNative(isPlaying)
                        if (context.isFinishing || context.isDestroyed) return
                        context.runOnUiThread {
                            try {
                                context.getWebView().evaluateJavascript(
                                    "javascript:onNativePlayStateChanged($isPlaying)",
                                    null
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("WebAppInterface", "onPlayStateChanged回调失败: ${e.message}")
                            }
                        }
                    }

                    override fun onProgressThreshold(position: Long, duration: Long) {
                        android.util.Log.d("ShiYinSync", "[预加载] 播放进度达80%: $position/$duration")
                        geQuQieHuanManager.chuLiJinDuBianHua(position, duration)
                    }
                }
            }
            android.util.Log.d("ShiYinSync", "[播放器] 原生音频播放器初始化成功")
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "初始化原生播放器失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 预先设置歌曲时长（毫秒）
     * 用于解决网络流媒体 MediaPlayer.duration() 返回0的问题
     * @param durationMs 歌曲时长（毫秒）
     */
    @JavascriptInterface
    fun setExpectedDuration(durationMs: Long) {
        android.util.Log.d("WebAppInterface", "[时长] 预设歌曲时长: ${durationMs}ms")
        nativeAudioPlayer?.setExpectedDuration(durationMs)
    }

    /**
     * 使用原生播放器加载并播放音频
     * @param url 音频URL
     * @param startPosition 起始位置（毫秒）
     * @return 是否成功开始加载
     */
    @JavascriptInterface
    fun nativeLoadAndPlay(url: String, startPosition: Long = 0): Boolean {
        try {
            android.util.Log.d("WebAppInterface", "nativeLoadAndPlay: url=${url.take(60)}..., pos=$startPosition")
            if (url.isEmpty() || url == "null" || url == "undefined") {
                android.util.Log.w("WebAppInterface", "nativeLoadAndPlay: URL为空或无效")
                return false
            }
            if (nativeAudioPlayer == null) {
                val initialized = initNativePlayer()
                if (!initialized) {
                    android.util.Log.e("WebAppInterface", "原生播放器初始化失败")
                    return false
                }
            }
            // 关键修复：清除上一个原生切歌留下的 dangQianBoFangGeQu 引用
            // 防止 gengXinBoFangShiChang 用旧歌曲信息覆盖 H5 已设置的正确元数据
            geQuQieHuanManager.qingChuDangQianBoFangGeQu()
            nativeAudioPlayer?.loadAndPlay(url, startPosition)
            mediaSessionManager.requestAudioFocus()
            android.util.Log.d("WebAppInterface", "nativeLoadAndPlay: 已提交加载请求")
            return true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativeLoadAndPlay 错误: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 原生播放器播放
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativePlay(): Boolean {
        return try {
            val player = nativeAudioPlayer
            if (player != null) {
                android.util.Log.d("WebAppInterface", "[控制] nativePlay: player存在, isPlaying=${player.isPlayingState()}")
                player.play()
            } else {
                android.util.Log.w("WebAppInterface", "[控制] nativePlay: player为null, 降级到JS")
                // 原生播放器已被释放，降级到JS控制HTML5音频
                // 同时通知JS更新播放状态，否则按钮UI不会更新
                evaluateJs("""
                    if(window.player && window.player.paused) window.player.play();
                    if(typeof onNativePlayStateChanged === 'function') onNativePlayStateChanged(true);
                """.trimIndent())
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativePlay 错误: ${e.message}")
            false
        }
    }

    /**
     * 原生播放器暂停
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativePause(): Boolean {
        return try {
            val player = nativeAudioPlayer
            if (player != null) {
                android.util.Log.d("WebAppInterface", "[控制] nativePause: player存在, isPlaying=${player.isPlayingState()}")
                player.pause()
            } else {
                android.util.Log.w("WebAppInterface", "[控制] nativePause: player为null, 降级到JS")
                // 原生播放器已被释放，降级到JS控制HTML5音频
                // 同步通知JS更新播放状态，否则 isPlaying 一直为true导致按钮不更新
                evaluateJs("""
                    if(window.player && !window.player.paused) window.player.pause();
                    if(typeof onNativePlayStateChanged === 'function') onNativePlayStateChanged(false);
                """.trimIndent())
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativePause 错误: ${e.message}")
            false
        }
    }
    
    /**
     * 原生播放器恢复播放
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativeResume(): Boolean {
        return try {
            val player = nativeAudioPlayer
            if (player != null) {
                android.util.Log.d("WebAppInterface", "[控制] nativeResume: player存在, isPlaying=${player.isPlayingState()}")
                player.resume()
            } else {
                android.util.Log.w("WebAppInterface", "[控制] nativeResume: player为null, 降级到JS")
                // 原生播放器已被释放，降级到JS控制HTML5音频
                // 同步通知JS更新播放状态
                evaluateJs("""
                    if(window.player && window.player.paused) window.player.play();
                    if(typeof onNativePlayStateChanged === 'function') onNativePlayStateChanged(true);
                """.trimIndent())
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativeResume 错误: ${e.message}")
            false
        }
    }

    /**
     * 原生播放器跳转到指定位置
     * @param position 位置（毫秒）
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativeSeekTo(position: Long): Boolean {
        return try {
            android.util.Log.d("WebAppInterface", "[控制] nativeSeekTo: position=${position}ms")
            val player = nativeAudioPlayer
            if (player != null) {
                player.seekTo(position)
            } else {
                android.util.Log.w("WebAppInterface", "[控制] nativeSeekTo: player为null, 降级到JS")
                // 原生播放器已释放，降级到JS通过HTML5 audio跳转
                val seekMs = position
                evaluateJs("if(window.player) window.player.currentTime = $seekMs / 1000;")
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativeSeekTo 错误: ${e.message}")
            false
        }
    }

    /**
     * 获取原生播放器当前位置
     */
    @JavascriptInterface
    fun nativeGetCurrentPosition(): Long {
        val player = nativeAudioPlayer
        if (player != null) {
            return player.getCurrentPosition()
        }
        // 原生播放器已释放时，无法同步获取位置，返回0避免错误
        return 0
    }

    /**
     * 获取原生播放器总时长
     */
    @JavascriptInterface
    fun nativeGetDuration(): Long {
        val player = nativeAudioPlayer
        if (player != null) {
            return player.getDuration()
        }
        return 0
    }

    /**
     * 原生播放器是否正在播放
     */
    @JavascriptInterface
    fun nativeIsPlaying(): Boolean {
        return nativeAudioPlayer?.isPlayingState() ?: false
    }

    /**
     * 释放原生播放器资源
     */
    @JavascriptInterface
    fun nativeRelease(): Boolean {
        android.util.Log.d("WebAppInterface", "[释放] nativeRelease: 释放原生播放器, isPlaying=${nativeAudioPlayer?.isPlayingState() ?: false}")
        mediaSessionManager.setNativeAudioPlayer(null)
        nativeAudioPlayer?.release()
        nativeAudioPlayer = null
        try {
            if (!context.isFinishing && !context.isDestroyed) {
                context.runOnUiThread {
                    try {
                        context.getWebView().evaluateJavascript(
                            "javascript:if(typeof onNativePlayerReleased==='function') onNativePlayerReleased();",
                            null
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("WebAppInterface", "通知H5播放器释放失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WebAppInterface", "nativeRelease回调异常: ${e.message}")
        }
        return true
    }

    /**
     * 安全读取剪贴板内容（供 H5 调用）
     * @return 剪贴板文本内容，如果无法读取则返回 null
     */
    @JavascriptInterface
    fun readClipboard(): String? {
        return try {
            clipboardHelper.readClipboard()
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "读取剪贴板失败: ${e.message}")
            null
        }
    }

    /**
     * 安全写入剪贴板内容（供 H5 调用）
     * @param text 要写入的文本
     * @return 是否写入成功
     */
    @JavascriptInterface
    fun writeClipboard(text: String): Boolean {
        return try {
            clipboardHelper.writeClipboard(text)
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "写入剪贴板失败: ${e.message}")
            false
        }
    }

    // ==================== 原生网络请求接口（绕过 CORS）====================

    /**
     * 原生搜索歌曲（异步版本，绕过 WebView CORS 限制）
     * @param keyword 搜索关键词
     * @param page 页码（从1开始）
     * @param callback JS 回调函数名
     */
    @JavascriptInterface
    fun nativeSearchSongsAsync(keyword: String, page: Int, callback: String) {
        android.util.Log.d("WebAppInterface", "原生搜索(异步): keyword=$keyword, page=$page, callback=$callback")

        try {
            jiaZaiSouSuoPeiZhi()
            if (searchApiUrl.isEmpty()) {
                android.util.Log.e("WebAppInterface", "搜索API URL未配置，无法执行搜索")
                val errorResult = "{\"code\":-1,\"msg\":\"搜索API未配置\",\"data\":[]}"
                invokeJsCallback(callback, errorResult)
                return
            }
            val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "$searchApiUrl?gm=$encodedKeyword&n&br=lossless&token=$searchApiToken"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json")
                .addHeader("Referer", try { val u = java.net.URI(searchApiUrl); "${u.scheme}://${u.host}/" } catch (_: Exception) { "https://apicx.asia/" })
                .build()

            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    android.util.Log.e("WebAppInterface", "搜索失败: ${e.message}")
                    val errorResult = "{\"code\":-1,\"msg\":\"${e.message?.replace("'", "\\'")}\",\"data\":[]}"
                    invokeJsCallback(callback, errorResult)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        val responseBody = response.body?.string()
                        if (response.isSuccessful && responseBody != null) {
                            android.util.Log.d("WebAppInterface", "搜索成功，返回 ${responseBody.length} 字符")
                            val normalized = convertApicxSearchToNeteaseFormat(responseBody)
                            invokeJsCallback(callback, normalized)
                        } else {
                            android.util.Log.e("WebAppInterface", "搜索失败: HTTP ${response.code}")
                            val errorResult = "{\"code\":-1,\"msg\":\"请求失败\",\"data\":[]}"
                            invokeJsCallback(callback, errorResult)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "搜索异常: ${e.message}")
            val errorResult = "{\"code\":-1,\"msg\":\"${e.message?.replace("'", "\\'")}\",\"data\":[]}"
            invokeJsCallback(callback, errorResult)
        }
    }

    /**
     * 将残影搜索响应转换为网易云官方搜索格式，供 Web 层无感消费
     */
    private fun convertApicxSearchToNeteaseFormat(responseBody: String): String {
        return try {
            val json = org.json.JSONObject(responseBody)
            val code = json.optInt("code", -1)
            if (code != 200) {
                // 接口返回业务错误时，回调空结果
                return "{\"code\":-1,\"msg\":\"${json.optString("msg", "搜索失败")}\",\"data\":[]}"
            }
            val data = json.optJSONObject("data") ?: return "{\"code\":200,\"result\":{\"songs\":[]}}"
            val songs = data.optJSONArray("songs") ?: return "{\"code\":200,\"result\":{\"songs\":[]}}"
            val resultSongs = org.json.JSONArray()
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val newSong = org.json.JSONObject()
                newSong.put("id", song.opt("id"))
                newSong.put("name", song.optString("name"))

                val artistsStr = song.optString("artists", "")
                val artistsArr = org.json.JSONArray()
                if (artistsStr.isNotEmpty()) {
                    val parts = artistsStr.split("[,/]".toRegex())
                    for (part in parts) {
                        val trimmed = part.trim()
                        if (trimmed.isNotEmpty()) {
                            val artistObj = org.json.JSONObject()
                            artistObj.put("name", trimmed)
                            artistsArr.put(artistObj)
                        }
                    }
                }
                newSong.put("artists", artistsArr)

                val albumObj = org.json.JSONObject()
                albumObj.put("name", song.optString("album"))
                val pic = song.optString("pic", "")
                albumObj.put("picUrl", pic)
                newSong.put("album", albumObj)
                newSong.put("picUrl", pic)
                newSong.put("duration", song.optInt("time", 0))
                resultSongs.put(newSong)
            }
            val result = org.json.JSONObject()
            result.put("songs", resultSongs)
            val out = org.json.JSONObject()
            out.put("code", 200)
            out.put("result", result)
            out.toString()
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "搜索响应解析失败: ${e.message}")
            "{\"code\":-1,\"msg\":\"解析失败\",\"data\":[]}"
        }
    }

    /**
     * 从播放列表查询歌曲的歌名和来源
     * @return Pair(歌名, source) 或 null（未找到）
     */
    private fun chaGeQuXinXiFromPlaylist(songId: String): Pair<String, String>? {
        return try {
            val playlistJson = playlistRepository.loadPlaylist() ?: return null
            val json = org.json.JSONObject(playlistJson)
            val songs = json.optJSONArray("songs") ?: return null
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                if (song.optString("id") == songId) {
                    return Pair(song.optString("name", ""), song.optString("source", "wy"))
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("WebAppInterface", "从播放列表查询歌曲信息失败: ${e.message}")
            null
        }
    }

    /**
     * 原生请求歌曲详情（异步版本，绕过 WebView CORS 限制）
     * @param songId 歌曲 ID（网易云数字ID / QQ音乐字母ID）
     * @param geQuMing 歌曲名
     * @param callback JS 回调函数名
     */
    @JavascriptInterface
    fun nativeGetSongDetailAsync(songId: String, geQuMing: String, callback: String) {
        android.util.Log.d("WebAppInterface", "[原生通道] >>> 入口: id=$songId, name=$geQuMing, callback=$callback")
        RiZhiGuanLiQi.logInfo("WebAppInterface", "[原生通道] >>> 入口: id=$songId, name=$geQuMing")

        var searchName = geQuMing
        var geQuLaiYuan = "wy" // 默认网易云

        // 从播放列表查询歌曲信息（歌名和来源）
        val geQuXinXi = chaGeQuXinXiFromPlaylist(songId)
        if (geQuXinXi != null) {
            if (searchName.isEmpty()) {
                searchName = geQuXinXi.first
                android.util.Log.d("WebAppInterface", "[原生通道] 从播放列表获取到歌名: $searchName")
            }
            geQuLaiYuan = geQuXinXi.second
        }

        if (searchName.isEmpty()) {
            android.util.Log.w("WebAppInterface", "[原生通道] 歌名为空且无法获取")
            invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"无法获取歌曲名称，请检查播放列表\"}")
            return
        }

        // 根据歌曲来源路由API：QQ歌曲走QQ音乐，其他走云智(网易云)
        RiZhiGuanLiQi.logInfo("WebAppInterface", "[原生通道] 来源: $geQuLaiYuan, 歌名: $searchName, songId: $songId")
        if (geQuLaiYuan == "qq") {
            qingQiuQqmusicSouSuo(searchName, songId, callback)
        } else {
            qingQiuCenguiguiAPI(songId, callback)
        }
    }

    /** apicx 搜索（第一步） */
    private fun qingQiuApicxSouSuo(geQuMing: String, songId: String, callback: String) {
        val souSuoUrl = yinYuanPeiZhi.huoQuApicxSouSuoUrl(geQuMing)
        android.util.Log.d("WebAppInterface", "[API追踪] apicx搜索: $souSuoUrl")
        RiZhiGuanLiQi.logInfo("WebAppInterface", "[apicx搜索] 歌名=$geQuMing, songId=$songId")

        val request = Request.Builder()
            .url(souSuoUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e("WebAppInterface", "[原生通道] apicx搜索失败: ${e.message}")
                RiZhiGuanLiQi.logWarn("WebAppInterface", "[apicx搜索] 网络失败: ${e.message}")
                invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx搜索失败: ${e.message?.replace("'", "\\'")}\"}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        RiZhiGuanLiQi.logWarn("WebAppInterface", "[apicx搜索] HTTP ${response.code}")
                        invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx搜索HTTP ${response.code}\"}")
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        if (json.optInt("code", -1) != 200) {
                            invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx搜索返回异常\"}")
                            return
                        }
                        val dataObj = json.optJSONObject("data") ?: run {
                            invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx搜索无数据\"}")
                            return
                        }
                        val songsArray = dataObj.optJSONArray("songs")
                        if (songsArray == null || songsArray.length() == 0) {
                            invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx搜索结果为空\"}")
                            return
                        }
                        var piPeiN = -1
                        for (i in 0 until songsArray.length()) {
                            val song = songsArray.getJSONObject(i)
                            // 使用optLong避免ID超过Int.MAX_VALUE(2147483647)时溢出
                            val apiId = song.optLong("id", -1).toString()
                            val apiIdStr = if (apiId == "-1") song.optString("id", "") else apiId
                            if (apiIdStr == songId || song.optString("id", "") == songId) {
                                piPeiN = song.optInt("n", -1)
                                break
                            }
                        }
                        if (piPeiN <= 0) {
                            RiZhiGuanLiQi.logWarn("WebAppInterface", "[apicx搜索] 未匹配到歌曲ID: songId=$songId")
                            invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx未匹配到歌曲ID\"}")
                            return
                        }
                        qingQiuApicxXiangQing(geQuMing, piPeiN, songId, callback)
                    } catch (e: Exception) {
                        RiZhiGuanLiQi.logError("WebAppInterface", "[apicx搜索] 解析失败: ${e.message}")
                        invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx搜索解析失败: ${e.message?.replace("'", "\\'")}\"}")
                    }
                }
            }
        })
    }

    /** apicx 详情（第二步） */
    private fun qingQiuApicxXiangQing(geQuMing: String, n: Int, songId: String, callback: String) {
        val xiangQingUrl = yinYuanPeiZhi.huoQuApicxXiangQingUrl(geQuMing, n)
        android.util.Log.d("WebAppInterface", "[API追踪] apicx详情: n=$n")
        RiZhiGuanLiQi.logInfo("WebAppInterface", "[apicx详情] 歌名=$geQuMing, n=$n")

        val request = Request.Builder()
            .url(xiangQingUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                RiZhiGuanLiQi.logWarn("WebAppInterface", "[apicx详情] 网络失败: ${e.message}")
                invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx详情失败: ${e.message?.replace("'", "\\'")}\"}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            if (json.optInt("code", -1) == 200) {
                                val dataObj = json.optJSONObject("data")
                                if (dataObj != null && (dataObj.optJSONObject("song") != null || dataObj.has("url"))) {
                                    // 修复: 验证URL是否有效，空URL降级到云智
                                    val url = dataObj.optString("url", "")
                                    if (url.isNotEmpty() && url.length >= 10 && (url.startsWith("https://") || url.startsWith("http://"))) {
                                        android.util.Log.d("WebAppInterface", "[原生通道] ✓ apicx成功")
                                        RiZhiGuanLiQi.logInfo("WebAppInterface", "[apicx详情] ✓ 成功")
                                        invokeJsCallback(callback, body)
                                        return
                                    } else {
                                        android.util.Log.w("WebAppInterface", "[apicx详情] URL为空或无效，降级到云智: songId=$songId")
                                        RiZhiGuanLiQi.logWarn("WebAppInterface", "[apicx详情] URL无效，降级云智")
                                        // 降级到云智API
                                        qingQiuCenguiguiAPI(songId, callback)
                                        return
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("WebAppInterface", "[API追踪] apicx详情解析异常: ${e.message}")
                        }
                    }
                    android.util.Log.w("WebAppInterface", "[API追踪] apicx详情数据异常, body前200=${body?.take(200)}")
                    RiZhiGuanLiQi.logWarn("WebAppInterface", "[apicx详情] 数据异常")
                    invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"apicx详情数据异常\"}")
                }
            }
        })
    }

    /** QQ音乐搜索+详情（一步到位，通过歌名获取播放地址） */
    private fun qingQiuQqmusicSouSuo(geQuMing: String, songId: String, callback: String) {
        // 使用n=1取第一个匹配结果
        val souSuoUrl = yinYuanPeiZhi.huoQuQqmusicDetailUrl(geQuMing, 1)
        android.util.Log.d("WebAppInterface", "[QQ音乐] 搜索: $souSuoUrl")
        RiZhiGuanLiQi.logInfo("WebAppInterface", "[QQ音乐] 搜索: 歌名=$geQuMing, songId=$songId")

        val request = Request.Builder()
            .url(souSuoUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e("WebAppInterface", "[QQ音乐] 失败: ${e.message}")
                RiZhiGuanLiQi.logWarn("WebAppInterface", "[QQ音乐] 网络失败: ${e.message}")
                invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"QQ音乐请求失败: ${e.message?.replace("'", "\\'")}\"}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        try {
                            val json = JSONObject(body)
                            if (json.optInt("code", -1) == 200 && json.has("data")) {
                                val dataObj = json.getJSONObject("data")
                                val playUrl = dataObj.optString("play_url", "")
                                if (playUrl.isNotEmpty()) {
                                    // QQ音乐歌词：提取formatted LRC文本
                                    val lyricsObj = dataObj.optJSONObject("lyrics")
                                    val lrcText = if (lyricsObj != null) lyricsObj.optString("formatted", "") else ""
                                    
                                    // 保存QQ歌词到Native缓存，后续loadLyrics直接命中缓存
                                    if (lrcText.isNotEmpty()) {
                                        val lrcResult = JSONObject().apply {
                                            put("code", 200)
                                            put("lrc", JSONObject().apply { put("lyric", lrcText) })
                                            put("tlyric", JSONObject().apply { put("lyric", "") })
                                        }
                                        geCiHuanCun.baoCunGeCi(songId, lrcResult.toString())
                                    }
                                    
                                    // 转换为H5端期望的格式（兼容tiQuGeQuYuanShuJu）
                                    val convertedJson = JSONObject().apply {
                                        put("code", 200)
                                        put("data", JSONObject().apply {
                                            put("url", playUrl)
                                            put("song", JSONObject().apply {
                                                put("name", dataObj.optString("name", ""))
                                                put("artist", dataObj.optString("artist", ""))
                                                put("album", dataObj.optString("album", ""))
                                                put("pic", dataObj.optString("cover", ""))
                                                put("url", playUrl)
                                                put("lrc", lrcText)
                                            })
                                            // data.lrc放格式化LRC文本，tiQuGeQuYuanShuJu从此读取
                                            put("lrc", lrcText)
                                        })
                                    }
                                    android.util.Log.d("WebAppInterface", "[QQ音乐] ✓ 成功: ${dataObj.optString("name", "")} - ${dataObj.optString("artist", "")}, 歌词=${if (lrcText.isNotEmpty()) "${lrcText.length}字符" else "无"}")
                                    RiZhiGuanLiQi.logInfo("WebAppInterface", "[QQ音乐] ✓ 成功")
                                    invokeJsCallback(callback, convertedJson.toString())
                                    return
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("WebAppInterface", "[QQ音乐] 解析异常: ${e.message}")
                        }
                    }
                    android.util.Log.w("WebAppInterface", "[QQ音乐] 数据异常, body前200=${body?.take(200)}")
                    RiZhiGuanLiQi.logWarn("WebAppInterface", "[QQ音乐] 数据异常")
                    invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"QQ音乐获取失败\"}")
                }
            }
        })
    }

    /** 云智API请求（通过歌曲ID直接获取） */
    private fun qingQiuCenguiguiAPI(songId: String, callback: String) {
        val apiUrl = yinYuanPeiZhi.huoQuYunzhiUrl(songId)
        android.util.Log.d("WebAppInterface", "[API追踪] 云智API: $apiUrl")

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                android.util.Log.e("WebAppInterface", "[原生通道] 云智API失败: ${e.message}")
                invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"云智API失败: ${e.message?.replace("'", "\\'")}\"}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        android.util.Log.e("WebAppInterface", "[原生通道] 云智API HTTP ${response.code}")
                        invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"云智API HTTP ${response.code}\"}")
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        // 云智API: code=1 表示成功，数据在 data 中
                        if (json.optInt("code", -1) == 1 && json.optJSONObject("data") != null) {
                                val dataObj = json.getJSONObject("data")
                                val url = dataObj.optString("url", "")
                                if (url.isNotEmpty()) {
                                    // 转换为H5端期望的格式（兼容apicx格式）
                                    // url必须同时放在data和data.song里，因为tiQuGeQuYuanShuJu从data.url提取
                                    val convertedJson = JSONObject().apply {
                                        put("code", 200)
                                        put("data", JSONObject().apply {
                                            put("url", url)  // 放在data级别
                                            put("song", JSONObject().apply {
                                                put("name", dataObj.optString("name", ""))
                                                put("artist", dataObj.optString("artist", ""))
                                                put("album", dataObj.optString("album", ""))
                                                put("pic", dataObj.optString("pic", ""))
                                                put("url", url)
                                                put("lrc", dataObj.optString("lrc", ""))
                                            })
                                            put("lrc", dataObj.optString("lrc", ""))
                                        })
                                    }
                                    android.util.Log.d("WebAppInterface", "[原生通道] ✓ 云智API成功: ${dataObj.optString("name", "")}")
                                    RiZhiGuanLiQi.logInfo("WebAppInterface", "[云智API] ✓ 成功: ${dataObj.optString("name", "")} - ${dataObj.optString("artist", "")}")
                                    invokeJsCallback(callback, convertedJson.toString())
                            } else {
                                android.util.Log.e("WebAppInterface", "[原生通道] 云智API返回URL为空")
                                invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"云智API返回URL为空\"}")
                            }
                        } else {
                            android.util.Log.e("WebAppInterface", "[原生通道] 云智API返回异常: ${body.take(200)}")
                            invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"云智API返回异常\"}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WebAppInterface", "[原生通道] 云智API解析失败: ${e.message}")
                        invokeJsCallback(callback, "{\"code\":-1,\"msg\":\"云智API解析失败: ${e.message?.replace("'", "\\'")}\"}")
                    }
                }
            }
        })
    }

    /**
     * 调用 JS 回调函数
     * @param callback 回调函数名
     * @param result JSON 结果字符串
     */
    private fun invokeJsCallback(callback: String, result: String) {
        try {
            if (context.isFinishing || context.isDestroyed) return
            android.util.Log.d("WebAppInterface", "[API追踪] invokeJsCallback: callback=$callback, result前200字符=${result.take(200)}")
            RiZhiGuanLiQi.logDebug("WebAppInterface", "[JS回调] callback=$callback, 结果前100=${result.take(100)}")
            val escapedResult = result.replace("\\/", "/")  // 修复：JSONObject.toString() 将 / 转义为 \/，还原后封面URL才能正常加载
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("\$", "\\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r")

            context.getWebView().post {
                if (context.isFinishing || context.isDestroyed) return@post
                val js = "if(typeof $callback === 'function') $callback(`$escapedResult`);"
                context.getWebView().evaluateJavascript(js) { evalResult ->
                    android.util.Log.d("WebAppInterface", "[原生通道] <<< JS回调执行完成: $callback, evalResult=$evalResult")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "[API追踪] invokeJsCallback失败: ${e.message}")
        }
    }

    /**
     * 获取歌词（带缓存）
     * @param songId 歌曲ID
     * @param callback JS回调函数名
     */
    @JavascriptInterface
    fun nativeGetLyricAsync(songId: String, callback: String) {
        geCiHuanCun.huoQuGeCiFromAPI(songId) { chengGong, result ->
            if (chengGong && result != null) {
                invokeJsCallback(callback, result)
            } else {
                val errorResult = "{\"code\":-1,\"msg\":\"歌词获取失败\"}"
                invokeJsCallback(callback, errorResult)
            }
        }
    }

    /**
     * 保存歌词到Native缓存
     * @param songId 歌曲ID
     * @param lyricJson 歌词JSON字符串
     */
    @JavascriptInterface
    fun nativeSaveLyric(songId: String, lyricJson: String) {
        geCiHuanCun.baoCunGeCi(songId, lyricJson)
    }

    /**
     * 在 WebView 主线程执行 JavaScript（用于原生播放器释放后的降级控制）
     * @param js 要执行的 JavaScript 代码
     */
    private fun evaluateJs(js: String) {
        try {
            context.getWebView().post {
                context.getWebView().evaluateJavascript("javascript:try{$js}catch(e){console.error(e)}", null)
            }
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "evaluateJs 失败: ${e.message}")
        }
    }

    /**
     * 歌曲信息数据类
     */
    data class SongInfo(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val duration: Long
    )

    // ==================== 全局异常处理桥接接口 ====================

    /** 保存播放状态到全局异常处理模块（供H5定期调用） */
    @JavascriptInterface
    fun baoCunBoFangZhuangTai(
        geQuId: String?, geQuMing: String?, geShou: String?,
        fengMian: String?, dangQianWeiZhi: Long, zongShiChang: Long,
        shiFouBoFangZhong: Boolean
    ): Boolean {
        return try {
            if (geQuId.isNullOrEmpty()) return false
            context.quanJuYiChangChuLi.baoCunBoFangZhuangTai(
                geQuId, geQuMing ?: "", geShou ?: "", fengMian ?: "",
                dangQianWeiZhi, zongShiChang, shiFouBoFangZhong
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "保存播放状态失败: ${e.message}")
            false
        }
    }

    /** 检查网络是否可用 */
    @JavascriptInterface
    fun wangLuoShiFouKeYong(): Boolean {
        return context.quanJuYiChangChuLi.wangLuoShiFouKeYong()
    }

    /** 获取歌曲的Native缓存路径 */
    @JavascriptInterface
    fun huoQuHuanCunLuJing(geQuId: String): String {
        val luJing = geQuQieHuanManager.huoQuHuanCunLuJing(geQuId)
        return luJing ?: ""
    }

    /** 报告播放器错误给全局异常处理模块 */
    @JavascriptInterface
    fun baoGaoBoFangQiCuoWu(cuoWuXinXi: String) {
        android.util.Log.w("ShiYinSync", "[错误] H5报告播放器错误: $cuoWuXinXi")
        context.quanJuYiChangChuLi.chuLiBoFangQiBengKui(cuoWuXinXi)
    }

    /** 通知全局异常处理模块播放器恢复成功 */
    @JavascriptInterface
    fun tongZhiBoFangQiHuiFuChengGong() {
        context.quanJuYiChangChuLi.bengKuiHuiFuChengGong()
    }

    // ==================== 生命周期同步接口 ====================

    /** 通知H5 Activity生命周期事件 */
    fun tongZhiH5ShengMingZhouQi(shiJian: String) {
        try {
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().post {
                if (context.isFinishing || context.isDestroyed) return@post
                context.getWebView().evaluateJavascript(
                    "javascript:if(typeof onYuanShengShengMingZhouQi==='function') onYuanShengShengMingZhouQi('$shiJian');",
                    null
                )
            }
            android.util.Log.d("ShiYinSync", "[生命周期] Native→H5: $shiJian")
        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "通知H5生命周期失败: ${e.message}")
        }
    }

    /** 通知H5播放状态变化（通知栏操作后同步UI） */
    fun tongZhiH5BoFangZhuangTaiBianHua(shiFouBoFang: Boolean) {
        try {
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().post {
                if (context.isFinishing || context.isDestroyed) return@post
                context.getWebView().evaluateJavascript(
                    "javascript:if(typeof onNativePlayStateChanged==='function') onNativePlayStateChanged($shiFouBoFang);",
                    null
                )
            }
            android.util.Log.d("ShiYinSync", "[播放状态] Native→H5: $shiFouBoFang")
        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "通知H5播放状态失败: ${e.message}")
        }
    }

    // ==================== 定时关闭接口 ====================

    @JavascriptInterface
    fun sheZhiDingShiGuanBi(fenZhong: Int, boWanZaiTing: Boolean) {
        android.util.Log.d("DingShiGuanBi", "H5请求设置定时关闭: ${fenZhong}分钟, boWanZaiTing=$boWanZaiTing")
        dingShiGuanBiManager.sheZhi(fenZhong, boWanZaiTing,
            onDaoJiShi = { shengYuMiaoShu ->
                try {
                    if (context.isFinishing || context.isDestroyed) return@sheZhi
                    context.runOnUiThread {
                        if (context.isFinishing || context.isDestroyed) return@runOnUiThread
                        try {
                            context.getWebView().evaluateJavascript(
                                "javascript:if(typeof onDingShiGuanBiGengXin==='function') onDingShiGuanBiGengXin($shengYuMiaoShu);",
                                null
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("DingShiGuanBi", "倒计时回调失败: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DingShiGuanBi", "倒计时回调异常: ${e.message}")
                }
            },
            onDaoQi = {
                try {
                    nativeAudioPlayer?.pause()
                    mediaSessionManager.setPlayStateFromNative(false)
                    if (context.isFinishing || context.isDestroyed) return@sheZhi
                    context.runOnUiThread {
                        if (context.isFinishing || context.isDestroyed) return@runOnUiThread
                        try {
                            context.getWebView().evaluateJavascript(
                                "javascript:if(typeof onNativePlayStateChanged==='function') onNativePlayStateChanged(false);",
                                null
                            )
                            context.getWebView().evaluateJavascript(
                                "javascript:if(typeof onDingShiGuanBiDaoQi==='function') onDingShiGuanBiDaoQi();",
                                null
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("DingShiGuanBi", "到期回调失败: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DingShiGuanBi", "到期处理异常: ${e.message}")
                }
            }
        )
    }

    @JavascriptInterface
    fun quXiaoDingShiGuanBi() {
        android.util.Log.d("DingShiGuanBi", "H5请求取消定时关闭")
        dingShiGuanBiManager.quXiao()
    }

    @JavascriptInterface
    fun huoQuDingShiZhuangTai(): String {
        val shengYu = dingShiGuanBiManager.huoQuShengYuMiaoShu()
        val yiSheZhi = dingShiGuanBiManager.shiFouYiSheZhi()
        return """{"enabled":$yiSheZhi,"remainingSeconds":$shengYu}"""
    }

    // ==================== 日志导出接口 ====================

    /**
     * 导出日志到文件并分享
     * @return 导出结果描述
     */
    @JavascriptInterface
    fun daoChuRiZhi(): String {
        return riZhiGuanLiQi.daoChuRiZhi()
    }

    /**
     * 获取日志内容（用于H5直接显示）
     * @return 日志文本内容
     */
    @JavascriptInterface
    fun huoQuRiZhi(): String {
        return riZhiGuanLiQi.huoQuRiZhiNeiRong()
    }

    /**
     * 清空日志缓存
     */
    @JavascriptInterface
    fun qingKongRiZhi() {
        riZhiGuanLiQi.qingKongRiZhi()
    }

    // ==================== 状态查询接口 ====================

    /** 清理Handler回调，防止内存泄漏 */
    fun qingLi() {
        handler.removeCallbacksAndMessages(null)
        dingShiGuanBiManager.wanQuanQingLi()
    }

    /** 查询Native端当前播放状态（供H5主动同步） */
    @JavascriptInterface
    fun chaXunBoFangZhuangTai(): String {
        val isPlaying = mediaSessionManager.isPlaying()
        val position = nativeAudioPlayer?.getCurrentPosition() ?: 0L
        val duration = nativeAudioPlayer?.getDuration() ?: 0L
        val playMode = mediaSessionManager.getDangQianBoFangMoShi()
        return """{"isPlaying":$isPlaying,"position":$position,"duration":$duration,"playMode":$playMode}"""
    }

    /**
     * 获取当前音源配置
     * @return auto=自动切换, yunzhi=云智API, apicx=残影API
     */
    @JavascriptInterface
    fun huoQuYinYuanPeiZhi(): String {
        return yinYuanPeiZhi.huoQuDangQianYinYuan()
    }

    /**
     * 设置音源配置
     * @param yinYuan auto=自动切换, yunzhi=云智API, apicx=残影API
     */
    @JavascriptInterface
    fun sheZhiYinYuanPeiZhi(yinYuan: String) {
        val validValues = listOf(YinYuanPeiZhi.YIN_YUAN_ZI_DONG, YinYuanPeiZhi.YIN_YUAN_YUNZHI, YinYuanPeiZhi.YIN_YUAN_APICX, YinYuanPeiZhi.YIN_YUAN_QQMUSIC)
        if (yinYuan in validValues) {
            yinYuanPeiZhi.sheZhiYinYuan(yinYuan)
            RiZhiGuanLiQi.logInfo("WebAppInterface", "切换音源: $yinYuan")
        } else {
            RiZhiGuanLiQi.logWarn("WebAppInterface", "无效音源配置: $yinYuan")
        }
    }

    /** 通知H5音频焦点变化（来电、其他应用抢焦点等） */
    fun tongZhiH5YinPinJiaoDianBianHua(huoDeJiaoDian: Boolean) {
        try {
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().post {
                if (context.isFinishing || context.isDestroyed) return@post
                context.getWebView().evaluateJavascript(
                    "javascript:if(typeof onYinPinJiaoDianBianHua==='function') onYinPinJiaoDianBianHua($huoDeJiaoDian);",
                    null
                )
            }
            android.util.Log.d("ShiYinSync", "[音频焦点] Native→H5: ${if (huoDeJiaoDian) "获得焦点" else "失去焦点"}")
        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "通知H5音频焦点变化失败: ${e.message}")
        }
    }

    @JavascriptInterface
    fun huoQuYinPinHuanCunLieBiao(): String {
        return try {
            val cacheDir = java.io.File(context.cacheDir, "ge_qu_huan_cun/songs")
            if (!cacheDir.exists()) return "[]"
            
            val playlistJson = playlistRepository.loadPlaylist()
            val playlistSongs = mutableMapOf<String, Pair<String, String>>()
            
            if (!playlistJson.isNullOrBlank()) {
                try {
                    val jsonObject = org.json.JSONObject(playlistJson)
                    val songsArray = jsonObject.getJSONArray("songs")
                    for (i in 0 until songsArray.length()) {
                        val song = songsArray.getJSONObject(i)
                        val id = song.optString("id")
                        val name = song.optString("name", "")
                        val artist = song.optString("artist", "")
                        if (id.isNotEmpty()) {
                            playlistSongs[id] = Pair(name, artist)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WebAppInterface", "解析播放列表失败: ${e.message}")
                }
            }
            
            val songs = mutableListOf<Map<String, Any>>()
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.extension == "mp3" || file.extension == "cache")) {
                    val songId = file.nameWithoutExtension
                    val size = file.length()
                    val (name, artist) = playlistSongs[songId] ?: Pair("歌曲 $songId", "")
                    songs.add(mapOf(
                        "id" to songId,
                        "name" to name,
                        "artist" to artist,
                        "size" to size
                    ))
                }
            }
            
            val jsonArray = org.json.JSONArray()
            songs.forEach { song ->
                val jsonObject = org.json.JSONObject()
                song.forEach { (key, value) ->
                    jsonObject.put(key, value)
                }
                jsonArray.put(jsonObject)
            }
            jsonArray.toString()
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "获取音频缓存列表失败: ${e.message}")
            "[]"
        }
    }

    @JavascriptInterface
    fun shanChuYinPinHuanCun(songId: String) {
        try {
            geQuZiYuanHuanCun.shanChuGeQuHuanCun(songId)
            android.util.Log.d("WebAppInterface", "删除音频缓存: $songId")
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "删除音频缓存失败: ${e.message}")
        }
    }

    @JavascriptInterface
    fun qingKongYinPinHuanCun() {
        try {
            geQuZiYuanHuanCun.qingKongHuanCun()
            android.util.Log.d("WebAppInterface", "清空所有音频缓存")
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "清空音频缓存失败: ${e.message}")
        }
    }

    /**
     * H5播放时触发音频缓存下载（异步，不影响当前播放）
     * @param songId 歌曲ID
     * @param songName 歌曲名称
     */
    @JavascriptInterface
    fun xiaZaiYinPinHuanCun(songId: String, songName: String) {
        try {
            android.util.Log.d("WebAppInterface", "H5请求缓存音频: $songId($songName)")
            // 查找歌曲来源，确保缓存使用正确的API
            var geQuLaiYuan = ""
            try {
                val playlistJson = playlistRepository.loadPlaylist()
                if (playlistJson != null) {
                    val json = org.json.JSONObject(playlistJson)
                    val songs = json.optJSONArray("songs")
                    if (songs != null) {
                        for (i in 0 until songs.length()) {
                            val song = songs.getJSONObject(i)
                            if (song.optString("id") == songId) {
                                geQuLaiYuan = song.optString("source", "")
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 查找来源失败不影响缓存下载
            }
            geQuZiYuanHuanCun.huoQuUrlBingHuanCun(songId, songName, geQuLaiYuan) { chengGong, luJing ->
                if (chengGong && luJing != null) {
                    android.util.Log.d("WebAppInterface", "音频缓存成功: $songId, 路径: $luJing")
                } else {
                    android.util.Log.w("WebAppInterface", "音频缓存失败: $songId")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "触发音频缓存失败: ${e.message}")
        }
    }

    // ==================== 悬浮窗歌词接口 ====================

    /** 发送当前歌曲歌词数据给原生，用于悬浮窗后台同步 */
    @JavascriptInterface
    fun sendLyricData(json: String) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                Log.d("WebAppInterface", "收到歌词数据，长度: ${json.length}")
                context.xuanFuGeCiManager.sheZhiGeCiLieBiao(json)
            }
        }
    }

    /** 清空悬浮窗歌词（切歌时调用），显示"加载歌词中..." */
    @JavascriptInterface
    fun qingLiGeCi() {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                Log.d("WebAppInterface", "清空悬浮窗歌词")
                context.xuanFuGeCiManager.qingLiGeCi()
            }
        }
    }

    /** 开启悬浮窗歌词 */
    @JavascriptInterface
    fun kaiQiXuanFuGeCi() {
        handler.post {
            if (!context.xuanFuGeCiYiChuShiHua()) return@post
            if (!XuanFuGeCiManager.youXuanFuChuangQuanXian(context)) {
                android.util.Log.w("WebAppInterface", "没有悬浮窗权限，请先申请")
                return@post
            }
            context.xuanFuGeCiManager.kaiQi()
            // 开启后主动请求H5推送当前歌词（如果有缓存则立即显示，如果没有则加载）
            try {
                context.getWebView().evaluateJavascript(
                    "try{if(typeof sendLyricData==='function'&&typeof lyrics!=='undefined'&&Array.isArray(lyrics)&&lyrics.length>0){sendLyricData(lyrics)}else{console.log('[AndroidBridge] 悬浮窗已开启，但当前无歌词数据')}}catch(e){console.error('[AndroidBridge] 请求歌词失败',e)}", null
                )
            } catch (e: Exception) {
                android.util.Log.e("WebAppInterface", "请求H5推送歌词失败: ${e.message}")
            }
        }
    }

    /** 关闭悬浮窗歌词 */
    @JavascriptInterface
    fun guanBiXuanFuGeCi() {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.guanBi()
            }
        }
    }

    /** 查询悬浮窗歌词是否开启 */
    @JavascriptInterface
    fun xuanFuGeCiShiFouKaiQi(): Boolean {
        return if (context.xuanFuGeCiYiChuShiHua()) {
            context.xuanFuGeCiManager.shiFouKaiQi()
        } else false
    }

    /** 查询是否有悬浮窗权限 */
    @JavascriptInterface
    fun youXuanFuChuangQuanXian(): Boolean {
        return XuanFuGeCiManager.youXuanFuChuangQuanXian(context)
    }

    /** 请求悬浮窗权限（跳转到系统设置） */
    @JavascriptInterface
    fun qingQiuXuanFuQuanXian() {
        handler.post {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            context.overlayPermissionLauncher.launch(intent)
        }
    }

    /** 设置悬浮窗歌词颜色 */
    @JavascriptInterface
    fun sheZhiXuanFuYanSe(yanSe: String) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.sheZhiYanSe(yanSe)
            }
        }
    }

    /** 设置悬浮窗歌词字号 */
    @JavascriptInterface
    fun sheZhiXuanFuZiHao(ziHao: Float) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.sheZhiZiHao(ziHao)
            }
        }
    }

    /** 设置悬浮窗歌词透明度(0-100) */
    @JavascriptInterface
    fun sheZhiXuanFuTouMingDu(touMingDu: Int) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.sheZhiTouMingDu(touMingDu)
            }
        }
    }

    /** 设置悬浮窗歌词左右位置(0-100) */
    @JavascriptInterface
    fun sheZhiXuanFuZuoYou(zuoYou: Int) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.sheZhiZuoYou(zuoYou)
            }
        }
    }

    /** 设置悬浮窗歌词上下位置(像素) */
    @JavascriptInterface
    fun sheZhiXuanFuShangXia(shangXia: Int) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.sheZhiWeiZhi(shangXia)
            }
        }
    }

    /** 设置悬浮窗歌词宽度百分比(10-100) */
    @JavascriptInterface
    fun sheZhiXuanFuKuanDu(kuanDu: Int) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.sheZhiKuanDu(kuanDu)
            }
        }
    }

    /** 设置悬浮窗歌词对齐方式: "left" 或 "center" */
    @JavascriptInterface
    fun sheZhiXuanFuDuiQi(duiQi: String) {
        handler.post {
            if (context.xuanFuGeCiYiChuShiHua()) {
                context.xuanFuGeCiManager.sheZhiDuiQi(duiQi)
            }
        }
    }

    /** 获取悬浮窗歌词全部配置（JSON格式） */
    @JavascriptInterface
    fun huoQuXuanFuPeiZhi(): String {
        val prefs = context.getSharedPreferences("xuafugeci_prefs", android.content.Context.MODE_PRIVATE)
        val yanSe = prefs.getString("xuanfu_yanse", "#1DB954") ?: "#1DB954"
        val ziHao = prefs.getFloat("xuanfu_zihao", 16f)
        val touMingDu = prefs.getInt("xuanfu_toumingdu", 90)
        val weiZhiY = prefs.getInt("xuanfu_weizhi_y", 300)
        val weiZhiX = prefs.getInt("xuanfu_weizhi_x", 50)
        val kuanDu = prefs.getInt("xuanfu_kuandu", 80)
        val duiQi = prefs.getString("xuanfu_duiqi", "center") ?: "center"
        return """{"yanSe":"$yanSe","ziHao":$ziHao,"touMingDu":$touMingDu,"weiZhiY":$weiZhiY,"weiZhiX":$weiZhiX,"kuanDu":$kuanDu,"duiQi":"$duiQi"}"""
    }
}
