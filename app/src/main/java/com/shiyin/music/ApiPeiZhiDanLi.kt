package com.shiyin.music

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * API配置单例
 * 启动时一次性从 assets/api_config.json 读取并缓存，供所有模块共用
 * 避免同一文件被多处独立打开和解析
 */
object ApiPeiZhiDanLi {

    private const val TAG = "ApiPeiZhiDanLi"
    private const val PEI_ZHI_WEN_JIAN = "api_config.json"

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
            Log.d(TAG, "API配置加载成功")
        } catch (e: Exception) {
            Log.e(TAG, "加载API配置失败: ${e.message}")
        }
    }

    /** 获取配置 JSONObject，如果未初始化则返回空 JSONObject */
    fun huoQuPeiZhi(): JSONObject {
        return configJson ?: JSONObject()
    }

    /** 获取指定API配置项的URL */
    fun huoQuUrl(key: String): String {
        return huoQuPeiZhi().optJSONObject(key)?.optString("url", "").orEmpty()
    }

    /** 获取指定API配置项的Token */
    fun huoQuToken(key: String): String {
        return huoQuPeiZhi().optJSONObject(key)?.optString("token", "").orEmpty()
    }

    /** 配置是否已加载成功 */
    fun yiJiaZai(): Boolean = configJson != null
}
