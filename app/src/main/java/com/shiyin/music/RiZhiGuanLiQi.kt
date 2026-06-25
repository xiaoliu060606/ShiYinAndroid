package com.shiyin.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志管理器 - 收集应用日志并导出为 TXT
 */
class RiZhiGuanLiQi(private val context: Context) {

    companion object {
        private const val TAG = "ShiYinRiZhi"
        private const val MAX_LOG_LINES = 5000
        private val riZhiHuanChong = ArrayDeque<String>(MAX_LOG_LINES)
        private val riQiGeShi = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        private var banBenXinXi = "未知"
        private var sheBeiXinXi = "未知"

        fun chuShiHua(banBen: String, sheBei: String) {
            banBenXinXi = banBen
            sheBeiXinXi = sheBei
        }

        @JvmStatic
        fun logDebug(biaoQian: String, xiaoXi: String) {
            val shiJian = riQiGeShi.format(Date())
            val riZhi = "[$shiJian] D/$biaoQian: $xiaoXi"
            synchronized(riZhiHuanChong) {
                if (riZhiHuanChong.size >= MAX_LOG_LINES) {
                    riZhiHuanChong.removeFirst()
                }
                riZhiHuanChong.addLast(riZhi)
            }
            Log.d(biaoQian, xiaoXi)
        }

        @JvmStatic
        fun logInfo(biaoQian: String, xiaoXi: String) {
            val shiJian = riQiGeShi.format(Date())
            val riZhi = "[$shiJian] I/$biaoQian: $xiaoXi"
            synchronized(riZhiHuanChong) {
                if (riZhiHuanChong.size >= MAX_LOG_LINES) {
                    riZhiHuanChong.removeFirst()
                }
                riZhiHuanChong.addLast(riZhi)
            }
            Log.i(biaoQian, xiaoXi)
        }

        @JvmStatic
        fun logWarn(biaoQian: String, xiaoXi: String) {
            val shiJian = riQiGeShi.format(Date())
            val riZhi = "[$shiJian] W/$biaoQian: $xiaoXi"
            synchronized(riZhiHuanChong) {
                if (riZhiHuanChong.size >= MAX_LOG_LINES) {
                    riZhiHuanChong.removeFirst()
                }
                riZhiHuanChong.addLast(riZhi)
            }
            Log.w(biaoQian, xiaoXi)
        }

        @JvmStatic
        fun logError(biaoQian: String, xiaoXi: String) {
            val shiJian = riQiGeShi.format(Date())
            val riZhi = "[$shiJian] E/$biaoQian: $xiaoXi"
            synchronized(riZhiHuanChong) {
                if (riZhiHuanChong.size >= MAX_LOG_LINES) {
                    riZhiHuanChong.removeFirst()
                }
                riZhiHuanChong.addLast(riZhi)
            }
            Log.e(biaoQian, xiaoXi)
        }

        /**
         * 获取静态日志缓冲区内容
         */
        @JvmStatic
        fun riZhiHuanChongXinXi(): String {
            return synchronized(riZhiHuanChong) {
                buildString {
                    appendLine("===== 拾音音乐 日志导出 =====")
                    appendLine("导出时间: ${riQiGeShi.format(Date())}")
                    appendLine("应用版本: $banBenXinXi")
                    appendLine("设备信息: $sheBeiXinXi")
                    appendLine("日志条数: ${riZhiHuanChong.size} / $MAX_LOG_LINES")
                    appendLine("============================")
                    appendLine()
                    riZhiHuanChong.forEach { appendLine(it) }
                }
            }
        }
    }

    /**
     * 导出日志到文件并分享
     */
    fun daoChuRiZhi(): String {
        return try {
            // 读取静态缓冲区的日志
            val riZhiNeiRong = RiZhiGuanLiQi.riZhiHuanChongXinXi()

            val wenJianMing = "shiyin_log_${System.currentTimeMillis()}.txt"
            val muLu = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "logs")
            if (!muLu.exists()) {
                muLu.mkdirs()
            }

            val wenJian = File(muLu, wenJianMing)
            wenJian.writeText(riZhiNeiRong)

            // 分享文件
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", wenJian)
            } else {
                Uri.fromFile(wenJian)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "拾音音乐日志")
                putExtra(Intent.EXTRA_TEXT, "请查看附件中的日志文件")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "导出日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            "日志已导出: ${wenJian.absolutePath}"
        } catch (e: Exception) {
            "导出失败: ${e.message}"
        }
    }

    /**
     * 获取日志内容（用于直接显示）
     */
    fun huoQuRiZhiNeiRong(): String {
        return RiZhiGuanLiQi.riZhiHuanChongXinXi()
    }

    /**
     * 清空日志缓存
     */
    fun qingKongRiZhi() {
        synchronized(riZhiHuanChong) {
            riZhiHuanChong.clear()
        }
    }
}
