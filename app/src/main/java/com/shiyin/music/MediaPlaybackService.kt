package com.shiyin.music

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media.MediaBrowserCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_COVER = "cover"
        const val EXTRA_DURATION = "duration"
    }

    private var currentTitle = "扣扣云"
    private var currentArtist = "正在播放..."

    override fun onCreate() {
        super.onCreate()
    }

    // 通知渠道由 MediaSessionManager 统一创建，避免重复
    private val channelId: String
        get() = MediaSessionManager.CHANNEL_ID

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP) {
            val notification = createMinimalNotification()
            startForeground(NOTIFICATION_ID, notification)
        }
        
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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
        // 异步清理 Glide 缓存，避免阻塞主线程
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Glide.get(this@MediaPlaybackService).clearDiskCache()
                android.util.Log.d("Memory", "Glide 磁盘缓存已清理完成")
            } catch (e: Exception) {
                android.util.Log.e("Memory", "清理磁盘缓存失败", e)
            }
        }
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
            setContentTitle(currentTitle)
            setContentText(currentArtist)
            setContentIntent(contentIntent)
            setOngoing(true)
            setSilent(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setPriority(NotificationCompat.PRIORITY_MIN)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }.build()
    }
}
