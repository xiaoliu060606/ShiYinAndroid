package com.shiyin.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * MediaSession 管理类
 * 负责管理媒体会话、锁屏控制、通知栏播放器
 */
class MediaSessionManager(private val context: MainActivity) {

    companion object {
        const val CHANNEL_ID = "shiyin_music_channel_v2"
        const val NOTIFICATION_ID = 1001
        
        // 媒体控制动作
        const val ACTION_PLAY = "com.shiyin.music.PLAY"
        const val ACTION_PAUSE = "com.shiyin.music.PAUSE"
        const val ACTION_PREVIOUS = "com.shiyin.music.PREVIOUS"
        const val ACTION_NEXT = "com.shiyin.music.NEXT"
        const val ACTION_STOP = "com.shiyin.music.STOP"
    }

    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null
    private var audioManager: AudioManager? = null

    private var isPlaying = false
    private var currentPosition = 0L
    private var duration = 0L
    private var playbackSpeed = 1.0f
    private var currentPlayMode = 0

    private var isUpdatingFromWeb = java.util.concurrent.atomic.AtomicBoolean(false)
    
    private var nativeAudioPlayer: NativeAudioPlayer? = null
    private var geQuQieHuanManager: GeQuQieHuanManager? = null
    
    private var lastStateChangeTime = 0L
    private val MIN_STATE_CHANGE_INTERVAL_MS = 500L
    
    private var currentSongId = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbumArt: Bitmap? = null
    private var currentAlbumArtUrl = ""  // 当前封面URL，用于预加载去重
    private var metadataUpdateGeneration = 0L
    private var lastNotificationUpdateTime = 0L
    private val NOTIFICATION_UPDATE_INTERVAL = 3000L // 3秒更新一次通知
    private var currentLyricLine = ""

    private var wasPlayingBeforeFocusLoss = false
    private var isDucking = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        android.util.Log.d("ShiYinSync", "[音频焦点] 系统通知: focusChange=$focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = isPlaying
                if (isPlaying) {
                    val player = nativeAudioPlayer
                    if (player != null) {
                        player.pause()
                    } else {
                        context.sendCommandToWeb("pause")
                    }
                    updatePlaybackState(false)
                    updateNotification()
                    context.webAppInterface.tongZhiH5YinPinJiaoDianBianHua(false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss = isPlaying
                if (isPlaying) {
                    val player = nativeAudioPlayer
                    if (player != null) {
                        player.pause()
                    } else {
                        context.sendCommandToWeb("pause")
                    }
                    updatePlaybackState(false)
                    updateNotification()
                    context.webAppInterface.tongZhiH5YinPinJiaoDianBianHua(false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 正确行为：降低音量（duck），而非暂停
                // 通知、导航语音等短暂音频不应打断音乐播放
                isDucking = true
                wasPlayingBeforeFocusLoss = isPlaying
                nativeAudioPlayer?.setVolume(0.3f)
                android.util.Log.d("ShiYinSync", "[音频焦点] CAN_DUCK: 降低音量至30%，保留播放状态")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 恢复音量（如果之前降音了）
                if (isDucking) {
                    isDucking = false
                    nativeAudioPlayer?.setVolume(1.0f)
                    android.util.Log.d("ShiYinSync", "[音频焦点] GAIN: 恢复音量至100%")
                }
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    val player = nativeAudioPlayer
                    if (player != null) {
                        player.resume()
                    } else {
                        context.sendCommandToWeb("play")
                    }
                    updatePlaybackState(true)
                    updateNotification()
                    context.webAppInterface.tongZhiH5YinPinJiaoDianBianHua(true)
                }
            }
        }
    }

    init {
        initMediaSession()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        currentAlbumArt = getDefaultAlbumArt()
    }

    fun chuShiHua() {
        if (mediaSession == null) {
            initMediaSession()
        }
    }

    /**
     * 初始化 MediaSession
     */
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(context, "ShiYinMusic").apply {
            // 设置媒体控制回调
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    super.onPlay()
                    requestAudioFocus()
                    val player = nativeAudioPlayer
                    if (player != null) {
                        android.util.Log.d("ShiYinSync", "[MediaSession] onPlay: 直接控制原生播放器播放")
                        player.resume()
                        // 关键修复：同步状态到 H5，让 UI 更新
                        context.webAppInterface.tongZhiH5BoFangZhuangTaiBianHua(true)
                    } else {
                        android.util.Log.w("ShiYinSync", "[MediaSession] onPlay: NativeAudioPlayer 未注入，降级到 Web 层控制")
                        if (!isUpdatingFromWeb.get()) {
                            context.sendCommandToWeb("play")
                        }
                    }
                }

                override fun onPause() {
                    super.onPause()
                    // 关键修复：直接控制原生播放器暂停
                    val player = nativeAudioPlayer
                    if (player != null) {
                        android.util.Log.d("ShiYinSync", "[MediaSession] onPause: 直接控制原生播放器暂停")
                        player.pause()
                        // 关键修复：同步状态到 H5，让 UI 更新
                        context.webAppInterface.tongZhiH5BoFangZhuangTaiBianHua(false)
                    } else {
                        android.util.Log.w("ShiYinSync", "[MediaSession] onPause: NativeAudioPlayer 未注入，降级到 Web 层控制")
                        if (!isUpdatingFromWeb.get()) {
                            context.sendCommandToWeb("pause")
                        }
                    }
                }

                override fun onStop() {
                    super.onStop()
                    abandonAudioFocus()
                    if (!isUpdatingFromWeb.get()) {
                        val player = nativeAudioPlayer
                        if (player != null) {
                            player.pause()
                            player.seekTo(0)
                            updatePlaybackState(false, 0)
                            updateNotification()
                            android.util.Log.d("ShiYinSync", "[MediaSession] onStop: 停止原生播放器并跳到开头")
                        } else {
                            context.sendCommandToWeb("stop")
                            updatePlaybackState(false)
                            updateNotification()
                        }
                    }
                }

                override fun onSkipToPrevious() {
                    super.onSkipToPrevious()
                    val manager = geQuQieHuanManager
                    // 关键修复：随机模式下通过H5切歌，因为H5有正确的随机队列顺序
                    // 原生GeQuQieHuanManager只有顺序列表，随机模式会选错歌
                    if (manager != null && !manager.lieBiaoShiFouKong() && manager.huoQuBoFangMoShi() != 2) {
                        manager.qieGe("previous")
                    } else if (!isUpdatingFromWeb.get()) {
                        context.sendCommandToWeb("previous")
                    }
                }

                override fun onSkipToNext() {
                    super.onSkipToNext()
                    val manager = geQuQieHuanManager
                    // 关键修复：随机模式下通过H5切歌，因为H5有正确的随机队列顺序
                    if (manager != null && !manager.lieBiaoShiFouKong() && manager.huoQuBoFangMoShi() != 2) {
                        manager.qieGe("next")
                    } else if (!isUpdatingFromWeb.get()) {
                        context.sendCommandToWeb("next")
                    }
                }

                override fun onSeekTo(pos: Long) {
                    super.onSeekTo(pos)
                    // 关键修复：优先使用原生播放器处理 seek
                    val player = nativeAudioPlayer
                    if (player != null) {
                        player.seekTo(pos)
                        currentPosition = pos
                        updatePlaybackState(isPlaying)
                        android.util.Log.d("ShiYinSync", "[MediaSession] onSeekTo: 直接控制原生播放器跳到 $pos ms")
                    } else if (!isUpdatingFromWeb.get()) {
                        context.sendCommandToWeb("seek:$pos")
                        currentPosition = pos
                        updatePlaybackState(isPlaying)
                    }
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    if (mediaButtonEvent != null) {
                        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }

                        keyEvent?.let { event ->
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                when (event.keyCode) {
                                    KeyEvent.KEYCODE_MEDIA_PLAY,
                                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                        return false
                                    }
                                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                        val mgr = geQuQieHuanManager
                                        // 随机模式通过H5切歌
                                        if (mgr != null && !mgr.lieBiaoShiFouKong() && mgr.huoQuBoFangMoShi() != 2) {
                                            mgr.qieGe("previous")
                                        } else if (!isUpdatingFromWeb.get()) {
                                            context.sendCommandToWeb("previous")
                                        }
                                    }
                                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                        val mgr = geQuQieHuanManager
                                        // 随机模式通过H5切歌
                                        if (mgr != null && !mgr.lieBiaoShiFouKong() && mgr.huoQuBoFangMoShi() != 2) {
                                            mgr.qieGe("next")
                                        } else if (!isUpdatingFromWeb.get()) {
                                            context.sendCommandToWeb("next")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return true
                }
            })

            // 设置支持的媒体控制标志
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                     MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

            // 设置 MediaButton 接收器（关键：接收耳机/蓝牙/系统媒体按钮）
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(context, MediaButtonReceiver::class.java)
            }
            val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                context, 0, mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(mediaButtonPendingIntent)

            // 设置点击通知时打开的 Activity
            val sessionIntent = Intent(context, MainActivity::class.java)
            val sessionPendingIntent = PendingIntent.getActivity(
                context, 0, sessionIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            setSessionActivity(sessionPendingIntent)

            // 设置初始播放状态
            updatePlaybackState(false)
        }
    }

    /**
     * 初始化通知渠道（Android 8.0+）
     */
    fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val groupId = "shiyin_music_group"
            val groupName = "拾音音乐"
            val channelGroup = NotificationChannelGroup(groupId, groupName)
            notificationManager?.createNotificationChannelGroup(channelGroup)

            val name = "扣扣云播放"
            val descriptionText = "音乐播放控制和通知"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableLights(true)
                lightColor = android.graphics.Color.parseColor("#FF6B6B")
                enableVibration(false)
                setSound(null, null)
                group = groupId
            }
            notificationManager?.createNotificationChannel(channel)
            
            android.util.Log.d("ShiYinSync", "[通知渠道] 已创建: $CHANNEL_ID (importance=$importance)")
        }
    }

    /**
     * 更新播放状态
     */
    private fun updatePlaybackState(playing: Boolean, position: Long = currentPosition) {
        isPlaying = playing
        currentPosition = position

        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        var actions = (PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP)

        when (currentPlayMode) {
            1 -> { actions = actions or PlaybackStateCompat.ACTION_SET_REPEAT_MODE }
            2 -> { actions = actions or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE }
        }

        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(state, position, playbackSpeed)
            .setActions(actions)

        mediaSession?.setPlaybackState(stateBuilder.build())
    }
    
    fun setPlayMode(mode: Int) {
        currentPlayMode = mode
        android.util.Log.d("ShiYinSync", "[播放模式] H5→Native: mode=$mode (${when(mode) {0 -> "顺序" 1 -> "单曲循环" else -> "随机"}})")
        updatePlaybackState(isPlaying)
    }

    fun getDangQianBoFangMoShi(): Int = currentPlayMode

    /**
     * 更新通知栏歌词显示（由 H5 通过 Bridge 调用）
     * 当有歌词时通知栏显示歌词，无歌词时显示歌手名
     */
    fun updateGeCi(lyric: String) {
        if (lyric == currentLyricLine) return
        currentLyricLine = lyric
        updateNotification()
        android.util.Log.d("ShiYinSync", "[歌词通知] 更新: $lyric")
    }

    /**
     * 更新歌曲元数据
     * QQ音乐式优化：预加载封面，避免状态栏封面延时切换
     */
    fun updateMetadata(title: String, artist: String, albumArtUrl: String, durationMs: Long, songId: String = "") {
        currentSongId = songId
        currentTitle = title
        currentArtist = artist
        currentLyricLine = ""
        if (durationMs >= 0) {
            this.duration = durationMs
        }
        
        val currentGeneration = ++metadataUpdateGeneration

        // 截断歌名和歌手名，防止通知栏文字重叠
        val displayMetaTitle = if (title.length > 25) title.substring(0, 22) + "..." else title
        val displayMetaArtist = if (artist.length > 25) artist.substring(0, 22) + "..." else artist

        // 先立即更新文字信息（不等待封面加载），确保通知栏歌名/歌手及时更新
        // 保留当前封面作为占位，避免显示默认封面造成闪烁
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentSongId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayMetaTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayMetaArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, displayMetaArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, this.duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentAlbumArt ?: getDefaultAlbumArt())
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentAlbumArt ?: getDefaultAlbumArt())
        mediaSession?.setMetadata(metadataBuilder.build())
        updateNotification()
        android.util.Log.d("ShiYinSync", "[元数据] 立即更新文字: $displayMetaTitle - $displayMetaArtist (generation=$currentGeneration)")

        // 异步加载封面，加载完成后再更新一次
        context.loadAlbumArt(albumArtUrl) { bitmap ->
            if (currentGeneration != metadataUpdateGeneration) {
                android.util.Log.d("ShiYinSync", "[元数据] 跳过过时的封面更新: generation=$currentGeneration != latest=$metadataUpdateGeneration")
                return@loadAlbumArt
            }
            
            currentAlbumArt = bitmap ?: getDefaultAlbumArt()
            
            val metadataWithCover = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentSongId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayMetaTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayMetaArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, displayMetaArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, this.duration)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentAlbumArt)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentAlbumArt)

            mediaSession?.setMetadata(metadataWithCover.build())
            updateNotification()
            android.util.Log.d("ShiYinSync", "[元数据] 封面加载完成，已更新: $displayMetaTitle - $displayMetaArtist (generation=$currentGeneration)")
        }
    }
    
    /**
     * 预加载下一首歌曲封面（QQ音乐式优化）
     * 在播放当前歌曲时预加载下一首封面，避免切歌时封面延时
     */
    fun preloadNextAlbumArt(albumArtUrl: String) {
        if (albumArtUrl.isEmpty() || albumArtUrl == currentAlbumArtUrl) return
        currentAlbumArtUrl = albumArtUrl
        android.util.Log.d("ShiYinSync", "[预加载] 开始预加载下一首封面: ${albumArtUrl.substring(0, minOf(50, albumArtUrl.length))}")
        context.loadAlbumArt(albumArtUrl) { bitmap ->
            if (bitmap != null) {
                android.util.Log.d("ShiYinSync", "[预加载] 下一首封面预加载完成")
            }
        }
    }

    /**
     * 获取默认专辑封面图标
     */
    private fun getDefaultAlbumArt(): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_default_album)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 更新播放进度
     */
    fun updateProgress(position: Long, duration: Long) {
        currentPosition = position
        this.duration = duration

        // 关键修复：使用 AtomicBoolean 的 compareAndSet 防止并发问题
        if (isUpdatingFromWeb.compareAndSet(false, true)) {
            try {
                // 直接更新 PlaybackState，传入正确的 position
                val state = if (isPlaying) {
                    PlaybackStateCompat.STATE_PLAYING
                } else {
                    PlaybackStateCompat.STATE_PAUSED
                }

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

                mediaSession?.setPlaybackState(stateBuilder.build())
                
                // 关键修复：更新通知栏的进度条显示
                // 每3秒更新一次通知，避免频繁刷新
                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdateTime >= NOTIFICATION_UPDATE_INTERVAL) {
                    updateNotification()
                    lastNotificationUpdateTime = now
                }
            } finally {
                isUpdatingFromWeb.set(false)
            }
        }
    }

    /**
     * 设置播放状态（从 WebView 接收）
     */
    fun setPlayState(playing: Boolean) {
        if (isPlaying != playing) {
            if (isUpdatingFromWeb.compareAndSet(false, true)) {
                try {
                    if (playing) {
                        requestAudioFocus()
                    }
                    updatePlaybackState(playing)
                    updateNotification()
                } finally {
                    isUpdatingFromWeb.set(false)
                }
            }
        }
    }

    /** 设置播放状态（从原生播放器直接设置，不走H5） */
    fun setPlayStateFromNative(playing: Boolean) {
        if (isPlaying != playing) {
            if (playing) {
                requestAudioFocus()
            }
            val currentPosition = nativeAudioPlayer?.getCurrentPosition() ?: currentPosition
            updatePlaybackState(playing, currentPosition)
            updateNotification()
        }
    }

    /**
     * 设置原生音频播放器（由 WebAppInterface 调用）
     * 关键修复：注入播放器引用，使锁屏控制可以直控原生播放器
     */
    fun setNativeAudioPlayer(player: NativeAudioPlayer?) {
        this.nativeAudioPlayer = player
        android.util.Log.d("ShiYinSync", "[播放器] NativeAudioPlayer 已注入: ${player != null}")
    }

    fun setGeQuQieHuanManager(manager: GeQuQieHuanManager?) {
        this.geQuQieHuanManager = manager
        android.util.Log.d("ShiYinSync", "[切歌管理器] GeQuQieHuanManager 已注入: ${manager != null}")
    }

    /** 请求系统音频焦点（让其他应用静音） */
    fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        .build()
                }
                val result = am.requestAudioFocus(audioFocusRequest!!)
                android.util.Log.d("ShiYinSync", "[音频焦点] 请求 (API 26+): $result")
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                android.util.Log.d("ShiYinSync", "[音频焦点] 请求 (旧API): $result")
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "[音频焦点] 请求失败: ${e.message}")
            false
        }
    }

    /** 释放系统音频焦点（允许其他应用发声） */
    fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    am.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
            wasPlayingBeforeFocusLoss = false
            isDucking = false
            android.util.Log.d("ShiYinSync", "[音频焦点] 已释放")
        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "[音频焦点] 释放失败: ${e.message}")
        }
    }

    /**
     * 获取当前播放状态
     */
    fun isPlaying(): Boolean {
        return isPlaying
    }

    /**
     * 获取 MediaSession 实例
     */
    fun getMediaSession(): MediaSessionCompat? {
        return mediaSession
    }

    /**
     * 更新通知栏播放器
     */
    private fun updateNotification() {
        val notification = getCurrentNotification() ?: return
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 设置 MediaSession 活跃状态
     */
    fun setActive(active: Boolean) {
        mediaSession?.isActive = active
    }

    /**
     * 通知歌曲播放完成（Activity销毁时的降级处理）
     * 通过发送next命令重新启动Activity来触发切歌
     */
    fun notifySongCompletion() {
        android.util.Log.d("ShiYinSync", "[切歌] 尝试通知下一首")
        val manager = geQuQieHuanManager
        // 非随机模式时使用原生切歌，随机模式优先走H5（有正确的随机队列）
        if (manager != null && !manager.lieBiaoShiFouKong() && manager.huoQuBoFangMoShi() != 2) {
            manager.qieGe("next", false)
            return
        }
        try {
            if (!context.isFinishing && !context.isDestroyed) {
                context.runOnUiThread {
                    try {
                        context.getWebView().evaluateJavascript(
                            "javascript:if(typeof playNextSong==='function') playNextSong();",
                            null
                        )
                        android.util.Log.d("ShiYinSync", "[切歌] 通过WebView切歌")
                    } catch (e: Exception) {
                        android.util.Log.e("ShiYinSync", "[切歌] WebView切歌失败: ${e.message}")
                    }
                }
            } else {
                val intent = Intent(context, MediaButtonReceiver::class.java).apply {
                    action = ACTION_NEXT
                }
                context.sendBroadcast(intent)
                android.util.Log.d("ShiYinSync", "[切歌] 通过广播切歌")
            }
        } catch (e: Exception) {
            android.util.Log.e("ShiYinSync", "[切歌] 失败: ${e.message}")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        abandonAudioFocus()
        notificationManager?.cancel(NOTIFICATION_ID)
        mediaSession?.release()
        mediaSession = null
        nativeAudioPlayer = null
        if (currentAlbumArt != null && currentAlbumArt != getDefaultAlbumArt()) {
            val huiShouBitmap = currentAlbumArt
            currentAlbumArt = null
            huiShouBitmap?.recycle()
        } else {
            currentAlbumArt = null
        }
    }

    /**
     * 获取 MediaSession Token（用于通知）
     */
    fun getSessionToken(): MediaSessionCompat.Token? {
        return mediaSession?.sessionToken
    }

    /**
     * 处理媒体按钮事件
     */
    fun handleMediaButtonEvent(event: KeyEvent?) {
        event?.let {
            mediaSession?.controller?.dispatchMediaButtonEvent(it)
        }
    }

    /** 获取当前通知栏通知对象（供前台服务使用） */
    fun getCurrentNotification(): Notification? {
        if (currentTitle.isEmpty()) return null
        if (mediaSession?.sessionToken == null) return null

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "暂停",
                createMediaActionPendingIntent(PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "播放",
                createMediaActionPendingIntent(PlaybackStateCompat.ACTION_PLAY)
            )
        }

        val previousAction = NotificationCompat.Action(
            R.drawable.ic_previous,
            "上一首",
            createMediaActionPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        )

        val nextAction = NotificationCompat.Action(
            R.drawable.ic_next,
            "下一首",
            createMediaActionPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        )

        val stopAction = NotificationCompat.Action(
            R.drawable.ic_stop,
            "停止",
            createMediaActionPendingIntent(PlaybackStateCompat.ACTION_STOP)
        )

        val displayTitle = currentTitle.ifEmpty { "扣扣云" }.let {
            if (it.length > 30) it.substring(0, 27) + "..." else it
        }
        val displayArtist = currentArtist.ifEmpty { "正在播放..." }.let {
            if (it.length > 30) it.substring(0, 27) + "..." else it
        }
        val displaySubText = if (currentLyricLine.isNotEmpty()) {
            if (currentLyricLine.length > 40) currentLyricLine.substring(0, 37) + "..." else currentLyricLine
        } else {
            "扣扣云"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(displayTitle)
            setContentText(displayArtist)
            setSubText(displaySubText)
            setLargeIcon(currentAlbumArt)
            setContentIntent(contentIntent)
            setOngoing(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setPriority(NotificationCompat.PRIORITY_MAX)
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setShowWhen(false)
            setOnlyAlertOnce(true)

            addAction(previousAction)
            addAction(playPauseAction)
            addAction(nextAction)
            addAction(stopAction)

            if (duration > 0) {
                setProgress((duration / 1000).toInt(), (currentPosition / 1000).toInt(), false)
            }

            setStyle(MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            )
        }.build()
    }

    /** 创建通知栏按钮的 PendingIntent（点击通知栏按钮时触发） */
    private fun createMediaActionPendingIntent(action: Long): PendingIntent {
        // 关键修复：直接使用 MediaSession 的 MediaButtonReceiver
        // 避免通过 BroadcastReceiver 再 startActivity 的延迟
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(context, MediaButtonReceiver::class.java)
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                when (action) {
                    PlaybackStateCompat.ACTION_PLAY -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    PlaybackStateCompat.ACTION_PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                    PlaybackStateCompat.ACTION_STOP -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                    else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
            ))
        }
        return PendingIntent.getBroadcast(
            context, action.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
