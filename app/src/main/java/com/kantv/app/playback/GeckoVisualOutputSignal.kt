package com.kantv.app.playback

/**
 * Privacy-safe classification of a tiny in-memory compositor sample.
 *
 * Callers retain neither the pixels nor an image-derived fingerprint. Only this fixed enum may be
 * persisted. A flat frame is not accepted as a rendered first frame because Gecko can leave its
 * Android output surface filled with one neutral colour while the DOM video clock keeps moving.
 */
internal enum class GeckoVisualOutputSignal {
    VISIBLE_DETAIL,
    FLAT_NEUTRAL,
    FLAT_OTHER,
    NO_SAMPLE,
}

internal object GeckoVisualOutputClassifier {
    fun classify(argbSamples: IntArray): GeckoVisualOutputSignal {
        if (argbSamples.isEmpty()) return GeckoVisualOutputSignal.NO_SAMPLE
        var minLuma = 255
        var maxLuma = 0
        var minRed = 255
        var maxRed = 0
        var minGreen = 255
        var maxGreen = 0
        var minBlue = 255
        var maxBlue = 0
        var maxChannelSpread = 0
        var lumaTotal = 0L
        argbSamples.forEach { pixel ->
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            val luma = (77 * red + 150 * green + 29 * blue) ushr 8
            minLuma = minOf(minLuma, luma)
            maxLuma = maxOf(maxLuma, luma)
            minRed = minOf(minRed, red)
            maxRed = maxOf(maxRed, red)
            minGreen = minOf(minGreen, green)
            maxGreen = maxOf(maxGreen, green)
            minBlue = minOf(minBlue, blue)
            maxBlue = maxOf(maxBlue, blue)
            maxChannelSpread = maxOf(
                maxChannelSpread,
                maxOf(red, green, blue) - minOf(red, green, blue),
            )
            lumaTotal += luma
        }
        val spatialChannelRange = maxOf(maxRed - minRed, maxGreen - minGreen, maxBlue - minBlue)
        if (maxLuma - minLuma >= MIN_DETAIL_RANGE || spatialChannelRange >= MIN_DETAIL_RANGE) {
            return GeckoVisualOutputSignal.VISIBLE_DETAIL
        }
        val averageLuma = (lumaTotal / argbSamples.size).toInt()
        return if (averageLuma in NEUTRAL_LUMA_RANGE && maxChannelSpread <= MAX_NEUTRAL_CHROMA) {
            GeckoVisualOutputSignal.FLAT_NEUTRAL
        } else {
            GeckoVisualOutputSignal.FLAT_OTHER
        }
    }

    private const val MIN_DETAIL_RANGE = 8
    private const val MAX_NEUTRAL_CHROMA = 3
    private val NEUTRAL_LUMA_RANGE = 112..144
}
