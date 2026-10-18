package com.kantv.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 证据组（v11 新增）的构造合法性。
 *
 * 存在理由：2026-09-15 实测踩过一次——`GeckoVisualOutputSignal` 的枚举名是 `VISIBLE_DETAIL`，
 * 而诊断白名单用的是 `DETAIL`，直接落 `name` 会在采样循环里抛 `require`，
 * **导致采样协程被取消、app 反复崩溃重启**。这里把"默认值 + 允许值"钉死，防止再漂移。
 */
class DiagnosticEvidenceGroupsTest {
    @Test
    fun defaultsAreConstructible() {
        val values = DiagnosticPlaybackValues()
        assertEquals("unknown", values.visual.sampleOutcome)
        assertEquals(-1L, values.visual.sampleCallbackMs)
        assertEquals(-1L, values.visual.millisSinceVerified)
        assertEquals("unknown", values.watchdog.decision)
        assertEquals("unknown", values.watchdog.reason)
        assertEquals("NONE", values.watchdog.sampleSkipReason)
        assertEquals("unknown", values.context.networkTransport)
    }

    @Test
    fun everyVisualOutcomeReachingTheJournalIsWhitelisted() {
        // 与 GeckoVisualOutputSignal → 白名单值的映射保持一致（映射在宿主里，此处校验取值域）。
        val allowed = listOf("DETAIL", "FLAT_NEUTRAL", "FLAT_OTHER", "NO_SAMPLE")
        allowed.forEach { outcome ->
            val values = DiagnosticPlaybackValues(
                visual = DiagnosticPlaybackValues.VisualEvidence(sampleOutcome = outcome),
            )
            assertEquals(outcome, values.visual.sampleOutcome)
        }
    }

    @Test
    fun timeoutSentinelIsAllowed() {
        val values = DiagnosticPlaybackValues(
            visual = DiagnosticPlaybackValues.VisualEvidence(
                sampleOutcome = "NO_SAMPLE",
                sampleCallbackMs = -2, // 发起后超时未回调
            ),
        )
        assertEquals(-2L, values.visual.sampleCallbackMs)
    }

    @Test
    fun everyWatchdogDecisionAndReasonIsWhitelisted() {
        listOf("NONE", "REBUILD", "FALLBACK").forEach { decision ->
            DiagnosticPlaybackValues(
                watchdog = DiagnosticPlaybackValues.WatchdogEvidence(decision = decision),
            )
        }
        listOf(
            "NONE", "FIRST_FRAME_TIMEOUT", "FRAME_PROGRESS_TIMEOUT", "CLOCK_STALLED",
            "PICTURE_UNVERIFIED", "PROBE_TIMED_OUT", "RENDERER_GONE", "BUDGET_EXHAUSTED", "MANUAL",
        ).forEach { reason ->
            DiagnosticPlaybackValues(
                watchdog = DiagnosticPlaybackValues.WatchdogEvidence(reason = reason),
            )
        }
    }

    @Test
    fun everyNetworkTransportIsWhitelisted() {
        listOf("NONE", "WIFI", "CELLULAR", "ETHERNET", "VPN", "OTHER").forEach { transport ->
            DiagnosticPlaybackValues(
                context = DiagnosticPlaybackValues.ContextEvidence(networkTransport = transport),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownVisualOutcomeIsRejectedLoudly() {
        // 枚举名漂移（VISIBLE_DETAIL）就是这一类：必须在构造时炸，而不是静默落盘。
        DiagnosticPlaybackValues(
            visual = DiagnosticPlaybackValues.VisualEvidence(sampleOutcome = "VISIBLE_DETAIL"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun callbackLatencyBelowTheTimeoutSentinelIsRejected() {
        DiagnosticPlaybackValues(
            visual = DiagnosticPlaybackValues.VisualEvidence(sampleCallbackMs = -3),
        )
    }

    @Test
    fun evidenceGroupsSerializeToFlatWhitelistedKeys() {
        val fields = DiagnosticLogEntry(
            DiagnosticEntryKind.SAMPLE,
            1,
            DiagnosticPlaybackValues(
                visual = DiagnosticPlaybackValues.VisualEvidence(
                    sampleOutcome = "FLAT_NEUTRAL",
                    sampleCallbackMs = 39,
                    millisSinceVerified = 94_000,
                ),
                watchdog = DiagnosticPlaybackValues.WatchdogEvidence(
                    wedgeActive = true,
                    decision = "REBUILD",
                    reason = "FIRST_FRAME_TIMEOUT",
                    recoveries = 1,
                    recoveryLimit = 3,
                ),
                context = DiagnosticPlaybackValues.ContextEvidence(
                    networkTransport = "ETHERNET",
                    millisSinceHeartbeat = 61_754,
                ),
            ),
        ).toSerializableFields()

        assertEquals("FLAT_NEUTRAL", fields["visualSampleOutcome"])
        assertEquals(39L, fields["visualSampleCallbackMs"])
        assertEquals(true, fields["wedgeActive"])
        assertEquals("REBUILD", fields["watchdogDecision"])
        assertEquals("ETHERNET", fields["networkTransport"])
        assertTrue("必须展开为扁平键，保持报告可读", !fields.containsKey("visual"))
    }
}
