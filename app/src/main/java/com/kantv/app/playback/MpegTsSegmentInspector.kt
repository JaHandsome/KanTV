package com.kantv.app.playback

/** Lightweight, payload-only MPEG-TS bootstrap check. It never retains media bytes. */
internal object MpegTsSegmentInspector {
    data class Bootstrap(
        val pat: Boolean = false,
        val pmt: Boolean = false,
        val pcr: Boolean = false,
        val h264: Boolean = false,
        val sps: Boolean = false,
        val pps: Boolean = false,
        val idr: Boolean = false,
    ) {
        val independentlyDecodable: Boolean get() = pat && pmt && pcr && sps && pps && idr
        operator fun plus(other: Bootstrap) = Bootstrap(
            pat || other.pat, pmt || other.pmt, pcr || other.pcr, h264 || other.h264,
            sps || other.sps, pps || other.pps, idr || other.idr,
        )
    }

    fun inspect(bytes: ByteArray): Bootstrap {
        var pat = false; var pmt = false; var pcr = false
        var pmtPid: Int? = null; var videoPid: Int? = null
        var sps = false; var pps = false; var idr = false
        var offset = 0
        while (offset + 188 <= bytes.size) {
            if (bytes[offset].toInt() and 0xff != 0x47) { offset++; continue }
            val b1 = bytes[offset + 1].toInt() and 0xff
            val pid = ((b1 and 0x1f) shl 8) or (bytes[offset + 2].toInt() and 0xff)
            val payloadStart = b1 and 0x40 != 0
            val adaptation = (bytes[offset + 3].toInt() ushr 4) and 3
            var payload = offset + 4
            if (adaptation == 2 || adaptation == 3) {
                val length = bytes.getOrNull(payload)?.toInt()?.and(0xff) ?: 0
                val flags = bytes.getOrNull(payload + 1)?.toInt()?.and(0xff) ?: 0
                if ((flags and 0x10) != 0) pcr = true
                payload += 1 + length
            }
            if (adaptation == 1 || adaptation == 3) {
                val end = minOf(offset + 188, bytes.size)
                if (payload < end && payloadStart && pid == 0) {
                    val section = payload + 1 + (bytes[payload].toInt() and 0xff)
                    if (section + 12 <= end && bytes[section].toInt() and 0xff == 0) {
                        pat = true
                        pmtPid = ((bytes[section + 10].toInt() and 0x1f) shl 8) or (bytes[section + 11].toInt() and 0xff)
                    }
                } else if (payload < end && payloadStart && pid == pmtPid) {
                    val section = payload + 1 + (bytes[payload].toInt() and 0xff)
                    if (section + 12 <= end && bytes[section].toInt() and 0xff == 2) {
                        pmt = true
                        var es = section + 12 + (((bytes[section + 10].toInt() and 0x0f) shl 8) or (bytes[section + 11].toInt() and 0xff))
                        while (es + 5 <= end) {
                            val type = bytes[es].toInt() and 0xff
                            val elementaryPid = ((bytes[es + 1].toInt() and 0x1f) shl 8) or (bytes[es + 2].toInt() and 0xff)
                            val infoLength = ((bytes[es + 3].toInt() and 0x0f) shl 8) or (bytes[es + 4].toInt() and 0xff)
                            if (type == 0x1b) videoPid = elementaryPid
                            es += 5 + infoLength
                        }
                    }
                }
                if (payload < end && pid == videoPid) {
                    var i = payload
                    while (i + 4 < end) {
                        val start = if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) i + 3
                        else if (i + 5 < end && bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 0 && bytes[i + 3].toInt() == 1) i + 4
                        else { i++; continue }
                        when (bytes[start].toInt() and 0x1f) { 7 -> sps = true; 8 -> pps = true; 5 -> idr = true }
                        i = start + 1
                    }
                }
            }
            offset += 188
        }
        return Bootstrap(
            pat = pat,
            pmt = pmt,
            pcr = pcr,
            h264 = videoPid != null,
            sps = sps,
            pps = pps,
            idr = idr,
        )
    }
}
