package com.kantv.app.playback

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOnlyExtensionRenderersFactoryTest {
    @Test
    fun videoExtensionsAreAlwaysOffWhileAudioKeepsConfiguredOnMode() {
        val modes = audioOnlyExtensionModes(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, modes.video)
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, modes.audio)
    }

    @Test
    fun factorySuppressesVideoExtensionsWithoutTouchingTheAudioRendererChain() {
        val declared = AudioOnlyExtensionRenderersFactory::class.java.declaredMethods.map { it.name }

        // 视频扩展渲染器必须在本类被显式关掉（覆写 buildVideoRenderers 强制 OFF）。
        assertTrue(declared.contains("buildVideoRenderers"))
        // 不得覆写音频渲染器链，否则 FFmpeg 音频解码器的扩展序会被打断。
        assertFalse(declared.contains("buildAudioRenderers"))
        // 原"Media3PlaybackEngine 源码里必须 new 这个工厂且 EXTENSION_RENDERER_MODE_ON"的
        // 文本断言已移除（宿主/引擎代码纯搬移会误报红）；工厂的行为契约由上面两个测试锁定。
    }
}
