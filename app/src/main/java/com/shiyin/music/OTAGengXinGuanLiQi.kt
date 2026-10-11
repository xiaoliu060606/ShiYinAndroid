package com.shiyin.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * OTA 更新管理器（原生实现，供 WebAppInterface 桥接给 H5）
 *
 * 契约：
 * - 检查更新：优先用 ApiPeiZhiDanLi.huoQuOtaUrl()（非空时 GET 自定义 JSON）；
 *   ota_url 为空时回退 GitHub releases latest；任何网络失败统一返回中文错误 JSON；
 * - 下载：OkHttp 流式写入 cacheDir/ota/update.apk（FileProvider cache-path 已覆盖），
 *   按百分比回调进度，支持取消（断开连接 + 删半成品）；
 * - 安装：FileProvider + ACTION_VIEW；无"安装未知应用"权限时先跳系统设置页再返回 false；
 * - 版本号来自 PackageManager（项目 buildConfig=false，不存在 BuildConfig 类）；
 * - 日志严禁输出 downloadUrl / token；错误文案全部中文。
 *
 * 线程约定：HuiDiao 可能在 OkHttp 工作线程回调，调用方（WebAppInterface）负责切主线程。
 */
class OTAGengXinGuanLiQi(private val context: Context) {

    companion object {
        private const val TAG = "OTAGengXinGuanLiQi"
        private const val APK_MU_LU_MING = "ota"
        private const val APK_WEN_JIAN_MING = "update.apk"
        private const val GITHUB_OTA_URL =
            "https://api.github.com/repos/xiaoliu060606/ShiYinAndroid/releases/latest"
        private const val CHA_ZHAO_SHI_BAI = "检查更新失败，请稍后重试"
    }

    /** 当前版本信息 */
    data class BanBenXinXi(val versionCode: Long, val versionName: String)

    /** 远端更新信息 */
    data class GengXinXinXi(
        val versionName: String,
        val versionCode: Long,
        val changelog: String,
        val downloadUrl: String,
        val forceUpdate: Boolean,
        val fileSize: Long
    )

    /** 下载/错误回调（调用方负责切主线程通知 H5） */
    interface HuiDiao {
        /** 下载进度百分比 0-100 */
        fun onJinDu(baiFenBi: Int)

        /** 下载完成，返回安装包绝对路径 */
        fun onXiaZaiWanCheng(luJing: String)

        /** 出错，message 为中文提示 */
        fun onCuoWu(message: String)
    }

    // 下载用共享单例的下载变体（连接 15s / 读 30s）
    private val httpClient = OkHttpDanLi.xiaZaiKeHuDuan
    private val zhengZaiXiaZai = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var dangQianCall: Call? = null

    /** 回调注入（WebAppInterface 设置；传 null 解除） */
    @Volatile
    var huiDiao: HuiDiao? = null

    // ==================== 检查更新 ====================

    /**
     * 检查更新（同步网络请求，由 JS 桥工作线程调用，返回 JSON 字符串）
     * 有更新：{"hasUpdate":true,"versionName":"x.y","versionCode":N,"changelog":"...","downloadUrl":"...","fileSize":N,"forceUpdate":false}
     * 无更新：{"hasUpdate":false,"currentVersion":"x.y"}
     * 失败：{"error":"检查更新失败，请稍后重试"}
     */
    fun jianChaGengXin(): String {
        val dangQian = try {
            huoQuDangQianBanBen()
        } catch (e: Exception) {
            Log.e(TAG, "获取当前版本异常: ${e.message}")
            null
        } ?: return """{"error":"$CHA_ZHAO_SHI_BAI"}"""

        return try {
            val ziDingYiUrl = ApiPeiZhiDanLi.huoQuOtaUrl()
            val xinXi = if (ziDingYiUrl.isNotEmpty()) {
                congZiDingYiYuanPanDuan(ziDingYiUrl, dangQian)
            } else {
                congGitHubPanDuan(dangQian)
            }
            if (xinXi == null) {
                // 无新版本
                JSONObject().apply {
                    put("hasUpdate", false)
                    put("currentVersion", dangQian.versionName)
                }.toString()
            } else {
                JSONObject().apply {
                    put("hasUpdate", true)
                    put("versionName", xinXi.versionName)
                    put("versionCode", xinXi.versionCode)
                    put("changelog", xinXi.changelog)
                    put("downloadUrl", xinXi.downloadUrl)
                    put("fileSize", xinXi.fileSize)
                    put("forceUpdate", xinXi.forceUpdate)
                }.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查更新失败: ${e.message}")
            """{"error":"$CHA_ZHAO_SHI_BAI"}"""
        }
    }

    /**
     * 自定义 OTA 源：期望 JSON {versionCode,versionName,changelog,downloadUrl,forceUpdate}
     * versionCode 不高于当前版本时视为无更新
     */
    private fun congZiDingYiYuanPanDuan(url: String, dangQian: BanBenXinXi): GengXinXinXi? {
        val body = qingQiuWenBen(url) ?: return null
        val json = JSONObject(body)
        val versionCode = json.optLong("versionCode", -1L)
        val downloadUrl = json.optString("downloadUrl", "")
        if (versionCode < 0 || downloadUrl.isEmpty()) {
            Log.e(TAG, "自定义OTA源返回数据不完整")
            return null
        }
        if (versionCode <= dangQian.versionCode) return null
        return GengXinXinXi(
            versionName = json.optString("versionName", ""),
            versionCode = versionCode,
            changelog = json.optString("changelog", ""),
            downloadUrl = downloadUrl,
            forceUpdate = json.optBoolean("forceUpdate", false),
            fileSize = json.optLong("fileSize", 0)
        )
    }

    /**
     * GitHub releases latest 兜底
     * tag_name 提取数字点版本号与当前 versionName 比较；
     * assets[0].browser_download_url 作下载地址，body 作更新日志
     */
    private fun congGitHubPanDuan(dangQian: BanBenXinXi): GengXinXinXi? {
        val body = qingQiuWenBen(GITHUB_OTA_URL) ?: return null
        val json = JSONObject(body)
        val banBenMing = tiQuShuZiBanBen(json.optString("tag_name", ""))
        if (banBenMing.isEmpty()) {
            Log.e(TAG, "GitHub releases tag_name 无法解析")
            return null
        }
        val assets = json.optJSONArray("assets")
        val firstAsset = assets?.optJSONObject(0)
        val downloadUrl = firstAsset?.optString("browser_download_url", "") ?: ""
        if (downloadUrl.isEmpty()) {
            Log.e(TAG, "GitHub releases 未找到安装包附件")
            return null
        }
        if (biJiaoBanBen(banBenMing, dangQian.versionName) <= 0) return null
        return GengXinXinXi(
            versionName = banBenMing,
            versionCode = heChengBanBenMa(banBenMing),
            changelog = json.optString("body", ""),
            downloadUrl = downloadUrl,
            forceUpdate = false,
            fileSize = firstAsset?.optLong("size", 0) ?: 0
        )
    }

    /** 同步 GET 文本（失败返回 null；日志不输出 URL） */
    private fun qingQiuWenBen(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .addHeader("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "更新检查HTTP失败: ${response.code}")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新检查网络失败: ${e.message}")
            null
        }
    }

    // ==================== 下载 ====================

    /**
     * 启动异步下载
     * @return true=下载已启动；false=已有下载在进行中或下载地址无效
     */
    fun kaiShiXiaZai(downloadUrl: String): Boolean {
        if (downloadUrl.isEmpty()) {
            Log.e(TAG, "下载地址为空，无法开始下载")
            return false
        }
        if (!zhengZaiXiaZai.compareAndSet(false, true)) {
            Log.w(TAG, "已有下载任务进行中，忽略重复请求")
            return false
        }
        val muBiaoWenJian = huoQuApkLuJing()
        try {
            // 清掉上次的半成品/旧包
            if (muBiaoWenJian.exists()) muBiaoWenJian.delete()
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()
            val call = httpClient.newCall(request)
            dangQianCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    zhengZaiXiaZai.set(false)
                    dangQianCall = null
                    shanChuBanChengPin(muBiaoWenJian)
                    Log.e(TAG, "下载失败: ${e.message}")
                    huiDiao?.onCuoWu("下载失败，请检查网络后重试")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        zhengZaiXiaZai.set(false)
                        dangQianCall = null
                        shanChuBanChengPin(muBiaoWenJian)
                        Log.e(TAG, "下载HTTP失败: ${response.code}")
                        huiDiao?.onCuoWu("下载失败（服务器无响应），请稍后重试")
                        return
                    }
                    try {
                        val body = response.body
                        val zongDaXiao = body?.contentLength() ?: -1L
                        var yiXiaZai = 0L
                        var shangCiBaiFenBi = -1
                        body?.byteStream()?.use { input ->
                            muBiaoWenJian.outputStream().use { output ->
                                val huanChong = ByteArray(64 * 1024)
                                while (true) {
                                    val duChu = input.read(huanChong)
                                    if (duChu == -1) break
                                    output.write(huanChong, 0, duChu)
                                    yiXiaZai += duChu
                                    if (zongDaXiao > 0) {
                                        val baiFenBi = ((yiXiaZai * 100) / zongDaXiao).toInt()
                                        if (baiFenBi != shangCiBaiFenBi) {
                                            shangCiBaiFenBi = baiFenBi
                                            huiDiao?.onJinDu(baiFenBi.coerceIn(0, 100))
                                        }
                                    }
                                }
                            }
                        }
                        zhengZaiXiaZai.set(false)
                        dangQianCall = null
                        Log.d(TAG, "下载完成: ${muBiaoWenJian.length() / 1024 / 1024}MB")
                        huiDiao?.onXiaZaiWanCheng(muBiaoWenJian.absolutePath)
                    } catch (e: Exception) {
                        zhengZaiXiaZai.set(false)
                        dangQianCall = null
                        shanChuBanChengPin(muBiaoWenJian)
                        Log.e(TAG, "下载写入失败: ${e.message}")
                        huiDiao?.onCuoWu("下载失败，请稍后重试")
                    }
                }
            })
        } catch (e: Exception) {
            zhengZaiXiaZai.set(false)
            dangQianCall = null
            shanChuBanChengPin(muBiaoWenJian)
            Log.e(TAG, "启动下载失败: ${e.message}")
            return false
        }
        return true
    }

    /**
     * 取消下载：断开连接并删除半成品
     * @return true=已取消；false=当前没有进行中的下载
     */
    fun quXiaoXiaZai(): Boolean {
        val call = dangQianCall
        if (!zhengZaiXiaZai.get() && call == null) {
            Log.d(TAG, "当前没有进行中的下载，无需取消")
            return false
        }
        zhengZaiXiaZai.set(false)
        dangQianCall = null
        try {
            call?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "取消下载连接失败: ${e.message}")
        }
        shanChuBanChengPin(huoQuApkLuJing())
        Log.d(TAG, "下载已取消")
        return true
    }

    // ==================== 安装 ====================

    /**
     * 触发安装 APK
     * @return true=已调起系统安装；false=无"安装未知应用"权限（已跳系统设置页）或调起失败
     */
    fun anZhuang(apkLuJing: String): Boolean {
        val wenJian = File(apkLuJing)
        if (!wenJian.exists() || wenJian.length() <= 0) {
            Log.e(TAG, "安装包不存在或为空")
            huiDiao?.onCuoWu("安装包不存在，请重新下载")
            return false
        }
        // Android 8.0+ 需要"安装未知应用"权限，无权限时引导用户去系统设置开启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Log.w(TAG, "无安装未知应用权限，跳转系统设置页")
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "跳转安装权限设置页失败: ${e.message}")
            }
            huiDiao?.onCuoWu("请先允许\"安装未知应用\"权限后重试")
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", wenJian
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "调起安装失败: ${e.message}")
            huiDiao?.onCuoWu("调起安装失败，请重试")
            false
        }
    }

    // ==================== 工具 ====================

    /** 安装包路径：cacheDir/ota/update.apk（manifest 的 cache-path 已覆盖整个 cacheDir） */
    fun huoQuApkLuJing(): File {
        val muLu = File(context.cacheDir, APK_MU_LU_MING)
        if (!muLu.exists()) muLu.mkdirs()
        return File(muLu, APK_WEN_JIAN_MING)
    }

    /** 删除半成品安装包（防御式：删除失败只记日志） */
    private fun shanChuBanChengPin(wenJian: File) {
        try {
            if (wenJian.exists()) wenJian.delete()
        } catch (e: Exception) {
            Log.e(TAG, "删除半成品安装包失败: ${e.message}")
        }
    }

    /**
     * 当前版本（PackageManager 读取；项目 buildConfig=false 不存在 BuildConfig 类）
     */
    fun huoQuDangQianBanBen(): BanBenXinXi? {
        return try {
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            BanBenXinXi(
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkg.longVersionCode
                } else {
                    pkg.versionCode.toLong()
                },
                versionName = pkg.versionName ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取当前版本失败: ${e.message}")
            null
        }
    }

    /** 从 tag_name 提取数字点版本号，如 "v5.9" / "release-5.9.1" -> "5.9.1" */
    private fun tiQuShuZiBanBen(tag: String): String {
        val jieGuo = StringBuilder()
        for (c in tag) {
            if (c.isDigit() || c == '.') jieGuo.append(c)
        }
        return jieGuo.toString().trim('.').replace(Regex("\\.{2,}"), ".")
    }

    /**
     * 数字点分段比较：a > b 返回正数，相等返回 0，a < b 返回负数
     * 缺省段按 0 处理（如 "5.9" 与 "5.9.0" 相等）
     */
    private fun biJiaoBanBen(a: String, b: String): Int {
        val aDuan = a.split(".")
        val bDuan = b.split(".")
        val changDu = maxOf(aDuan.size, bDuan.size)
        for (i in 0 until changDu) {
            val aZhi = aDuan.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val bZhi = bDuan.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (aZhi != bZhi) return aZhi - bZhi
        }
        return 0
    }

    /** 由点分版本号合成可比较版本码（回传 H5 展示用， GitHub 路径无原生 versionCode） */
    private fun heChengBanBenMa(banBen: String): Long {
        val duan = banBen.split(".")
        var ma = 0L
        for (i in 0 until minOf(3, duan.size)) {
            val zhi = duan[i].trim().toIntOrNull() ?: 0
            ma = ma * 1000 + zhi.coerceIn(0, 999)
        }
        return ma
    }
}
