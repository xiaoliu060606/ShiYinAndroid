package com.shiyin.music

import android.content.Context
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import java.lang.ref.WeakReference

/**
 * 原生音频播放器（MediaPlayer 封装）
 *
 * 线程模型（修复A1）：MediaPlayer 非线程安全。JS 桥(@JavascriptInterface，运行于 WebView 线程)、
 * 音频焦点/锁屏控制(主线程)、切歌(主线程)会并发操作同一实例，release 与 start/pause 竞争
 * 可触发 native SIGSEGV（Java 层 catch IllegalStateException 兜不住）。
 * 因此内部维护专用串行线程 "player-thread"，所有 MediaPlayer 的创建/释放/操作均 post 到该线程执行；
 * 对外回调统一 post 回主线程，保持上层现有线程语义不变。公开方法签名不变，调用方零改动。
 */
class NativeAudioPlayer(
    context: Context,
    private val mediaSession: MediaSessionCompat?
) {
    private val contextRef = WeakReference(context)
    private var mediaPlayer: MediaPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentUrl: String = ""

    // 以下状态字段可能被 getter 在其他线程读取，标 @Volatile 保证跨线程可见性
    @Volatile private var isPrepared = false
    @Volatile private var _currentPosition: Long = 0
    @Volatile private var _duration: Long = 0
    @Volatile private var _expectedDuration: Long = 0
    @Volatile private var _isPlaying: Boolean = false

    // 专用播放线程：MediaPlayer 的所有创建/释放/操作串行化到此线程，杜绝跨线程并发
    private val playerThread = HandlerThread("player-thread").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    // 主线程 Handler：仅用于把对外回调 post 回主线程（不触碰 MediaPlayer）
    private val handler = Handler(Looper.getMainLooper())

    // 以下字段仅在 player-thread 访问，无需 volatile
    private var progressRunnable: Runnable? = null
    private var prepareTimeoutRunnable: Runnable? = null
    private var loadRetryCount = 0
    private val MAX_LOAD_RETRY = 2
    private var hasTriggeredThreshold = false
    private var isRetrying = false
    // 用于未知时长流媒体的预加载计时（类级别，暂停/恢复时保留）
    private var elapsedPlayTime = 0L
    private var lastKnownPosition = 0L

    interface AudioPlayerCallback {
        fun onPrepared(duration: Long)
        fun onProgressChanged(position: Long, duration: Long)
        fun onCompletion()
        fun onError(error: String)
        // reason: "paused"=主动暂停(用户/蓝牙/锁屏/音频焦点丢失), ""=未知/自然停止(用于H5切歌兜底判断)
        fun onPlayStateChanged(isPlaying: Boolean, reason: String = "")
        fun onProgressThreshold(position: Long, duration: Long)
    }

    @Volatile
    var callback: AudioPlayerCallback? = null

    /**
     * 预先设置歌曲时长（毫秒）
     * 用于解决网络流媒体 MediaPlayer.duration() 返回0的问题
     */
    fun setExpectedDuration(durationMs: Long) {
        playerHandler.post { setExpectedDurationInternal(durationMs) }
    }

    private fun setExpectedDurationInternal(durationMs: Long) {
        _expectedDuration = durationMs
        android.util.Log.d("NativeAudioPlayer", "[时长] 预设时长: ${durationMs}ms")
    }

    /**
     * 加载并播放音频（实际操作 post 到 player-thread 串行执行）
     */
    fun loadAndPlay(url: String, startPosition: Long = 0) {
        playerHandler.post { loadAndPlayInternal(url, startPosition) }
    }

    private fun loadAndPlayInternal(url: String, startPosition: Long) {
        try {
            android.util.Log.d("NativeAudioPlayer", "[加载] url=${url.take(80)}..., pos=$startPosition")
            if (isRetrying) {
                isRetrying = false
            }
            releaseInternal()

            isPrepared = false
            _isPlaying = false
            _currentPosition = startPosition
            _duration = 0
            _expectedDuration = 0
            currentUrl = url
            hasTriggeredThreshold = false
            elapsedPlayTime = 0
            lastKnownPosition = 0

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)

                // 获取WiFi锁，防止息屏后WiFi断开导致缓冲中断
                acquireWifiLock()

                setOnPreparedListener {
                    android.util.Log.d("NativeAudioPlayer", "[准备完成] 音频已就绪, duration=${it.duration}ms")
                    cancelPrepareTimeout()
                    loadRetryCount = 0
                    isPrepared = true
                    val mediaPlayerDuration = it.duration.toLong()
                    _duration = if (mediaPlayerDuration > 0) mediaPlayerDuration else _expectedDuration
                    if (_expectedDuration > 0 && mediaPlayerDuration == 0L) {
                        android.util.Log.d("NativeAudioPlayer", "[时长] 使用预设时长: ${_duration}ms (MediaPlayer返回0)")
                    }

                    if (startPosition > 0) {
                        val safePosition = startPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                        it.seekTo(safePosition)
                        _currentPosition = startPosition
                    }

                    it.start()
                    _isPlaying = true

                    startProgressUpdate()

                    handler.post {
                        callback?.onPrepared(_duration)
                        callback?.onPlayStateChanged(true)
                    }
                }

                setOnCompletionListener {
                    android.util.Log.d("NativeAudioPlayer", "[播放完成] 歌曲播放完毕, position=${_currentPosition}ms, duration=${_duration}ms")
                    _isPlaying = false
                    stopProgressUpdate()
                    handler.post {
                        callback?.onPlayStateChanged(false)
                        callback?.onCompletion()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("NativeAudioPlayer", "[播放器错误] what=$what, extra=$extra, url=${currentUrl.take(60)}")
                    cancelPrepareTimeout()
                    _isPlaying = false
                    isPrepared = false
                    stopProgressUpdate()

                    if (loadRetryCount < MAX_LOAD_RETRY && !isRetrying) {
                        loadRetryCount++
                        isRetrying = true
                        android.util.Log.w("ShiYinSync", "[播放器] 播放错误(重试 $loadRetryCount/$MAX_LOAD_RETRY): what=$what, extra=$extra")
                        playerHandler.postDelayed({
                            if (currentUrl.isNotEmpty() && isRetrying) {
                                try {
                                    val savedUrl = currentUrl
                                    val savedPosition = _currentPosition
                                    releaseInternal()
                                    loadAndPlayInternal(savedUrl, savedPosition)
                                } catch (e: Exception) {
                                    isRetrying = false
                                    notifyError("重试加载失败: ${e.message}")
                                }
                            } else {
                                isRetrying = false
                            }
                        }, 1000)
                        return@setOnErrorListener true
                    }

                    isRetrying = false
                    loadRetryCount = 0
                    android.util.Log.e("NativeAudioPlayer", "[播放器错误] 重试耗尽(${MAX_LOAD_RETRY}次): what=$what, extra=$extra, url=${currentUrl.take(60)}")
                    val cuoWuXinXi = "播放错误: what=$what, extra=$extra, URL=${currentUrl.take(50)}..."
                    RiZhiGuanLiQi.logError("NativeAudioPlayer", cuoWuXinXi)
                    notifyError(cuoWuXinXi)
                    true
                }

                setOnBufferingUpdateListener { _, percent ->
                    // 缓冲更新，可用于日志
                    if (percent < 100) {
                        android.util.Log.d("NativeAudioPlayer", "[缓冲] $percent%")
                    }
                }

                schedulePrepareTimeout()
                prepareAsync()
            }
        } catch (e: Exception) {
            _isPlaying = false
            isPrepared = false
            cancelPrepareTimeout()
            stopProgressUpdate()
            mediaPlayer?.release()
            mediaPlayer = null
            val cuoWuXinXi = "加载失败: ${e.message}, URL=${url.take(50)}..."
            RiZhiGuanLiQi.logError("NativeAudioPlayer", cuoWuXinXi)
            notifyError(cuoWuXinXi)
        }
    }

    /**
     * 播放（实际操作 post 到 player-thread 串行执行）
     */
    fun play() {
        playerHandler.post { playInternal() }
    }

    private fun playInternal() {
        mediaPlayer?.let {
            try {
                if (isPrepared && !it.isPlaying) {
                    it.start()
                    _isPlaying = true
                    startProgressUpdate()
                    notifyPlayStateChanged(true)
                } else if (!isPrepared && currentUrl.isNotEmpty()) {
                    loadAndPlayInternal(currentUrl, _currentPosition)
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("NativeAudioPlayer", "play IllegalStateException: ${e.message}")
                if (currentUrl.isNotEmpty()) {
                    loadAndPlayInternal(currentUrl, _currentPosition)
                }
            }
            Unit
        }
    }

    /**
     * 暂停（实际操作 post 到 player-thread 串行执行）
     */
    fun pause() {
        playerHandler.post { pauseInternal() }
    }

    private fun pauseInternal() {
        android.util.Log.d("NativeAudioPlayer", "pause() 被调用, mediaPlayer=${mediaPlayer != null}, isPrepared=$isPrepared")
        mediaPlayer?.let {
            try {
                if (isPrepared && it.isPlaying) {
                    android.util.Log.d("NativeAudioPlayer", "pause: 真正暂停 MediaPlayer")
                    it.pause()
                    _isPlaying = false
                    stopProgressUpdate()
                    notifyPlayStateChanged(false, "paused")
                } else if (!isPrepared && _isPlaying) {
                    _isPlaying = false
                    stopProgressUpdate()
                    notifyPlayStateChanged(false, "paused")
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("NativeAudioPlayer", "pause IllegalStateException: ${e.message}")
                _isPlaying = false
                notifyPlayStateChanged(false, "paused")
            } catch (e: Exception) {
                android.util.Log.e("NativeAudioPlayer", "pause error: ${e.message}")
            }
            Unit
        } ?: run {
            android.util.Log.d("NativeAudioPlayer", "pause: mediaPlayer为null")
            if (_isPlaying) {
                _isPlaying = false
                notifyPlayStateChanged(false, "paused")
            }
        }
    }

    /**
     * 恢复播放（实际操作 post 到 player-thread 串行执行）
     */
    fun resume() {
        playerHandler.post { resumeInternal() }
    }

    private fun resumeInternal() {
        android.util.Log.d("NativeAudioPlayer", "resume() 被调用, mediaPlayer=${mediaPlayer != null}, isPrepared=$isPrepared")
        mediaPlayer?.let {
            try {
                if (isPrepared && !it.isPlaying) {
                    it.start()
                    _isPlaying = true
                    startProgressUpdate()
                    notifyPlayStateChanged(true)
                } else if (!isPrepared) {
                    android.util.Log.d("NativeAudioPlayer", "resume: 还在准备中，等待回调")
                } else {
                    android.util.Log.d("NativeAudioPlayer", "resume: 已经在播放中，不重复触发")
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("NativeAudioPlayer", "resume IllegalStateException: ${e.message}")
                _isPlaying = false
                notifyPlayStateChanged(false)
            } catch (e: Exception) {
                android.util.Log.e("NativeAudioPlayer", "resume error: ${e.message}")
                _isPlaying = false
                notifyPlayStateChanged(false)
            }
            Unit
        } ?: run {
            android.util.Log.d("NativeAudioPlayer", "resume: mediaPlayer为null")
            if (_isPlaying) {
                _isPlaying = false
                notifyPlayStateChanged(false)
            }
        }
    }

    /**
     * 跳转到指定位置（实际操作 post 到 player-thread 串行执行）
     */
    fun seekTo(position: Long) {
        playerHandler.post { seekToInternal(position) }
    }

    private fun seekToInternal(position: Long) {
        try {
            android.util.Log.d("NativeAudioPlayer", "[跳转] seekTo position=${position}ms, isPrepared=$isPrepared, duration=$_duration")
            mediaPlayer?.let {
                if (isPrepared) {
                    val safePosition = position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    it.seekTo(safePosition)
                    _currentPosition = position
                    notifyProgressChanged(position, _duration)
                } else {
                    android.util.Log.w("NativeAudioPlayer", "[跳转] 未就绪，忽略seekTo")
                }
            } ?: run {
                android.util.Log.w("NativeAudioPlayer", "[跳转] mediaPlayer为null，忽略seekTo")
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("NativeAudioPlayer", "seekTo IllegalStateException: ${e.message}")
        }
    }

    /**
     * 设置音量（用于音频焦点 duck 降音处理）
     * @param volume 0.0f（静音）~ 1.0f（最大）
     */
    fun setVolume(volume: Float) {
        playerHandler.post { setVolumeInternal(volume) }
    }

    private fun setVolumeInternal(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        mediaPlayer?.let {
            try {
                if (isPrepared) {
                    it.setVolume(clamped, clamped)
                    android.util.Log.d("NativeAudioPlayer", "[音量] 设置为 $clamped")
                } else {
                    android.util.Log.d("NativeAudioPlayer", "[音量] 播放器未就绪，忽略音量设置")
                }
            } catch (e: Exception) {
                android.util.Log.e("NativeAudioPlayer", "[音量] 设置失败: ${e.message}")
            }
        }
    }

    /**
     * 获取当前位置（仅读缓存字段，不直接访问 MediaPlayer，保证线程安全）
     */
    fun getCurrentPosition(): Long {
        return _currentPosition
    }

    /**
     * 获取总时长（仅读缓存字段，不直接访问 MediaPlayer，保证线程安全）
     */
    fun getDuration(): Long {
        return if (_duration > 0) _duration else _expectedDuration
    }

    /**
     * 是否正在播放（仅读缓存字段，保证线程安全）
     */
    fun isPlayingState(): Boolean {
        return _isPlaying
    }

    /**
     * 释放资源（释放 MediaPlayer 并退出专用播放线程）
     */
    fun release() {
        playerHandler.post {
            releaseInternal()
            playerThread.quitSafely()
        }
    }

    private fun releaseInternal() {
        android.util.Log.d("NativeAudioPlayer", "[释放] release(), wasPlaying=$_isPlaying, isPrepared=$isPrepared")
        cancelPrepareTimeout()
        stopProgressUpdate()
        releaseWifiLock()
        val wasPlaying = _isPlaying
        mediaPlayer?.release()
        mediaPlayer = null
        isPrepared = false
        _isPlaying = false
        currentUrl = ""
        if (wasPlaying) {
            notifyPlayStateChanged(false)
        }
    }

    /** 安排准备超时检测（15秒） */
    private fun schedulePrepareTimeout() {
        cancelPrepareTimeout()
        prepareTimeoutRunnable = Runnable {
            if (!isPrepared) {
                android.util.Log.w("ShiYinSync", "[播放器] 准备超时(15秒)，当前URL: $currentUrl")
                _isPlaying = false
                isPrepared = false
                stopProgressUpdate()

                if (loadRetryCount < MAX_LOAD_RETRY && !isRetrying) {
                    loadRetryCount++
                    isRetrying = true
                    android.util.Log.w("NativeAudioPlayer", "准备超时，重试 $loadRetryCount/$MAX_LOAD_RETRY")
                    val savedUrl = currentUrl
                    val savedPosition = _currentPosition
                    if (savedUrl.isNotEmpty()) {
                        try {
                            releaseInternal()
                            loadAndPlayInternal(savedUrl, savedPosition)
                        } catch (e: Exception) {
                            isRetrying = false
                            notifyError("超时重试失败: ${e.message}")
                        }
                    } else {
                        isRetrying = false
                    }
                } else {
                    isRetrying = false
                    loadRetryCount = 0
                    notifyError("准备超时")
                }
            }
        }
        playerHandler.postDelayed(prepareTimeoutRunnable!!, 15000)
    }

    /** 取消准备超时检测 */
    private fun cancelPrepareTimeout() {
        prepareTimeoutRunnable?.let {
            playerHandler.removeCallbacks(it)
        }
        prepareTimeoutRunnable = null
    }

    /**
     * 开始进度更新
     */
    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressRunnable = object : Runnable {

            override fun run() {
                try {
                    mediaPlayer?.let {
                        if (isPrepared && it.isPlaying) {
                            _currentPosition = it.currentPosition.toLong()
                            val actualDuration = if (_duration > 0) _duration else {
                                try { it.duration.toLong() } catch (e: Exception) { 0L }
                            }
                            if (actualDuration > 0 && actualDuration != _duration) {
                                _duration = actualDuration
                            }
                            notifyProgressChanged(_currentPosition, _duration)

                            // 预加载阈值检查：支持未知时长场景
                            var yingChuFa = false
                            if (!hasTriggeredThreshold) {
                                if (_duration > 0 && _currentPosition >= _duration * 0.7) {
                                    // 情况1：已知时长，达到70%阈值
                                    yingChuFa = true
                                } else if (_duration <= 0 && _currentPosition > 0) {
                                    // 情况2：未知时长但播放位置持续增加（网络流媒体）
                                    // 连续播放超过120秒且位置还在增长时触发预加载
                                    elapsedPlayTime += 1000
                                    if (elapsedPlayTime >= 120000 && _currentPosition > lastKnownPosition) {
                                        yingChuFa = true
                                        // 用当前位置+估算剩余来构造"伪时长"
                                        _duration = _currentPosition + 30000
                                    }
                                }
                                if (yingChuFa) {
                                    hasTriggeredThreshold = true
                                    val baoGaoShiChang = if (_duration > 0) _duration else _currentPosition + 30000
                                    android.util.Log.d("ShiYin", "[预加载] NativeAudioPlayer 触发阈值: pos=$_currentPosition, dur=$baoGaoShiChang")
                                    notifyProgressThreshold(_currentPosition, baoGaoShiChang)
                                }
                            }
                            lastKnownPosition = _currentPosition
                            playerHandler.postDelayed(this, 1000)
                        }
                    }
                } catch (e: IllegalStateException) {
                    android.util.Log.w("NativeAudioPlayer", "进度更新时 MediaPlayer 状态异常: ${e.message}")
                }
            }
        }
        playerHandler.post(progressRunnable!!)
    }

    /**
     * 停止进度更新
     */
    private fun stopProgressUpdate() {
        progressRunnable?.let {
            playerHandler.removeCallbacks(it)
        }
        progressRunnable = null
    }

    // ==================== 对外回调：统一 post 回主线程，保持上层线程语义不变 ====================

    private fun notifyPlayStateChanged(isPlaying: Boolean, reason: String = "") {
        handler.post { callback?.onPlayStateChanged(isPlaying, reason) }
    }

    private fun notifyProgressChanged(position: Long, duration: Long) {
        handler.post { callback?.onProgressChanged(position, duration) }
    }

    private fun notifyProgressThreshold(position: Long, duration: Long) {
        handler.post { callback?.onProgressThreshold(position, duration) }
    }

    private fun notifyError(error: String) {
        handler.post { callback?.onError(error) }
    }

    /** 获取WiFi锁，防止息屏后WiFi断开导致流媒体缓冲中断 */
    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wifiManager = contextRef.get()?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ShiYinMusic::PlaybackWifiLock")
            }
            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    android.util.Log.d("NativeAudioPlayer", "[WiFi锁] 已获取")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("NativeAudioPlayer", "[WiFi锁] 获取失败: ${e.message}")
        }
    }

    /** 释放WiFi锁 */
    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    android.util.Log.d("NativeAudioPlayer", "[WiFi锁] 已释放")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("NativeAudioPlayer", "[WiFi锁] 释放失败: ${e.message}")
        }
    }
}
