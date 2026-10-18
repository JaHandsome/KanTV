package com.kantv.app.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWebRuntimePolicyTest {
    @Test
    fun statePriorityUsesOnlyObservableFacts() {
        assertEquals(OfficialWebState.RENDERER_GONE, classify(rendererGone = true, loginRequired = true))
        assertEquals(OfficialWebState.POLICY_OR_REGION_OR_DRM_BLOCKED,
            classify(explicitPolicyOrRegionOrDrmBlock = true, loginRequired = true, adVisible = true))
        assertEquals(OfficialWebState.LOGIN_REQUIRED, classify(loginRequired = true, adVisible = true))
        assertEquals(OfficialWebState.AD, classify(adVisible = true, rendererResponsive = false))
        assertEquals(OfficialWebState.RENDERER_UNRESPONSIVE, classify(rendererResponsive = false))
        assertEquals(OfficialWebState.INITIALIZING, classify(nodeCount = 0, initializing = true))
        assertEquals(OfficialWebState.NO_PLAYER_NODE, classify(nodeCount = 0))
        assertEquals(OfficialWebState.NODE_REPLACED, classify(nodeCount = 1, nodeReplaced = true, frameProgress = true))
        assertEquals(OfficialWebState.PLAYING, classify(nodeCount = 1, frameProgress = true))
        assertEquals(OfficialWebState.PLAYER_NO_FRAME_PROGRESS, classify(nodeCount = 1))
    }

    @Test
    fun nodeGenerationRecordsReplacementButNotTemporaryAbsence() {
        val tracker = OfficialWebNodeGenerationTracker()
        assertEquals(OfficialWebNodeObservation(1, 1, false), tracker.observe(1, "1:2"))
        assertEquals(OfficialWebNodeObservation(1, 1, false), tracker.observe(1, "1:2"))
        assertEquals(OfficialWebNodeObservation(0, 1, false), tracker.observe(0, null))
        assertEquals(OfficialWebNodeObservation(1, 2, true), tracker.observe(1, "3:4"))
        tracker.pageStarted()
        assertEquals(OfficialWebNodeObservation(1, 3, false), tracker.observe(1, "5"))
    }

    @Test
    fun faultEpisodeUsesOneSoftThenOneHardThenShowsFailure() {
        val policy = OfficialWebRecoveryPolicy(observationsBeforeRecovery = 2)
        assertEquals(OfficialWebRecoveryAction.NONE, policy.observe(OfficialWebState.NO_PLAYER_NODE).action)
        assertEquals(OfficialWebRecoveryAction.SOFT_RELOAD, policy.observe(OfficialWebState.NO_PLAYER_NODE).action)
        assertEquals(OfficialWebRecoveryAction.NONE, policy.observe(OfficialWebState.PLAYER_NO_FRAME_PROGRESS).action)
        assertEquals(OfficialWebRecoveryAction.HARD_RECREATE, policy.observe(OfficialWebState.PLAYER_NO_FRAME_PROGRESS).action)
        policy.runtimeStarted()
        assertEquals(OfficialWebRecoveryAction.NONE, policy.observe(OfficialWebState.NO_PLAYER_NODE).action)
        assertEquals(OfficialWebRecoveryAction.SHOW_FAILURE, policy.observe(OfficialWebState.NO_PLAYER_NODE).action)
        assertEquals(OfficialWebRecoveryAction.NONE, policy.observe(OfficialWebState.NO_PLAYER_NODE, confirmed = true).action)
        assertEquals(OfficialWebRecoveryStage.FAILED, policy.stage)
    }

    @Test
    fun rendererGoneSkipsSoftAndSuccessResetsEpisode() {
        val policy = OfficialWebRecoveryPolicy(1)
        assertEquals(OfficialWebRecoveryAction.HARD_RECREATE, policy.observe(OfficialWebState.RENDERER_GONE).action)
        assertEquals(OfficialWebRecoveryAction.SHOW_FAILURE, policy.observe(OfficialWebState.RENDERER_GONE).action)
        assertEquals(OfficialWebRecoveryAction.NONE, policy.observe(OfficialWebState.PLAYING).action)
        assertEquals(OfficialWebRecoveryAction.SOFT_RELOAD,
            policy.observe(OfficialWebState.PLAYER_NO_FRAME_PROGRESS).action)
    }

    @Test
    fun protectedPageStatesNeverRecover() {
        val policy = OfficialWebRecoveryPolicy(1)
        for (state in listOf(
            OfficialWebState.LOGIN_REQUIRED,
            OfficialWebState.AD,
            OfficialWebState.POLICY_OR_REGION_OR_DRM_BLOCKED,
        )) {
            assertEquals(OfficialWebRecoveryAction.NONE, policy.observe(state, confirmed = true).action)
            assertEquals(OfficialWebRecoveryStage.BLOCKED, policy.stage)
        }
    }

    @Test
    fun callbackGateRejectsOldRuntimePageViewAndRoute() {
        val gate = OfficialWebCallbackGate(routeSessionId = 7, routeGeneration = 9)
        val runtimeOne = gate.runtimeStarted(identity = 100)
        val pageOne = requireNotNull(gate.pageStarted(runtimeOne))
        assertTrue(gate.accepts(pageOne))
        val pageTwo = requireNotNull(gate.pageStarted(runtimeOne))
        assertFalse(gate.accepts(pageOne))
        assertTrue(gate.accepts(pageTwo))
        val runtimeTwo = gate.runtimeStarted(identity = 101)
        assertFalse(gate.acceptsRuntime(runtimeOne))
        assertTrue(gate.acceptsRuntime(runtimeTwo))
        assertFalse(gate.accepts(runtimeTwo.copy(routeSessionId = 8)))
        assertFalse(gate.accepts(runtimeTwo.copy(routeGeneration = 10)))
        gate.invalidateRuntime()
        assertFalse(gate.acceptsRuntime(runtimeTwo))
    }

    @Test
    fun protectedOrSuccessfulStateCancelsSoftRecoveryWatchdog() {
        val token = OfficialWebCallbackToken(1, 2, 3, 4, 5)
        for (state in listOf(
            OfficialWebState.LOGIN_REQUIRED,
            OfficialWebState.AD,
            OfficialWebState.POLICY_OR_REGION_OR_DRM_BLOCKED,
            OfficialWebState.PLAYING,
        )) {
            val policy = OfficialWebRecoveryPolicy(1)
            assertEquals(
                OfficialWebRecoveryAction.SOFT_RELOAD,
                policy.observe(OfficialWebState.NO_PLAYER_NODE).action,
            )
            assertEquals(OfficialWebRecoveryAction.NONE, policy.observe(state).action)
            assertTrue(OfficialWebRecoveryWatchdogPolicy.shouldCancel(state, OfficialWebRecoveryStage.SOFT_RELOAD))
            assertFalse(OfficialWebRecoveryWatchdogPolicy.shouldEscalate(
                OfficialWebState.NO_PLAYER_NODE, state, token, token,
                sameWebView = true, stopped = false, stage = OfficialWebRecoveryStage.SOFT_RELOAD,
            ))
        }
        assertTrue(OfficialWebRecoveryWatchdogPolicy.shouldCancel(
            OfficialWebState.NO_PLAYER_NODE, OfficialWebRecoveryStage.FAILED,
        ))
    }

    @Test
    fun watchdogEscalatesOnlyUnchangedFaultTokenViewAndForeground() {
        val token = OfficialWebCallbackToken(1, 2, 3, 4, 5)
        fun check(
            currentState: OfficialWebState = OfficialWebState.NO_PLAYER_NODE,
            currentToken: OfficialWebCallbackToken? = token,
            sameView: Boolean = true,
            stopped: Boolean = false,
        ) = OfficialWebRecoveryWatchdogPolicy.shouldEscalate(
            OfficialWebState.NO_PLAYER_NODE, currentState, token, currentToken,
            sameView, stopped, OfficialWebRecoveryStage.SOFT_RELOAD,
        )
        assertTrue(check())
        assertFalse(check(currentState = OfficialWebState.PLAYER_NO_FRAME_PROGRESS))
        assertFalse(check(currentToken = token.copy(pageGeneration = 5)))
        assertFalse(check(sameView = false))
        assertFalse(check(stopped = true))
    }

    @Test
    fun sameUrlOldFinishedAndErrorCannotMutateCurrentDocumentStatus() {
        val current = OfficialWebDocumentStatus(loadComplete = false, errorCode = 0)
        val oldFinished = OfficialWebDocumentStatusReducer.update(
            current, OfficialWebDocumentStatusSource.PAGE_FINISHED_CALLBACK, true, 0,
        )
        val oldError = OfficialWebDocumentStatusReducer.update(
            oldFinished, OfficialWebDocumentStatusSource.MAIN_FRAME_ERROR_CALLBACK, false, 500,
        )
        assertEquals(current, oldFinished)
        assertEquals(current, oldError)
        assertEquals(
            OfficialWebDocumentStatus(loadComplete = true, errorCode = 4),
            OfficialWebDocumentStatusReducer.update(
                current, OfficialWebDocumentStatusSource.ACTIVE_DOCUMENT_OBSERVATION, true, 4,
            ),
        )
    }

    @Test
    fun disposalPlanIsCompleteHealthyMinimalGoneAndIdempotent() {
        val healthy = OfficialWebDisposalGate()
        assertEquals(OfficialWebDisposalStep.entries, healthy.begin(rendererGone = false))
        assertTrue(healthy.begin(rendererGone = false).isEmpty())

        val gone = OfficialWebDisposalGate()
        val goneSteps = gone.begin(rendererGone = true)
        assertFalse(goneSteps.contains(OfficialWebDisposalStep.TEARDOWN_PAGE_OBSERVERS))
        assertFalse(goneSteps.contains(OfficialWebDisposalStep.STOP_LOADING))
        assertTrue(goneSteps.contains(OfficialWebDisposalStep.DETACH))
        assertTrue(goneSteps.contains(OfficialWebDisposalStep.DESTROY))
        assertTrue(gone.begin(rendererGone = true).isEmpty())
    }



    private fun classify(
        rendererGone: Boolean = false,
        rendererResponsive: Boolean = true,
        explicitPolicyOrRegionOrDrmBlock: Boolean = false,
        loginRequired: Boolean = false,
        adVisible: Boolean = false,
        nodeCount: Int = 0,
        nodeReplaced: Boolean = false,
        initializing: Boolean = false,
        frameProgress: Boolean = false,
    ) = OfficialWebStateClassifier.classify(
        OfficialWebObservableFacts(
            rendererGone,
            rendererResponsive,
            explicitPolicyOrRegionOrDrmBlock,
            loginRequired,
            adVisible,
            nodeCount,
            nodeReplaced,
            initializing,
            frameProgress,
        ),
    )
}
