package com.shiyin.music

import android.content.Context
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import java.lang.ref.WeakReference

class NativeAudioPlayer(
    context: Context,
    private val mediaSession: MediaSessionCompat?
) {
    private val contextRef = WeakReference(context)
    private var mediaPlayer: MediaPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentUrl: String = ""
    private var isPrepared = false
    private var _currentPosition: Long = 0
    private var _duration: Long = 0
    private var _expectedDuration: Long = 0
    private var _isPlaying: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var prepareTimeoutRunnable: Runnable? = null
    private var loadRetryCount = 0
    private val MAX_LOAD_RETRY = 2
    private var hasTriggeredThreshold = false
    private var isRetrying = false
    
    interface AudioPlayerCallback {
        fun onPrepared(duration: Long)
        fun onProgressChanged(position: Long, duration: Long)
        fun onCompletion()
        fun onError(error: String)
        fun onPlayStateChanged(isPlaying: Boolean)
        fun onProgressThreshold(position: Long, duration: Long)
    }
    
    var callback: AudioPlayerCallback? = null

    /**
     * 预先设置歌曲时长（毫秒）
     * 用于解决网络流媒体 MediaPlayer.duration() 返回0的问题
     */
    fun setExpectedDuration(durationMs: Long) {
        _expectedDuration = durationMs
        android.util.Log.d("NativeAudioPlayer", "[时长] 预设时长: ${durationMs}ms")
    }

    /**
     * 加载并播放音频
     */
    fun loadAndPlay(url: String, startPosition: Long = 0) {
        try {
            android.util.Log.d("NativeAudioPlayer", "[加载] url=${url.take(80)}..., pos=$startPosition")
            if (isRetrying) {
                isRetrying = false
            }
            release()

            isPrepared = false
            _isPlaying = false
            _currentPosition = startPosition
            _duration = 0
            _expectedDuration = 0
            currentUrl = url
            hasTriggeredThreshold = false

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
                    
                    callback?.onPrepared(_duration)
                    callback?.onPlayStateChanged(true)
                }
                
                setOnCompletionListener {
                    android.util.Log.d("NativeAudioPlayer", "[播放完成] 歌曲播放完毕, position=${_currentPosition}ms, duration=${_duration}ms")
                    _isPlaying = false
                    stopProgressUpdate()
                    callback?.onPlayStateChanged(false)
                    callback?.onCompletion()
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
                        handler.postDelayed({
                            if (currentUrl.isNotEmpty() && isRetrying) {
                                try {
                                    val savedUrl = currentUrl
                                    val savedPosition = _currentPosition
                                    release()
                                    loadAndPlay(savedUrl, savedPosition)
                                } catch (e: Exception) {
                                    isRetrying = false
                                    callback?.onError("重试加载失败: ${e.message}")
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
                    callback?.onError(cuoWuXinXi)
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
            callback?.onError(cuoWuXinXi)
        }
    }
    
    /**
     * 播放
     */
    fun play() {
        mediaPlayer?.let {
            try {
                if (isPrepared && !it.isPlaying) {
                    it.start()
                    _isPlaying = true
                    startProgressUpdate()
                    callback?.onPlayStateChanged(true)
                } else if (!isPrepared && currentUrl.isNotEmpty()) {
                    loadAndPlay(currentUrl, _currentPosition)
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("NativeAudioPlayer", "play IllegalStateException: ${e.message}")
                if (currentUrl.isNotEmpty()) {
                    loadAndPlay(currentUrl, _currentPosition)
                }
            }
            Unit
        }
    }
    
    /**
     * 暂停
     */
    fun pause() {
        android.util.Log.d("NativeAudioPlayer", "pause() 被调用, mediaPlayer=${mediaPlayer != null}, isPrepared=$isPrepared")
        mediaPlayer?.let {
            try {
                if (isPrepared && it.isPlaying) {
                    android.util.Log.d("NativeAudioPlayer", "pause: 真正暂停 MediaPlayer")
                    it.pause()
                    _isPlaying = false
                    stopProgressUpdate()
                    callback?.onPlayStateChanged(false)
                } else if (!isPrepared && _isPlaying) {
                    _isPlaying = false
                    stopProgressUpdate()
                    callback?.onPlayStateChanged(false)
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("NativeAudioPlayer", "pause IllegalStateException: ${e.message}")
                _isPlaying = false
                callback?.onPlayStateChanged(false)
            } catch (e: Exception) {
                android.util.Log.e("NativeAudioPlayer", "pause error: ${e.message}")
            }
            Unit
        } ?: run {
            android.util.Log.d("NativeAudioPlayer", "pause: mediaPlayer为null")
            if (_isPlaying) {
                _isPlaying = false
                callback?.onPlayStateChanged(false)
            }
        }
    }

    /**
     * 恢复播放
     */
    fun resume() {
        android.util.Log.d("NativeAudioPlayer", "resume() 被调用, mediaPlayer=${mediaPlayer != null}, isPrepared=$isPrepared")
        mediaPlayer?.let {
            try {
                if (isPrepared && !it.isPlaying) {
                    it.start()
                    _isPlaying = true
                    startProgressUpdate()
                    callback?.onPlayStateChanged(true)
                } else if (!isPrepared) {
                    android.util.Log.d("NativeAudioPlayer", "resume: 还在准备中，等待回调")
                } else {
                    android.util.Log.d("NativeAudioPlayer", "resume: 已经在播放中，不重复触发")
                }
            } catch (e: IllegalStateException) {
                android.util.Log.e("NativeAudioPlayer", "resume IllegalStateException: ${e.message}")
                _isPlaying = false
                callback?.onPlayStateChanged(false)
            } catch (e: Exception) {
                android.util.Log.e("NativeAudioPlayer", "resume error: ${e.message}")
                _isPlaying = false
                callback?.onPlayStateChanged(false)
            }
            Unit
        } ?: run {
            android.util.Log.d("NativeAudioPlayer", "resume: mediaPlayer为null")
            if (_isPlaying) {
                _isPlaying = false
                callback?.onPlayStateChanged(false)
            }
        }
    }
    
    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Long) {
        try {
            android.util.Log.d("NativeAudioPlayer", "[跳转] seekTo position=${position}ms, isPrepared=$isPrepared, duration=$_duration")
            mediaPlayer?.let {
                if (isPrepared) {
                    val safePosition = position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    it.seekTo(safePosition)
                    _currentPosition = position
                    callback?.onProgressChanged(position, _duration)
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
     * 获取当前位置
     */
    fun getCurrentPosition(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: _currentPosition
        } catch (e: Exception) {
            _currentPosition
        }
    }
    
    /**
     * 获取总时长
     */
    fun getDuration(): Long {
        return try {
            if (_duration > 0) _duration else (mediaPlayer?.duration?.toLong() ?: 0)
        } catch (e: Exception) {
            _duration
        }
    }
    
    /**
     * 是否正在播放
     */
    fun isPlayingState(): Boolean {
        return _isPlaying
    }
    
    /**
     * 释放资源
     */
    fun release() {
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
            callback?.onPlayStateChanged(false)
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
                            release()
                            loadAndPlay(savedUrl, savedPosition)
                        } catch (e: Exception) {
                            isRetrying = false
                            callback?.onError("超时重试失败: ${e.message}")
                        }
                    } else {
                        isRetrying = false
                    }
                } else {
                    isRetrying = false
                    loadRetryCount = 0
                    callback?.onError("准备超时")
                }
            }
        }
        handler.postDelayed(prepareTimeoutRunnable!!, 15000)
    }
    
    /** 取消准备超时检测 */
    private fun cancelPrepareTimeout() {
        prepareTimeoutRunnable?.let {
            handler.removeCallbacks(it)
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
                            callback?.onProgressChanged(_currentPosition, _duration)
                            if (!hasTriggeredThreshold && _duration > 0 && _currentPosition >= _duration * 0.7) {
                                hasTriggeredThreshold = true
                                android.util.Log.d("ShiYin", "[预加载] NativeAudioPlayer 触发 70% 阈值: $_currentPosition/$_duration")
                                callback?.onProgressThreshold(_currentPosition, _duration)
                            }
                            handler.postDelayed(this, 1000)
                        }
                    }
                } catch (e: IllegalStateException) {
                    android.util.Log.w("NativeAudioPlayer", "进度更新时 MediaPlayer 状态异常: ${e.message}")
                }
            }
        }
        handler.post(progressRunnable!!)
    }
    
    /**
     * 停止进度更新
     */
    private fun stopProgressUpdate() {
        progressRunnable?.let {
            handler.removeCallbacks(it)
        }
        progressRunnable = null
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
