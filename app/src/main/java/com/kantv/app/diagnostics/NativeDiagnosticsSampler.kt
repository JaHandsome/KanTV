package com.kantv.app.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.PixelCopy
import com.kantv.app.BuildConfig
import com.kantv.app.catalog.HubRouteRegistry
import com.kantv.app.playback.HubRoutePlan
import com.kantv.app.playback.PictureMode
import com.kantv.app.playback.PictureMotion
import com.kantv.app.playback.PictureMotionObservation
import com.kantv.app.playback.PictureMotionTracker
import com.kantv.app.playback.PlaybackCoordinator
import com.kantv.app.playback.PlaybackDiagnosticFact
import com.kantv.app.playback.PlaybackDiagnosticSnapshot
import com.kantv.app.playback.PlaybackDiagnostics
import com.kantv.app.playback.PlaybackRequest
import com.kantv.app.playback.PlaybackTrackKind
import com.kantv.app.playback.PlaybackUiState
import com.kantv.app.playback.presentedFramesForDiagnostics

/**
 * 原生播放诊断采样簇：会话管理、周期采样、画面抓帧观测、用户可见状态、用户"报错"上报。
 *
 * 只采集/上报诊断数据，不参与任何播放决策。宿主事实（播放状态、屏幕/菜单状态、抓帧目标、
 * 提示渲染）全部经构造参数注入；判定逻辑、阈值、时序与日志措辞与搬移前保持一致。
 */
class NativeDiagnosticsSampler(
    context: Context,
    private val diagnostics: PlaybackDiagnostics,
    private val coordinator: () -> PlaybackCoordinator,
    private val hubRoutes: () -> HubRouteRegistry,
    private val playbackUiState: () -> PlaybackUiState,
    private val isFullscreen: () -> Boolean,
    private val isSourceMenuShowing: () -> Boolean,
    private val firstChannelOpening: () -> Boolean,
    private val isFinishing: () -> Boolean,
    private val pictureCopyTargets: () -> List<Any>,
    private val showStatus: (String) -> Unit,
    private val renderPlaybackUiState: (PlaybackUiState) -> Unit,
    private val showFullscreen: () -> Unit,
    debugDisablePixelCopy: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val nativeDiagnosticHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val noticeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var nativeDiagnosticSessionId = 0L
    private var rejectedDiagnosticSessionId = 0L
    private var nativeDiagnosticInBackground = false
    @Volatile
    private var nativeDiagnosticPssKb = -1L
    private var nativeDiagnosticPssAtMs = 0L
    private var nativeDiagnosticPssSampling = false
    // getPss() 要扫 /proc，主线程调用在低端盒上是十 ms 级卡顿源；读数只进诊断报告，异步采样即可。
    private val nativeDiagnosticPssExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kantv-pss").apply { isDaemon = true }
    }
    // 画面运动观测：只观测、只上报，不参与任何播放决策。
    // 原生路径原先只有"时钟是否推进"这一条在播证据，而静止内容的时钟同样在推进，
    // 因此"解码正常但画面不变"的死源无法被识别。这里补上缺失的那一维：画面内容有没有变。
    private val pictureMotion = PictureMotionTracker()
    private var pictureMotionObservation: PictureMotionObservation? = null
    private var pictureCaptureBitmap: Bitmap? = null
    private var pictureCaptureToken = 0
    private var pictureCaptureInFlight = false
    private var pictureCaptureStartedMs = 0L
    private var lastPictureCaptureAtMs = 0L
    // Immutable safety + debug control, checked before host access/allocation/issue in every build.
    private val pictureCopyBlockedReason = NativePictureCopySafetyPolicy.blockedReason(
        model = android.os.Build.MODEL,
        hardware = android.os.Build.HARDWARE,
        sdkInt = android.os.Build.VERSION.SDK_INT,
        debugControlDisabled = BuildConfig.DEBUG && debugDisablePixelCopy,
    )
    private var debugPictureCopyIssued = 0L
    private var debugPictureCopySurfaceIssued = 0L
    private var debugPictureCopyWindowIssued = 0L
    private var debugPictureCopyFinished = 0L
    private var debugPictureCopyTimeouts = 0L
    private var debugPictureCopyCallFailures = 0L
    private var debugPictureCopyLastLoggedToken = -1
    private var debugPictureCopyLogCount = 0
    // 用户可见状态：屏幕上显示的是什么、已经持续了多久。这是"体验差"最直接的答案。
    private var lastUserVisibleState = ""
    private var lastUserVisibleStateAtMs = 0L
    private val nativeDiagnosticRecorder by lazy {
        OfficialWebDiagnosticsRecorder(appContext) {
            DiagnosticUploadConfig.schedule(appContext)
        }
    }
    private val samplingLoop = NativeDiagnosticSamplingLoop(
        post = { task, delayMs -> nativeDiagnosticHandler.postDelayed(task, delayMs); Unit },
        cancel = nativeDiagnosticHandler::removeCallbacks,
        sample = ::sampleNativeDiagnostics,
        canSample = {
            BuildConfig.KANTV_DIAGNOSTICS && !isFinishing() &&
                coordinator().acceptsNativeDiagnostics(nativeDiagnosticSessionId)
        },
    )

    private fun currentSnapshotValues(): DiagnosticPlaybackValues {
        val values = diagnostics.currentSnapshot()?.takeIf { it.sessionId == nativeDiagnosticSessionId }
            ?.let { NativeDiagnosticMapper.fromSnapshot(it) } ?: DiagnosticPlaybackValues()
        // Do not attach an old pixel observation to ordinary immediate events.
        return if (pictureCopyBlockedReason != NativePictureCopySafetyPolicy.NONE) {
            values.copy(visual = pictureVisualEvidence())
        } else values
    }

    fun prepare() {
        if (BuildConfig.KANTV_DIAGNOSTICS) nativeDiagnosticRecorder.prepare()
    }

    fun close() {
        samplingLoop.close()
        nativeDiagnosticSessionId = 0L
        if (BuildConfig.KANTV_DIAGNOSTICS) nativeDiagnosticRecorder.close()
    }

    fun shutdown() {
        samplingLoop.close()
        nativeDiagnosticPssExecutor.shutdown()
    }

    fun begin() {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return
        val snapshot = diagnostics.currentSnapshot() ?: return
        openNativeDiagnosticSession(snapshot.sessionId, snapshot.channelId)
    }

    fun stop(kind: DiagnosticEntryKind) {
        samplingLoop.stop(nativeDiagnosticSessionId.takeIf { it != 0L }
            ?: diagnostics.currentSnapshot()?.sessionId ?: 0L)
        if (BuildConfig.KANTV_DIAGNOSTICS && nativeDiagnosticSessionId != 0L) {
            nativeDiagnosticRecorder.event(nativeDiagnosticSessionId, kind, values = currentSnapshotValues())
        }
        nativeDiagnosticSessionId = 0L
    }

    /**
     * Native Media3 candidate fact -> existing diagnostics journal. The mapping is pure and lives in
     * the diagnostics package; this method only forwards it, so the recorder stays the single writer
     * and no second local ledger exists.
     */
    fun recordFact(fact: PlaybackDiagnosticFact) {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return
        if (fact.reason == com.kantv.app.playback.PlaybackDiagnosticReason.SESSION_ENDED) {
            // Closing belongs to the still-open session even if its snapshot has already advanced.
            if (nativeDiagnosticSessionId == 0L || fact.sessionId != nativeDiagnosticSessionId) return
            val closingSnapshot = diagnostics.currentSnapshot()?.takeIf { it.sessionId == fact.sessionId }
            nativeDiagnosticRecorder.event(
                fact.sessionId,
                DiagnosticEntryKind.STOP,
                values = NativeDiagnosticMapper.values(fact, closingSnapshot),
            )
            samplingLoop.stop(nativeDiagnosticSessionId)
            nativeDiagnosticSessionId = 0L
            return
        }
        val snapshot = diagnostics.currentSnapshot() ?: return
        // Late callbacks must not open the current snapshot on behalf of another session.
        if (fact.sessionId != snapshot.sessionId || !samplingLoop.canOpen(fact.sessionId) ||
            !coordinator().acceptsNativeDiagnostics(fact.sessionId)) return
        // Preserve facts arriving before begin(), without resetting the periodic sample deadline.
        openNativeDiagnosticSession(snapshot.sessionId, snapshot.channelId)
        if (fact.sessionId != nativeDiagnosticSessionId) return
        nativeDiagnosticRecorder.event(
            sessionId = fact.sessionId,
            kind = NativeDiagnosticMapper.entryKind(fact, fact.errorKind),
            values = NativeDiagnosticMapper.values(fact, snapshot),
        )
    }

    /** 熄屏/切后台时的会话处理：状态钉住、暂停周期采样、留痕 BACKGROUND。 */
    fun onAppBackgrounded() {
        nativeDiagnosticInBackground = true
        samplingLoop.background()
        if (BuildConfig.KANTV_DIAGNOSTICS && nativeDiagnosticSessionId != 0L) {
            nativeDiagnosticRecorder.event(nativeDiagnosticSessionId, DiagnosticEntryKind.BACKGROUND, values = currentSnapshotValues())
        }
    }

    /** 回到前台：状态复位、留痕 FOREGROUND 并恢复周期采样。 */
    fun onAppForegrounded() {
        nativeDiagnosticInBackground = false
        samplingLoop.foreground()
        if (BuildConfig.KANTV_DIAGNOSTICS && nativeDiagnosticSessionId != 0L) {
            nativeDiagnosticRecorder.event(nativeDiagnosticSessionId, DiagnosticEntryKind.FOREGROUND, values = currentSnapshotValues())
        }
    }

    /** 播放 UI 状态切换时的会话事件（PLAYBACK_FAULT / RECOVERY_*），与周期采样共用同一会话。 */
    fun onPlaybackUiStateChanged(state: PlaybackUiState, previousState: PlaybackUiState) {
        if (BuildConfig.KANTV_DIAGNOSTICS && nativeDiagnosticSessionId != 0L) {
            val kind = NativeDiagnosticMapper.uiStateEntryKind(state, previousState)
            kind?.let { nativeDiagnosticRecorder.event(nativeDiagnosticSessionId, it, values = currentSnapshotValues()) }
        }
    }

    /** 用户切换音轨成功：所选会话仍活跃时留痕 AUDIO_TRACK_CHANGED。 */
    fun onAudioTrackSelected(choiceSessionId: Long) {
        if (BuildConfig.KANTV_DIAGNOSTICS && nativeDiagnosticSessionId == choiceSessionId) {
            nativeDiagnosticRecorder.event(nativeDiagnosticSessionId, DiagnosticEntryKind.AUDIO_TRACK_CHANGED, values = currentSnapshotValues())
        }
    }

    /**
     * 用户按下"报错"：把这一刻钉住，并带上完整上下文。
     *
     * app 不再自己判断线路好坏，所以这是质量发现的**主要渠道**：用户只需一次点击，不需要描述、
     * 不需要选频道、不需要知道自己在哪条线路上。写入的是与周期样本同一套取值，所以事后一条记录
     * 就能看出"哪个频道、哪条上游线路、画面什么状态、屏幕上显示的是什么、已经持续多久"。
     *
     * 不阻塞、不弹窗、不打断播放：只给一句 1.5 秒的确认。
     */
    fun reportBadSource() {
        val snapshot = diagnostics.currentSnapshot()
        val now = SystemClock.elapsedRealtime()
        if (snapshot == null || nativeDiagnosticSessionId == 0L) {
            showTransientNotice("暂时无法记录")
            return
        }
        val (visibleState, visibleStateMs) = sampleUserVisibleState(now)
        val identity = sourceIdentityFor(coordinator().activeRequest())
        nativeDiagnosticRecorder.event(
            sessionId = snapshot.sessionId,
            kind = DiagnosticEntryKind.USER_SOURCE_REPORT,
            elapsedMs = now,
            values = NativeDiagnosticMapper.fromSnapshot(snapshot).copy(
                sourceRoute = identity?.route?.wireName?.takeIf { it.isNotBlank() } ?: "unknown",
                // The payload's own label, with the parser's literal "null" normalised away: the line
                // genuinely declared no quality, and "unknown" says that without pretending otherwise.
                sourceQuality = identity?.quality
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    ?: "unknown",
                sourceUrlKey = identity?.urlKey?.take(SOURCE_URL_KEY_PREFIX) ?: "unknown",
                sourceScoreUpstream = identity?.score ?: -1,
                userVisibleState = visibleState,
                userVisibleStateMs = visibleStateMs,
                visual = pictureVisualEvidence(),
            ),
        )
        // 回到画面再给确认：控制面板此刻正盖在提示上，不先收起就什么也看不见。
        showFullscreen()
        showTransientNotice("已记录，正在检查")
    }

    /**
     * Opens the existing diagnostics journal for one native session.
     *
     * The journal requires a validated catalogue identifier, so a malformed identity disables native
     * journaling for this session instead of letting the recorder throw on the playback path.
     */
    private fun openNativeDiagnosticSession(sessionId: Long, channelId: String) {
        if (!BuildConfig.KANTV_DIAGNOSTICS || !samplingLoop.canOpen(sessionId) ||
            !coordinator().acceptsNativeDiagnostics(sessionId)) return
        if (sessionId == nativeDiagnosticSessionId) {
            samplingLoop.start(sessionId)
            return
        }
        if (!CHANNEL_ID_PATTERN.matches(channelId)) {
            if (rejectedDiagnosticSessionId != sessionId) {
                android.util.Log.w("KanTvDiag", "native diagnostics disabled: invalid catalogue identifier")
                rejectedDiagnosticSessionId = sessionId
            }
            return
        }
        rejectedDiagnosticSessionId = 0L
        nativeDiagnosticSessionId = sessionId
        nativeDiagnosticRecorder.start(
            channelId,
            DiagnosticProvider.M3U,
            sessionId,
        )
        samplingLoop.start(sessionId)
    }

    /** Bounded periodic sample: PTS progress, underruns, delivery, and prefetch counters. */
    private fun sampleNativeDiagnostics() {
        if (!BuildConfig.KANTV_DIAGNOSTICS) return
        // 这两个 return 是**正常的过渡态**，不是故障：没有快照说明这一轮还没有会话可采；会话号不匹配
        // 说明选源正在切换。当初为"原生路径为何零样本"加的临时日志已撤除——那个问题已有结论
        // （采样本来就是活的，早期读到的是刚开工的片段），而真正要观测的东西现在由样本字段承载：
        // 源身份、画面判定、用户可见状态，任何一条样本都自带完整上下文。
        val snapshot = diagnostics.currentSnapshot() ?: return
        if (!coordinator().acceptsNativeDiagnostics(snapshot.sessionId)) return
        openNativeDiagnosticSession(snapshot.sessionId, snapshot.channelId)
        if (snapshot.sessionId != nativeDiagnosticSessionId) return
        val now = SystemClock.elapsedRealtime()
        // 画面运动观测排在最前：它异步发起抓帧，本次样本写入的是上一次的结论，滞后一个周期。
        samplePictureMotion(snapshot, now)
        val (visibleState, visibleStateMs) = sampleUserVisibleState(now)
        val sourceIdentity = sourceIdentityFor(coordinator().activeRequest())
        if (now - nativeDiagnosticPssAtMs >= 30_000 && !nativeDiagnosticPssSampling) {
            nativeDiagnosticPssAtMs = now
            nativeDiagnosticPssSampling = true
            nativeDiagnosticPssExecutor.execute {
                val sampled = runCatching { android.os.Debug.getPss().toLong() }.getOrDefault(-1L)
                nativeDiagnosticPssKb = sampled
                nativeDiagnosticPssSampling = false
            }
        }
        val video = snapshot.tracks.firstOrNull { it.kind == PlaybackTrackKind.VIDEO && it.selected }
            ?: snapshot.tracks.firstOrNull { it.kind == PlaybackTrackKind.VIDEO }
        val timing = snapshot.audioTiming
        val progress = snapshot.videoProgressCount
        val presented = presentedFramesForDiagnostics(timing)
        nativeDiagnosticRecorder.sample(OfficialWebDiagnosticSample(
            sessionId = snapshot.sessionId,
            elapsedMs = now,
            values = NativeDiagnosticMapper.fromSnapshot(snapshot).copy(
                frames = progress,
                width = video?.width?.coerceAtLeast(0) ?: 0,
                height = video?.height?.coerceAtLeast(0) ?: 0,
                processPssKb = nativeDiagnosticPssKb,
                kernelCode = snapshot.kernel?.ordinal?.plus(1) ?: 0,
                pictureModeCode = PictureMode.FILL.ordinal + 1,
                readyState = when (playbackUiState()) {
                    is PlaybackUiState.Ready -> 4
                    is PlaybackUiState.Buffering -> 2
                    is PlaybackUiState.Loading, is PlaybackUiState.Recovering -> 1
                    else -> 0
                },
                paused = false,
                buffering = playbackUiState() is PlaybackUiState.Buffering ||
                    playbackUiState() is PlaybackUiState.Loading ||
                    playbackUiState() is PlaybackUiState.Recovering,
                videoDecoderName = snapshot.videoDecoderName ?: "unknown",
                audioDecoderName = snapshot.audioDecoderName ?: "unknown",
                audioMimeType = snapshot.audioMimeType ?: "unknown",
                audioCodec = snapshot.audioCodec ?: "unknown",
                audioChannelCount = snapshot.audioChannelCount,
                audioSampleRate = snapshot.audioSampleRate,
                lostAudioBuffers = timing?.lostBuffers?.toLong()?.coerceAtLeast(-1)
                    ?: snapshot.audioUnderrunCount.toLong(),
                selectedAudioTrackId = timing?.selectedTrackId ?: -1,
                configuredAudioDelayUs = timing?.configuredDelayUs ?: 0,
                sourceAudioPid1 = timing?.sourceAudioPid1 ?: -1,
                sourceAudioMinusVideoMs1 = timing?.sourceAudioMinusVideoMs1 ?: 0,
                sourceAudioPid2 = timing?.sourceAudioPid2 ?: -1,
                sourceAudioMinusVideoMs2 = timing?.sourceAudioMinusVideoMs2 ?: 0,
                readBytes = timing?.readBytes ?: -1,
                demuxReadBytes = timing?.demuxReadBytes ?: -1,
                inputKbps = timing?.inputKbps ?: -1,
                demuxKbps = timing?.demuxKbps ?: -1,
                demuxCorrupted = timing?.demuxCorrupted?.toLong()?.coerceAtLeast(-1) ?: -1,
                demuxDiscontinuity = timing?.demuxDiscontinuity?.toLong()?.coerceAtLeast(-1) ?: -1,
                decodedAudio = timing?.decodedAudio?.toLong()?.coerceAtLeast(-1) ?: -1,
                playedAudioBuffers = timing?.playedBuffers?.toLong()?.coerceAtLeast(-1) ?: -1,
                videoFirstFrameRenderTimeMs = snapshot.videoFirstFrameRenderTimeMs.coerceAtLeast(-1),
                videoFirstFrameSource = snapshot.videoFirstFrameSource?.name ?: "unknown",
                actualPresentedAvailability = snapshot.actualPresentedAvailability.name,
                tsInputSource = snapshot.transportStreamObservation?.inputSource?.name ?: "unknown",
                tsPcrPid = snapshot.transportStreamObservation?.pcrPid ?: -1,
                tsPcrBackwardJumpCount = snapshot.transportStreamObservation?.pcrBackwardJumpCount ?: -1,
                tsAdaptationDiscontinuityCount = snapshot.transportStreamObservation
                    ?.adaptationDiscontinuityCount ?: -1,
                tsHlsDiscontinuity = snapshot.transportStreamObservation?.hlsDiscontinuity ?: false,
                tsHlsDiscontinuitySequence = snapshot.transportStreamObservation
                    ?.hlsDiscontinuitySequence ?: -1,
                tsWouldRepair = snapshot.transportStreamObservation?.wouldRepair ?: false,
                sourceRoute = sourceIdentity?.route?.wireName?.takeIf { it.isNotBlank() } ?: "unknown",
                sourceQuality = sourceIdentity?.quality
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    ?: "unknown",
                sourceUrlKey = sourceIdentity?.urlKey?.take(SOURCE_URL_KEY_PREFIX) ?: "unknown",
                sourceScoreUpstream = sourceIdentity?.score ?: -1,
                userVisibleState = visibleState,
                userVisibleStateMs = visibleStateMs,
                visual = pictureVisualEvidence(),
            ),
            menuVisible = !isFullscreen() || isSourceMenuShowing(),
            appInBackground = nativeDiagnosticInBackground,
        ))
        // Grey-screen evidence for the native path: the sampler is the only place that sees a
        // frozen picture without new frame callbacks.
        coordinator().sampleGreyScreenEvidence()
    }

    /**
     * Samples the picture itself, so "the decoder is running but the picture never changes" is
     * visible in a report.
     *
     * Reuses the existing periodic tick instead of adding a timer. The picture is only judged when
     * it is really the picture on screen: an overlay would be captured instead of video, and a
     * candidate that is still opening is not being asked to show anything yet.
     *
     * Java failures make the observation unavailable. PixelCopy also enters the native GPU/driver
     * path; a driver fatal cannot be caught here. The debug-only off control isolates that path.
     */
    private fun samplePictureMotion(snapshot: PlaybackDiagnosticSnapshot, now: Long) {
        if (pictureCopyBlockedReason != NativePictureCopySafetyPolicy.NONE) return
        val judgeable = isFullscreen() &&
            !isSourceMenuShowing() &&
            playbackUiState() is PlaybackUiState.Ready
        if (!judgeable) {
            // Deliberately not looking: the elapsed count must not grow while an overlay covers the
            // picture, or a menu left open would read as a minute of stillness.
            pictureMotionObservation = pictureMotion.onNotObserved(now)
            return
        }
        if (pictureCaptureInFlight) return
        if (now - lastPictureCaptureAtMs < PICTURE_CAPTURE_INTERVAL_MS) return
        val generation = snapshot.runtimeGeneration
        val bitmap = pictureCaptureBitmap
            ?: Bitmap.createBitmap(PICTURE_CAPTURE_WIDTH, PICTURE_CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888)
                .also { pictureCaptureBitmap = it }
        pictureCaptureInFlight = true
        pictureCaptureStartedMs = SystemClock.elapsedRealtime()
        val token = ++pictureCaptureToken
        requestPictureCopy(token, bitmap, generation, attempt = 0)
        // This main-handler watchdog only abandons a pending Java observation after request returns;
        // it cannot bound a synchronous native block or cancel GPU work. No buffer is recycled here.
        // A missing callback records the existing timeout sentinel (-2).
        nativeDiagnosticHandler.postDelayed({
            if (token == pictureCaptureToken && pictureCaptureInFlight) {
                if (BuildConfig.DEBUG) debugPictureCopyTimeouts++
                pictureCaptureToken++
                pictureCaptureInFlight = false
                lastPictureCaptureAtMs = SystemClock.elapsedRealtime()
                pictureMotionObservation = pictureMotion.onSample(
                    nowMs = lastPictureCaptureAtMs,
                    sourceGeneration = generation,
                    signature = null,
                    captureMs = PICTURE_CAPTURE_TIMEOUT_MS,
                )
            }
        }, PICTURE_CAPTURE_TIMEOUT_MS)
    }

    /**
     * Copies the picture from wherever the video is actually drawn.
     *
     * A window copy only sees what the compositor put into the window buffer. Where the player draws
     * into a separate surface — which is what a TV box does, and the reason a real device reported a
     * black frame on every native sample while the emulator reported a real picture — a window copy
     * is blind. So the video surface is asked first, and the window is kept as the fallback for
     * devices that draw into the window instead. A target that throws is treated as a failed target,
     * never as a verdict: failing to look must not become evidence of anything.
     */
    private fun requestPictureCopy(token: Int, bitmap: Bitmap, generation: Long, attempt: Int) {
        val targets = pictureCopyTargets()
        val target = targets.getOrNull(attempt)
        if (target == null) {
            completePictureCopy(token, bitmap, null, generation, SystemClock.elapsedRealtime())
            return
        }
        val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
            if (token != pictureCaptureToken) return@OnPixelCopyFinishedListener
            if (BuildConfig.DEBUG) debugPictureCopyFinished++
            if (result != PixelCopy.SUCCESS && attempt + 1 < targets.size) {
                requestPictureCopy(token, bitmap, generation, attempt + 1)
                return@OnPixelCopyFinishedListener
            }
            completePictureCopy(
                token = token,
                bitmap = bitmap,
                result = if (result == PixelCopy.SUCCESS) PixelCopy.SUCCESS else null,
                generation = generation,
                finishedAtMs = SystemClock.elapsedRealtime(),
            )
        }
        if (BuildConfig.DEBUG) {
            debugPictureCopyIssued++
            when (target) {
                is android.view.SurfaceView -> debugPictureCopySurfaceIssued++
                is android.view.Window -> debugPictureCopyWindowIssued++
            }
            debugLogPictureCopy(token, target)
        }
        val requested = runCatching {
            when (target) {
                is android.view.SurfaceView -> PixelCopy.request(target, bitmap, listener, nativeDiagnosticHandler)
                is android.view.Window -> PixelCopy.request(target, bitmap, listener, nativeDiagnosticHandler)
                else -> error("unsupported copy target")
            }
        }.isSuccess
        if (requested) return
        if (BuildConfig.DEBUG) debugPictureCopyCallFailures++
        if (attempt + 1 < targets.size) {
            requestPictureCopy(token, bitmap, generation, attempt + 1)
        } else {
            completePictureCopy(token, bitmap, null, generation, SystemClock.elapsedRealtime())
        }
    }

    /** One line per capture, capped per sampler. Terminal totals appear on the next issue line.
     * `issued` counts API attempts (including calls that throw), `finished` accepted callbacks;
     * stale callbacks remain ignored by the original token gate. No bitmap/payload is logged.
     */
    private fun debugLogPictureCopy(token: Int, target: Any) {
        if (!BuildConfig.DEBUG) return
        if (debugPictureCopyLastLoggedToken == token || debugPictureCopyLogCount >= 256) return
        debugPictureCopyLastLoggedToken = token
        debugPictureCopyLogCount++
        runCatching {
            val targetCode = when (target) {
                is android.view.SurfaceView -> 1
                is android.view.Window -> 2
                else -> 0
            }
            android.util.Log.d(
                "KanTvGlControl",
                "pixel_copy elapsedMs=${SystemClock.elapsedRealtime()} capture=${debugPictureCopyLogCount}" +
                    " target=$targetCode issued=$debugPictureCopyIssued surfaceIssued=$debugPictureCopySurfaceIssued" +
                    " windowIssued=$debugPictureCopyWindowIssued finished=$debugPictureCopyFinished" +
                    " timeouts=$debugPictureCopyTimeouts callFailures=$debugPictureCopyCallFailures",
            )
        }
    }

    /** [result] is [PixelCopy.SUCCESS] to read the bitmap, or null when no usable frame arrived. */
    private fun completePictureCopy(
        token: Int,
        bitmap: Bitmap,
        result: Int?,
        generation: Long,
        finishedAtMs: Long,
    ) {
        if (token != pictureCaptureToken) return
        pictureCaptureInFlight = false
        lastPictureCaptureAtMs = finishedAtMs
        pictureMotionObservation = pictureMotion.onSample(
            nowMs = finishedAtMs,
            sourceGeneration = generation,
            signature = if (result == PixelCopy.SUCCESS) signatureOf(bitmap) else null,
            captureMs = finishedAtMs - pictureCaptureStartedMs,
        )
    }

    /** 一句短确认，1.5 秒后自动消失；播放不受任何影响。 */
    private fun showTransientNotice(text: String) {
        showStatus(text)
        noticeHandler.removeCallbacksAndMessages(null)
        noticeHandler.postDelayed({
            if (!isFinishing()) renderPlaybackUiState(playbackUiState())
        }, NOTICE_VISIBLE_MS)
    }

    /** Downscaled luma signature: the only thing kept from a frame, and it is never persisted. */
    private fun signatureOf(bitmap: Bitmap): DoubleArray? = runCatching {
        val pixels = IntArray(PICTURE_CAPTURE_WIDTH * PICTURE_CAPTURE_HEIGHT)
        bitmap.getPixels(pixels, 0, PICTURE_CAPTURE_WIDTH, 0, 0, PICTURE_CAPTURE_WIDTH, PICTURE_CAPTURE_HEIGHT)
        DoubleArray(pixels.size) { index ->
            val color = pixels[index]
            (Color.red(color) * 0.2126) + (Color.green(color) * 0.7152) + (Color.blue(color) * 0.0722)
        }
    }.getOrNull()

    /**
     * The upstream's own names for the candidate that is playing, so a report can be discussed in the
     * publisher's terms instead of only in this app's internal digest. Resolved by address from the hub
     * payload the catalogue already holds; null when the candidate did not come from there.
     */
    private fun sourceIdentityFor(request: PlaybackRequest?): HubRoutePlan? {
        if (request == null) return null
        return runCatching {
            hubRoutes().plansForInternal(request.channelId).firstOrNull { it.uri == request.uri }
        }.getOrNull()
    }

    /**
     * What the viewer was shown on screen, as a bounded value, plus how long it has been that way.
     *
     * This is the closest thing to an answer for "why does this feel bad": the viewer's experience is
     * the sentence and the spinner in front of them. Derived from what the app is actually displaying,
     * never from an opinion about playback health — the app's own opinion was wrong before.
     */
    private fun sampleUserVisibleState(now: Long): Pair<String, Long> {
        val state = when {
            nativeDiagnosticInBackground -> "BACKGROUND"
            isSourceMenuShowing() || !isFullscreen() -> "MENU_OPEN"
            playbackUiState() is PlaybackUiState.Failed -> "FAILED_NOTICE"
            playbackUiState() is PlaybackUiState.Recovering -> "RECOVERING"
            playbackUiState() is PlaybackUiState.Buffering -> "BUFFERING"
            playbackUiState() is PlaybackUiState.Loading ->
                if (firstChannelOpening()) "FIRST_OPEN" else "CONNECTING"
            playbackUiState() is PlaybackUiState.Ready -> "PLAYING"
            else -> "unknown"
        }
        if (state != lastUserVisibleState) {
            lastUserVisibleState = state
            lastUserVisibleStateAtMs = now
        }
        val since = if (lastUserVisibleStateAtMs <= 0L) -1L else (now - lastUserVisibleStateAtMs).coerceAtLeast(0L)
        return state to since
    }

    /** Pixel outcomes retain their existing enums; schema22 adds a fixed blocked reason. */
    private fun pictureVisualEvidence(): DiagnosticPlaybackValues.VisualEvidence {
        if (pictureCopyBlockedReason != NativePictureCopySafetyPolicy.NONE) {
            return DiagnosticPlaybackValues.VisualEvidence(
                sampleOutcome = "NO_SAMPLE",
                sampleBlockedReason = pictureCopyBlockedReason,
            )
        }
        val observation = pictureMotionObservation
            ?: return DiagnosticPlaybackValues.VisualEvidence(sampleBlockedReason = NativePictureCopySafetyPolicy.NONE)
        val outcome = when (observation.motion) {
            PictureMotion.MOVING -> "DETAIL"
            PictureMotion.STILL -> "FLAT_NEUTRAL"
            PictureMotion.UNVERIFIABLE -> "FLAT_OTHER"
            PictureMotion.UNAVAILABLE -> "NO_SAMPLE"
        }
        return DiagnosticPlaybackValues.VisualEvidence(
            sampleOutcome = outcome,
            sampleCallbackMs = observation.captureMs.coerceAtLeast(-2L),
            millisSinceVerified = observation.millisSincePictureVerified.coerceAtLeast(-1L),
            sampleBlockedReason = NativePictureCopySafetyPolicy.NONE,
        )
    }

    private companion object {
        /** Same identity rule the diagnostics journal enforces before it opens a session. */
        val CHANNEL_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")

        /**
         * Picture observation cadence. Deliberately slower than the 3 s diagnostics tick: this is a
         * GPU readback on a low-power TV box, and the verdict only needs to be fine enough to read a
         * "still for N seconds" duration, not to track frames.
         */
        const val PICTURE_CAPTURE_INTERVAL_MS = 15_000L

        /** A capture this slow is treated as unavailable; the report already reserves -2 for it. */
        const val PICTURE_CAPTURE_TIMEOUT_MS = 2_000L

        /** Small destination signature; it does not bound full-surface GPU work or driver resources. */
        const val PICTURE_CAPTURE_WIDTH = 64
        const val PICTURE_CAPTURE_HEIGHT = 36

        /** Enough of the server digest to identify a line without carrying the whole key. */
        const val SOURCE_URL_KEY_PREFIX = 8

        /** 与"已自动恢复"一致：一次短确认，1.5 秒后消失。 */
        const val NOTICE_VISIBLE_MS = 1_500L
    }
}

/** Main-thread lifecycle gate: facts never change the one pending sample deadline. */
internal class NativeDiagnosticSamplingLoop(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val sample: () -> Unit,
    private val canSample: () -> Boolean,
) {
    private var sessionId = 0L
    private var stoppedSessionId = 0L
    private var inBackground = false
    private var closed = false
    private var generation = 0L
    private var pending: Runnable? = null
    private var running = false

    fun canOpen(id: Long): Boolean = !closed && id != 0L && id != stoppedSessionId

    fun start(id: Long) {
        if (!canOpen(id)) return
        if (sessionId != id) {
            cancelPending()
            sessionId = id
        }
        schedule(0L)
    }

    fun stop(id: Long) {
        if (id != 0L) stoppedSessionId = id
        sessionId = 0L
        cancelPending()
    }

    fun background() {
        inBackground = true
        cancelPending()
    }

    fun foreground() {
        inBackground = false
        schedule(0L)
    }

    fun close() {
        closed = true
        sessionId = 0L
        cancelPending()
    }

    private fun cancelPending() {
        generation++
        pending?.let(cancel)
        pending = null
    }

    private fun schedule(delayMs: Long) {
        if (closed || inBackground || sessionId == 0L || pending != null || running) return
        val expectedGeneration = generation
        val task = Runnable {
            if (expectedGeneration != generation || closed || inBackground || sessionId == 0L) return@Runnable
            pending = null
            if (!canSample()) return@Runnable
            running = true
            try {
                sample()
            } finally {
                running = false
                // A reentrant stop/background must not let the executing callback resurrect its loop.
                if (expectedGeneration == generation) schedule(INTERVAL_MS)
                else schedule(0L)
            }
        }
        pending = task
        post(task, delayMs)
    }

    companion object {
        const val INTERVAL_MS = 3_000L
    }
}
