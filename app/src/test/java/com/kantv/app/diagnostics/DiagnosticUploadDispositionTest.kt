package com.kantv.app.diagnostics

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归：上传通道必须"永远能被催"，不得被旧排期或系统退避长期锁死。
 *
 * 2026-09-17 事故分两段：
 * 1. 服务端报告卷满（507）期间，上传失败走 `Result.retry()`，退避涨到上限 5 小时；
 *    服务端修好后上传长时间不恢复。
 * 2. 修好"失败处置"后（0.2.19）装到电视上仍不上传：因为注册任务用的 `KEEP` 被
 *    旧版本留在退避队列里的任务静默忽略，下一次尝试仍由旧排期决定。
 *
 * 因此这里同时锁定两件事：失败处置恒为 success；进入应用时一律重置排期。
 */
class DiagnosticUploadDispositionTest {
    @Test
    fun failedAttemptKeepsPeriodicCadenceInsteadOfSystemBackoff() {
        assertEquals(
            ListenableWorker.Result.success()::class.java,
            uploadAttemptDisposition()::class.java,
        )
    }

    @Test
    fun enteringAppReschedulesUploadsInsteadOfKeepingStaleBackoff() {
        assertEquals(ExistingWorkPolicy.REPLACE, oneTimeUploadEnqueuePolicy())
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, periodicUploadEnqueuePolicy())
    }
}
