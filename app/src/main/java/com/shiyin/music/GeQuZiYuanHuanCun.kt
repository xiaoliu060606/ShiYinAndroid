package com.shiyin.music

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeQuZiYuanHuanCun(private val context: Context) {

    companion object {
        private const val TAG = "GeQuZiYuanHuanCun"
        private const val HUAN_CUN_MU_LU_NAME = "ge_qu_huan_cun"
        private const val ZUI_DA_HUAN_CUN_DA_XIAO = 1024L * 1024 * 1024 // 1GB
        private const val GUO_QI_HAO_MIAO = 30L * 24 * 60 * 60 * 1000L // 1个月
        private const val QING_LI_JIAN_GE_HAO_MIAO = 30L * 24 * 60 * 60 * 1000L // 1个月
        private const val SHANG_CI_QING_LI_PIAN_HAO = "shang_ci_qing_li_shi_jian"
    }

    private val yinYuanPeiZhi = YinYuanPeiZhi(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val huanCunMuLu = File(context.cacheDir, HUAN_CUN_MU_LU_NAME)
    private val zhengZaiXiaZai = ConcurrentHashMap<String, AtomicBoolean>()
    private val sharedPreferences = context.getSharedPreferences("ge_qu_huan_cun_pei_zhi", Context.MODE_PRIVATE)

    data class HuanCunTiaoMu(
        val geQuId: String,
        val yuanShiUrl: String,
        val benDiWenJian: File,
        val huanCunShiJian: Long,
        val daXiao: Long
    )

    private val huanCunMap = ConcurrentHashMap<String, HuanCunTiaoMu>()
    private val zhengZaiHuoQuUrl = ConcurrentHashMap<String, MutableList<(Boolean, String?) -> Unit>>()

    init {
        if (!huanCunMuLu.exists()) {
            huanCunMuLu.mkdirs()
        }
        chuangJianHuanCunMuLu()
        huiFuHuanCunPeiZhi()
    }

    private fun huiFuHuanCunPeiZhi() {
        try {
            val jsonStr = sharedPreferences.getString("huan_cun_list", null) ?: return
            val jsonArray = org.json.JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                val yuanShiUrl = obj.optString("yuanShiUrl", "")
                val huanCunShiJian = obj.optLong("huanCunShiJian", 0)
                val daXiao = obj.optLong("daXiao", 0)
                if (id.isNotEmpty() && yuanShiUrl.isNotEmpty()) {
                    val wenJianHouZhui = if (yuanShiUrl.contains(".mp3")) ".mp3" else ".cache"
                    val benDiWenJian = File(huanCunMuLu, "songs/${id}$wenJianHouZhui")
                    if (benDiWenJian.exists() && benDiWenJian.length() > 0) {
                        huanCunMap[id] = HuanCunTiaoMu(id, yuanShiUrl, benDiWenJian, huanCunShiJian, daXiao)
                    }
                }
            }
            Log.d(TAG, "恢复缓存配置: ${huanCunMap.size}条")
        } catch (e: Exception) {
            Log.e(TAG, "恢复缓存配置失败: ${e.message}")
        }
    }

    private fun chuangJianHuanCunMuLu() {
        try {
            val geQuMuLu = File(huanCunMuLu, "songs")
            if (!geQuMuLu.exists()) {
                geQuMuLu.mkdirs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建缓存目录失败: ${e.message}")
        }
    }

    fun huoQuHuanCunLuJing(geQuId: String): String? {
        val tiaoMu = huanCunMap[geQuId] ?: return null
        if (!tiaoMu.benDiWenJian.exists() || tiaoMu.benDiWenJian.length() == 0L) {
            huanCunMap.remove(geQuId)
            return null
        }
        return tiaoMu.benDiWenJian.absolutePath
    }

    fun huoQuHuanCunDaXiao(): Long {
        return try {
            huanCunMuLu.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            0L
        }
    }

    fun xiaZaiBingHuanCun(geQuId: String, yuanShiUrl: String, huiDiao: (Boolean, String?) -> Unit) {
        if (zhengZaiXiaZai.containsKey(geQuId)) {
            Log.d(TAG, "歌曲正在下载中: $geQuId")
            huiDiao(false, null)
            return
        }
        zhengZaiXiaZai[geQuId] = AtomicBoolean(true)

        val wenJianHouZhui = if (yuanShiUrl.contains(".mp3")) ".mp3" else ".cache"
        val benDiWenJian = File(huanCunMuLu, "songs/${geQuId}$wenJianHouZhui")

        val request = Request.Builder()
            .url(yuanShiUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Referer", "https://music.163.com/")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                zhengZaiXiaZai.remove(geQuId)
                Log.e(TAG, "下载失败: $geQuId, ${e.message}")
                huiDiao?.invoke(false, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    if (!response.isSuccessful) {
                        zhengZaiXiaZai.remove(geQuId)
                        Log.e(TAG, "下载响应失败: $geQuId, HTTP ${response.code}")
                        huiDiao?.invoke(false, null)
                        return
                    }

                    response.body?.byteStream()?.use { inputStream ->
                        benDiWenJian.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    zhengZaiXiaZai.remove(geQuId)
                    val daXiao = benDiWenJian.length()
                    val tiaoMu = HuanCunTiaoMu(
                        geQuId = geQuId,
                        yuanShiUrl = yuanShiUrl,
                        benDiWenJian = benDiWenJian,
                        huanCunShiJian = System.currentTimeMillis(),
                        daXiao = daXiao
                    )
                    huanCunMap[geQuId] = tiaoMu
                    baoCunHuanCunPeiZhi()
                    Log.d(TAG, "缓存成功: $geQuId, 大小: ${daXiao / 1024 / 1024}MB")
                    huiDiao?.invoke(true, benDiWenJian.absolutePath)
                } catch (e: Exception) {
                    zhengZaiXiaZai.remove(geQuId)
                    Log.e(TAG, "下载异常: $geQuId, ${e.message}")
                    huiDiao?.invoke(false, null)
                }
            }
        })
    }

    fun huoQuUrlBingHuanCun(geQuId: String, geQuMing: String, geQuLaiYuan: String = "", huiDiao: (Boolean, String?) -> Unit) {
        val cachedPath = huoQuHuanCunLuJing(geQuId)
        if (cachedPath != null) {
            Log.d(TAG, "使用缓存: $geQuId")
            huiDiao(true, cachedPath)
            return
        }

        // computeIfAbsent 保证原子性：多次同时调用同一个 geQuId 只有第一个触发请求，
        // 后续调用者的 huiDiao 追加到列表中等待请求完成
        val huiDiaoLieBiao = zhengZaiHuoQuUrl.computeIfAbsent(geQuId) { mutableListOf() }
        val shiDiYiGeQingQiuZhe = huiDiaoLieBiao.isEmpty()
        huiDiaoLieBiao.add(huiDiao)
        if (!shiDiYiGeQingQiuZhe) {
            Log.d(TAG, "URL获取中，加入等待队列: $geQuId")
            return
        }

        Log.d(TAG, "歌曲来源: $geQuLaiYuan, 歌曲: $geQuId($geQuMing)")

        // 根据单曲 source 字段路由，不再依赖全局音源配置
        when {
            geQuLaiYuan == "qq" -> {
                val qqUrl = yinYuanPeiZhi.huoQuQqmusicDetailUrl(geQuMing, 1)
                qingQiuQQMusicUrl(qqUrl, geQuId) { chengGong, luJing ->
                    val huiDiaoLieBiao = zhengZaiHuoQuUrl.remove(geQuId) ?: mutableListOf()
                    huiDiaoLieBiao.forEach { it(chengGong, luJing) }
                }
            }
            else -> {
                // wy 或空值，走云智(网易云)API
                qingQiuYunzhiApi(geQuId) { chengGong, luJing ->
                    val huiDiaoLieBiao = zhengZaiHuoQuUrl.remove(geQuId) ?: mutableListOf()
                    huiDiaoLieBiao.forEach { it(chengGong, luJing) }
                }
            }
        }
    }

    /** 云智API请求（通过歌曲ID直接获取） */
    private fun qingQiuYunzhiApi(geQuId: String, huiDiao: (Boolean, String?) -> Unit) {
        val apiUrl = yinYuanPeiZhi.huoQuYunzhiUrl(geQuId)
        Log.d(TAG, "云智API: $apiUrl")

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "云智API失败: ${e.message}")
                huiDiao(false, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            val code = json.optInt("code", -1)
                            // 云智API: code=1 表示成功
                            if (code == 1) {
                                val dataObj = json.optJSONObject("data")
                                if (dataObj != null) {
                                    val songUrl = dataObj.optString("url", "")
                                    if (songUrl.isNotEmpty() && songUrl != "null") {
                                        xiaZaiBingHuanCun(geQuId, songUrl, huiDiao)
                                    } else {
                                        Log.e(TAG, "云智API返回URL为空")
                                        huiDiao(false, null)
                                    }
                                } else {
                                    Log.e(TAG, "云智API返回数据为空")
                                    huiDiao(false, null)
                                }
                            } else {
                                Log.e(TAG, "云智API失败: code=$code")
                                huiDiao(false, null)
                            }
                        } else {
                            Log.e(TAG, "云智API HTTP失败: ${response.code}")
                            huiDiao(false, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "云智API解析失败: ${e.message}")
                        huiDiao(false, null)
                    }
                }
            }
        })
    }

    /** QQ音乐API直接获取URL并下载缓存 */
    private fun qingQiuQQMusicUrl(qqUrl: String, geQuId: String, huiDiao: (Boolean, String?) -> Unit) {
        Log.d(TAG, "QQ音乐获取URL: $qqUrl")
        
        val request = Request.Builder()
            .url(qqUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()
        
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "QQ音乐API失败: ${e.message}")
                huiDiao(false, null)
            }
            
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            if (json.optInt("code", -1) == 200) {
                                val dataObj = json.optJSONObject("data")
                                val playUrl = dataObj?.optString("play_url", "")
                                if (!playUrl.isNullOrEmpty()) {
                                    xiaZaiBingHuanCun(geQuId, playUrl!!, huiDiao)
                                    return
                                }
                            }
                        }
                        Log.e(TAG, "QQ音乐API返回无效数据")
                        huiDiao(false, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "QQ音乐API解析失败: ${e.message}")
                        huiDiao(false, null)
                    }
                }
            }
        })
    }

    /** apicx API请求（两步：搜索→匹配ID→获取详情） */
    private fun qingQiuApicxApi(geQuId: String, geQuMing: String, huiDiao: (Boolean, String?) -> Unit) {
        val souSuoUrl = yinYuanPeiZhi.huoQuApicxSouSuoUrl(geQuMing)
        Log.d(TAG, "apicx搜索: $geQuMing")

        val request = Request.Builder()
            .url(souSuoUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "apicx搜索失败: ${e.message}")
                huiDiao(false, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            val code = json.optInt("code", -1)
                            if (code == 200) {
                                val dataObj = json.optJSONObject("data")
                                if (dataObj != null) {
                                    val songsArray = dataObj.optJSONArray("songs")
                                    if (songsArray != null) {
                                        for (i in 0 until songsArray.length()) {
                                            val song = songsArray.getJSONObject(i)
                                            // 使用optLong避免ID超过Int.MAX_VALUE(2147483647)时溢出
                                            val apiId = song.optLong("id", -1).toString()
                                            val apiIdStr = if (apiId == "-1") song.optString("id", "") else apiId
                                            if (apiIdStr == geQuId || song.optString("id", "") == geQuId) {
                                                val n = song.optInt("n", -1)
                                                if (n > 0) {
                                                    qingQiuApicxXiangQing(geQuMing, n, geQuId, huiDiao)
                                                    return
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Log.e(TAG, "apicx搜索未匹配到歌曲")
                        huiDiao(false, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "apicx搜索解析失败: ${e.message}")
                        huiDiao(false, null)
                    }
                }
            }
        })
    }

    private fun qingQiuApicxXiangQing(geQuMing: String, n: Int, geQuId: String, huiDiao: (Boolean, String?) -> Unit) {
        val xiangQingUrl = yinYuanPeiZhi.huoQuApicxXiangQingUrl(geQuMing, n)
        Log.d(TAG, "apicx详情: n=$n")

        val request = Request.Builder()
            .url(xiangQingUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "apicx详情失败: ${e.message}")
                huiDiao(false, null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            val code = json.optInt("code", -1)
                            if (code == 200) {
                                val dataObj = json.optJSONObject("data")
                                if (dataObj != null && dataObj.optJSONObject("song") != null) {
                                    val songUrl = dataObj.optString("url", "")
                                    if (songUrl.isNotEmpty()) {
                                        xiaZaiBingHuanCun(geQuId, songUrl, huiDiao)
                                        return
                                    }
                                }
                            }
                        }
                        Log.e(TAG, "apicx详情返回无效数据")
                        huiDiao(false, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "apicx详情解析失败: ${e.message}")
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
            Log.e(TAG, "删除缓存失败: ${e.message}")
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

    private fun baoCunHuanCunPeiZhi() {
        try {
            val editor = sharedPreferences.edit()
            val jsonArray = org.json.JSONArray()
            huanCunMap.forEach { (id, tiaoMu) ->
                val obj = org.json.JSONObject()
                obj.put("id", id)
                obj.put("yuanShiUrl", tiaoMu.yuanShiUrl)
                obj.put("huanCunShiJian", tiaoMu.huanCunShiJian)
                obj.put("daXiao", tiaoMu.daXiao)
                jsonArray.put(obj)
            }
            editor.putString("huan_cun_list", jsonArray.toString())
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存缓存配置失败: ${e.message}")
        }
    }

    fun shanChuGeQuHuanCun(geQuId: String) {
        shanChuHuanCun(geQuId)
        baoCunHuanCunPeiZhi()
    }

    fun qingKongHuanCun() {
        val keys = huanCunMap.keys().toList()
        for (key in keys) {
            shanChuHuanCun(key)
        }
        baoCunHuanCunPeiZhi()
    }
}
