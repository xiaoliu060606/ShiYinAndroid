package com.shiyin.music

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray

/**
 * 悬浮窗歌词管理器
 * 在所有应用顶部显示当前歌词，类似QQ音乐桌面歌词功能
 */
class XuanFuGeCiManager(
    private val context: Context,
    private val playerProvider: () -> NativeAudioPlayer? = { null }
) {

    companion object {
        private const val TAG = "XuanFuGeCi"
        private const val PREF_NAME = "xuafugeci_prefs"
        private const val KEY_ENABLED = "xuanfu_enabled"
        private const val KEY_YAN_SE = "xuanfu_yanse"
        private const val KEY_ZI_HAO = "xuanfu_zihao"
        private const val KEY_TOU_MING_DU = "xuanfu_toumingdu"
        private const val KEY_WEI_ZHI_Y = "xuanfu_weizhi_y"
        private const val KEY_WEI_ZHI_X = "xuanfu_weizhi_x"       // 左右偏移 0~100
        private const val KEY_KUAN_DU = "xuanfu_kuandu"             // 宽度百分比 10~100
        private const val KEY_DUI_QI = "xuanfu_duiqi"               // 对齐: left/center
        private const val SYNC_INTERVAL_MS = 200L
        private const val DEFAULT_TEXT = "加载歌词中..."

        fun youXuanFuChuangQuanXian(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }
    }

    private var windowManager: WindowManager? = null
    private var xuanFuView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var sharedPreferences: SharedPreferences? = null
    private var isShowing = false

    private val syncHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    // 歌词缓存：按时间戳（毫秒）排序的 Pair<time, text> 列表
    private var geCiLieBiao: List<Pair<Long, String>> = emptyList()
    private var lastLyricText: String? = null
    // 上次播放状态，用于检测暂停/恢复过渡
    private var wasPlaying = false

    fun chuShiHua() {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val isKaiQi = sharedPreferences?.getBoolean(KEY_ENABLED, false) ?: false
        if (isKaiQi && youXuanFuChuangQuanXian(context)) {
            kaiQi()
        }
        Log.d(TAG, "悬浮窗歌词初始化，开启状态: $isKaiQi")
    }

    fun kaiQi() {
        if (xuanFuView != null && isShowing) return
        if (!youXuanFuChuangQuanXian(context)) {
            Log.w(TAG, "没有悬浮窗权限")
            return
        }

        val yanSe = sharedPreferences?.getString(KEY_YAN_SE, "#1DB954") ?: "#1DB954"
        val ziHao = sharedPreferences?.getFloat(KEY_ZI_HAO, 16f) ?: 16f
        val touMingDu = sharedPreferences?.getInt(KEY_TOU_MING_DU, 90) ?: 90
        val weiZhiY = sharedPreferences?.getInt(KEY_WEI_ZHI_Y, 300) ?: 300
        val weiZhiX = sharedPreferences?.getInt(KEY_WEI_ZHI_X, 50) ?: 50
        val kuanDu = sharedPreferences?.getInt(KEY_KUAN_DU, 80) ?: 80
        val duiQi = sharedPreferences?.getString(KEY_DUI_QI, "center") ?: "center"

        val screenWidth = context.resources.displayMetrics.widthPixels
        val viewWidth = (screenWidth * kuanDu / 100f).toInt()

        xuanFuView = TextView(context).apply {
            // 如果有缓存的歌词，立即显示；否则显示默认提示
            text = if (geCiLieBiao.isNotEmpty() && geCiLieBiao[0].second.isNotEmpty()) geCiLieBiao[0].second else DEFAULT_TEXT
            setTextColor(Color.parseColor(yanSe))
            textSize = ziHao
            alpha = touMingDu / 100f
            gravity = if (duiQi == "left") Gravity.START else Gravity.CENTER
            // 完全透明背景，不显示任何色块
            setBackgroundColor(Color.TRANSPARENT)
            val hPadding = (12 * context.resources.displayMetrics.density).toInt()
            val vPadding = (4 * context.resources.displayMetrics.density).toInt()
            setPadding(hPadding, vPadding, hPadding, vPadding)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 计算水平偏移：weiZhiX 0~100 映射到实际 x 坐标
        val maxX = screenWidth - viewWidth
        val offsetX = (maxX * weiZhiX / 100f).toInt()

        layoutParams = WindowManager.LayoutParams(
            viewWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = offsetX
            y = weiZhiY
        }

        try {
            windowManager?.addView(xuanFuView, layoutParams)
            isShowing = true
            sharedPreferences?.edit()?.putBoolean(KEY_ENABLED, true)?.apply()
            // 初始化播放状态标记，避免首次打开时从0淡入
            wasPlaying = playerProvider()?.isPlayingState() ?: false
            kaiShiTongBu()
            Log.d(TAG, "悬浮窗歌词已开启")
        } catch (e: Exception) {
            Log.e(TAG, "开启悬浮窗失败: ${e.message}")
        }
    }

    fun guanBi() {
        if (xuanFuView != null && isShowing) {
            try {
                windowManager?.removeView(xuanFuView)
            } catch (e: Exception) {
                Log.e(TAG, "关闭悬浮窗失败: ${e.message}")
            }
            xuanFuView = null
            isShowing = false
            Log.d(TAG, "悬浮窗歌词已关闭")
        }
        tingZhiTongBu()
        sharedPreferences?.edit()?.putBoolean(KEY_ENABLED, false)?.apply()
    }

    /**
     * H5 切歌/加载歌词时调用，传入完整歌词数组 JSON
     * 格式: [{"time": 毫秒, "text": "歌词文本"}, ...]
     */
    fun sheZhiGeCiLieBiao(json: String) {
        try {
            val array = JSONArray(json)
            val list = mutableListOf<Pair<Long, String>>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val time = obj.optLong("time", -1L)
                val text = obj.optString("text", "")
                if (time >= 0) {
                    list.add(time to text)
                }
            }
            list.sortBy { it.first }
            geCiLieBiao = list
            lastLyricText = null
            Log.d(TAG, "歌词已缓存，共 ${list.size} 行")
            // 立即刷新一次当前歌词
            gengXinXianShi()
        } catch (e: Exception) {
            Log.e(TAG, "解析歌词数据失败: ${e.message}")
            geCiLieBiao = emptyList()
        }
    }

    /** 启动原生同步 Handler，按播放进度自动刷新歌词 */
    private fun kaiShiTongBu() {
        tingZhiTongBu()
        syncRunnable = object : Runnable {
            override fun run() {
                gengXinXianShi()
                syncHandler.postDelayed(this, SYNC_INTERVAL_MS)
            }
        }
        syncHandler.post(syncRunnable!!)
        Log.d(TAG, "歌词同步已启动")
    }

    /** 停止原生同步 Handler */
    private fun tingZhiTongBu() {
        syncRunnable?.let { syncHandler.removeCallbacks(it) }
        syncRunnable = null
    }

    /** 根据 NativeAudioPlayer 当前进度刷新悬浮窗歌词 */
    private fun gengXinXianShi() {
        val player = playerProvider() ?: return
        val isPlaying = player.isPlayingState()

        // 检测暂停→恢复播放：淡入
        if (isPlaying && !wasPlaying) {
            Log.d(TAG, "检测到恢复播放，淡入歌词")
            val touMingDu = sharedPreferences?.getInt(KEY_TOU_MING_DU, 90) ?: 90
            xuanFuView?.post {
                xuanFuView?.animate()?.cancel()
                xuanFuView?.alpha = 0f
                xuanFuView?.animate()?.alpha(touMingDu / 100f)?.setDuration(500)?.start()
            }
        }

        // 检测播放→暂停：淡出
        if (!isPlaying && wasPlaying) {
            Log.d(TAG, "检测到暂停，淡出歌词")
            xuanFuView?.post {
                xuanFuView?.animate()?.cancel()
                xuanFuView?.animate()?.alpha(0f)?.setDuration(500)?.start()
            }
        }

        wasPlaying = isPlaying

        if (!isPlaying) return

        val position = player.getCurrentPosition()
        val text = findCurrentLyric(position)
        if (text != lastLyricText) {
            Log.d(TAG, "歌词切换 pos=$position -> \"$text\"")
            xuanFuView?.post {
                xuanFuView?.text = text
            }
            lastLyricText = text
        }
    }

    /** 二分查找当前进度对应的歌词行 */
    private fun findCurrentLyric(position: Long): String {
        if (geCiLieBiao.isEmpty()) return DEFAULT_TEXT
        // 早于第一行时预热显示第一行，避免歌曲开头显示默认文案
        if (position < geCiLieBiao[0].first) return geCiLieBiao[0].second

        var left = 0
        var right = geCiLieBiao.size - 1
        var index = -1
        while (left <= right) {
            val mid = (left + right) ushr 1
            if (geCiLieBiao[mid].first <= position) {
                index = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }
        return if (index >= 0) geCiLieBiao[index].second else DEFAULT_TEXT
    }

    /**
     * 清空歌词缓存（切歌时调用），悬浮窗显示"加载歌词中..."
     */
    fun qingLiGeCi() {
        geCiLieBiao = emptyList()
        lastLyricText = null
        xuanFuView?.post { xuanFuView?.text = DEFAULT_TEXT }
        Log.d(TAG, "歌词已清空")
    }

    fun sheZhiYanSe(yanSe: String) {
        sharedPreferences?.edit()?.putString(KEY_YAN_SE, yanSe)?.apply()
        xuanFuView?.post { xuanFuView?.setTextColor(Color.parseColor(yanSe)) }
    }

    fun sheZhiZiHao(ziHao: Float) {
        sharedPreferences?.edit()?.putFloat(KEY_ZI_HAO, ziHao)?.apply()
        xuanFuView?.post { xuanFuView?.textSize = ziHao }
    }

    fun sheZhiTouMingDu(touMingDu: Int) {
        sharedPreferences?.edit()?.putInt(KEY_TOU_MING_DU, touMingDu)?.apply()
        xuanFuView?.post { xuanFuView?.alpha = touMingDu / 100f }
    }

    fun sheZhiWeiZhi(y: Int) {
        sharedPreferences?.edit()?.putInt(KEY_WEI_ZHI_Y, y)?.apply()
        chongXinBuJu()
    }

    /** 设置左右位置 0~100 */
    fun sheZhiZuoYou(zuoYou: Int) {
        sharedPreferences?.edit()?.putInt(KEY_WEI_ZHI_X, zuoYou)?.apply()
        chongXinBuJu()
    }

    /** 设置宽度百分比 10~100 */
    fun sheZhiKuanDu(kuanDu: Int) {
        sharedPreferences?.edit()?.putInt(KEY_KUAN_DU, kuanDu)?.apply()
        chongXinBuJu()
    }

    /** 设置对齐方式: "left" 或 "center" */
    fun sheZhiDuiQi(duiQi: String) {
        sharedPreferences?.edit()?.putString(KEY_DUI_QI, duiQi)?.apply()
        xuanFuView?.post {
            xuanFuView?.gravity = if (duiQi == "left") Gravity.START else Gravity.CENTER
        }
    }

    /** 根据最新配置重新布局悬浮窗 */
    private fun chongXinBuJu() {
        if (layoutParams == null || xuanFuView == null) return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val kuanDu = sharedPreferences?.getInt(KEY_KUAN_DU, 80) ?: 80
        val weiZhiX = sharedPreferences?.getInt(KEY_WEI_ZHI_X, 50) ?: 50
        val weiZhiY = sharedPreferences?.getInt(KEY_WEI_ZHI_Y, 300) ?: 300
        val viewWidth = (screenWidth * kuanDu / 100f).toInt()
        val maxX = screenWidth - viewWidth
        val offsetX = (maxX * weiZhiX / 100f).toInt()

        layoutParams!!.width = viewWidth
        layoutParams!!.x = offsetX
        layoutParams!!.y = weiZhiY
        try {
            windowManager?.updateViewLayout(xuanFuView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "重新布局失败: ${e.message}")
        }
    }

    fun shiFouKaiQi(): Boolean = isShowing

    fun shiFang() {
        guanBi()
        windowManager = null
    }
}
