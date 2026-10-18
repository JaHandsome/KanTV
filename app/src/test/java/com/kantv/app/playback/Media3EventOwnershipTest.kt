package com.kantv.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's identity gate in pure form: a Media3 callback may only move the state machine when it
 * names the MediaItem that is actually playing. This is what prevents a previous *candidate* from
 * being mistaken for a new one during a same-session source switch, where the external session id is
 * deliberately unchanged.
 */
class Media3EventOwnershipTest {
    @Test
    fun aCallbackForThePlayingItemIsAccepted() {
        assertEquals(
            7L,
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = "7",
                currentMediaItemMediaId = "7",
                activeSessionId = 7L,
            ),
        )
    }

    @Test
    fun aCompositeMediaItemIdStillMapsToItsExternalSession() {
        assertEquals(7L, Media3EventOwnership.sessionIdFromMediaItemId("7:12"))
        assertEquals(
            7L,
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = "7:12",
                currentMediaItemMediaId = "7:12",
                activeSessionId = 7L,
            ),
        )
        assertNull(Media3EventOwnership.sessionIdFromMediaItemId("invalid:12"))
    }

    @Test
    fun aCallbackFromTheReplacedCandidateIsRejectedEvenWithTheSameSessionId() {
        // Same session id, different MediaItem: this is a late callback from the candidate that was
        // just replaced. Accepting it would close or reopen the new candidate's rebuffer interval.
        assertNull(
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = "7",
                currentMediaItemMediaId = "7:replacement",
                activeSessionId = 7L,
            ),
        )
    }

    @Test
    fun aCallbackForAStoppedSessionIsRejected() {
        assertNull(
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = "7",
                currentMediaItemMediaId = "7",
                activeSessionId = 8L,
            ),
        )
        assertNull(
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = "7",
                currentMediaItemMediaId = "7",
                activeSessionId = 0L,
            ),
        )
    }

    @Test
    fun anEmptyTimelineOrAnEmptyCurrentItemIsNotEvidenceOfOwnership() {
        assertNull(
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = null,
                currentMediaItemMediaId = "7",
                activeSessionId = 7L,
            ),
        )
        assertNull(
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = "7",
                currentMediaItemMediaId = null,
                activeSessionId = 7L,
            ),
        )
        assertNull(
            Media3EventOwnership.owningSessionId(
                eventTimelineMediaId = "not-a-session",
                currentMediaItemMediaId = "not-a-session",
                activeSessionId = 7L,
            ),
        )
    }

    @Test
    fun theFirstFrameGateStillResolvesWhenTheTimelineIsEmpty() {
        val gate = RenderedFirstFrameSessionGate()
        gate.begin(sessionId = 7L, startedRealtimeMs = 1_000L)
        gate.record(periodUid = "period-1", sessionId = 7L)

        val resolved = Media3EventOwnership.firstFrameSessionId(
            owningSessionId = null,
            periodUid = "period-1",
            eventRealtimeMs = 2_000L,
            gate = gate,
        )

        assertEquals(7L, resolved)
        assertNull(
            Media3EventOwnership.firstFrameSessionId(
                owningSessionId = null,
                periodUid = "period-of-an-older-candidate",
                eventRealtimeMs = 2_000L,
                gate = gate,
            ),
        )
        assertFalse(gate.sessionFor("period-of-an-older-candidate") != null)
        assertTrue(gate.sessionFor("period-1") == 7L)
    }

    @Test
    fun aCandidateSwitchForgetsTheReplacedCandidatesPeriodOwnership() {
        val gate = RenderedFirstFrameSessionGate()
        gate.begin(sessionId = 7L, startedRealtimeMs = 1_000L)
        gate.record(periodUid = "period-of-replaced-candidate", sessionId = 7L)
        // Before the switch the old period would have been accepted for this session.
        assertEquals(7L, gate.sessionFor("period-of-replaced-candidate"))

        gate.forgetPeriods()

        // After the switch the same late callback is unrecognisable, so it cannot be credited to the
        // candidate that replaced it - the media item id alone cannot make that distinction.
        assertNull(gate.sessionFor("period-of-replaced-candidate"))
        assertNull(
            Media3EventOwnership.firstFrameSessionId(
                owningSessionId = null,
                periodUid = "period-of-replaced-candidate",
                eventRealtimeMs = 2_000L,
                gate = gate,
            ),
        )
        // The session itself is still active: only the period mapping was fenced.
        gate.record(periodUid = "period-of-new-candidate", sessionId = 7L)
        assertEquals(7L, gate.sessionFor("period-of-new-candidate"))
    }

    @Test
    fun aGenuineMediaItemMismatchIsDistinguishedFromMissingInformation() {
        // A different item than the one playing: this error belongs to the replaced candidate.
        assertTrue(
            Media3EventOwnership.isFromAnotherMediaItem(
                eventTimelineMediaId = "7:previous",
                currentMediaItemMediaId = "7",
            ),
        )
        // Same item: the callback is about the current candidate.
        assertFalse(
            Media3EventOwnership.isFromAnotherMediaItem(
                eventTimelineMediaId = "7",
                currentMediaItemMediaId = "7",
            ),
        )
        // No information on either side is never treated as a mismatch.
        assertFalse(Media3EventOwnership.isFromAnotherMediaItem(null, "7"))
        assertFalse(Media3EventOwnership.isFromAnotherMediaItem("7", null))
    }
}
