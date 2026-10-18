package com.kantv.app.diagnostics

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/**
 * POST JSON + Bearer 的共享连接配置薄封装（2026-09-28，客户端重构 3-3）。
 *
 * 由 PlaybackFeedbackUploader 的 HttpUrlConnectionFeedbackTransport 与
 * DiagnosticUploadWorker 两处近乎逐行重复的连接配置合并而来。两边的历史行为
 * 差异以参数显式保留，**不允许顺手统一**：
 * - [sendAcceptJson]：回流侧发 `Accept: application/json`，诊断侧历史不发；
 * - [disableCaches]：回流侧显式 `useCaches = false`，诊断侧历史不设（跟随系统默认）；
 * - [bodyLength]：回流侧传字符串 UTF-8 字节数，诊断侧传文件字节数（File.length()）。
 *
 * 请求体写入（内存一次性写 vs 文件 8KB 缓冲流式复制 + isStopped 协作取消）、
 * 响应码/响应体处理与异常归类都留在各自调用方——那些是两边各自的语义。
 */
internal fun openPostJsonBearerConnection(
    uri: String,
    token: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    sendAcceptJson: Boolean,
    disableCaches: Boolean,
    bodyLength: Long,
): HttpURLConnection =
    (URI(uri).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
        requestMethod = "POST"
        instanceFollowRedirects = false
        if (disableCaches) useCaches = false
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        if (sendAcceptJson) setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
        setFixedLengthStreamingMode(bodyLength)
    }
