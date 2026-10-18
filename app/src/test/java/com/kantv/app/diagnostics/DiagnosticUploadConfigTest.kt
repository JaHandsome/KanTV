package com.kantv.app.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticUploadConfigTest {
    @Test fun onlyCanonicalLanReportEndpointsAreAccepted() {
        listOf("http://192.168.1.10:18769/v1/reports", "https://10.0.2.2/v1/reports",
            "http://172.16.1.2/v1/reports").forEach { assertTrue(it, DiagnosticUploadConfig.validEndpoint(it)) }
        listOf("http://example.com/v1/reports", "http://8.8.8.8/v1/reports", "http://192.168.1.1@evil.test/v1/reports",
            "http://192.168.1.1/v1/reports?token=x", "http://192.168.1.1/v1/reports#x",
            "http://192.168.1.1:0/v1/reports", "http://192.168.1.1:65536/v1/reports",
            "http://192.168.1.1/other", "file:///v1/reports", "http://010.0.0.1/v1/reports",
            "http://127.0.0.1/v1/reports").forEach { assertFalse(it, DiagnosticUploadConfig.validEndpoint(it)) }
    }

    @Test fun onlyBoundedUrlSafeUploadTokensAreAccepted() {
        assertTrue(DiagnosticUploadConfig.validToken("a".repeat(32)))
        assertTrue(DiagnosticUploadConfig.validToken("A0_-".repeat(32)))
        assertFalse(DiagnosticUploadConfig.validToken("a".repeat(31)))
        assertFalse(DiagnosticUploadConfig.validToken("a".repeat(129)))
        assertFalse(DiagnosticUploadConfig.validToken("a".repeat(31) + "+"))
    }

    @Test fun pairedUpgradeRepairsOldAppliedMarkerWithEmptyConfiguration() {
        assertTrue(DiagnosticUploadConfig.shouldRefreshBundledDefaults(
            defaultsApplied = true,
            enabled = false,
            currentEndpoint = "",
            currentToken = "",
        ))
        assertTrue(DiagnosticUploadConfig.shouldRefreshBundledDefaults(
            defaultsApplied = true,
            enabled = true,
            currentEndpoint = "http://192.168.1.10:18769/v1/reports",
            currentToken = "short",
        ))
    }

    @Test fun validEnabledPairedConfigurationIsNotRewritten() {
        assertFalse(DiagnosticUploadConfig.shouldRefreshBundledDefaults(
            defaultsApplied = true,
            enabled = true,
            currentEndpoint = "http://192.168.1.10:18769/v1/reports",
            currentToken = "a".repeat(32),
        ))
    }
}
