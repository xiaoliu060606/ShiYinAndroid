package com.shiyin.music

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.app.ActivityManager
import android.app.Activity
import android.app.ActivityManager.RunningAppProcessInfo

/**
 * 剪贴板帮助类
 * 安全地访问剪贴板，避免后台访问导致的权限错误
 */
class ClipboardHelper(private val context: Context) {
    
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * 安全读取剪贴板
     */
    fun readClipboard(): String? {
        // 检查App是否在前台（有焦点）
        if (!isAppInForeground()) {
            return null // 后台时直接返回，避免报错
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            } else {
                @Suppress("DEPRECATION")
                clipboardManager.text?.toString()
            }
        } catch (e: SecurityException) {
            // 捕获权限异常，避免Crash
            null
        } catch (e: IllegalStateException) {
            // 捕获 IllegalStateException，避免崩溃
            null
        }
    }

    /**
     * 安全写入剪贴板
     */
    fun writeClipboard(text: String): Boolean {
        if (!isAppInForeground()) {
            return false
        }
        
        return try {
            val clip = ClipData.newPlainText("music_text", text)
            clipboardManager.setPrimaryClip(clip)
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        } catch (e: IllegalStateException) {
            // 捕获 IllegalStateException，避免崩溃
            false
        }
    }

    /**
     * 判断App是否在前台且有焦点
     * 关键修复：使用多种检测方法确保准确性
     */
    private fun isAppInForeground(): Boolean {
        return try {
            // 方法1：检查应用是否在前台
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses ?: return false
            
            var isForeground = false
            for (processInfo in appProcesses) {
                if (processInfo.processName == context.packageName) {
                    // 检查应用是否在前台且有焦点
                    if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                        processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                        isForeground = true
                        break
                    }
                }
            }
            
            // 方法2：对于 Android 10+，使用 ActivityManager.getAppTasks 进一步验证
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isForeground) {
                val appTasks = activityManager.appTasks
                if (appTasks.isNotEmpty()) {
                    // 检查是否有活动的任务
                    val taskInfo = appTasks[0].taskInfo
                    if (taskInfo.topActivity?.packageName == context.packageName) {
                        return true
                    }
                }
            }
            
            isForeground
        } catch (e: SecurityException) {
            // 捕获权限异常，返回 false
            false
        } catch (e: Exception) {
            // 捕获其他异常，返回 false
            false
        }
    }
}
