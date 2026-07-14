package com.shiyin.music

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GeCiHuanCun(private val context: Context) {

    companion object {
        private const val TAG = "GeCiHuanCun"
        private const val HUAN_CUN_MU_LU_NAME = "ge_ci_huan_cun"
        private const val ZUI_DA_HUAN_CUN_DA_XIAO = 100L * 1024 * 1024
        private const val GUO_QI_HAO_MIAO = 7L * 24 * 60 * 60 * 1000L
        private const val QING_LI_JIAN_GE_HAO_MIAO = 7L * 24 * 60 * 60 * 1000L
        private const val SHANG_CI_QING_LI_PIAN_HAO = "ge_ci_shang_ci_qing_li"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val huanCunMuLu = File(context.cacheDir, HUAN_CUN_MU_LU_NAME)
    private val sharedPreferences = context.getSharedPreferences("ge_ci_huan_cun_pei_zhi", Context.MODE_PRIVATE)

    // 启动期一次性从 assets/api_config.json 读入
    @Volatile private var lyricApiUrl: String = ""
    @Volatile private var lyricApiToken: String = ""

    data class GeCiTiaoMu(
        val geQuId: String,
        val benDiWenJian: File,
        val huanCunShiJian: Long,
        val daXiao: Long
    )

    private val huanCunMap = ConcurrentHashMap<String, GeCiTiaoMu>()

    init {
        if (!huanCunMuLu.exists()) huanCunMuLu.mkdirs()
        jiaZaiApiPeiZhi()
        saoMiaoHuanCun()
        dingShiQingLi()
    }

    private fun jiaZaiApiPeiZhi() {
        try {
            lyricApiUrl = ApiPeiZhiDanLi.huoQuUrl("lyric_api")
            lyricApiToken = ApiPeiZhiDanLi.huoQuToken("lyric_api")
            if (lyricApiToken.startsWith("<TBD")) {
                Log.e(TAG, "歌词API token 未配置（仍为占位符），请求将被拒绝")
            }
            Log.d(TAG, "歌词API配置加载: url=$lyricApiUrl, tokenConfigured=${lyricApiToken.isNotEmpty() && !lyricApiToken.startsWith("<TBD")}")
        } catch (e: Exception) {
            Log.e(TAG, "加载API配置失败: ${e.message}")
        }
    }

    private fun saoMiaoHuanCun() {
        try {
            val files = huanCunMuLu.listFiles() ?: return
            var totalSize = 0L
            for (file in files) {
                if (file.isFile && file.name.endsWith(".lrc")) {
                    val geQuId = file.name.removeSuffix(".lrc")
                    val tiaoMu = GeCiTiaoMu(
                        geQuId = geQuId,
                        benDiWenJian = file,
                        huanCunShiJian = file.lastModified(),
                        daXiao = file.length()
                    )
                    huanCunMap[geQuId] = tiaoMu
                    totalSize += file.length()
                }
            }
            Log.d(TAG, "扫描歌词缓存: ${huanCunMap.size}个文件, ${totalSize / 1024}KB")
        } catch (e: Exception) {
            Log.e(TAG, "扫描歌词缓存失败: ${e.message}")
        }
    }

    private fun dingShiQingLi() {
        val now = System.currentTimeMillis()
        val shangCiQingLi = sharedPreferences.getLong(SHANG_CI_QING_LI_PIAN_HAO, 0L)
        if (now - shangCiQingLi < QING_LI_JIAN_GE_HAO_MIAO) return

        Log.d(TAG, "触发7天歌词缓存定期清理")
        qingLiGuoQiHuanCun()
        qingLiHuanCunDaoDaXiao()
        sharedPreferences.edit().putLong(SHANG_CI_QING_LI_PIAN_HAO, now).apply()
    }

    fun huoQuHuanCunGeCi(geQuId: String): String? {
        val tiaoMu = huanCunMap[geQuId] ?: return null
        if (System.currentTimeMillis() - tiaoMu.huanCunShiJian > GUO_QI_HAO_MIAO) {
            shanChuHuanCun(geQuId)
            return null
        }
        return try {
            if (tiaoMu.benDiWenJian.exists()) tiaoMu.benDiWenJian.readText() else null
        } catch (e: Exception) {
            Log.e(TAG, "读取歌词缓存失败: ${e.message}")
            null
        }
    }

    fun baoCunGeCi(geQuId: String, geCiNeiRong: String) {
        try {
            val muBiaoWenJian = File(huanCunMuLu, "$geQuId.lrc")
            muBiaoWenJian.writeText(geCiNeiRong)

            val tiaoMu = GeCiTiaoMu(
                geQuId = geQuId,
                benDiWenJian = muBiaoWenJian,
                huanCunShiJian = System.currentTimeMillis(),
                daXiao = muBiaoWenJian.length()
            )
            huanCunMap[geQuId] = tiaoMu
            qingLiHuanCunDaoDaXiao()
            Log.d(TAG, "保存歌词缓存: $geQuId, ${muBiaoWenJian.length()}字节")
        } catch (e: Exception) {
            Log.e(TAG, "保存歌词缓存失败: ${e.message}")
        }
    }

    fun huoQuGeCiFromAPI(geQuId: String, huiDiao: (Boolean, String?) -> Unit) {
        val cached = huoQuHuanCunGeCi(geQuId)
        if (cached != null) {
            huiDiao(true, cached)
            return
        }

        // 懒加载重试：每次调用都尝试确保配置已加载（ApiPeiZhiDanLi可能在init之后才就绪）
        if (lyricApiUrl.isBlank()) {
            lyricApiUrl = ApiPeiZhiDanLi.huoQuUrl("lyric_api")
            lyricApiToken = ApiPeiZhiDanLi.huoQuToken("lyric_api")
            val peiZhiOk = lyricApiUrl.isNotEmpty()
            if (peiZhiOk) {
                Log.d(TAG, "歌词API配置重新加载成功: url=$lyricApiUrl")
            } else {
                Log.d(TAG, "歌词API配置重新加载: 仍未就绪，等待下次重试")
            }
        }

        if (lyricApiUrl.isBlank() || lyricApiToken.isBlank() || lyricApiToken.startsWith("<TBD")) {
            val yuanYin = when {
                lyricApiUrl.isBlank() -> "URL为空"
                lyricApiToken.isBlank() -> "Token为空"
                else -> "Token为占位符"
            }
            Log.e(TAG, "歌词API未配置，跳过请求: id=$geQuId, 原因=$yuanYin")
            RiZhiGuanLiQi.logError(TAG, "歌词API未配置，跳过请求: id=$geQuId, 原因=$yuanYin")
            huiDiao(false, null)
            return
        }

        val apiUrl = "$lyricApiUrl?id=$geQuId&token=$lyricApiToken"
        Log.d(TAG, "歌词API请求: id=$geQuId, url=${apiUrl.take(80)}...")
        RiZhiGuanLiQi.logInfo(TAG, "歌词API请求: id=$geQuId, url=${apiUrl.take(80)}...")
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "歌词API请求失败: id=$geQuId, error=${e.message}")
                RiZhiGuanLiQi.logError(TAG, "歌词API请求失败: id=$geQuId, error=${e.message}")
                huiDiao(false, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val body = response.body?.string()
                        Log.d(TAG, "歌词API响应: id=$geQuId, httpCode=${response.code}, bodyLength=${body?.length ?: 0}")
                        RiZhiGuanLiQi.logInfo(TAG, "歌词API响应: id=$geQuId, httpCode=${response.code}, bodyLength=${body?.length ?: 0}")
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            val apiCode = json.optInt("code", -1)
                            if (apiCode == 200) {
                                Log.d(TAG, "歌词API成功: id=$geQuId, hasLyric=${json.optJSONObject("data")?.optBoolean("has_lyric", true)}")
                                // 新格式（apicx）：{ code, data:{lyric_data:{lyric, tlyric}, has_lyric} }
                                // 旧消费方期望：{ code, lrc:{lyric}, tlyric:{lyric}, pureMusic }
                                val dataObj = json.optJSONObject("data")
                                val lyricData = dataObj?.optJSONObject("lyric_data")
                                val rawLrc = lyricData?.optString("lyric", "").orEmpty()
                                val rawTlyric = lyricData?.optString("tlyric", "").orEmpty()
                                val hasLyric = dataObj?.optBoolean("has_lyric", true) ?: true
                                val pureMusic = !hasLyric || rawLrc.isEmpty()
                                val lrcObj = JSONObject().put("lyric", rawLrc)
                                val tlyricObj = JSONObject().put("lyric", rawTlyric)
                                val result = JSONObject().apply {
                                    put("code", 200)
                                    put("pureMusic", pureMusic)
                                    put("lrc", lrcObj)
                                    put("tlyric", tlyricObj)
                                }
                                val resultStr = result.toString()
                                baoCunGeCi(geQuId, resultStr)
                                huiDiao(true, resultStr)
                                return
                            }
                            Log.w(TAG, "歌词API业务失败: id=$geQuId, apiCode=$apiCode")
                            RiZhiGuanLiQi.logError(TAG, "歌词API业务失败: id=$geQuId, apiCode=$apiCode")
                        }
                        huiDiao(false, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "歌词API解析失败: ${e.message}")
                        RiZhiGuanLiQi.logError(TAG, "歌词API解析失败: id=$geQuId, error=${e.message}")
                        huiDiao(false, null)
                    }
                }
            }
        })
    }

    private fun shanChuHuanCun(geQuId: String) {
        val tiaoMu = huanCunMap.remove(geQuId) ?: return
        try {
            if (tiaoMu.benDiWenJian.exists()) tiaoMu.benDiWenJian.delete()
        } catch (e: Exception) {
            Log.e(TAG, "删除歌词缓存失败: ${e.message}")
        }
    }

    fun qingLiGuoQiHuanCun() {
        val now = System.currentTimeMillis()
        val iterator = huanCunMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.huanCunShiJian > GUO_QI_HAO_MIAO) {
                shanChuHuanCun(entry.key)
            }
        }
    }

    fun qingLiHuanCunDaoDaXiao() {
        var totalSize = huanCunMap.values.sumOf { it.daXiao }
        if (totalSize <= ZUI_DA_HUAN_CUN_DA_XIAO) return

        val sorted = huanCunMap.values.sortedBy { it.huanCunShiJian }
        for (tiaoMu in sorted) {
            if (totalSize <= ZUI_DA_HUAN_CUN_DA_XIAO * 0.8) break
            shanChuHuanCun(tiaoMu.geQuId)
            totalSize -= tiaoMu.daXiao
        }
        Log.d(TAG, "歌词缓存清理后: ${huanCunMap.size}个文件")
    }
}
