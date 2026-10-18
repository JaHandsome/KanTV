package com.kantv.app.diagnostics

import com.kantv.app.playback.PictureMotion
import com.kantv.app.playback.PictureMotionObservation
import com.kantv.app.playback.PlaybackDiagnosticSnapshot
import com.kantv.app.playback.PlaybackDiagnostics
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativePictureCopySafetyPolicyTest {
    @Test fun exactDeviceTupleBlocksInProductionAndDebugCannotBypassIt() {
        for (debugOff in listOf(false, true)) {
            for ((model, hardware) in listOf("BA001" to "amlogic", "ba001" to "AMLOGIC")) {
                assertEquals(NativePictureCopySafetyPolicy.DEVICE_GPU_READBACK_UNSAFE,
                    NativePictureCopySafetyPolicy.blockedReason(model, hardware, 30, debugOff))
            }
        }
    }

    @Test fun nearbyDevicesAndSdkVersionsKeepNormalReadback() {
        for ((model, hardware, sdk) in listOf(
            Triple("BA002", "amlogic", 30), Triple("BA001-other", "amlogic", 30),
            Triple("BA001", "other", 30), Triple("BA001", "amlogic-other", 30),
            Triple("BA001", "amlogic", 29), Triple("BA001", "amlogic", 31),
            Triple(null, "amlogic", 30), Triple("BA001", null, 30), Triple(null, null, 0),
        )) {
            assertEquals(NativePictureCopySafetyPolicy.NONE,
                NativePictureCopySafetyPolicy.blockedReason(model, hardware, sdk, false))
        }
    }

    @Test fun debugCanOnlyAdditionallyDisableOtherDevices() {
        assertEquals(NativePictureCopySafetyPolicy.DEBUG_CONTROL,
            NativePictureCopySafetyPolicy.blockedReason("BA002", "amlogic", 30, true))
    }

    @Test fun realSamplerSafetyGuardReturnsBeforeAnyHostBitmapCopyOrTimeoutInBothControls() {
        for (debugOff in listOf(false, true)) {
            val reason = NativePictureCopySafetyPolicy.blockedReason("BA001", "amlogic", 30, debugOff)
            val sampler = sampler(reason)
            val snapshot = allocateWithoutConstructor(PlaybackDiagnosticSnapshot::class.java)
            invoke(sampler, "samplePictureMotion", arrayOf(PlaybackDiagnosticSnapshot::class.java, java.lang.Long.TYPE),
                arrayOf(snapshot, 30_000L))
            for (fieldName in listOf("pictureCaptureBitmap", "nativeDiagnosticHandler", "isFullscreen", "pictureCopyTargets")) {
                assertNull(fieldName, field(sampler, fieldName))
            }
            assertEquals(false, field(sampler, "pictureCaptureInFlight"))
            assertEquals(0, field(sampler, "pictureCaptureToken"))
        }
    }

    @Test fun blockedVisualAndImmediateEventNeverReuseEarlierVerifiedPicture() {
        for (reason in listOf(NativePictureCopySafetyPolicy.DEVICE_GPU_READBACK_UNSAFE, NativePictureCopySafetyPolicy.DEBUG_CONTROL)) {
            val sampler = sampler(reason)
            setField(sampler, "pictureMotionObservation", PictureMotionObservation(PictureMotion.MOVING, 123, 7))
            val visual = invoke(sampler, "pictureVisualEvidence") as DiagnosticPlaybackValues.VisualEvidence
            assertBlocked(visual, reason)
            setField(sampler, "diagnostics", PlaybackDiagnostics { 0L })
            val event = invoke(sampler, "currentSnapshotValues") as DiagnosticPlaybackValues
            assertBlocked(event.visual, reason)
        }
    }

    @Test fun allowingReadbackKeepsUnknownUntilObservedRatherThanFakingPicture() {
        val sampler = sampler(NativePictureCopySafetyPolicy.NONE)
        val visual = invoke(sampler, "pictureVisualEvidence") as DiagnosticPlaybackValues.VisualEvidence
        assertEquals("unknown", visual.sampleOutcome)
        assertEquals(-1L, visual.sampleCallbackMs)
        assertEquals(-1L, visual.millisSinceVerified)
        assertEquals("NONE", visual.sampleBlockedReason)
        setField(sampler, "diagnostics", PlaybackDiagnostics { 0L })
        assertEquals(DiagnosticPlaybackValues(), invoke(sampler, "currentSnapshotValues"))
    }

    @Test fun modelRejectsFreeTextAndAnyInventedPictureForBlockedSampling() {
        for (bad in listOf("UNREVIEWED_DEVICE", "arbitrary text")) {
            expectInvalid { DiagnosticPlaybackValues.VisualEvidence(sampleBlockedReason = bad) }
        }
        expectInvalid { DiagnosticPlaybackValues.VisualEvidence(sampleOutcome = "DETAIL",
            sampleBlockedReason = NativePictureCopySafetyPolicy.DEVICE_GPU_READBACK_UNSAFE) }
        expectInvalid { DiagnosticPlaybackValues.VisualEvidence(sampleOutcome = "NO_SAMPLE", sampleCallbackMs = 0,
            sampleBlockedReason = NativePictureCopySafetyPolicy.DEBUG_CONTROL) }
        expectInvalid { DiagnosticPlaybackValues.VisualEvidence(sampleOutcome = "NO_SAMPLE", millisSinceVerified = 1,
            sampleBlockedReason = NativePictureCopySafetyPolicy.DEVICE_GPU_READBACK_UNSAFE) }
    }

    @Test fun actualProducerExportsSchema22PixelSafetyAndUserReportFixtures() {
        assertEquals(22, DIAGNOSTIC_SCHEMA_VERSION)
        val output = File("build/outputs/schema22-wire-fixtures").apply { mkdirs() }
        for ((name, reason, kind) in listOf(
            Triple("device-blocked-sample.json", NativePictureCopySafetyPolicy.DEVICE_GPU_READBACK_UNSAFE, DiagnosticEntryKind.SAMPLE),
            Triple("device-blocked-user.json", NativePictureCopySafetyPolicy.DEVICE_GPU_READBACK_UNSAFE, DiagnosticEntryKind.USER_SOURCE_REPORT),
            Triple("debug-blocked-sample.json", NativePictureCopySafetyPolicy.DEBUG_CONTROL, DiagnosticEntryKind.SAMPLE),
        )) {
            val visual = invoke(sampler(reason), "pictureVisualEvidence") as DiagnosticPlaybackValues.VisualEvidence
            val json = DiagnosticLogEntry(kind, 1, DiagnosticPlaybackValues(visual = visual)).toJson()
            assertEquals(reason, json.getString("visualSampleBlockedReason"))
            assertEquals("NO_SAMPLE", json.getString("visualSampleOutcome"))
            File(output, name).writeText(json.toString())
        }
        val unknown = DiagnosticLogEntry(DiagnosticEntryKind.SAMPLE, 1, DiagnosticPlaybackValues()).toJson()
        assertEquals("unknown", unknown.getString("visualSampleBlockedReason"))
        File(output, "unknown-sample.json").writeText(unknown.toString())
    }

    private fun assertBlocked(visual: DiagnosticPlaybackValues.VisualEvidence, reason: String) {
        assertEquals("NO_SAMPLE", visual.sampleOutcome)
        assertEquals(-1L, visual.sampleCallbackMs)
        assertEquals(-1L, visual.millisSinceVerified)
        assertEquals(reason, visual.sampleBlockedReason)
    }

    private fun sampler(reason: String): Any = allocateWithoutConstructor(NativeDiagnosticsSampler::class.java).also {
        setField(it, "pictureCopyBlockedReason", reason)
    }

    private fun allocateWithoutConstructor(type: Class<*>): Any {
        // Android constructor work is deliberately omitted on the test JVM. Real private sampler
        // methods execute; null host/Handler fields fail if the guard reaches any GPU setup work.
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) }
        return requireNotNull(unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(instance)
    }

    private fun setField(instance: Any, name: String, value: Any) = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.set(instance, value)
    }

    private fun invoke(instance: Any, name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any> = emptyArray()): Any? =
        instance.javaClass.getDeclaredMethod(name, *types).let { it.isAccessible = true; it.invoke(instance, *args) }

    private fun expectInvalid(body: () -> Unit) {
        try { body(); fail("Expected invalid fixed evidence") } catch (_: IllegalArgumentException) { }
    }
}
