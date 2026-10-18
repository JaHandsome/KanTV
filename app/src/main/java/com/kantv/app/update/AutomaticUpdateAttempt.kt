package com.kantv.app.update

import java.util.concurrent.atomic.AtomicBoolean

/** One automatic attempt per process, unless foreground or playback defers it. */
internal class AutomaticUpdateAttempt {
    private val reserved = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)

    fun reserve(returningFromPermission: Boolean): Boolean =
        returningFromPermission || reserved.compareAndSet(false, true)

    fun begin(): Boolean = inFlight.compareAndSet(false, true)

    fun finish() {
        inFlight.set(false)
    }

    fun retryWhenForeground() {
        reserved.set(false)
    }
}
