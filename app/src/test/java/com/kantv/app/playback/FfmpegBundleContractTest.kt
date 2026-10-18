package com.kantv.app.playback

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegBundleContractTest {
    @Test
    fun `default ffmpeg artifact matches the fixed corresponding source identity`() {
        val provenance = provenance()
        assertEquals(AAR_NAME, provenance.getProperty("aar.file"))
        assertEquals(AAR_SHA256, provenance.getProperty("aar.sha256"))
        assertEquals("1.11.0", provenance.getProperty("media3.version"))
        assertEquals("2bc207851df311340767e913931ca7b28cab1794", provenance.getProperty("media3.commit"))
        assertEquals("b37bf81a7a9782438e4fe06a249329e279694494", provenance.getProperty("ffmpeg.commit"))
        assertEquals("4b9b0e60597a82048b51567dac72967fa3dff356c7587bd10f03f935f61f8596", provenance.getProperty("source.bundle.sha256"))
        assertEquals(SOURCE_URL, provenance.getProperty("source.bundle.url"))
        assertEquals(DECODERS, provenance.getProperty("decoders").split(","))
        assertEquals(ABIS.toSet(), provenance.getProperty("abis").split(",").toSet())
        assertEquals(AAR_SHA256, bundledFile(AAR_NAME).readBytes().sha256())
    }

    @Test
    fun `verified ffmpeg provides five decoders on all four abis with upstream JNI keep rules`() {
        val provenance = provenance()
        val aar = bundledFile(provenance.getProperty("aar.file"))
        assertEquals(AAR_SHA256, aar.readBytes().sha256())
        ZipFile(aar).use { archive ->
            val libraries = archive.entries().asSequence().map { it.name }
                .filter { it.startsWith("jni/") && it.endsWith(".so") }.toSet()
            assertEquals(ABIS.map { "jni/$it/libffmpegJNI.so" }.toSet(), libraries)
            ABIS.forEach { abi ->
                val entryName = "jni/$abi/libffmpegJNI.so"
                val entry = archive.getEntry(entryName)
                assertNotNull("Missing $entryName", entry)
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                assertEquals("Native artifact changed: $abi", provenance.getProperty("jni.$abi.sha256"), bytes.sha256())
                DECODERS.forEach { decoder ->
                    assertTrue("$decoder decoder missing from $entryName", bytes.containsAscii("ff_${decoder}_decoder"))
                }
            }
            val keepRules = archive.getInputStream(archive.getEntry("proguard.txt")).bufferedReader().use { it.readText() }
            assertTrue(keepRules.contains("native <methods>"))
            assertTrue(keepRules.contains("class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder"))
            assertTrue(keepRules.contains("private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int)"))
        }
    }

    private fun provenance(): Properties = Properties().apply {
        bundledFile("media3-ffmpeg-provenance.properties").inputStream().use { load(it) }
    }

    private fun bundledFile(name: String): File = sequenceOf(File("libs/$name"), File("app/libs/$name"))
        .firstOrNull(File::isFile) ?: error("Missing bundled FFmpeg artifact: $name")

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val target = value.toByteArray(Charsets.US_ASCII)
        if (target.isEmpty() || size < target.size) return false
        var offset = 0
        while (offset <= size - target.size) {
            if (target.indices.all { index -> this[offset + index] == target[index] }) return true
            offset++
        }
        return false
    }

    private companion object {
        const val AAR_NAME = "media3-decoder-ffmpeg-1.11.0-ffmpeg6.0-b37bf81-repro.aar"
        const val AAR_SHA256 = "82024db102de0681cda9d0816c2d4edf660a2b15a2434d4bda00ba6a223a39b5"
        const val SOURCE_URL = "https://github.com/JaHandsome/KanTV/releases/download/ffmpeg-build-1.11.0-b37bf81-20261018/media3-ffmpeg-1.11.0-b37bf81-corresponding-source-r2.tar.gz"
        val DECODERS = listOf("aac", "ac3", "eac3", "mp2", "mp3")
        val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
}
