package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSessionEventGateTest {
    @Test
    fun queuedOldEventsAreDroppedUntilNewMediaBecomesActive() {
        val gate = PlaybackSessionEventGate()
        gate.begin(1)
        gate.activatePending()
        gate.begin(2)

        assertEquals(0, gate.current())

        gate.activatePending()
        assertEquals(2, gate.current())
    }

    @Test
    fun resetDropsLateEvents() {
        val gate = PlaybackSessionEventGate()
        gate.begin(7)
        gate.activatePending()

        gate.reset()

        assertEquals(0, gate.current())
    }
}

class RenderedFirstFrameSessionGateTest {
    @Test
    fun timelineEmptyCallbackUsesOnlyCurrentSessionsRecordedMediaPeriod() {
        val gate = RenderedFirstFrameSessionGate()
        val firstPeriod = Any()
        gate.begin(sessionId = 1L, startedRealtimeMs = 100L)
        gate.record(firstPeriod, sessionId = 1L)

        assertEquals(1L, gate.resolve(null, firstPeriod, eventRealtimeMs = 110L))
        assertEquals(1L, gate.resolve(1L, null, eventRealtimeMs = 110L))
    }

    @Test
    fun delayedOldPeriodCannotCreditNewSession() {
        val gate = RenderedFirstFrameSessionGate()
        val oldPeriod = Any()
        val newPeriod = Any()
        gate.begin(sessionId = 1L, startedRealtimeMs = 100L)
        gate.record(oldPeriod, sessionId = 1L)

        gate.begin(sessionId = 2L, startedRealtimeMs = 200L)
        gate.record(newPeriod, sessionId = 2L)

        assertNull(gate.resolve(null, oldPeriod, eventRealtimeMs = 210L))
        assertNull(gate.resolve(1L, oldPeriod, eventRealtimeMs = 210L))
        assertNull(gate.resolve(2L, newPeriod, eventRealtimeMs = 199L))
        assertEquals(2L, gate.resolve(null, newPeriod, eventRealtimeMs = 210L))
    }
}
