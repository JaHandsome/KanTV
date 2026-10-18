package com.kantv.app

import com.kantv.app.ui.TvDirectionalKeyGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDirectionalKeyGateTest {
    @Test
    fun malformedFirstDirectionalRepeatIsAcceptedOnlyOnceUntilRelease() {
        val gate = TvDirectionalKeyGate()

        assertTrue(gate.shouldHandleDown(21, 1))
        assertFalse(gate.shouldHandleDown(21, 2))
        gate.release(21)
        assertTrue(gate.shouldHandleDown(21, 1))
    }

    @Test
    fun ordinaryNewDirectionalPressIsAlwaysAccepted() {
        val gate = TvDirectionalKeyGate()

        assertTrue(gate.shouldHandleDown(21, 0))
        assertFalse(gate.shouldHandleDown(21, 1))
        assertTrue(gate.shouldHandleDown(21, 0))
        assertTrue(gate.shouldHandleDown(22, 1))
    }
}
