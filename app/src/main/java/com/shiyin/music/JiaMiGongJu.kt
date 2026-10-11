package com.shiyin.music

/**
 * 轻量混淆加密工具（纯函数对象）
 *
 * 契约：
 * - jiami / jiemi 必须互逆：jiemi(jiami(明文)) == 明文，jiami(jiemi(编码)) == 编码；
 * - 不依赖任何 Android API（Base64 为纯 Kotlin 实现），保证 JVM 单测可直接运行，
 *   同时兼容 minSdk 24（java.util.Base64 需 API 26、android.util.Base64 无法在 JVM 单测运行）；
 * - 输入非法（非标准 Base64）时 jiemi 返回空字符串，由调用方走"未配置"防御逻辑。
 *
 * 用途：把内置默认 API 配置（域名/Token）以编码常量形式写进代码，运行时解密使用，
 * 避免明文 API 信息直接暴露在 APK 中。属于混淆层，不追求抗逆向的强加密。
 */
object JiaMiGongJu {

    /** XOR 固定密钥：由项目包名派生，仅用于提高明文可读性门槛 */
    private val MI_YAO: ByteArray = "com.shiyin.music".toByteArray(Charsets.UTF_8)

    /** 标准 Base64 字符表（含 + / 与 = 填充） */
    private const val BASE64_BIAO_ZHUN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 明文 -> 编码串（XOR 逐字节 + Base64） */
    fun jiami(plain: String): String {
        if (plain.isEmpty()) return ""
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val jiaMi = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor MI_YAO[i % MI_YAO.size].toInt()).toByte()
        }
        return base64BianMa(jiaMi)
    }

    /** 编码串 -> 明文；输入非法时返回空字符串（防御式编程） */
    fun jiemi(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val jiaMi = base64JieMa(encoded) ?: return ""
        val bytes = ByteArray(jiaMi.size) { i ->
            (jiaMi[i].toInt() xor MI_YAO[i % MI_YAO.size].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 纯 Kotlin Base64 编码（标准字母表，= 填充，缺失比特位补 0）
     * 意图：与 java.util.Base64 / android.util.Base64 输出完全一致，
     * 但不引入平台 API 依赖，保证单测与 minSdk 24 下行为一致。
     */
    private fun base64BianMa(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            sb.append(BASE64_BIAO_ZHUN[(b0 shr 2) and 0x3F])
            // 第二字符：b1 缺失时低 4 位补 0
            if (b1 == -1) {
                sb.append(BASE64_BIAO_ZHUN[(b0 shl 4) and 0x3F])
                sb.append('=')
                sb.append('=')
            } else {
                sb.append(BASE64_BIAO_ZHUN[((b0 shl 4) or ((b1 shr 4) and 0x0F)) and 0x3F])
                if (b2 == -1) {
                    // 第三字符：b2 缺失时低 2 位补 0
                    sb.append(BASE64_BIAO_ZHUN[(b1 shl 2) and 0x3F])
                    sb.append('=')
                } else {
                    sb.append(BASE64_BIAO_ZHUN[((b1 shl 2) and 0x3F) or ((b2 shr 6) and 0x03)])
                    sb.append(BASE64_BIAO_ZHUN[b2 and 0x3F])
                }
            }
            i += 3
        }
        return sb.toString()
    }

    /**
     * 纯 Kotlin Base64 解码
     * @return 解码字节数组；长度非 4 倍数或含非法字符时返回 null
     */
    private fun base64JieMa(text: String): ByteArray? {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length % 4 != 0) return null

        val values = IntArray(clean.length)
        var tianChong = 0
        for (i in clean.indices) {
            val c = clean[i]
            if (c == '=') {
                values[i] = -1
                tianChong++
            } else {
                val index = BASE64_BIAO_ZHUN.indexOf(c)
                if (index < 0) return null
                values[i] = index
            }
        }
        if (tianChong > 2) return null

        val outLength = clean.length / 4 * 3 - tianChong
        val out = ByteArray(outLength)
        var outIndex = 0
        var i = 0
        while (i + 3 < clean.length) {
            val v0 = values[i]
            val v1 = values[i + 1]
            val v2 = values[i + 2]
            val v3 = values[i + 3]
            // 填充符只允许出现在末尾
            if (v0 < 0 || v1 < 0 || (v2 < 0 && v3 >= 0)) return null
            val triple = (v0 shl 18) or (v1 shl 12) or
                ((if (v2 < 0) 0 else v2) shl 6) or
                (if (v3 < 0) 0 else v3)
            if (outIndex < outLength) out[outIndex++] = ((triple shr 16) and 0xFF).toByte()
            if (outIndex < outLength) out[outIndex++] = ((triple shr 8) and 0xFF).toByte()
            if (outIndex < outLength) out[outIndex++] = (triple and 0xFF).toByte()
            i += 4
        }
        return out
    }
}
