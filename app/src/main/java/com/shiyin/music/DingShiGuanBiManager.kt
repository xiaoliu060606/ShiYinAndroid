package com.shiyin.music

import android.os.Handler
import android.os.Looper
import android.util.Log

class DingShiGuanBiManager {

    companion object {
        private const val TAG = "DingShiGuanBi"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var daoQiShiJian: Long = 0L
    @Volatile private var yiSheZhi = false
    @Volatile private var boWanZaiTing = false

    private var onDaoJiShiHuiDiao: ((shengYuMiaoShu: Long) -> Unit)? = null
    private var onDaoQiHuiDiao: (() -> Unit)? = null

    private val daoJiShiRunnable = object : Runnable {
        override fun run() {
            if (!yiSheZhi) return
            val now = System.currentTimeMillis()
            val shengYu = (daoQiShiJian - now) / 1000
            if (shengYu <= 0) {
                Log.d(TAG, "定时关闭到期")
                yiSheZhi = false
                daoQiShiJian = 0L
                if (boWanZaiTing) {
                    Log.d(TAG, "播完当前再停模式，等待歌曲播放完成")
                    onDaoJiShiHuiDiao?.invoke(-2)
                } else {
                    Log.d(TAG, "执行暂停")
                    onDaoQiHuiDiao?.invoke()
                    onDaoJiShiHuiDiao?.invoke(0)
                }
            } else {
                onDaoJiShiHuiDiao?.invoke(shengYu)
                handler.postDelayed(this, 1000)
            }
        }
    }

    fun sheZhi(fenZhong: Int, boWanZaiTing: Boolean = false, onDaoJiShi: (Long) -> Unit, onDaoQi: () -> Unit) {
        if (fenZhong <= 0) {
            if (yiSheZhi) {
                qingLi()
                onDaoJiShi(-1)
            }
            return
        }
        qingLi()
        yiSheZhi = true
        this.boWanZaiTing = boWanZaiTing
        onDaoJiShiHuiDiao = onDaoJiShi
        onDaoQiHuiDiao = onDaoQi
        daoQiShiJian = System.currentTimeMillis() + fenZhong.toLong() * 60 * 1000L
        Log.d(TAG, "设置定时关闭: ${fenZhong}分钟后, boWanZaiTing=$boWanZaiTing")
        onDaoJiShi(fenZhong.toLong() * 60L)
        handler.post(daoJiShiRunnable)
    }

    fun quXiao() {
        if (!yiSheZhi) return
        Log.d(TAG, "取消定时关闭")
        qingLi()
        onDaoJiShiHuiDiao?.invoke(-1)
    }

    fun huoQuShengYuMiaoShu(): Long {
        if (!yiSheZhi || daoQiShiJian <= 0) return -1
        val shengYu = (daoQiShiJian - System.currentTimeMillis()) / 1000
        return if (shengYu > 0) shengYu else 0
    }

    fun shiFouYiSheZhi(): Boolean = yiSheZhi

    fun shiFouBoWanZaiTing(): Boolean = boWanZaiTing

    fun zaiGeQuBoWanShiJianCha() {
        if (yiSheZhi && boWanZaiTing) {
            Log.d(TAG, "歌曲播放完成，执行定时暂停")
            qingLi()
            onDaoQiHuiDiao?.invoke()
            onDaoJiShiHuiDiao?.invoke(0)
        }
    }

    fun qingLi() {
        handler.removeCallbacks(daoJiShiRunnable)
        yiSheZhi = false
        boWanZaiTing = false
        daoQiShiJian = 0L
    }

    fun wanQuanQingLi() {
        qingLi()
        onDaoJiShiHuiDiao = null
        onDaoQiHuiDiao = null
    }
}
