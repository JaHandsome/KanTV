package com.kantv.app.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class ObservingDataSourceTest {
    private class FakeSource : DataSource {
        var opened: DataSpec? = null
        var readBuffer: ByteArray? = null
        var readOffset = -1
        var readLength = -1
        var closeCalls = 0
        var result = 4
        var openFailure: IOException? = null
        var readFailure: IOException? = null
        var closeFailure: IOException? = null
        val headers = mapOf("fixed" to listOf("value"))
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = null
        override fun getResponseHeaders() = headers
        override fun open(dataSpec: DataSpec): Long {
            opened = dataSpec
            openFailure?.let { throw it }
            return 73
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readBuffer = buffer; readOffset = offset; readLength = length
            readFailure?.let { throw it }
            return result
        }
        override fun close() { closeCalls++; closeFailure?.let { throw it } }
    }
    // A plain JVM has stubbed Android Uri. Only DataSpec identity is needed: the delegate owns it.
    private fun spec(): DataSpec {
        // Resolve the JVM-only fixture allocator at runtime, outside Android's compile classpath.
        val allocatorClass = Class.forName("sun.misc.Unsafe")
        val allocator = allocatorClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return allocatorClass.getMethod("allocateInstance", Class::class.java)
            .invoke(allocator, DataSpec::class.java) as DataSpec
    }
    private class Harness(val source: FakeSource = FakeSource()) {
        val epoch = NativeTransferEpoch(1, AtomicLong())
        val summaries = mutableListOf<NativeTransferSummary>()
        var nanos = 0L
        var elapsed = 100L
        val wrapper = ObservingDataSource(source, { epoch }, { _, value -> summaries += value },
            { nanos.also { nanos += 1_000 } }, { elapsed.also { elapsed += 10 } })
    }

    @Test fun forwardsIdenticalRequestBufferArgumentsAndResultsWithOneSummary() {
        val h = Harness()
        val spec = spec()
        assertEquals(73L, h.wrapper.open(spec))
        assertSame(spec, h.source.opened)
        assertEquals(1, h.epoch.activeCount.get())
        val buffer = ByteArray(12)
        assertEquals(4, h.wrapper.read(buffer, 2, 8))
        assertSame(buffer, h.source.readBuffer)
        assertEquals(2, h.source.readOffset)
        assertEquals(8, h.source.readLength)
        assertSame(h.source.headers, h.wrapper.responseHeaders)
        assertTrue(h.summaries.isEmpty())
        h.source.result = -1
        assertEquals(-1, h.wrapper.read(buffer, 0, 1))
        h.wrapper.close()
        h.wrapper.close()
        assertEquals(2, h.source.closeCalls)
        assertEquals(0, h.epoch.activeCount.get())
        val summary = h.summaries.single()
        assertEquals("CLOSED_EOF", summary.outcome)
        assertTrue(summary.eofSeen)
        assertEquals(4L, summary.bytes)
        assertEquals(2L, summary.readCallCount)
        assertEquals(1L, summary.openDurationUs)
        assertEquals(2L, summary.readDurationUs)
        assertEquals(1L, summary.closeDurationUs)
    }

    @Test fun successfulCloseWithoutEofDoesNotClaimCompletionOrPartialLoad() {
        val h = Harness()
        h.wrapper.open(spec()); h.wrapper.close()
        assertEquals("CLOSED_NO_EOF", h.summaries.single().outcome)
        assertFalse(h.summaries.single().eofSeen)
    }

    @Test fun delegatedFailuresRemainTheSameExceptionAndAreObservedOnClose() {
        for (outcome in listOf("OPEN_ERROR", "READ_ERROR", "CLOSE_ERROR")) {
            val h = Harness()
            val failure = IOException("fixture")
            when (outcome) {
                "OPEN_ERROR" -> h.source.openFailure = failure
                "READ_ERROR" -> h.source.readFailure = failure
                else -> h.source.closeFailure = failure
            }
            val caught = assertThrows(IOException::class.java) {
                h.wrapper.open(spec())
                h.wrapper.read(ByteArray(1), 0, 1)
                h.wrapper.close()
            }
            assertSame(failure, caught)
            if (outcome != "CLOSE_ERROR") h.wrapper.close()
            assertEquals(outcome, h.summaries.single().outcome)
        }
    }

    @Test fun observerThrowingCannotChangeAnyDelegateOutcome() {
        val source = FakeSource()
        val epoch = NativeTransferEpoch(1, AtomicLong())
        val wrapper = ObservingDataSource(source, { epoch }, { _, _ -> error("observer") },
            { throw IllegalStateException("clock") }, { throw IllegalStateException("clock") })
        assertEquals(73L, wrapper.open(spec()))
        assertEquals(4, wrapper.read(ByteArray(4), 0, 4))
        wrapper.close()
        assertEquals(0, epoch.activeCount.get())
        val failure = IOException("delegate")
        source.openFailure = failure
        assertSame(failure, assertThrows(IOException::class.java) { wrapper.open(spec()) })
        wrapper.close()
        source.openFailure = null
        source.readFailure = failure
        wrapper.open(spec())
        assertSame(failure, assertThrows(IOException::class.java) { wrapper.read(ByteArray(4), 0, 4) })
        wrapper.close()
        val captureFailure = ObservingDataSource(FakeSource(), { error("observer") }, { _, _ -> error("unused") }, { 0 }, { 0 })
        assertEquals(73L, captureFailure.open(spec()))
        assertEquals(4, captureFailure.read(ByteArray(4), 0, 4))
        captureFailure.close()
    }

    @Test fun clockBackwardsIsUnknownAndSignedNanoOriginIsValid() {
        val h = Harness()
        h.wrapper.open(spec())
        h.nanos = -5_000
        h.wrapper.read(ByteArray(4), 0, 4)
        h.elapsed = 50
        h.wrapper.close()
        assertEquals(1L, h.summaries.single().readDurationUs)
        assertEquals(-1L, h.summaries.single().endElapsedMs)
        val values = listOf(10L, 9L, 20L, 19L).iterator()
        val summaries = mutableListOf<NativeTransferSummary>()
        val wrapper = ObservingDataSource(FakeSource(), { h.epoch }, { _, v -> summaries += v }, { values.next() }, { 100 })
        wrapper.open(spec()); wrapper.close()
        assertEquals(-1L, summaries.single().openDurationUs)
        assertEquals(-1L, summaries.single().closeDurationUs)
    }
    @Test fun wrapperJoinsTheExactDelegateRequestWithoutChangingAnyTransferFacts() {
        val h = Harness()
        val request = spec()
        h.wrapper.open(request)
        h.source.result = -1
        h.wrapper.read(ByteArray(1), 0, 1)
        h.wrapper.close()
        h.epoch.mediaRequestJoin.started(1, request, 0)
        h.epoch.mediaRequestJoin.terminal(1, "COMPLETED")
        assertSame(request, h.source.opened)
        assertEquals("MATCHED", h.epoch.mediaRequestJoin.observation.status)
        assertSame(h.summaries.single(), h.epoch.mediaRequestJoin.observation.transfer)
    }

}
