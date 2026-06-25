package com.shiyin.music

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class GeQuQieHuanManager(
    private val context: MainActivity,
    private val mediaSessionManager: MediaSessionManager,
    private val geQuZiYuanHuanCun: GeQuZiYuanHuanCun
) {

    companion object {
        private const val TAG = "GeQuQieHuanManager"
    }

    data class BoFangLieBiaoXiang(
        val id: String,
        val name: String,
        val artist: String,
        val pic: String
    )

    private var boFangLieBiao = mutableListOf<BoFangLieBiaoXiang>()
    private var dangQianSuoYin = 0
    private var boFangMoShi = 0
    private var yuJiaZaiChuFa = AtomicBoolean(false)
    private var yuJiaZaiZhong = AtomicBoolean(false)
    @Volatile private var yuJiaZaiGeQuId: String? = null
    @Volatile private var yuJiaZaiLuJing: String? = null
    private var zhengZaiQieGe = AtomicBoolean(false)
    @Volatile private var nativeAudioPlayer: NativeAudioPlayer? = null
    private val qieGeChaoShiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val QIE_GE_CHAO_SHI = 15000L
    @Volatile private var wanBuGeQuInfo: BoFangLieBiaoXiang? = null

    fun sheZhiBoFangQi(player: NativeAudioPlayer?) {
        nativeAudioPlayer = player
    }

    @Synchronized
    fun gengXinBoFangLieBiaoXiangQing(geQuLieBiaoJson: String) {
        try {
            val jsonArray = JSONArray(geQuLieBiaoJson)
            val xinLieBiao = mutableListOf<BoFangLieBiaoXiang>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                xinLieBiao.add(BoFangLieBiaoXiang(
                    id = item.optString("id", ""),
                    name = item.optString("name", ""),
                    artist = item.optString("artist", ""),
                    pic = item.optString("pic", "")
                ))
            }
            boFangLieBiao = xinLieBiao
            val msg = "更新播放列表详情: ${boFangLieBiao.size}首, 当前索引=$dangQianSuoYin"
            Log.d(TAG, msg)
            RiZhiGuanLiQi.logInfo(TAG, msg)
        } catch (e: Exception) {
            val msg = "更新播放列表详情失败: ${e.message}"
            Log.e(TAG, msg)
            RiZhiGuanLiQi.logInfo(TAG, "[错误] $msg")
        }
    }

    @Synchronized
    fun gengXinBoFangSuoYin(suoYin: Int, moShi: Int) {
        dangQianSuoYin = suoYin
        boFangMoShi = moShi
        yuJiaZaiChuFa.set(false)
        Log.d(TAG, "更新索引: $suoYin, 模式: $moShi")
    }

    fun chuLiJinDuBianHua(weiZhi: Long, zongShiChang: Long) {
        if (zongShiChang <= 0) return
        if (yuJiaZaiChuFa.compareAndSet(false, true)) {
            chuFaYuJiaZai()
        }
    }

    private fun chuFaYuJiaZai() {
        if (yuJiaZaiZhong.getAndSet(true)) return

        val jieGuo = jiSuanMuBiao("next")
        if (jieGuo == null) {
            yuJiaZaiZhong.set(false)
            return
        }

        val (xiaYiShou, _) = jieGuo
        yuJiaZaiGeQuId = xiaYiShou.id
        Log.d(TAG, "预加载触发: ${xiaYiShou.name}(${xiaYiShou.id})")

        geQuZiYuanHuanCun.huoQuUrlBingHuanCun(xiaYiShou.id, xiaYiShou.name) { chengGong, luJing ->
            yuJiaZaiZhong.set(false)
            if (chengGong && luJing != null) {
                yuJiaZaiLuJing = luJing
                Log.d(TAG, "预加载成功: ${xiaYiShou.name}, 缓存大小: ${geQuZiYuanHuanCun.huoQuHuanCunDaXiao() / 1024 / 1024}MB")
            } else {
                yuJiaZaiGeQuId = null
                Log.w(TAG, "预加载失败: ${xiaYiShou.name}")
            }
        }
    }

    @Synchronized
    private fun jiSuanMuBiao(fangXiang: String, huLueBoFangMoShi: Boolean = false): Pair<BoFangLieBiaoXiang, Int>? {
        if (boFangLieBiao.isEmpty()) return null
        val shiJiMoShi = if (huLueBoFangMoShi) 0 else boFangMoShi
        val muBiaoSuoYin = when (fangXiang) {
            "next" -> when (shiJiMoShi) {
                1 -> dangQianSuoYin
                2 -> (0 until boFangLieBiao.size).random()
                else -> (dangQianSuoYin + 1) % boFangLieBiao.size
            }
            "previous" -> when (shiJiMoShi) {
                1 -> dangQianSuoYin
                2 -> (0 until boFangLieBiao.size).random()
                else -> (dangQianSuoYin - 1 + boFangLieBiao.size) % boFangLieBiao.size
            }
            else -> dangQianSuoYin
        }
        val muBiao = boFangLieBiao.getOrElse(muBiaoSuoYin) { null } ?: return null
        return Pair(muBiao, muBiaoSuoYin)
    }

    fun qieGe(fangXiang: String, huLueBoFangMoShi: Boolean = false): Boolean {
        val msg1 = "[切歌] 开始: 方向=$fangXiang, 当前索引=$dangQianSuoYin, 模式=$boFangMoShi, 列表大小=${boFangLieBiao.size}"
        Log.d(TAG, msg1)
        RiZhiGuanLiQi.logInfo(TAG, msg1)

        if (!zhengZaiQieGe.compareAndSet(false, true)) {
            val msg2 = "[切歌] 进行中，忽略请求: $fangXiang"
            Log.w(TAG, msg2)
            RiZhiGuanLiQi.logInfo(TAG, msg2)
            return true
        }

        qieGeChaoShiHandler.postDelayed({
            if (zhengZaiQieGe.get()) {
                val msg3 = "切歌超时，强制释放锁"
                Log.w(TAG, msg3)
                RiZhiGuanLiQi.logInfo(TAG, msg3)
                zhengZaiQieGe.set(false)
            }
        }, QIE_GE_CHAO_SHI)

        val jieGuo = jiSuanMuBiao(fangXiang, huLueBoFangMoShi)
        if (jieGuo == null) {
            val msg4 = "[切歌] 计算目标失败，列表大小=${boFangLieBiao.size}，可能为空"
            Log.w(TAG, msg4)
            RiZhiGuanLiQi.logInfo(TAG, msg4)
            zhengZaiQieGe.set(false)
            qieGeChaoShiHandler.removeCallbacksAndMessages(null)
            return false
        }

        val (muBiao, muBiaoSuoYin) = jieGuo
        val msg5 = "[切歌] 目标: ${muBiao.name}(${muBiao.id}), 索引=$muBiaoSuoYin"
        Log.d(TAG, msg5)
        RiZhiGuanLiQi.logInfo(TAG, msg5)

        val yuJiaZaiId = yuJiaZaiGeQuId
        val yuJiaZaiLuJing = yuJiaZaiLuJing

        if (fangXiang == "next" && yuJiaZaiId == muBiao.id && yuJiaZaiLuJing != null) {
            RiZhiGuanLiQi.logInfo(TAG, "[切歌] 使用预加载缓存: ${muBiao.name}")
            qingChuYuJiaZaiZhuangTai()
            qieGeChaoShiHandler.removeCallbacksAndMessages(null)
            context.runOnUiThread {
                boFang(muBiao, muBiaoSuoYin, yuJiaZaiLuJing)
                zhengZaiQieGe.set(false)
            }
            return true
        }

        val huanCunLuJing = geQuZiYuanHuanCun.huoQuHuanCunLuJing(muBiao.id)
        if (huanCunLuJing != null) {
            RiZhiGuanLiQi.logInfo(TAG, "[切歌] 使用本地缓存: ${muBiao.name}")
            qieGeChaoShiHandler.removeCallbacksAndMessages(null)
            context.runOnUiThread {
                boFang(muBiao, muBiaoSuoYin, huanCunLuJing)
                zhengZaiQieGe.set(false)
            }
            return true
        }

        // 缓存未命中，请求H5获取URL
        RiZhiGuanLiQi.logInfo(TAG, "[切歌] 缓存未命中，请求H5获取URL: ${muBiao.name}")
        qingQiuH5Url(muBiao, muBiaoSuoYin)

        return true
    }

    /**
     * 请求H5获取歌曲URL
     */
    private fun qingQiuH5Url(muBiao: BoFangLieBiaoXiang, muBiaoSuoYin: Int) {
        try {
            if (context.isFinishing || context.isDestroyed) {
                zhengZaiQieGe.set(false)
                return
            }
            val json = JSONObject().apply {
                put("id", muBiao.id)
                put("name", muBiao.name)
                put("index", muBiaoSuoYin)
            }
            val escapedJson = json.toString()
                .replace("\\/", "/")  // 修复：JSONObject.toString() 将 / 转义为 \/，必须还原，否则封面URL失效
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            context.getWebView().evaluateJavascript(
                "javascript:if(typeof onNativeRequestUrl==='function') onNativeRequestUrl('$escapedJson');"
            ) { result ->
                Log.d(TAG, "[请求H5] onNativeRequestUrl 回调: 目标=${muBiao.name}(${muBiao.id}), result=$result")
            }
            Log.d(TAG, "[请求H5] 已发送请求: ${muBiao.name}(${muBiao.id}), 索引=$muBiaoSuoYin")
        } catch (e: Exception) {
            Log.e(TAG, "请求H5获取URL失败: ${e.message}")
            zhengZaiQieGe.set(false)
            qieGeChaoShiHandler.removeCallbacksAndMessages(null)
        }
    }

    /**
     * H5返回URL后，原生播放
     */
    fun onH5UrlResponse(geQuId: String, url: String, suoYin: Int) {
        Log.d(TAG, "[H5回调] 收到URL: id=$geQuId, url长度=${url.length}, 索引=$suoYin")
        context.runOnUiThread {
            qieGeChaoShiHandler.removeCallbacksAndMessages(null)
            val muBiao = boFangLieBiao.find { it.id == geQuId }
            if (muBiao != null && url.isNotEmpty()) {
                Log.d(TAG, "[H5回调] URL有效，调用 boFang")
                boFang(muBiao, suoYin, url)
            } else {
                Log.w(TAG, "[H5回调] URL无效或歌曲未找到: muBiao=${muBiao != null}, url空=${url.isEmpty()}")
                // 回退到原生获取
                geQuZiYuanHuanCun.huoQuUrlBingHuanCun(geQuId, muBiao?.name ?: "") { chengGong, luJing ->
                    context.runOnUiThread {
                        if (chengGong && luJing != null && muBiao != null) {
                            boFang(muBiao, suoYin, luJing)
                        } else {
                            Log.e(TAG, "原生获取也失败，切歌失败: $geQuId")
                        }
                        zhengZaiQieGe.set(false)
                    }
                }
                return@runOnUiThread
            }
            zhengZaiQieGe.set(false)
        }
    }

    @Volatile private var dangQianBoFangGeQu: BoFangLieBiaoXiang? = null

    private fun boFang(geQu: BoFangLieBiaoXiang, suoYin: Int, luJing: String) {
        val msg = "[播放] 开始播放: ${geQu.name}(${geQu.id}), 索引=$suoYin"
        Log.d(TAG, msg)
        RiZhiGuanLiQi.logInfo(TAG, msg)
        synchronized(this) {
            dangQianSuoYin = suoYin
        }

        // 切歌时先清空悬浮窗歌词，避免显示上一首歌的旧歌词
        if (context.xuanFuGeCiYiChuShiHua()) {
            context.xuanFuGeCiManager.qingLiGeCi()
        }

        yuJiaZaiChuFa.set(false)
        dangQianBoFangGeQu = geQu

        val player = nativeAudioPlayer
        if (player != null) {
            RiZhiGuanLiQi.logInfo(TAG, "[播放] 已提交到 NativeAudioPlayer")
        } else {
            RiZhiGuanLiQi.logInfo(TAG, "[播放] NativeAudioPlayer为空，切歌失败")
            zhengZaiQieGe.set(false)
            tongZhiH5QieGeShiBai("next")
            return
        }

        mediaSessionManager.updateMetadata(
            title = geQu.name,
            artist = geQu.artist,
            albumArtUrl = geQu.pic,
            durationMs = -1,
            songId = geQu.id
        )

        player.loadAndPlay(luJing, 0)
        mediaSessionManager.requestAudioFocus()

        tongZhiH5QieGe(geQu, suoYin)
        
        // QQ音乐式优化：预加载下一首封面
        yuJiaZaiXiaYiShouFengMian()
    }
    
    /**
     * 预加载下一首歌曲封面（QQ音乐式优化）
     */
    private fun yuJiaZaiXiaYiShouFengMian() {
        try {
            val xiaYiShou = jiSuanMuBiao("next", huLueBoFangMoShi = true)
            if (xiaYiShou != null) {
                val (geQu, _) = xiaYiShou
                if (geQu.pic.isNotEmpty()) {
                    mediaSessionManager.preloadNextAlbumArt(geQu.pic)
                    Log.d(TAG, "[预加载] 已触发下一首封面预加载: ${geQu.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[预加载] 预加载下一首封面失败: ${e.message}")
        }
    }

    fun gengXinBoFangShiChang(shiChang: Long) {
        val geQu = dangQianBoFangGeQu
        if (geQu != null) {
            if (shiChang > 0) {
                mediaSessionManager.updateMetadata(
                    title = geQu.name,
                    artist = geQu.artist,
                    albumArtUrl = geQu.pic,
                    durationMs = shiChang,
                    songId = geQu.id
                )
            }
        } else if (shiChang > 0) {
            // 兜底：nativeLoadAndPlay路径未设置dangQianBoFangGeQu，使用H5传递的歌曲信息
            val info = wanBuGeQuInfo
            if (info != null) {
                mediaSessionManager.updateMetadata(
                    title = info.name,
                    artist = info.artist,
                    albumArtUrl = info.pic,
                    durationMs = shiChang,
                    songId = info.id
                )
                val msg = "更新时长(兜底): ${info.name}, 时长=${shiChang}ms"
                Log.d(TAG, msg)
                RiZhiGuanLiQi.logInfo(TAG, msg)
            }
        }
    }

    fun sheZhiWanBuGeQuInfo(name: String, artist: String, pic: String, id: String) {
        wanBuGeQuInfo = BoFangLieBiaoXiang(id = id, name = name, artist = artist, pic = pic)
        Log.d(TAG, "设置兜底歌曲信息: $name - $artist")
    }

    /**
     * 清除当前播放歌曲引用（H5 nativeLoadAndPlay 路径使用）
     * 防止 gengXinBoFangShiChang 用旧歌曲信息覆盖 H5 已设置的正确元数据
     */
    fun qingChuDangQianBoFangGeQu() {
        dangQianBoFangGeQu = null
        Log.d(TAG, "清除当前播放歌曲引用")
    }

    private fun tongZhiH5QieGe(geQu: BoFangLieBiaoXiang, suoYin: Int, baoLiuJinDu: Boolean = false) {
        try {
            if (context.isFinishing || context.isDestroyed) return
            val json = JSONObject().apply {
                put("id", geQu.id)
                put("name", geQu.name)
                put("artist", geQu.artist)
                put("pic", geQu.pic)
                put("index", suoYin)
                put("playMode", boFangMoShi)
                put("baoLiuJinDu", baoLiuJinDu)
            }
            val escapedJson = json.toString()
                .replace("\\/", "/")  // 修复：JSONObject.toString() 将 / 转义为 \/，必须还原，否则封面URL失效
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            context.getWebView().evaluateJavascript(
                "javascript:if(typeof onYuanShengQieGe==='function') onYuanShengQieGe('$escapedJson');"
            ) { result ->
                Log.d(TAG, "[通知H5] onYuanShengQieGe 回调: ${geQu.name}, result=$result")
            }
            RiZhiGuanLiQi.logInfo(TAG, "[通知H5] 已通知H5切歌: ${geQu.name} - ${geQu.artist}, 索引=$suoYin, 保留进度=$baoLiuJinDu")
        } catch (e: Exception) {
            RiZhiGuanLiQi.logInfo(TAG, "[通知H5] 通知H5切歌失败: ${e.message}")
        }
    }

    private fun tongZhiH5QieGeShiBai(fangXiang: String) {
        try {
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().evaluateJavascript(
                "javascript:window.yuanShengQieGeShiBai=true;",
                null
            )
            Log.w(TAG, "通知H5切歌失败: $fangXiang")
        } catch (e: Exception) {
            Log.e(TAG, "通知H5切歌失败异常: ${e.message}")
        }
    }

    private fun qingChuYuJiaZaiZhuangTai() {
        yuJiaZaiGeQuId = null
        yuJiaZaiLuJing = null
    }

    fun zhongZhiYuJiaZai() {
        yuJiaZaiChuFa.set(false)
        yuJiaZaiZhong.set(false)
        yuJiaZaiGeQuId = null
        yuJiaZaiLuJing = null
        qieGeChaoShiHandler.removeCallbacksAndMessages(null)
        zhengZaiQieGe.set(false)
    }

    @Synchronized
    fun huoQuDangQianSuoYin(): Int = dangQianSuoYin

    /**
     * 同步当前歌曲信息到H5（解决onResume时UI跳回上一首的问题）
     * 注意：此方法用于恢复同步，不重置播放进度
     * 使用 @Synchronized 确保读取 dangQianSuoYin 时能看到 boFang() 写入的最新值，
     * 避免后台切歌恢复时发送错误的旧索引给H5
     */
    @Synchronized
    fun tongBuDangQianGeQuZhiH5() {
        val geQu = dangQianBoFangGeQu ?: return
        tongZhiH5QieGe(geQu, dangQianSuoYin, baoLiuJinDu = true)
    }

    fun huoQuHuanCunLuJing(geQuId: String): String? {
        return geQuZiYuanHuanCun.huoQuHuanCunLuJing(geQuId)
    }

    @Synchronized
    fun huoQuBoFangMoShi(): Int = boFangMoShi

    @Synchronized
    fun lieBiaoShiFouKong(): Boolean = boFangLieBiao.isEmpty()
}
