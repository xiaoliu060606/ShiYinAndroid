package com.shiyin.music

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.media.session.MediaSessionCompat
import androidx.media.session.PlaybackStateCompat

/**
 * 原生音频播放器
 * 使用 Android MediaPlayer 实现，提供更好的系统进度条同步
 */
class NativeAudioPlayer(
    private val context: Context,
    private val mediaSession: MediaSessionCompat?
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentUrl: String = ""
    private var isPrepared = false
    private var _currentPosition: Long = 0
    private var _duration: Long = 0
    private var _isPlaying = false

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    
    // 回调接口
    interface AudioPlayerCallback {
        fun onPrepared(duration: Long)
        fun onProgressChanged(position: Long, duration: Long)
        fun onCompletion()
        fun onError(error: String)
        fun onPlayStateChanged(isPlaying: Boolean)
    }
    
    var callback: AudioPlayerCallback? = null
    
    /**
     * 加载并播放音频
     */
    fun loadAndPlay(url: String, startPosition: Long = 0) {
        try {
            // 释放之前的播放器
            release()
            
            // 重置状态
            isPrepared = false
            _isPlaying = false
            _currentPosition = startPosition
            currentUrl = url
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener {
                    isPrepared = true
                    _duration = it.duration.toLong()
                    
                    // 设置起始位置（安全转换，防止溢出）
                    if (startPosition > 0) {
                        val safePosition = startPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                        it.seekTo(safePosition)
                        _currentPosition = startPosition
                    }
                    
                    // 开始播放
                    it.start()
                    _isPlaying = true
                    
                    // 更新 MediaSession
                    updateMediaSessionState()
                    updateMediaSessionMetadata()
                    
                    // 开始进度更新
                    startProgressUpdate()
                    
                    callback?.onPrepared(_duration)
                    callback?.onPlayStateChanged(true)
                }
                
                setOnCompletionListener {
                    _isPlaying = false
                    stopProgressUpdate()
                    updateMediaSessionState()
                    callback?.onCompletion()
                }
                
                setOnErrorListener { _, what, extra ->
                    _isPlaying = false
                    isPrepared = false
                    stopProgressUpdate()
                    updateMediaSessionState()
                    callback?.onError("播放错误: what=$what, extra=$extra")
                    true
                }
                
                prepareAsync()
            }
        } catch (e: Exception) {
            _isPlaying = false
            isPrepared = false
            callback?.onError("加载失败: ${e.message}")
        }
    }
    
    /**
     * 播放
     */
    fun play() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                // 关键修复：如果未准备，先重新加载
                if (!isPrepared && currentUrl.isNotEmpty()) {
                    loadAndPlay(currentUrl, _currentPosition)
                } else {
                    it.start()
                    _isPlaying = true
                    startProgressUpdate()
                    updateMediaSessionState()
                    callback?.onPlayStateChanged(true)
                }
            }
        }
    }
    
    /**
     * 暂停
     */
    fun pause() {
        android.util.Log.d("NativeAudioPlayer", "pause() 被调用, mediaPlayer=${mediaPlayer != null}, isPrepared=$isPrepared")
        mediaPlayer?.let {
            try {
                // 只有真正在播放时才暂停并通知状态变化
                if (it.isPlaying) {
                    android.util.Log.d("NativeAudioPlayer", "pause: 真正暂停 MediaPlayer")
                    it.pause()
                    _isPlaying = false
                    stopProgressUpdate()
                    updateMediaSessionState()
                    callback?.onPlayStateChanged(false)
                } else {
                    android.util.Log.d("NativeAudioPlayer", "pause: it.isPlaying=false，不重复暂停")
                }
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
                // 检查 MediaPlayer 是否已准备好，避免在 Preparing 状态调用 start()
                if (isPrepared && !it.isPlaying) {
                    android.util.Log.d("NativeAudioPlayer", "resume: 真正恢复播放")
                    it.start()
                    _isPlaying = true
                    startProgressUpdate()
                    updateMediaSessionState()
                    callback?.onPlayStateChanged(true)
                } else if (!isPrepared) {
                    android.util.Log.d("NativeAudioPlayer", "resume: 还在准备中，等待回调")
                } else {
                    android.util.Log.d("NativeAudioPlayer", "resume: 已经在播放中，不重复触发")
                }
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
        mediaPlayer?.let {
            if (isPrepared) {
                // 安全转换，防止溢出（Int.MAX_VALUE ≈ 596小时）
                val safePosition = position.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                it.seekTo(safePosition)
                _currentPosition = position
                // 立即同步系统进度条
                updateMediaSessionState()
                callback?.onProgressChanged(position, _duration)
            }
        }
    }
    
    /**
     * 获取当前位置
     */
    fun getCurrentPosition(): Long {
        return mediaPlayer?.currentPosition?.toLong() ?: _currentPosition
    }
    
    /**
     * 获取总时长
     */
    fun getDuration(): Long {
        return if (_duration > 0) _duration else (mediaPlayer?.duration?.toLong() ?: 0)
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
        stopProgressUpdate()
        mediaPlayer?.release()
        mediaPlayer = null
        isPrepared = false
        _isPlaying = false
    }
    
    /**
     * 开始进度更新
     */
    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _currentPosition = it.currentPosition.toLong()
                        callback?.onProgressChanged(_currentPosition, _duration)
                        // 同步 MediaSession 进度
                        updateMediaSessionState()
                    }
                }
                handler.postDelayed(this, 1000)
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
    
    /**
     * 更新 MediaSession 播放状态
     * 这是关键：实时同步系统进度条
     */
    private fun updateMediaSessionState() {
        mediaSession?.let { session ->
            val state = if (_isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            
            val position = getCurrentPosition()
            val playbackSpeed = 1.0f
            
            val stateBuilder = PlaybackStateCompat.Builder()
                .setState(state, position, playbackSpeed)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
                )
            
            session.setPlaybackState(stateBuilder.build())
        }
    }
    
    /**
     * 更新 MediaSession 元数据
     */
    private fun updateMediaSessionMetadata() {
        // 元数据通过 WebView 传递，这里只更新状态
    }
}
