package com.shiyin.music

import android.content.Context

/**
 * 音源配置管理
 * 支持切换: yunzhi(云智-网易云), qqmusic(QQ音乐), auto(自动降级)
 */
class YinYuanPeiZhi(context: Context) {

    companion object {
        const val YIN_YUAN_YUNZHI = "yunzhi"
        const val YIN_YUAN_APICX = "apicx"
        const val YIN_YUAN_QQMUSIC = "qqmusic"
        const val YIN_YUAN_ZI_DONG = "auto"

        private const val PREF_NAME = "yin_yuan_pei_zhi"
        private const val KEY_DANG_QIAN_YIN_YUAN = "dang_qian_yin_yuan"
    }

    private val appContext = context.applicationContext
    private val sharedPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // 从 ApiPeiZhiDanLi 读取的配置（懒加载）
    private var yunzhiApiUrl: String = ""
    private var yunzhiToken: String = ""
    private var searchApiUrl: String = ""
    private var searchApiToken: String = ""
    private var qqmusicApiUrl: String = ""
    private var qqmusicApiToken: String = ""
    private var configLoaded = false

    private fun jiaZaiPeiZhi() {
        if (configLoaded) return
        try {
            yunzhiApiUrl = ApiPeiZhiDanLi.huoQuUrl("yunzhi_api")
            yunzhiToken = ApiPeiZhiDanLi.huoQuToken("yunzhi_api")
            searchApiUrl = ApiPeiZhiDanLi.huoQuUrl("search_api")
            searchApiToken = ApiPeiZhiDanLi.huoQuToken("search_api")
            qqmusicApiUrl = ApiPeiZhiDanLi.huoQuUrl("qqmusic_api")
            qqmusicApiToken = ApiPeiZhiDanLi.huoQuToken("qqmusic_api")
            configLoaded = true
            android.util.Log.d("YinYuanPeiZhi", "API配置加载: yunzhi=${yunzhiApiUrl.isNotEmpty()}, search=${searchApiUrl.isNotEmpty()}, qqmusic=${qqmusicApiUrl.isNotEmpty()}")
        } catch (e: Exception) {
            android.util.Log.e("YinYuanPeiZhi", "加载API配置失败: ${e.message}，下次调用将重试")
            // 不设置 configLoaded=true，允许后续重试
        }
    }

    fun huoQuDangQianYinYuan(): String {
        val saved = sharedPrefs.getString(KEY_DANG_QIAN_YIN_YUAN, YIN_YUAN_ZI_DONG) ?: YIN_YUAN_ZI_DONG
        return saved
    }

    fun sheZhiYinYuan(yinYuan: String) {
        sharedPrefs.edit().putString(KEY_DANG_QIAN_YIN_YUAN, yinYuan).apply()
    }

    /**
     * 获取云智API URL（通过歌曲ID获取）
     * @param geQuId 歌曲ID
     */
    fun huoQuYunzhiUrl(geQuId: String): String {
        jiaZaiPeiZhi()
        if (yunzhiApiUrl.isEmpty()) {
            android.util.Log.e("YinYuanPeiZhi", "云智API URL未配置，无法构建请求")
            return ""
        }
        return "$yunzhiApiUrl?id=$geQuId&type=wy&token=$yunzhiToken"
    }

    /**
     * 获取 apicx.asia API搜索URL（用歌名搜索，返回歌曲列表）
     */
    fun huoQuApicxSouSuoUrl(geQuMing: String): String {
        jiaZaiPeiZhi()
        if (searchApiUrl.isEmpty()) {
            android.util.Log.e("YinYuanPeiZhi", "搜索API URL未配置，无法构建请求")
            return ""
        }
        val encodedName = java.net.URLEncoder.encode(geQuMing, "UTF-8")
        return "$searchApiUrl?gm=$encodedName&n&br=lossless&token=$searchApiToken"
    }

    /**
     * 获取 apicx.asia API详情URL
     * @param geQuMing 歌曲名
     * @param n 歌曲在列表中的序号（n字段值）
     */
    fun huoQuApicxXiangQingUrl(geQuMing: String, n: Int): String {
        jiaZaiPeiZhi()
        if (searchApiUrl.isEmpty()) {
            android.util.Log.e("YinYuanPeiZhi", "搜索API URL未配置，无法构建请求")
            return ""
        }
        val encodedName = java.net.URLEncoder.encode(geQuMing, "UTF-8")
        return "$searchApiUrl?gm=$encodedName&n=$n&br=lossless&token=$searchApiToken"
    }

    /**
     * 获取QQ音乐搜索URL
     * @param geQuMing 歌曲名/关键词
     */
    fun huoQuQqmusicSouSuoUrl(geQuMing: String): String {
        jiaZaiPeiZhi()
        if (qqmusicApiUrl.isEmpty()) {
            android.util.Log.e("YinYuanPeiZhi", "QQ音乐API URL未配置，无法构建请求")
            return ""
        }
        val encodedName = java.net.URLEncoder.encode(geQuMing, "UTF-8")
        return "$qqmusicApiUrl?msg=$encodedName&token=$qqmusicApiToken"
    }

    /**
     * 获取QQ音乐详情URL（通过歌名+序号获取播放地址）
     * @param geQuMing 歌曲名
     * @param n 序号（1-20）
     */
    fun huoQuQqmusicDetailUrl(geQuMing: String, n: Int): String {
        jiaZaiPeiZhi()
        if (qqmusicApiUrl.isEmpty()) {
            android.util.Log.e("YinYuanPeiZhi", "QQ音乐API URL未配置，无法构建请求")
            return ""
        }
        val encodedName = java.net.URLEncoder.encode(geQuMing, "UTF-8")
        return "$qqmusicApiUrl?msg=$encodedName&n=$n&token=$qqmusicApiToken"
    }

    /**
     * 获取主URL（根据当前配置的音源）
     * yunzhi: 直接用歌曲ID
     * qqmusic: 用歌名+序号
     */
    fun huoQuZhuUrl(geQuId: String, geQuMing: String): String {
        return when (huoQuDangQianYinYuan()) {
            YIN_YUAN_YUNZHI -> huoQuYunzhiUrl(geQuId)
            else -> huoQuQqmusicDetailUrl(geQuMing, 1)
        }
    }

    /**
     * 获取备用URL（用于自动切换）
     * 主音源是yunzhi时备用qqmusic；反之备用yunzhi
     */
    fun huoQuBeiYongUrl(geQuId: String, geQuMing: String): String {
        return when (huoQuDangQianYinYuan()) {
            YIN_YUAN_YUNZHI -> huoQuQqmusicDetailUrl(geQuMing, 1)
            else -> huoQuYunzhiUrl(geQuId)
        }
    }

    /**
     * 判断当前音源是否需要歌名参数
     * yunzhi只需要歌曲ID，qqmusic需要歌名
     */
    fun xuYaoGeQuMing(): Boolean {
        return huoQuDangQianYinYuan() != YIN_YUAN_YUNZHI
    }
}