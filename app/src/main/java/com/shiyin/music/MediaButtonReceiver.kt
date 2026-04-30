package com.shiyin.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
            else -> {
                // 其他 ACTION 已通过 MediaSession.TransportControls 直接处理
                Log.d(TAG, "收到非媒体按钮事件: ${intent.action}")
            }
        }
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

    /**
     * 发送命令到 MainActivity
     */
    private fun sendCommandToMainActivity(context: Context, command: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "MEDIA_CONTROL"
            putExtra("command", command)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Log.d(TAG, "媒体命令已发送: $command")
    }
}
