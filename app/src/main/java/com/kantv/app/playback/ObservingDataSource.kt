package com.kantv.app.playback

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal fun interface NativeNanoClock { fun nanoTime(): Long }

/** Transparent DataSource wall-clock observation. Request identity is used only by the bounded diagnostic join. */
@OptIn(markerClass = [UnstableApi::class])
internal class ObservingDataSource(
    private val delegate: DataSource,
    private val captureEpoch: () -> NativeTransferEpoch?,
    private val summary: (NativeTransferEpoch, NativeTransferSummary) -> Unit,
    private val nanoTime: NativeNanoClock = NativeNanoClock { System.nanoTime() },
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : DataSource {
    private var epoch: NativeTransferEpoch? = null
    private var sequence = -1L
    private var joinedRequest: NativeMediaRequestJoin.Request? = null
    private var active = false
    private var startElapsedMs = -1L
    private var openNs = 0L
    private var readNs = 0L
    private var closeNs = 0L
    private var readCalls = 0L
    private var bytes = 0L
    private var eof = false
    private var failureOutcome: String? = null

    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        // Observe protocol misuse without correcting it or changing the delegate's behavior.
        if (active) finish("INVALIDATED")
        epoch = runCatching { captureEpoch() }.getOrNull()
        sequence = epoch?.begin() ?: -1
        joinedRequest = runCatching { epoch?.mediaRequestJoin?.opened(dataSpec) }.getOrNull()
        active = true
        startElapsedMs = safeElapsedRealtime()
        openNs = 0
        readNs = 0
        closeNs = 0
        readCalls = 0
        bytes = 0
        eof = false
        failureOutcome = null
        val start = safeNanoTime()
        try {
            return delegate.open(dataSpec)
        } catch (failure: Throwable) {
            failureOutcome = "OPEN_ERROR"
            throw failure
        } finally {
            openNs = durationNs(start, safeNanoTime())
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val start = safeNanoTime()
        try {
            val result = delegate.read(buffer, offset, length)
            if (result > 0) bytes += result
            if (result == C.RESULT_END_OF_INPUT) eof = true
            return result
        } catch (failure: Throwable) {
            failureOutcome = "READ_ERROR"
            throw failure
        } finally {
            readCalls++
            val duration = durationNs(start, safeNanoTime())
            readNs = if (readNs < 0 || duration < 0 || readNs > Long.MAX_VALUE - duration) -1 else readNs + duration
        }
    }

    override fun close() {
        val start = safeNanoTime()
        try {
            delegate.close()
        } catch (failure: Throwable) {
            failureOutcome = "CLOSE_ERROR"
            throw failure
        } finally {
            closeNs = durationNs(start, safeNanoTime())
            if (active) finish(failureOutcome ?: if (eof) "CLOSED_EOF" else "CLOSED_NO_EOF")
        }
    }

    // Primitive-only on the read path; observer clock failures never replace delegate results.
    private fun safeNanoTime(): Long = try { nanoTime.nanoTime() } catch (_: Throwable) { Long.MIN_VALUE }
    private fun safeElapsedRealtime(): Long = try { elapsedRealtimeMs() } catch (_: Throwable) { -1 }
    private fun durationNs(start: Long, end: Long): Long =
        if (start != Long.MIN_VALUE && end != Long.MIN_VALUE && end - start >= 0) end - start else -1
    private fun micros(durationNs: Long): Long = if (durationNs < 0) -1 else durationNs / 1_000

    private fun finish(outcome: String) {
        active = false
        val observedEpoch = epoch
        epoch = null
        val observedRequest = joinedRequest
        joinedRequest = null
        if (observedEpoch == null) return
        observedEpoch.activeCount.decrementAndGet()
        // A diagnostic delivery failure must not replace a successful read or its original error.
        runCatching {
            val transfer = NativeTransferSummary(
                sequence = sequence,
                outcome = outcome,
                startElapsedMs = startElapsedMs,
                endElapsedMs = safeElapsedRealtime().takeIf { startElapsedMs >= 0 && it >= startElapsedMs } ?: -1,
                openDurationUs = micros(openNs),
                readDurationUs = micros(readNs),
                closeDurationUs = micros(closeNs),
                readCallCount = readCalls,
                bytes = bytes,
                eofSeen = eof,
            )
            if (outcome == "INVALIDATED") {
                observedEpoch.mediaRequestJoin.invalidate()
                return@runCatching
            }
            observedEpoch.mediaRequestJoin.closed(observedRequest, transfer)
            summary(observedEpoch, transfer)
        }
    }
}
