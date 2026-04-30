package com.shiyin.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media.MediaMetadataCompat
import androidx.media.session.MediaSessionCompat
import androidx.media.session.PlaybackStateCompat
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

    // 当前播放状态
    private var isPlaying = false
    private var currentPosition = 0L
    private var duration = 0L
    private var playbackSpeed = 1.0f

    // 防止回调循环的标志位
    // 关键修复：使用 AtomicBoolean 防止并发场景下的状态竞争
    private var isUpdatingFromWeb = java.util.concurrent.atomic.AtomicBoolean(false)
    
    // 原生音频播放器引用（由 WebAppInterface 注入）
    // 关键修复：MediaSession 直接控制原生播放器，不再通知 Web 层
    private var nativeAudioPlayer: NativeAudioPlayer? = null
    
    // 状态翻转保护：记录上次状态变化时间，防止短时间内快速翻转
    private var lastStateChangeTime = 0L
    private val MIN_STATE_CHANGE_INTERVAL_MS = 500L  // 500ms 内不允许状态翻转
    
    // 当前歌曲信息
    private var currentSongId = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbumArt: Bitmap? = null

    init {
        initMediaSession()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        currentAlbumArt = getDefaultAlbumArt()
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
                    // 关键修复：直接控制原生播放器，不再通知 Web 层
                    // 避免状态循环导致的死锁问题
                    val player = nativeAudioPlayer
                    if (player != null) {
                        android.util.Log.d("MediaSession", "onPlay: 直接控制原生播放器播放")
                        if (player.isPlayingState()) {
                            android.util.Log.d("MediaSession", "onPlay: 已经在播放中，忽略")
                        } else {
                            player.resume()
                        }
                    } else {
                        android.util.Log.w("MediaSession", "onPlay: NativeAudioPlayer 未注入，降级到 Web 层控制")
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
                        android.util.Log.d("MediaSession", "onPause: 直接控制原生播放器暂停")
                        player.pause()
                    } else {
                        android.util.Log.w("MediaSession", "onPause: NativeAudioPlayer 未注入，降级到 Web 层控制")
                        if (!isUpdatingFromWeb.get()) {
                            context.sendCommandToWeb("pause")
                        }
                    }
                }

                override fun onStop() {
                    super.onStop()
                    if (!isUpdatingFromWeb.get()) {
                        val player = nativeAudioPlayer
                        if (player != null) {
                            player.pause()
                            player.seekTo(0)
                            android.util.Log.d("MediaSession", "onStop: 停止原生播放器并跳到开头")
                        } else {
                            context.sendCommandToWeb("stop")
                            updatePlaybackState(false)
                        }
                    }
                }

                override fun onSkipToPrevious() {
                    super.onSkipToPrevious()
                    // 上一首/下一首仍需要通知 Web 层处理播放列表
                    if (!isUpdatingFromWeb.get()) {
                        context.sendCommandToWeb("previous")
                    }
                }

                override fun onSkipToNext() {
                    super.onSkipToNext()
                    if (!isUpdatingFromWeb.get()) {
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
                        android.util.Log.d("MediaSession", "onSeekTo: 直接控制原生播放器跳到 $pos ms")
                    } else if (!isUpdatingFromWeb.get()) {
                        context.sendCommandToWeb("seek:$pos")
                        currentPosition = pos
                        updatePlaybackState(isPlaying)
                    }
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    // 处理媒体按键事件（耳机控制等）
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
                                        // 这些按键由原生播放器处理，不再通知 Web 层
                                    }
                                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                        if (!isUpdatingFromWeb.get()) {
                                            context.sendCommandToWeb("previous")
                                        }
                                    }
                                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                        if (!isUpdatingFromWeb.get()) {
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
            val name = "扣扣云播放"
            val descriptionText = "音乐播放控制和通知"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
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

        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(state, currentPosition, playbackSpeed)
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

        // 更新通知
        updateNotification()
    }

    /**
     * 更新歌曲元数据
     */
    fun updateMetadata(title: String, artist: String, albumArtUrl: String, durationMs: Long) {
        currentTitle = title
        currentArtist = artist
        this.duration = durationMs

        // 加载专辑封面
        context.loadAlbumArt(albumArtUrl) { bitmap ->
            // 如果没有加载到专辑封面，使用默认图标
            currentAlbumArt = bitmap ?: getDefaultAlbumArt()
            
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentSongId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentAlbumArt)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentAlbumArt)

            mediaSession?.setMetadata(metadataBuilder.build())
            
            // 更新通知
            updateNotification()
        }
    }

    /**
     * 获取默认专辑封面图标
     */
    private fun getDefaultAlbumArt(): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
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
                
                // 同时更新通知，确保进度同步显示
                updateNotification()
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
            // 关键修复：使用 AtomicBoolean 的 compareAndSet 防止并发问题
            if (isUpdatingFromWeb.compareAndSet(false, true)) {
                try {
                    updatePlaybackState(playing)
                } finally {
                    isUpdatingFromWeb.set(false)
                }
            }
        }
    }

    /**
     * 设置原生音频播放器（由 WebAppInterface 调用）
     * 关键修复：注入播放器引用，使锁屏控制可以直控原生播放器
     */
    fun setNativeAudioPlayer(player: NativeAudioPlayer?) {
        this.nativeAudioPlayer = player
        android.util.Log.d("MediaSessionManager", "NativeAudioPlayer 已注入: ${player != null}")
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
     * 释放资源
     */
    fun release() {
        notificationManager?.cancel(NOTIFICATION_ID)
        mediaSession?.release()
        mediaSession = null
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

    fun getCurrentNotification(): Notification? {
        if (currentTitle.isEmpty()) return null
        val token = mediaSession?.sessionToken ?: return null

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

        return NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(currentTitle.ifEmpty { "扣扣云" })
            setContentText(currentArtist.ifEmpty { "正在播放..." })
            setLargeIcon(currentAlbumArt)
            setContentIntent(contentIntent)
            setOngoing(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            addAction(previousAction)
            addAction(playPauseAction)
            addAction(nextAction)
            addAction(stopAction)

            setStyle(MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            )
        }.build()
    }
}
