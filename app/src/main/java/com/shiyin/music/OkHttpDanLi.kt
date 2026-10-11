package com.shiyin.music

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp 单例
 *
 * 契约：全项目共用同一个 OkHttpClient 实例（连接池、分发器、线程池共享），
 * 避免 WebAppInterface / GeCiHuanCun / GeQuZiYuanHuanCun 各自持有一套
 * 连接池+线程池造成资源浪费。
 *
 * 超时策略（与原三处配置保持等价）：
 * - keHuDuan：普通 API 请求，连接 10s / 读 15s / 写 15s；
 * - xiaZaiKeHuDuan：流式下载（歌曲缓存、OTA 安装包），连接 15s / 读 30s，
 *   由 keHuDuan.newBuilder() 派生，连接池与调度器仍共享，不新建系统资源。
 */
object OkHttpDanLi {

    private const val LIAN_JIE_CHAO_SHI = 10L
    private const val DU_QU_CHAO_SHI = 15L
    private const val XIE_RU_CHAO_SHI = 15L
    private const val XIA_ZAI_LIAN_JIE_CHAO_SHI = 15L
    private const val XIA_ZAI_DU_QU_CHAO_SHI = 30L

    /** 通用客户端（普通 JSON API 请求） */
    val keHuDuan: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(LIAN_JIE_CHAO_SHI, TimeUnit.SECONDS)
            .readTimeout(DU_QU_CHAO_SHI, TimeUnit.SECONDS)
            .writeTimeout(XIE_RU_CHAO_SHI, TimeUnit.SECONDS)
            .build()
    }

    /** 下载客户端（歌曲缓存、OTA 安装包等流式下载） */
    val xiaZaiKeHuDuan: OkHttpClient by lazy {
        keHuDuan.newBuilder()
            .connectTimeout(XIA_ZAI_LIAN_JIE_CHAO_SHI, TimeUnit.SECONDS)
            .readTimeout(XIA_ZAI_DU_QU_CHAO_SHI, TimeUnit.SECONDS)
            .build()
    }
}
