package com.shiyin.music

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * API配置单例
 * 启动时一次性从 assets/api_config.json 读取并缓存，供所有模块共用
 * 避免同一文件被多处独立打开和解析
 *
 * 安全策略（混淆默认配置）：
 * - assets/api_config.json 中敏感字段默认为空字符串，仅作用户覆盖入口；
 * - json 中某字段为空时，回退到内置的"混淆默认配置"——各 API 的 url/token 以
 *   JiaMiGongJu 编码常量形式硬编码，运行时解密后使用，避免明文域名/Token
 *   直接出现在 APK 中；
 * - 日志只输出脱敏 URL（打码域名），严禁输出 token。
 */
object ApiPeiZhiDanLi {

    private const val TAG = "ApiPeiZhiDanLi"
    private const val PEI_ZHI_WEN_JIAN = "api_config.json"

    // ==================== 内置混淆默认配置 ====================
    // 说明：以下均为 JiaMiGongJu.jiami(明文) 的编码结果，明文不落盘；
    // 修改默认值流程：本地算好 jiami(新明文) -> 替换常量 -> 同步更新 JiaMiGongJuTest 断言。
    // internal：仅供同模块单测 JiaMiGongJuTest 读取校验，不对外暴露。
    internal const val MI_LYRIC_API_URL = "CxsZXgBSRlYIHkcODV0IEAoOQk8DAUYOEBdxAQwBAAA="
    internal const val MI_LYRIC_API_TOKEN = "EQwDWSsLDCgqB0AGHz0zFjEAL1o+Dw=="
    internal const val MI_SEARCH_API_URL = "CxsZXgBSRlYIHkcODV0IEAoOQk8DAUYXDBpLDAYWRwITBg=="
    internal const val MI_SEARCH_API_TOKEN = "EQwDWSsLDCgqB0AGHz0zFjEAL1o+Dw=="
    internal const val MI_YUNZHI_API_URL = "CxsZXgBSRlYQG0AXHRoIEwpBDkBcKTkwRhdXBx0AGk0TBx0="
    internal const val MI_YUNZHI_API_TOKEN = "IjgsZjYlWhUvCU8d"
    internal const val MI_QQMUSIC_API_URL = "CxsZXgBSRlYIHkcODV0IEAoOQk8DAUYIGANbHhwQ"
    internal const val MI_QQMUSIC_API_TOKEN = "EQwDWSsLDCgqB0AGHz0zFjEAL1o+Dw=="

    /** API 配置键 -> (编码 url, 编码 token) */
    private val MO_REN_PEI_ZHI: Map<String, Pair<String, String>> = mapOf(
        "lyric_api" to (MI_LYRIC_API_URL to MI_LYRIC_API_TOKEN),
        "search_api" to (MI_SEARCH_API_URL to MI_SEARCH_API_TOKEN),
        "yunzhi_api" to (MI_YUNZHI_API_URL to MI_YUNZHI_API_TOKEN),
        "qqmusic_api" to (MI_QQMUSIC_API_URL to MI_QQMUSIC_API_TOKEN)
    )

    @Volatile
    private var configJson: JSONObject? = null

    /**
     * 初始化配置（Application 或 MainActivity onCreate 时调用一次）
     * @param context 应用上下文
     */
    fun chuShiHua(context: Context) {
        if (configJson != null) return
        try {
            val text = context.assets.open(PEI_ZHI_WEN_JIAN).bufferedReader(Charsets.UTF_8).use { it.readText() }
            configJson = JSONObject(text)
            Log.d(TAG, "API配置加载成功（空字段将回退内置混淆默认值）")
        } catch (e: Exception) {
            Log.e(TAG, "加载API配置失败: ${e.message}（将使用内置混淆默认值）")
        }
    }

    /** 获取配置 JSONObject，如果未初始化则返回空 JSONObject */
    fun huoQuPeiZhi(): JSONObject {
        return configJson ?: JSONObject()
    }

    /**
     * 获取指定API配置项的URL
     * json 中非空则用 json 值（用户覆盖），为空则用内置混淆默认值解密后的值
     */
    fun huoQuUrl(key: String): String {
        val jsonZhi = huoQuPeiZhi().optJSONObject(key)?.optString("url", "").orEmpty()
        if (jsonZhi.isNotEmpty()) return jsonZhi
        return jieMiMoRenZhi(key).first
    }

    /**
     * 获取指定API配置项的Token
     * json 中非空则用 json 值（用户覆盖），为空则用内置混淆默认值解密后的值
     */
    fun huoQuToken(key: String): String {
        val jsonZhi = huoQuPeiZhi().optJSONObject(key)?.optString("token", "").orEmpty()
        if (jsonZhi.isNotEmpty()) return jsonZhi
        return jieMiMoRenZhi(key).second
    }

    /**
     * 获取 OTA 更新检查地址
     * json 中 ota_url 非空时用自定义地址；为空表示走 GitHub releases 兜底
     */
    fun huoQuOtaUrl(): String {
        return huoQuPeiZhi().optString("ota_url", "").orEmpty()
    }

    /**
     * 解密内置默认配置（防御式：解码失败或键不存在时返回空串，
     * 由调用方走既有的"未配置"错误路径，绝不抛出异常）
     */
    private fun jieMiMoRenZhi(key: String): Pair<String, String> {
        val zu = MO_REN_PEI_ZHI[key] ?: return "" to ""
        return try {
            JiaMiGongJu.jiemi(zu.first) to JiaMiGongJu.jiemi(zu.second)
        } catch (e: Exception) {
            Log.e(TAG, "解密内置默认配置失败: key=$key, ${e.message}")
            "" to ""
        }
    }

    /**
     * URL 脱敏：只保留协议头，隐藏域名/路径/参数
     * 用于日志打印，防止 API 域名与查询参数（含 token）泄露
     */
    fun daMaUrl(url: String): String {
        if (url.isEmpty()) return ""
        val idx = url.indexOf("://")
        return if (idx > 0) url.substring(0, idx + 3) + "***" else "***"
    }

    /** 配置是否已加载成功 */
    fun yiJiaZai(): Boolean = configJson != null
}
