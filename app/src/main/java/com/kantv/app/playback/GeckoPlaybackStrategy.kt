package com.kantv.app.playback

/**
 * Gecko 官方页的固定策略。默认直接使用 Gecko 原页播放器；MSE 镜像只保留给
 * diagnostic 构建的显式 A/B，避免把尚未能稳定解码的本地镜像链路带入正常播放。
 */
enum class GeckoPlaybackStrategy(val usesMseMirror: Boolean) {
    MIRROR(true),
    NATIVE_PAGE(false),
    ;

    companion object {
        const val EXTRA = "kantvGeckoPlaybackStrategy"

        fun resolve(diagnosticsEnabled: Boolean, requested: String?): GeckoPlaybackStrategy =
            if (diagnosticsEnabled && requested == MIRROR.name) MIRROR else NATIVE_PAGE
    }
}
