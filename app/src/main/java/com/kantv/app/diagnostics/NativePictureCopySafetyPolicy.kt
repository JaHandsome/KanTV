package com.kantv.app.diagnostics

/** PixelCopy isolation reproduced GPU translation faults on this exact Android 11 device tuple.
 * Keep both SurfaceView and Window readbacks disabled until changed firmware passes real-device
 * readback testing. Destination bitmap size and the Java timeout do not bound native GPU work.
 * This policy only limits diagnostic observation; it never changes decoding or playback.
 */
internal object NativePictureCopySafetyPolicy {
    const val NONE = "NONE"
    const val DEVICE_GPU_READBACK_UNSAFE = "DEVICE_GPU_READBACK_UNSAFE"
    const val DEBUG_CONTROL = "DEBUG_CONTROL"

    fun blockedReason(
        model: String?,
        hardware: String?,
        sdkInt: Int,
        debugControlDisabled: Boolean,
    ): String = when {
        model.equals("BA001", ignoreCase = true) && hardware.equals("amlogic", ignoreCase = true) && sdkInt == 30 ->
            DEVICE_GPU_READBACK_UNSAFE
        debugControlDisabled -> DEBUG_CONTROL
        else -> NONE
    }
}
