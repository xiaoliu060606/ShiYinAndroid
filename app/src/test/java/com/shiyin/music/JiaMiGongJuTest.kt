package com.shiyin.music

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JiaMiGongJu 密钥正确性单测（JVM 单测，无 Android 依赖）
 *
 * 双重验证：
 * 1. ApiPeiZhiDanLi 内置的每一组混淆常量，解密结果必须等于预期明文
 *    （含新云智密钥 AWAHEM3lFgap 的编码常量）；
 * 2. 每一组明文 jiami 后的结果必须与内置常量完全一致，
 *    并覆盖 round-trip 与非法输入防御。
 *
 * 说明：本文件是"新密钥正确写入且解密无误"的唯一编译期证据，
 * 位于 app/src/test 不会打进 APK，因此预期明文只出现在这里。
 */
class JiaMiGongJuTest {

    /** 内置混淆常量与预期明文对照（与 ApiPeiZhiDanLi 常量一一对应） */
    private data class HunXiaoChangLiang(
        val mingCheng: String,
        val bianMa: String,
        val yuQiMingWen: String
    )

    private val changLiangLieBiao = listOf(
        HunXiaoChangLiang(
            "歌词API地址",
            ApiPeiZhiDanLi.MI_LYRIC_API_URL,
            "https://apicx.asia/api/wyy_lyric"
        ),
        HunXiaoChangLiang(
            "歌词API令牌",
            ApiPeiZhiDanLi.MI_LYRIC_API_TOKEN,
            "rcnwXceQCinkjNZuRoBtMg"
        ),
        HunXiaoChangLiang(
            "搜索API地址",
            ApiPeiZhiDanLi.MI_SEARCH_API_URL,
            "https://apicx.asia/api/netease.api"
        ),
        HunXiaoChangLiang(
            "搜索API令牌",
            ApiPeiZhiDanLi.MI_SEARCH_API_TOKEN,
            "rcnwXceQCinkjNZuRoBtMg"
        ),
        HunXiaoChangLiang(
            "云智API地址",
            ApiPeiZhiDanLi.MI_YUNZHI_API_URL,
            "https://yunzhiapi.cn/API/yyjhss.php"
        ),
        HunXiaoChangLiang(
            "云智API令牌（新密钥）",
            ApiPeiZhiDanLi.MI_YUNZHI_API_TOKEN,
            "AWAHEM3lFgap"
        ),
        HunXiaoChangLiang(
            "QQ音乐API地址",
            ApiPeiZhiDanLi.MI_QQMUSIC_API_URL,
            "https://apicx.asia/api/qqmusic"
        ),
        HunXiaoChangLiang(
            "QQ音乐API令牌",
            ApiPeiZhiDanLi.MI_QQMUSIC_API_TOKEN,
            "rcnwXceQCinkjNZuRoBtMg"
        )
    )

    /** 内置混淆常量解密结果必须等于预期明文（含新云智密钥） */
    @Test
    fun huanCunChangLiangJieMiZhengQue() {
        for (xiang in changLiangLieBiao) {
            assertEquals(
                "内置混淆常量解密结果与预期不一致: ${xiang.mingCheng}",
                xiang.yuQiMingWen,
                JiaMiGongJu.jiemi(xiang.bianMa)
            )
        }
    }

    /** 每一组明文 jiami 后必须与内置常量完全一致 */
    @Test
    fun jiamiJieGuoYuNeiZhiChangLiangYiZhi() {
        for (xiang in changLiangLieBiao) {
            assertEquals(
                "明文编码结果与内置常量不一致: ${xiang.mingCheng}",
                xiang.bianMa,
                JiaMiGongJu.jiami(xiang.yuQiMingWen)
            )
        }
    }

    /** round-trip：jiemi(jiami(明文)) == 明文 */
    @Test
    fun jiamiJiemiHuWei() {
        for (xiang in changLiangLieBiao) {
            assertEquals(
                "round-trip 失败: ${xiang.mingCheng}",
                xiang.yuQiMingWen,
                JiaMiGongJu.jiemi(JiaMiGongJu.jiami(xiang.yuQiMingWen))
            )
        }
    }

    /** 边界：空字符串 */
    @Test
    fun kongChuanBianJie() {
        assertEquals("", JiaMiGongJu.jiami(""))
        assertEquals("", JiaMiGongJu.jiemi(""))
    }

    /** 边界：单字符、多字节中文、含特殊字符的 URL */
    @Test
    fun teShuZiFuBianJie() {
        val ceShiWenBen = listOf("A", "a", "0", "{", "中文测试", "https://a.b/c?d=e&f=g")
        for (wenBen in ceShiWenBen) {
            assertEquals(wenBen, JiaMiGongJu.jiemi(JiaMiGongJu.jiami(wenBen)))
        }
    }

    /** 防御：非法输入（非 Base64 / 长度非 4 倍数 / 全是填充符）返回空串而非抛异常 */
    @Test
    fun feiFaShuRuFangYu() {
        assertEquals("", JiaMiGongJu.jiemi("这不是base64!!!"))
        assertEquals("", JiaMiGongJu.jiemi("ABC"))
        assertEquals("", JiaMiGongJu.jiemi("===="))
        assertEquals("", JiaMiGongJu.jiemi("=AAA"))
    }
}
