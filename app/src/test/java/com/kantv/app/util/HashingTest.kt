package com.kantv.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class HashingTest {
    private fun legacySha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun legacySha1Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }

    private fun sha256Digest(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    @Test
    fun sha256HexMatchesLegacyFormattingByteForByte() {
        val samples = listOf(
            ByteArray(0),
            "kantv".toByteArray(Charsets.UTF_8),
            "含 UTF-8 中文 🎬".toByteArray(Charsets.UTF_8),
            ByteArray(1024) { it.toByte() },
        )
        samples.forEach { sample ->
            assertEquals(legacySha256Hex(sample), Hashing.sha256Hex(sample))
            assertEquals(legacySha1Hex(sample), Hashing.sha1Hex(sample))
            assertEquals(Hashing.sha256Hex(sample), Hashing.hex(sha256Digest(sample)))
        }
    }

    @Test
    fun streamingSha256MatchesOneShot() {
        val payload = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val expected = Hashing.sha256Hex(payload)
        assertEquals(expected, payload.inputStream().use { Hashing.sha256Hex(it) })
    }

    @Test
    fun negativeBytesStayTwoLowercaseDigits() {
        // 0xab = -85：与 "%02x".format(byte) / and 0xff 两种旧写法逐一等价
        assertEquals("ab", Hashing.hex(byteArrayOf(0xab.toByte())))
        assertEquals(40, Hashing.sha1Hex("x".toByteArray(Charsets.UTF_8)).length)
        assertEquals(64, Hashing.sha256Hex("x".toByteArray(Charsets.UTF_8)).length)
    }
}
