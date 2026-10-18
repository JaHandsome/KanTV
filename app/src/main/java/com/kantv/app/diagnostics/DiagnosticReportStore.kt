package com.kantv.app.diagnostics

import java.io.File
import java.io.FileOutputStream

internal class DiagnosticReportStore(
    private val root: File,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val retentionMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxTotalBytes: Long = 64L * 1024 * 1024,
    private val maxReportBytes: Long = 512L * 1024,
    private val maxReportCount: Int = 240,
    private val onFailure: (String) -> Unit = {},
) {
    private val active = File(root, "active")
    private val reports = File(root, "reports")
    private val sent = File(root, "sent")

    fun writeActive(id: String, bytes: ByteArray): File? {
        if (maxReportCount < 1 || bytes.size > maxTotalBytes) {
            onFailure("ACTIVE_TOTAL_LIMIT")
            return null
        }
        val result = writeAtomic(active, id, bytes) ?: return null
        // A checkpoint replaces this same ID, so protecting it cannot make the default limits
        // unbounded. Older crash journals are the final eviction tier.
        enforceRetention(protectedActiveId = id)
        return result.takeIf(File::isFile)
    }

    fun writeReport(id: String, reason: DiagnosticReportReason, bytes: ByteArray): File? {
        val result = writeAtomic(reports, id, bytes)
        if (result != null) enforceRetention()
        return result
    }

    fun finalizeActive(id: String, reason: DiagnosticReportReason, bytes: ByteArray): File? {
        val result = writeReport(id, reason, bytes) ?: return null
        val journal = File(active, "$id.json")
        if (journal.exists() && !journal.delete()) onFailure("ACTIVE_CLEANUP_FAILED")
        return result
    }

    fun recoverActiveReports(canRecover: (String) -> Boolean = { true }): List<File> {
        val recovered = mutableListOf<File>()
        for (journal in safeJsonFiles(active)) {
            val id = journal.nameWithoutExtension
            if (!canRecover(id)) continue
            val completed = File(reports, "$id.json")
            if (completed.isFile && isDirectChild(completed, reports)) {
                if (!journal.delete()) onFailure("ACTIVE_CLEANUP_FAILED")
                continue
            }
            if (!REPORT_ID.matches(id) || journal.length() > maxReportBytes) {
                onFailure("ACTIVE_INVALID")
                continue
            }
            val sealed = sealRecoveredJournal(journal.readBytes(), id, nowEpochMs())
            if (sealed == null) {
                onFailure("ACTIVE_PARSE_FAILED")
                continue
            }
            finalizeActive(id, DiagnosticReportReason.PROCESS_TERMINATED, sealed)?.let(recovered::add)
        }
        enforceRetention()
        return recovered
    }

    fun pendingReports(): List<File> {
        enforceRetention()
        return safeJsonFiles(reports).filter { it.length() <= maxReportBytes }
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
    }

    fun activeReports(): List<File> = safeJsonFiles(active)

    /**
     * A diagnostic export is intentionally smaller than the on-device retention budget. It contains
     * only recent structured JSON journals and never walks arbitrary app files.
     */
    fun exportableReports(maxFiles: Int = 32, maxBytes: Long = 16L * 1024 * 1024): List<File> {
        enforceRetention()
        var bytes = 0L
        val selectedNewestFirst = (safeJsonFiles(reports) + safeJsonFiles(active))
            .filter { it.length() <= maxReportBytes }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .asSequence()
            .filter { file ->
                val next = bytes + file.length()
                if (next > maxBytes) false else {
                    bytes = next
                    true
                }
            }
            .take(maxFiles.coerceAtLeast(0))
            .toList()
        return selectedNewestFirst.asReversed()
    }

    fun markUploaded(file: File): Boolean {
        if (!file.isFile || !isDirectChild(file, reports) || file.extension != "json" || file.length() > maxReportBytes) {
            onFailure("UPLOAD_SOURCE_INVALID")
            return false
        }
        if (!sent.mkdirs() && !sent.isDirectory) { onFailure("SENT_DIRECTORY_FAILED"); return false }
        val destination = File(sent, file.name)
        if (!isDirectChild(destination, sent) || destination.exists()) { onFailure("SENT_DESTINATION_INVALID"); return false }
        val moved = file.renameTo(destination)
        if (moved) enforceRetention() else onFailure("SENT_MOVE_FAILED")
        return moved
    }

    private fun writeAtomic(directory: File, id: String, bytes: ByteArray): File? {
        if (!REPORT_ID.matches(id)) { onFailure("REPORT_ID_INVALID"); return null }
        if (bytes.size > maxReportBytes) { onFailure("REPORT_TOO_LARGE"); return null }
        if (!directory.mkdirs() && !directory.isDirectory) { onFailure("REPORT_DIRECTORY_FAILED"); return null }
        val destination = File(directory, "$id.json")
        if (!isDirectChild(destination, directory)) { onFailure("REPORT_PATH_INVALID"); return null }
        val temporary = File(directory, ".$id.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            if (!temporary.renameTo(destination)) throw java.io.IOException("rename failed")
        } catch (_: Exception) {
            temporary.delete()
            onFailure("REPORT_WRITE_FAILED")
            return null
        }
        return destination.takeIf(File::isFile)
    }

    /** Sent reports are expendable first, pending next, stale active journals last. */
    private fun enforceRetention(protectedActiveId: String? = null) {
        val cutoff = nowEpochMs() - retentionMs
        safeJsonFiles(sent).filter { it.lastModified() < cutoff }.forEach(File::delete)
        safeJsonFiles(reports).filter { it.lastModified() < cutoff }.forEach {
            if (it.delete()) onFailure("RETENTION_PENDING_EVICTED") else onFailure("RETENTION_DELETE_FAILED")
        }
        // Calls without recorder context (uploader/export/health reads) protect the newest active
        // journal. The live recorder refreshes its mtime on every atomic checkpoint.
        val protectedName = protectedActiveId?.let { "$it.json" }
            ?: safeJsonFiles(active).maxWithOrNull(compareBy<File>({ it.lastModified() }, { it.name }))?.name
        fun all(): List<File> = safeJsonFiles(sent) + safeJsonFiles(reports) + safeJsonFiles(active)
        fun victims(): List<File> = safeJsonFiles(sent).oldestFirst() + safeJsonFiles(reports).oldestFirst() +
            safeJsonFiles(active).filterNot { it.name == protectedName }.oldestFirst()
        while (all().size > maxReportCount) {
            val victim = victims().firstOrNull() ?: break
            if (!victim.delete()) { onFailure("RETENTION_DELETE_FAILED"); break }
            if (victim.parentFile == reports) onFailure("RETENTION_PENDING_EVICTED")
        }
        while (all().sumOf(File::length) > maxTotalBytes) {
            val victim = victims().firstOrNull() ?: break
            if (!victim.delete()) { onFailure("RETENTION_DELETE_FAILED"); break }
            if (victim.parentFile == reports) onFailure("RETENTION_PENDING_EVICTED")
        }
    }

    private fun List<File>.oldestFirst() = sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
    private fun safeJsonFiles(directory: File): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" && isDirectChild(it, directory) }
    private fun isDirectChild(file: File, directory: File): Boolean = try {
        file.canonicalFile.parentFile == directory.canonicalFile
    } catch (_: Exception) { false }

    companion object {
        private val REPORT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/**
 * Active files are atomic snapshots emitted by this process and always end with the samples array.
 * This narrow transformer stays JVM-testable and rejects anything outside that exact envelope.
 */
internal fun sealRecoveredJournal(bytes: ByteArray, reportId: String, closedAtMs: Long): ByteArray? {
    val json = bytes.toString(Charsets.UTF_8)
    if (!json.startsWith('{') || !json.endsWith("}") ||
        !json.contains("\"reportId\":\"$reportId\"") ||
        !json.contains("\"reason\":\"ACTIVE_SESSION\"") ||
        !json.contains("\"lifecycle\":\"ACTIVE\"") ||
        !json.contains("\"closedAtMs\":-1") ||
        !json.contains("\"samples\":[")) return null
    val sealed = json
        .replaceFirst("\"reason\":\"ACTIVE_SESSION\"", "\"reason\":\"PROCESS_TERMINATED\"")
        .replaceFirst("\"lifecycle\":\"ACTIVE\"", "\"lifecycle\":\"RECOVERED_AFTER_PROCESS_DEATH\"")
        .replaceFirst("\"closedAtMs\":-1", "\"closedAtMs\":$closedAtMs")
    val samplesEnd = sealed.lastIndexOf(']')
    if (samplesEnd <= sealed.lastIndexOf("\"samples\":[")) return null
    val separator = if (sealed[samplesEnd - 1] == '[') "" else ","
    val recoveredEntry = "$separator{\"kind\":\"PROCESS_RECOVERED\",\"elapsedMs\":-1}"
    return (sealed.substring(0, samplesEnd) + recoveredEntry + sealed.substring(samplesEnd))
        .toByteArray(Charsets.UTF_8)
}
