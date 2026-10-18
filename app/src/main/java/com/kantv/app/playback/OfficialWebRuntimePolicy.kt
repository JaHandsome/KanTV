package com.kantv.app.playback

/** Page/runtime states derived only from observable WebView and DOM facts. */
enum class OfficialWebState {
    NO_PLAYER_NODE,
    INITIALIZING,
    NODE_REPLACED,
    LOGIN_REQUIRED,
    AD,
    POLICY_OR_REGION_OR_DRM_BLOCKED,
    PLAYER_NO_FRAME_PROGRESS,
    RENDERER_UNRESPONSIVE,
    RENDERER_GONE,
    PLAYING,
}

data class OfficialWebObservableFacts(
    val rendererGone: Boolean = false,
    val rendererResponsive: Boolean = true,
    val explicitPolicyOrRegionOrDrmBlock: Boolean = false,
    val loginRequired: Boolean = false,
    val adVisible: Boolean = false,
    val nodeCount: Int = 0,
    val nodeReplaced: Boolean = false,
    val initializing: Boolean = false,
    val frameProgress: Boolean = false,
)

internal object OfficialWebStateClassifier {
    fun classify(facts: OfficialWebObservableFacts): OfficialWebState = when {
        facts.rendererGone -> OfficialWebState.RENDERER_GONE
        facts.explicitPolicyOrRegionOrDrmBlock -> OfficialWebState.POLICY_OR_REGION_OR_DRM_BLOCKED
        facts.loginRequired -> OfficialWebState.LOGIN_REQUIRED
        facts.adVisible -> OfficialWebState.AD
        !facts.rendererResponsive -> OfficialWebState.RENDERER_UNRESPONSIVE
        facts.nodeCount <= 0 && facts.initializing -> OfficialWebState.INITIALIZING
        facts.nodeCount <= 0 -> OfficialWebState.NO_PLAYER_NODE
        facts.nodeReplaced -> OfficialWebState.NODE_REPLACED
        facts.frameProgress -> OfficialWebState.PLAYING
        facts.initializing -> OfficialWebState.INITIALIZING
        else -> OfficialWebState.PLAYER_NO_FRAME_PROGRESS
    }
}

enum class OfficialWebRecoveryAction { NONE, SOFT_RELOAD, HARD_RECREATE, SHOW_FAILURE }
enum class OfficialWebRecoveryStage { IDLE, MONITORING, SOFT_RELOAD, HARD_RECREATE, BLOCKED, FAILED }

data class OfficialWebRecoveryDecision(
    val action: OfficialWebRecoveryAction,
    val stage: OfficialWebRecoveryStage,
)

/** One playback-fault episode may consume at most one soft and one hard recovery. */
internal class OfficialWebRecoveryPolicy(
    private val observationsBeforeRecovery: Int = 10,
) {
    init {
        require(observationsBeforeRecovery > 0)
    }

    private var consecutiveFaults = 0
    private var softUsed = false
    private var hardUsed = false
    private var failureShown = false
    var stage: OfficialWebRecoveryStage = OfficialWebRecoveryStage.IDLE
        private set

    fun observe(state: OfficialWebState, confirmed: Boolean = false): OfficialWebRecoveryDecision {
        when (state) {
            OfficialWebState.PLAYING -> {
                reset()
                return decision(OfficialWebRecoveryAction.NONE, OfficialWebRecoveryStage.IDLE)
            }
            OfficialWebState.LOGIN_REQUIRED,
            OfficialWebState.AD,
            OfficialWebState.POLICY_OR_REGION_OR_DRM_BLOCKED,
            -> {
                consecutiveFaults = 0
                return decision(OfficialWebRecoveryAction.NONE, OfficialWebRecoveryStage.BLOCKED)
            }
            OfficialWebState.INITIALIZING,
            OfficialWebState.NODE_REPLACED,
            -> {
                consecutiveFaults = 0
                return decision(OfficialWebRecoveryAction.NONE, OfficialWebRecoveryStage.MONITORING)
            }
            OfficialWebState.RENDERER_GONE -> {
                consecutiveFaults = 0
                return if (!hardUsed) {
                    hardUsed = true
                    decision(OfficialWebRecoveryAction.HARD_RECREATE, OfficialWebRecoveryStage.HARD_RECREATE)
                } else failOnce()
            }
            OfficialWebState.NO_PLAYER_NODE,
            OfficialWebState.PLAYER_NO_FRAME_PROGRESS,
            OfficialWebState.RENDERER_UNRESPONSIVE,
            -> Unit
        }

        consecutiveFaults = if (confirmed) observationsBeforeRecovery else consecutiveFaults + 1
        if (consecutiveFaults < observationsBeforeRecovery) {
            return decision(OfficialWebRecoveryAction.NONE, OfficialWebRecoveryStage.MONITORING)
        }
        consecutiveFaults = 0
        if (!softUsed) {
            softUsed = true
            return decision(OfficialWebRecoveryAction.SOFT_RELOAD, OfficialWebRecoveryStage.SOFT_RELOAD)
        }
        if (!hardUsed) {
            hardUsed = true
            return decision(OfficialWebRecoveryAction.HARD_RECREATE, OfficialWebRecoveryStage.HARD_RECREATE)
        }
        return failOnce()
    }

    /** A new runtime remains in the same episode, but starts its observation threshold afresh. */
    fun runtimeStarted() {
        consecutiveFaults = 0
        if (stage != OfficialWebRecoveryStage.IDLE) stage = OfficialWebRecoveryStage.MONITORING
    }

    fun reset() {
        consecutiveFaults = 0
        softUsed = false
        hardUsed = false
        failureShown = false
        stage = OfficialWebRecoveryStage.IDLE
    }

    private fun failOnce(): OfficialWebRecoveryDecision = if (!failureShown) {
        failureShown = true
        decision(OfficialWebRecoveryAction.SHOW_FAILURE, OfficialWebRecoveryStage.FAILED)
    } else {
        decision(OfficialWebRecoveryAction.NONE, OfficialWebRecoveryStage.FAILED)
    }

    private fun decision(
        action: OfficialWebRecoveryAction,
        nextStage: OfficialWebRecoveryStage,
    ) = OfficialWebRecoveryDecision(action, nextStage).also { stage = nextStage }
}

internal object OfficialWebRecoveryWatchdogPolicy {
    private val cancelStates = setOf(
        OfficialWebState.LOGIN_REQUIRED,
        OfficialWebState.AD,
        OfficialWebState.POLICY_OR_REGION_OR_DRM_BLOCKED,
        OfficialWebState.PLAYING,
    )
    private val recoverableStates = setOf(
        OfficialWebState.NO_PLAYER_NODE,
        OfficialWebState.PLAYER_NO_FRAME_PROGRESS,
        OfficialWebState.RENDERER_UNRESPONSIVE,
    )

    fun shouldCancel(state: OfficialWebState, stage: OfficialWebRecoveryStage): Boolean =
        state in cancelStates || stage == OfficialWebRecoveryStage.FAILED

    fun shouldEscalate(
        scheduledState: OfficialWebState,
        currentState: OfficialWebState,
        scheduledToken: OfficialWebCallbackToken,
        currentToken: OfficialWebCallbackToken?,
        sameWebView: Boolean,
        stopped: Boolean,
        stage: OfficialWebRecoveryStage,
    ): Boolean = !stopped && sameWebView && scheduledState in recoverableStates &&
        currentState == scheduledState && currentToken == scheduledToken &&
        stage != OfficialWebRecoveryStage.FAILED
}

enum class OfficialWebDocumentStatusSource {
    ACTIVE_DOCUMENT_OBSERVATION,
    PAGE_FINISHED_CALLBACK,
    MAIN_FRAME_ERROR_CALLBACK,
}

data class OfficialWebDocumentStatus(
    val loadComplete: Boolean = false,
    val errorCode: Int = 0,
)

/** Navigation callbacks are scheduling hints; only a token-gated current-document sample is semantic evidence. */
internal object OfficialWebDocumentStatusReducer {
    fun update(
        current: OfficialWebDocumentStatus,
        source: OfficialWebDocumentStatusSource,
        observedLoadComplete: Boolean,
        observedErrorCode: Int,
    ): OfficialWebDocumentStatus = if (source == OfficialWebDocumentStatusSource.ACTIVE_DOCUMENT_OBSERVATION) {
        OfficialWebDocumentStatus(observedLoadComplete, observedErrorCode.coerceAtLeast(0))
    } else {
        current
    }
}

data class OfficialWebNodeObservation(
    val nodeCount: Int,
    val nodeGeneration: Long,
    val replaced: Boolean,
)

/** Tracks only bounded numeric DOM identities generated by the injected observer. */
internal class OfficialWebNodeGenerationTracker {
    private var lastSignature: String? = null
    private var generation = 0L

    fun observe(nodeCount: Int, signature: String?): OfficialWebNodeObservation {
        val normalized = signature?.takeIf { nodeCount > 0 && TECHNICAL_SIGNATURE.matches(it) }
        if (normalized == null) return OfficialWebNodeObservation(nodeCount.coerceAtLeast(0), generation, false)
        val previous = lastSignature
        val replaced = previous != null && previous != normalized
        if (previous == null || replaced) generation++
        lastSignature = normalized
        return OfficialWebNodeObservation(nodeCount.coerceAtLeast(0), generation, replaced)
    }

    fun pageStarted() {
        lastSignature = null
    }

    private companion object {
        val TECHNICAL_SIGNATURE = Regex("[0-9]+(?::[0-9]+){0,7}")
    }
}

data class OfficialWebCallbackToken(
    val routeSessionId: Long,
    val routeGeneration: Long,
    val runtimeGeneration: Long,
    val pageGeneration: Long,
    val webViewIdentity: Long,
)

/** Rejects callbacks from replaced WebViews, prior navigations, or another external route. */
internal class OfficialWebCallbackGate(
    private val routeSessionId: Long,
    private val routeGeneration: Long,
) {
    private var runtimeGeneration = 0L
    private var pageGeneration = 0L
    private var webViewIdentity = -1L
    private var active = false

    fun runtimeStarted(identity: Long): OfficialWebCallbackToken {
        require(identity >= 0)
        runtimeGeneration++
        pageGeneration = 0
        webViewIdentity = identity
        active = true
        return currentToken()
    }

    fun pageStarted(runtimeToken: OfficialWebCallbackToken): OfficialWebCallbackToken? {
        if (!acceptsRuntime(runtimeToken)) return null
        pageGeneration++
        return currentToken()
    }

    fun current(): OfficialWebCallbackToken? = if (active) currentToken() else null

    fun accepts(token: OfficialWebCallbackToken): Boolean = active && token == currentToken()

    fun acceptsRuntime(token: OfficialWebCallbackToken): Boolean = active &&
        token.routeSessionId == routeSessionId && token.routeGeneration == routeGeneration &&
        token.runtimeGeneration == runtimeGeneration && token.webViewIdentity == webViewIdentity

    fun invalidateRuntime() {
        active = false
        pageGeneration++
        webViewIdentity = -1
    }

    private fun currentToken() = OfficialWebCallbackToken(
        routeSessionId,
        routeGeneration,
        runtimeGeneration,
        pageGeneration,
        webViewIdentity,
    )
}

internal enum class OfficialWebDisposalStep {
    TEARDOWN_PAGE_OBSERVERS,
    STOP_LOADING,
    REMOVE_DOCUMENT_START_SCRIPTS,
    DETACH,
    CLEAR_HISTORY,
    DESTROY,
    CLEAR_REFERENCE,
}

internal class OfficialWebDisposalGate {
    private var disposed = false

    fun begin(rendererGone: Boolean): List<OfficialWebDisposalStep> {
        if (disposed) return emptyList()
        disposed = true
        return if (rendererGone) {
            listOf(
                OfficialWebDisposalStep.REMOVE_DOCUMENT_START_SCRIPTS,
                OfficialWebDisposalStep.DETACH,
                OfficialWebDisposalStep.DESTROY,
                OfficialWebDisposalStep.CLEAR_REFERENCE,
            )
        } else {
            OfficialWebDisposalStep.entries
        }
    }
}
