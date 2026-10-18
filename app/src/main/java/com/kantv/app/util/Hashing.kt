package com.kantv.app.util

import java.io.InputStream
import java.security.MessageDigest

/**
 * 统一十六进制哈希助手，替代散落的 `MessageDigest.getInstance(...).digest(...)`
 * + `joinToString("") { "%02x".format(it) }` 手抄实现。
 *
 * 字节级等价已验证（JDK 17）：`"%02x".format(byte)`、`"%02x".format(byte.toInt() and 0xff)`
 * 与本助手的手算十六进制输出对全部 256 个字节值逐字节一致，负字节同样两位小写。
 */
object Hashing {
    private const val HEX_DIGITS = "0123456789abcdef"

    /** 小写十六进制 SHA-1，固定 2 字符/字节。 */
    fun sha1Hex(data: ByteArray): String = hexOf(MessageDigest.getInstance("SHA-1").digest(data))

    /** 小写十六进制 SHA-256，固定 2 字符/字节。 */
    fun sha256Hex(data: ByteArray): String = hexOf(MessageDigest.getInstance("SHA-256").digest(data))

    /** 流式 SHA-256：整段读完后一次性摘要，与分块 `digest.update` 等价。 */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return hexOf(digest.digest())
    }

    /** 低层十六进制编码：供需要增量 update（如下载边收边摘要）的调用点复用，勿新造第二份。 */
    fun hex(bytes: ByteArray): String = hexOf(bytes)

    private fun hexOf(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}
