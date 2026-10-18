package com.kantv.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticUpdateAttemptTest {
    @Test fun playbackDeferralAfterDownloadAllowsNextForegroundAttempt() {
        val attempt = AutomaticUpdateAttempt()
        assertTrue(attempt.reserve(returningFromPermission = false))
        assertTrue(attempt.begin())
        assertFalse(attempt.reserve(returningFromPermission = false))
        // The download has completed, but playback defers submitting the verified package.
        attempt.finish()
        attempt.retryWhenForeground()

        assertTrue(attempt.reserve(returningFromPermission = false))
        assertTrue(attempt.begin())
        assertFalse(attempt.begin())
    }

    @Test fun completedAttemptStaysReservedUnlessItWasDeferred() {
        val attempt = AutomaticUpdateAttempt()
        assertTrue(attempt.reserve(returningFromPermission = false))
        assertTrue(attempt.begin())
        attempt.finish()
        assertFalse(attempt.reserve(returningFromPermission = false))
        assertTrue(attempt.reserve(returningFromPermission = true))
        assertTrue(attempt.begin())
    }
}
