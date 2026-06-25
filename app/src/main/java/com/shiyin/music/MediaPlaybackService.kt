package com.shiyin.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.bumptech.glide.Glide

/**
 * 媒体播放服务
 * 用于后台播放保活
 * 通知由 MediaSessionManager 统一管理，此服务仅保持前台运行
 */
class MediaPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_METADATA = "com.shiyin.music.UPDATE_METADATA"
        const val ACTION_STOP = "com.shiyin.music.STOP"
        const val ACTION_SET_SESSION_TOKEN = "com.shiyin.music.SET_SESSION_TOKEN"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_COVER = "cover"
        const val EXTRA_DURATION = "duration"

        internal var pendingSessionToken: MediaSessionCompat.Token? = null

        fun setSessionToken(token: MediaSessionCompat.Token) {
            pendingSessionToken = token
        }
    }

    private var currentTitle = "扣扣云"
    private var currentArtist = "正在播放..."
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        anQuanChuangJianTongZhiQuDao()

        pendingSessionToken?.let {
            setSessionToken(it)
            android.util.Log.d("MediaPlaybackService", "sessionToken已设置")
        }
    }

    private fun anQuanChuangJianTongZhiQuDao() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val channel = notificationManager?.getNotificationChannel(channelId)
                if (channel == null) {
                    val newChannel = NotificationChannel(
                        channelId,
                        "扣扣云播放",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "音乐播放控制和通知"
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
                    notificationManager?.createNotificationChannel(newChannel)
                    android.util.Log.d("MediaPlaybackService", "通知渠道已安全创建: $channelId")
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaPlaybackService", "创建通知渠道失败: ${e.message}")
            }
        }
    }

    // 通知渠道由 MediaSessionManager 统一创建，避免重复
    private val channelId: String
        get() = MediaSessionManager.CHANNEL_ID

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_METADATA -> {
                currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "扣扣云"
                currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: "正在播放..."
            }
            "MEDIA_CONTROL" -> {
                val command = intent.getStringExtra("command")
                if (command != null) {
                    android.util.Log.d("MediaPlaybackService", "转发媒体命令: $command")
                    try {
                        val activityIntent = Intent(this, MainActivity::class.java).apply {
                            setAction("MEDIA_CONTROL")
                            putExtra("command", command)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(activityIntent)
                    } catch (e: Exception) {
                        android.util.Log.e("MediaPlaybackService", "转发命令失败: ${e.message}")
                    }
                }
            }
            else -> {
                huiFuGeQuXinXi()
                anQuanChuangJianTongZhiQuDao()
                try {
                    val notification = createMinimalNotification()
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    android.util.Log.e("MediaPlaybackService", "startForeground失败: ${e.message}")
                }
            }
        }
        return START_STICKY
    }

    /** 服务重启时从全局异常处理模块恢复歌曲信息到通知栏 */
    private fun huiFuGeQuXinXi() {
        try {
            val sharedPreferences = getSharedPreferences("bo_fang_zhuang_tai_huan_cun", Context.MODE_PRIVATE)
            val savedTitle = sharedPreferences.getString("ge_qu_ming", null)
            val savedArtist = sharedPreferences.getString("ge_shou", null)
            if (savedTitle != null && savedTitle.isNotEmpty()) {
                currentTitle = savedTitle
                currentArtist = savedArtist ?: "正在播放..."
                android.util.Log.d("MediaPlaybackService", "服务重启，已恢复歌曲信息: $currentTitle - $currentArtist")
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaPlaybackService", "恢复歌曲信息失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
        pendingSessionToken = null
        Thread {
            try {
                Glide.get(this@MediaPlaybackService).clearDiskCache()
                android.util.Log.d("Memory", "Glide 磁盘缓存已清理完成")
            } catch (e: Exception) {
                android.util.Log.e("Memory", "清理磁盘缓存失败", e)
            }
        }.start()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("__ROOT__", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    private fun createMinimalNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId).apply {
            setSmallIcon(R.drawable.ic_notification)
            val displayTitle = if (currentTitle.length > 25) currentTitle.substring(0, 22) + "..." else currentTitle
            val displayArtist = if (currentArtist.length > 25) currentArtist.substring(0, 22) + "..." else currentArtist
            setContentTitle(displayTitle)
            setContentText(displayArtist)
            setContentIntent(contentIntent)
            setOngoing(true)
            setSilent(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }.build()
    }
}
