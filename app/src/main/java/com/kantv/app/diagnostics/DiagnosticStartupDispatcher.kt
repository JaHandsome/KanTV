package com.kantv.app.diagnostics

/** Keeps slow recovery work off the ordered START journal lane. */
internal class DiagnosticStartupDispatcher(
    private val startExecutor: ((() -> Unit) -> Unit),
    private val recoveryExecutor: ((() -> Unit) -> Unit),
) {
    fun dispatchStart(task: () -> Unit) = startExecutor(task)
    fun dispatchRecovery(task: () -> Unit) = recoveryExecutor(task)
}
