package com.kantv.app.diagnostics

import com.kantv.app.playback.PlaybackDiagnosticSnapshot
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class NativePictureCopyControlTest {
    @Test fun disabledControlReturnsBeforeAnyHostAccessBitmapCopyOrTimeout() {
        // Bypass Android constructor work only to exercise the real private off branch. Every host,
        // handler and bitmap field is null: reaching any original work would fail this test.
        val sampler = allocateWithoutConstructor(NativeDiagnosticsSampler::class.java)
        val field = NativeDiagnosticsSampler::class.java.getDeclaredField("pictureCopyBlockedReason")
        field.isAccessible = true
        field.set(sampler, NativePictureCopySafetyPolicy.DEBUG_CONTROL)
        val snapshot = allocateWithoutConstructor(PlaybackDiagnosticSnapshot::class.java)
        val method = NativeDiagnosticsSampler::class.java.getDeclaredMethod(
            "samplePictureMotion", PlaybackDiagnosticSnapshot::class.java, java.lang.Long.TYPE,
        )
        method.isAccessible = true
        method.invoke(sampler, snapshot, 30_000L)
        val bitmap = NativeDiagnosticsSampler::class.java.getDeclaredField("pictureCaptureBitmap")
        bitmap.isAccessible = true
        assertNull(bitmap.get(sampler))
        field.set(sampler, NativePictureCopySafetyPolicy.NONE)
        try {
            method.invoke(sampler, snapshot, 30_000L)
            fail("On control must reach the original host predicate")
        } catch (failure: InvocationTargetException) {
            assertTrue(failure.cause is NullPointerException)
        }
    }

    private fun allocateWithoutConstructor(type: Class<*>): Any {
        // Test JVM facility accessed reflectively: Android's compile stub omits sun.misc types.
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null)
        }
        return requireNotNull(unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }
}
