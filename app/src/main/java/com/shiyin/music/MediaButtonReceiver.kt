package com.shiyin.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent

/**
 * 媒体按钮接收器
 * 处理耳机按键、锁屏控制、蓝牙设备控制等
 * 通知栏按钮已通过 MediaSession.TransportControls 直接处理，此接收器仅处理物理媒体按钮
 */
class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                handleMediaButton(context, intent)
            }
            MediaSessionManager.ACTION_PLAY,
            MediaSessionManager.ACTION_PAUSE,
            MediaSessionManager.ACTION_PREVIOUS,
            MediaSessionManager.ACTION_NEXT,
            MediaSessionManager.ACTION_STOP -> {
                handleNotificationAction(context, intent.action ?: return)
            }
            else -> {
                Log.d(TAG, "收到未处理的 action: ${intent.action}")
            }
        }
    }

    private fun handleNotificationAction(context: Context, action: String) {
        when (action) {
            MediaSessionManager.ACTION_PLAY -> {
                sendCommandToMainActivity(context, "play")
            }
            MediaSessionManager.ACTION_PAUSE -> {
                sendCommandToMainActivity(context, "pause")
            }
            MediaSessionManager.ACTION_PREVIOUS -> {
                sendCommandToMainActivity(context, "previous")
            }
            MediaSessionManager.ACTION_NEXT -> {
                sendCommandToMainActivity(context, "next")
            }
            MediaSessionManager.ACTION_STOP -> {
                sendCommandToMainActivity(context, "stop")
            }
        }
        Log.d(TAG, "通知栏命令已发送: $action")
    }

    /**
     * 处理物理媒体按钮事件
     */
    private fun handleMediaButton(context: Context, intent: Intent) {
        val keyEvent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

        keyEvent?.let { event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val command = when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
                    KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                    KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
                    KeyEvent.KEYCODE_HEADSETHOOK -> "toggle"
                    else -> null
                }
                
                if (command != null) {
                    sendCommandToMainActivity(context, command)
                }
            }
        }
    }

    private fun sendCommandToMainActivity(context: Context, command: String) {
        // 关键修复：优先通过 MediaSession TransportControls 直接处理
        // 避免 startActivity 带来的延迟和卡顿
        try {
            val sessionToken = MediaPlaybackService.pendingSessionToken
            if (sessionToken != null) {
                val mediaController = android.support.v4.media.session.MediaControllerCompat(context, sessionToken)
                val transportControls = mediaController.transportControls
                when (command) {
                    "play" -> transportControls.play()
                    "pause" -> transportControls.pause()
                    "next" -> transportControls.skipToNext()
                    "previous" -> transportControls.skipToPrevious()
                    "stop" -> transportControls.stop()
                    "toggle" -> {
                        val state = mediaController.playbackState
                        if (state != null && state.state == PlaybackStateCompat.STATE_PLAYING) {
                            transportControls.pause()
                        } else {
                            transportControls.play()
                        }
                    }
                }
                Log.d(TAG, "媒体命令已发送(通过MediaSession): $command")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "通过MediaSession发送失败: ${e.message}，降级到Activity")
        }

        // 降级方案：通过 Activity 处理
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "MEDIA_CONTROL"
                putExtra("command", command)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "媒体命令已发送(通过Activity): $command")
        } catch (e: Exception) {
            Log.e(TAG, "启动Activity也失败: ${e.message}")
            val fallbackIntent = Intent("MEDIA_CONTROL_FALLBACK").apply {
                putExtra("command", command)
                setPackage(context.packageName)
            }
            context.sendBroadcast(fallbackIntent)
        }
    }
}
