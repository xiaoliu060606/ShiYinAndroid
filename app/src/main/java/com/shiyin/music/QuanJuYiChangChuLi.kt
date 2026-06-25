package com.shiyin.music

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局异常处理模块
 * 负责：网络断开重连、播放器崩溃恢复、Service被杀后状态恢复
 */
class QuanJuYiChangChuLi(private val context: MainActivity) {

    companion object {
        private const val TAG = "全局异常处理"
        private const val PREFERENCES_NAME = "bo_fang_zhuang_tai_huan_cun"
        private const val KEY_GE_QU_ID = "ge_qu_id"
        private const val KEY_GE_QU_MING = "ge_qu_ming"
        private const val KEY_GE_SHOU = "ge_shou"
        private const val KEY_FENG_MIAN = "feng_mian"
        private const val KEY_DANG_QIAN_WEI_ZHI = "dang_qian_wei_zhi"
        private const val KEY_ZONG_SHI_CHANG = "zong_shi_chang"
        private const val KEY_SHI_FOU_BO_FANG_ZHONG = "shi_fou_bo_fang_zhong"
        private const val KEY_ZUI_HOU_BAO_CUN_SHI_JIAN = "zui_hou_bao_cun_shi_jian"

        private const val MAX_BENG_KUI_HUI_FU_CI_SHU = 3
        private const val WANG_LUO_HUI_FU_YAN_CHI = 2000L
        private const val ZHUANG_TAI_GUO_QI_SHI_JIAN = 24 * 60 * 60 * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var connectivityManager: ConnectivityManager? = null
    private var wangLuoJianKong: ConnectivityManager.NetworkCallback? = null
    private val wangLuoKeYong = AtomicBoolean(true)
    private val bengKuiHuiFuCiShu = AtomicInteger(0)
    private var wangLuoHuiFuRunnable: Runnable? = null
    private var shangCiBaoCunWeiZhiShiJian = 0L
    private var shangCiBaoCunWeiZhi = 0L

    /** 保存的播放状态数据 */
    data class BaoCunDeBoFangZhuangTai(
        val geQuId: String,
        val geQuMing: String,
        val geShou: String,
        val fengMian: String,
        val dangQianWeiZhi: Long,
        val zongShiChang: Long,
        val shiFouBoFangZhong: Boolean
    )

    /** 全局错误回调接口 */
    interface QuanJuCuoWuHuiDiao {
        /** 网络断开 */
        fun onWangLuoDuanKai()
        /** 网络恢复，可尝试重连 */
        fun onWangLuoHuiFu()
        /** 播放器崩溃，需要恢复 */
        fun onBoFangQiBengKui(zhuangTai: BaoCunDeBoFangZhuangTai)
        /** 服务被杀后重启，需要恢复状态 */
        fun onFuWuChongQiHuiFu(zhuangTai: BaoCunDeBoFangZhuangTai)
    }

    var huiDiao: QuanJuCuoWuHuiDiao? = null

    // ==================== 网络监控 ====================

    /** 启动网络状态监控 */
    fun qiDongWangLuoJianKong() {
        tingZhiWangLuoJianKong()
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        wangLuoJianKong = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasDisconnected = wangLuoKeYong.compareAndSet(false, true)
                Log.d(TAG, "网络已连接")
                if (wasDisconnected) {
                    wangLuoHuiFuRunnable?.let { handler.removeCallbacks(it) }
                    wangLuoHuiFuRunnable = Runnable {
                        huiDiao?.onWangLuoHuiFu()
                        tongZhiH5WangLuoHuiFu()
                        wangLuoHuiFuRunnable = null
                    }
                    handler.postDelayed(wangLuoHuiFuRunnable!!, WANG_LUO_HUI_FU_YAN_CHI)
                }
            }

            override fun onLost(network: Network) {
                wangLuoKeYong.set(false)
                wangLuoHuiFuRunnable?.let { handler.removeCallbacks(it) }
                wangLuoHuiFuRunnable = null
                Log.w(TAG, "网络已断开")
                huiDiao?.onWangLuoDuanKai()
                tongZhiH5WangLuoDuanKai()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                wangLuoKeYong.set(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, wangLuoJianKong!!)
        } catch (e: Exception) {
            Log.e(TAG, "注册网络监控失败: ${e.message}")
        }

        wangLuoKeYong.set(jianChaWangLuoZhuangTai())
        Log.d(TAG, "初始网络状态: ${if (wangLuoKeYong.get()) "可用" else "不可用"}")
    }

    /** 停止网络状态监控 */
    fun tingZhiWangLuoJianKong() {
        wangLuoJianKong?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "注销网络监控失败: ${e.message}")
            }
        }
        wangLuoJianKong = null
    }

    /** 检查当前网络是否可用 */
    fun wangLuoShiFouKeYong(): Boolean {
        return wangLuoKeYong.get()
    }

    /** 检查网络状态（兼容旧版API） */
    private fun jianChaWangLuoZhuangTai(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e(TAG, "检查网络状态失败: ${e.message}")
            false
        }
    }

    /** 通知H5网络断开 */
    private fun tongZhiH5WangLuoDuanKai() {
        try {
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().post {
                context.getWebView().evaluateJavascript(
                    "javascript:if(typeof onWangLuoDuanKai==='function') onWangLuoDuanKai();",
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "通知H5网络断开失败: ${e.message}")
        }
    }

    /** 通知H5网络恢复 */
    private fun tongZhiH5WangLuoHuiFu() {
        try {
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().post {
                context.getWebView().evaluateJavascript(
                    "javascript:if(typeof onWangLuoHuiFu==='function') onWangLuoHuiFu();",
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "通知H5网络恢复失败: ${e.message}")
        }
    }

    // ==================== 播放状态持久化 ====================

    /** 保存当前播放状态（用于崩溃/被杀后恢复） */
    fun baoCunBoFangZhuangTai(
        geQuId: String,
        geQuMing: String,
        geShou: String,
        fengMian: String,
        dangQianWeiZhi: Long,
        zongShiChang: Long,
        shiFouBoFangZhong: Boolean
    ) {
        try {
            val lastGeQuId = sharedPreferences.getString(KEY_GE_QU_ID, "")
            val lastWeiZhi = sharedPreferences.getLong(KEY_DANG_QIAN_WEI_ZHI, -1)
            val lastBoFangZhong = sharedPreferences.getBoolean(KEY_SHI_FOU_BO_FANG_ZHONG, false)
            val positionChanged = kotlin.math.abs(dangQianWeiZhi - lastWeiZhi) > 3000
            val songChanged = geQuId != lastGeQuId
            val stateChanged = shiFouBoFangZhong != lastBoFangZhong
            if (!songChanged && !positionChanged && !stateChanged) return

            sharedPreferences.edit().apply {
                putString(KEY_GE_QU_ID, geQuId)
                putString(KEY_GE_QU_MING, geQuMing)
                putString(KEY_GE_SHOU, geShou)
                putString(KEY_FENG_MIAN, fengMian)
                putLong(KEY_DANG_QIAN_WEI_ZHI, dangQianWeiZhi)
                putLong(KEY_ZONG_SHI_CHANG, zongShiChang)
                putBoolean(KEY_SHI_FOU_BO_FANG_ZHONG, shiFouBoFangZhong)
                putLong(KEY_ZUI_HOU_BAO_CUN_SHI_JIAN, System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存播放状态失败: ${e.message}")
        }
    }

    /** 读取保存的播放状态 */
    fun duQuBaoCunDeZhuangTai(): BaoCunDeBoFangZhuangTai? {
        return try {
            val geQuId = sharedPreferences.getString(KEY_GE_QU_ID, "") ?: ""
            if (geQuId.isEmpty()) return null

            val zuiHouBaoCun = sharedPreferences.getLong(KEY_ZUI_HOU_BAO_CUN_SHI_JIAN, 0)
            if (System.currentTimeMillis() - zuiHouBaoCun > ZHUANG_TAI_GUO_QI_SHI_JIAN) {
                Log.d(TAG, "保存的播放状态已过期，忽略")
                qingChuBaoCunDeZhuangTai()
                return null
            }

            BaoCunDeBoFangZhuangTai(
                geQuId = geQuId,
                geQuMing = sharedPreferences.getString(KEY_GE_QU_MING, "") ?: "",
                geShou = sharedPreferences.getString(KEY_GE_SHOU, "") ?: "",
                fengMian = sharedPreferences.getString(KEY_FENG_MIAN, "") ?: "",
                dangQianWeiZhi = sharedPreferences.getLong(KEY_DANG_QIAN_WEI_ZHI, 0),
                zongShiChang = sharedPreferences.getLong(KEY_ZONG_SHI_CHANG, 0),
                shiFouBoFangZhong = sharedPreferences.getBoolean(KEY_SHI_FOU_BO_FANG_ZHONG, false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取保存状态失败: ${e.message}")
            null
        }
    }

    /** 清除保存的播放状态 */
    fun qingChuBaoCunDeZhuangTai() {
        try {
            sharedPreferences.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "清除保存状态失败: ${e.message}")
        }
    }

    /** 仅更新播放位置（高频调用，轻量写入） */
    fun gengXinBoFangWeiZhi(dangQianWeiZhi: Long) {
        try {
            val now = System.currentTimeMillis()
            if (now - shangCiBaoCunWeiZhiShiJian < 5000 &&
                kotlin.math.abs(dangQianWeiZhi - shangCiBaoCunWeiZhi) < 10000) return
            shangCiBaoCunWeiZhiShiJian = now
            shangCiBaoCunWeiZhi = dangQianWeiZhi
            sharedPreferences.edit().putLong(KEY_DANG_QIAN_WEI_ZHI, dangQianWeiZhi).apply()
        } catch (e: Exception) {
            Log.e(TAG, "更新播放位置失败: ${e.message}")
        }
    }

    // ==================== 播放器崩溃恢复 ====================

    /** 处理播放器崩溃（由NativeAudioPlayer的onError回调触发） */
    fun chuLiBoFangQiBengKui(cuoWuXinXi: String) {
        val currentCount = bengKuiHuiFuCiShu.get()
        Log.e(
            TAG,
            "播放器崩溃: $cuoWuXinXi, 恢复次数: $currentCount/$MAX_BENG_KUI_HUI_FU_CI_SHU"
        )

        if (currentCount >= MAX_BENG_KUI_HUI_FU_CI_SHU) {
            Log.e(TAG, "崩溃恢复次数已达上限，不再尝试")
            bengKuiHuiFuCiShu.set(0)
            tongZhiH5BoFangQiWuFaHuiFu(cuoWuXinXi)
            return
        }

        bengKuiHuiFuCiShu.incrementAndGet()

        val zhuangTai = duQuBaoCunDeZhuangTai()
        if (zhuangTai != null) {
            handler.postDelayed({
                huiDiao?.onBoFangQiBengKui(zhuangTai)
            }, 1500)
        } else {
            tongZhiH5BoFangQiWuFaHuiFu(cuoWuXinXi)
        }
    }

    /** 播放器恢复成功后重置计数器 */
    fun bengKuiHuiFuChengGong() {
        val zhengZaiHuiFu = bengKuiHuiFuCiShu.getAndSet(0) > 0
        if (zhengZaiHuiFu) {
            Log.d(TAG, "播放器崩溃恢复成功，重置计数器")
        }
    }

    /** 通知H5播放器无法恢复 */
    private fun tongZhiH5BoFangQiWuFaHuiFu(cuoWuXinXi: String) {
        try {
            val anQuanXinXi = cuoWuXinXi.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("\n", " ").replace("\r", "")
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().post {
                context.getWebView().evaluateJavascript(
                    "javascript:if(typeof onBoFangQiWuFaHuiFu==='function') onBoFangQiWuFaHuiFu('$anQuanXinXi');",
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "通知H5播放器无法恢复失败: ${e.message}")
        }
    }

    // ==================== Service被杀后恢复 ====================

    /** 检查是否需要从Service重启中恢复 */
    fun shiFouXuYaoHuiFu(): Boolean {
        val zhuangTai = duQuBaoCunDeZhuangTai()
        return zhuangTai != null && zhuangTai.shiFouBoFangZhong
    }

    /** 执行Service被杀后的状态恢复 */
    fun huiFuFuWuZhuangTai() {
        val zhuangTai = duQuBaoCunDeZhuangTai()
        if (zhuangTai == null) {
            Log.d(TAG, "没有保存的播放状态，跳过恢复")
            return
        }

        Log.d(TAG, "开始恢复播放状态: ${zhuangTai.geQuMing} - ${zhuangTai.geShou}")
        huiDiao?.onFuWuChongQiHuiFu(zhuangTai)
    }

    /** 通知H5恢复播放（Service被杀重启后调用） */
    fun tongZhiH5HuiFuBoFang(zhuangTai: BaoCunDeBoFangZhuangTai) {
        try {
            val json = """{"geQuId":"${zhuangTai.geQuId.replace("\\", "\\\\").replace("\"", "\\\"")}","geQuMing":"${zhuangTai.geQuMing.replace("\\", "\\\\").replace("\"", "\\\"")}","geShou":"${zhuangTai.geShou.replace("\\", "\\\\").replace("\"", "\\\"")}","fengMian":"${zhuangTai.fengMian.replace("\\", "\\\\").replace("\"", "\\\"")}","dangQianWeiZhi":${zhuangTai.dangQianWeiZhi},"zongShiChang":${zhuangTai.zongShiChang},"shiFouBoFangZhong":${zhuangTai.shiFouBoFangZhong}}"""
            if (context.isFinishing || context.isDestroyed) return
            context.getWebView().post {
                context.getWebView().evaluateJavascript(
                    "javascript:if(typeof onHuiFuBoFangZhuangTai==='function') onHuiFuBoFangZhuangTai($json);",
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "通知H5恢复播放失败: ${e.message}")
        }
    }

    // ==================== 生命周期管理 ====================

    /** 释放所有资源 */
    fun shiFang() {
        tingZhiWangLuoJianKong()
        handler.removeCallbacksAndMessages(null)
        huiDiao = null
        shangCiBaoCunWeiZhiShiJian = 0L
        shangCiBaoCunWeiZhi = 0L
    }
}
