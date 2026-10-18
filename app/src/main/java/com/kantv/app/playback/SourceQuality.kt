package com.kantv.app.playback

/**
 * Bounded numeric quality rank derived **only** from trusted hub metadata.
 *
 * The hub declares a short quality label per route (`"1080p"`, `"720p"`, `"4k"`, `"8k"`, …). This
 * object turns that label into a comparable number so candidate ordering can prefer the better
 * declared stream. It is deliberately unreachable from any other input:
 * - a resolution is never inferred from a URL, a file name, a MIME type, or a request header;
 * - an unparsable or missing label yields [NONE], which orders last and keeps the original order
 *   relative to other [NONE] entries.
 */
object SourceQuality {
    /** No trusted quality metadata. Orders last; never treated as lowest resolution. */
    const val NONE = -1

    private const val MAX_RANK = 8_000

    private val PROGRESSIVE = Regex("^(\\d{3,4})[pi]$")
    private val ULTRA = Regex("^([48])k$")

    /** Highest resolution first. [qualityOf] is defensive: a stray value can never outrank 8K. */
    val BEST_FIRST: Comparator<Int> = Comparator { left, right ->
        qualityOf(right).compareTo(qualityOf(left))
    }

    fun qualityOf(value: Int): Int = value.coerceIn(NONE, MAX_RANK)

    fun isKnown(value: Int): Boolean = qualityOf(value) != NONE

    /**
     * Parses a hub quality label. `"4k"`/`"8k"` are the trusted UHD labels; a `####p`/`####i`
     * value is used as-is. Anything else is [NONE] rather than a guess.
     */
    fun parse(label: String?): Int {
        val value = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return NONE
        ULTRA.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let { return it * 1_000 }
        val digits = PROGRESSIVE.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull() ?: return NONE
        return digits.takeIf { it in 144..MAX_RANK } ?: NONE
    }
}
