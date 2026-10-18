package com.kantv.app.diagnostics

internal object ProcessDiagnosticRecoveryGate {
    private val activeIds = mutableSetOf<String>()
    private var recoveryClaimed = false
    @Synchronized fun claimRecovery(): Boolean = if (recoveryClaimed) false else true.also { recoveryClaimed = true }
    @Synchronized fun register(id: String) { activeIds += id }
    @Synchronized fun unregister(id: String) { activeIds -= id }
    @Synchronized fun isActive(id: String): Boolean = id in activeIds
    @Synchronized internal fun resetForTest() { activeIds.clear(); recoveryClaimed = false }
}
