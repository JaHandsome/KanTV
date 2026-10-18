// KanTV MSE 镜像桥（Gecko content script，隔离世界）。
// 数据通道：页面主域 hook（MseMirrorScript.geckoInstallScript 经 javascript: URL 注入）
// 攒批 postMessage {__kantvMirrorBatch, ep} → 本脚本经 browser.runtime.sendNativeMessage
// 推给 GeckoMirrorLiveActivity → MseMirrorServer.offer（行协议解析）。
// 注意：曾用 content script fetch 回环 POST /offer，实测被页面 CSP 拦（CCTV 官网
// connect-src 不含 127.0.0.1，NetworkError）；原生消息不受页面 CSP 约束。
// 信号通道：页面状态/就绪信号同样走 sendNativeMessage。
// DOM 层（querySelector/click/play/muted）在 content script 与页面共享，Xray 包装
// 允许 DOM 属性与方法操作（TVB 财经探针已验证同一套手法）。
(() => {
  // manifest 需要 all_frames 覆盖官网的播放器加载形态，但状态只能由顶层文档
  // 上报：子 frame 的 ready=0/noVideo 会覆盖真正播放页的连续帧观测。
  if (window.top !== window) return;
  if (window.__kantvBridgeInstalled) return;
  window.__kantvBridgeInstalled = 1;
  const native = (msg) => {
    try {
      const p = browser.runtime.sendNativeMessage('kantvAvProbe', msg);
      if (p && p.catch) p.catch(() => {
        try { browser.runtime.sendNativeMessage('kantvAvProbe', { kind: 'error', where: 'native-reject' }); } catch (e2) {}
      });
    } catch (e) {
      try { browser.runtime.sendNativeMessage('kantvAvProbe', { kind: 'error', where: 'native-throw' }); } catch (e2) {}
    }
  };
  native({ kind: 'bridge' });
  // 缓冲事实：只取**包含 currentTime** 的那一段 range（不是最后一段），换算成毫秒。
  // 没有 range、或 currentTime 不在任何 range 内，一律报 -1（未知），绝不把未知写成 0。
  const BOUNDED_MEDIA_MS = Number.MAX_SAFE_INTEGER;
  const bufferedFacts = (video) => {
    const ranges = video.buffered;
    const length = ranges && Number.isFinite(ranges.length) ? Math.floor(ranges.length) : 0;
    const now = Number.isFinite(video.currentTime) ? video.currentTime : -1;
    if (now < 0) return { positionMs: -1, durationMs: -1 };
    for (let i = 0; i < length; i++) {
      const start = ranges.start(i);
      const end = ranges.end(i);
      if (!Number.isFinite(start) || !Number.isFinite(end)) continue;
      // 直播前沿常带极小的浮点误差，比较时留千分之一秒容差。
      if (now + 0.001 >= start && now <= end + 0.001) {
        return {
          positionMs: Math.max(0, Math.min(BOUNDED_MEDIA_MS, Math.round(end * 1000))),
          durationMs: Math.max(0, Math.min(BOUNDED_MEDIA_MS, Math.round((end - now) * 1000))),
        };
      }
    }
    return { positionMs: -1, durationMs: -1 };
  };
  // Passive browser-compositor evidence. The observer never changes playback/audio routing.
  // Document clock anchors are reported as separate facts; they are never mixed with host clocks.
  const documentId = Math.floor(performance.timeOrigin * 1000);
  const bounded = (n, max = Number.MAX_SAFE_INTEGER) =>
    Number.isFinite(n) && n >= 0 ? Math.min(max, Math.floor(n)) : -1;
  const MAX_TICK_DURATION_MS = 86400000;
  const phaseUnknown = code => ({ duration: -1, maxCode: code, maxDuration: -1, scanNodes: -1 });
  let lastTickWork = phaseUnknown('UNKNOWN');
  let activeTickWork = null;
  const workNow = () => {
    try {
      const now = performance.now();
      return Number.isFinite(now) && now >= 0 ? now : -1;
    } catch (_) { return -1; }
  };
  const workDuration = (start, end) => start >= 0 && end >= start
    ? bounded(end - start, MAX_TICK_DURATION_MS) : -1;
  const workPhase = code => {
    const work = activeTickWork;
    const now = workNow();
    if (work.at < 0 || now < work.at) work.valid = false;
    const duration = now - work.at;
    if (work.valid && duration > work.maxRawDuration) {
      work.maxRawDuration = duration;
      work.maxCode = work.code;
    }
    work.code = code;
    work.at = now;
    return now;
  };
  const invalidateObservedWork = request => {
    request.workInvalid = true;
    request.trace.geckoAudioQueryObservedWorkCount = -1;
    request.trace.geckoAudioQueryObservedWorkTotalMs = -1;
    request.trace.geckoAudioQueryObservedWorkMaxMs = -1;
    request.trace.geckoAudioQueryObservedWorkMaxPhaseCode = 'UNKNOWN';
    request.trace.geckoAudioQueryObservedWorkMaxPhaseMs = -1;
    request.trace.geckoAudioQueryObservedWorkMaxScanNodes = -1;
  };
  const recordObservedWork = (request, work, end, valid) => {
    if (!request || request.workSettled || request.workInvalid) return;
    if (!valid || request.video !== observedVideo || request.node !== nodeGeneration ||
        request.epoch !== audioEpoch) {
      invalidateObservedWork(request);
      return;
    }
    const rawDuration = end - work.start;
    request.workCount = Math.min(MAX_QUERY_RVFC_COUNT, request.workCount + 1);
    request.workTotalRaw = Math.min(MAX_TICK_DURATION_MS, request.workTotalRaw + rawDuration);
    request.workMaxRaw = Math.max(request.workMaxRaw, rawDuration);
    if (work.maxRawDuration > request.workPhaseRaw) {
      request.workPhaseRaw = work.maxRawDuration;
      request.workPhaseCode = work.maxCode;
    }
    request.workMaxScanNodes = Math.max(request.workMaxScanNodes, work.scanNodes);
    const phaseMs = bounded(request.workPhaseRaw, MAX_TICK_DURATION_MS);
    request.trace.geckoAudioQueryObservedWorkCount = request.workCount;
    request.trace.geckoAudioQueryObservedWorkTotalMs = bounded(request.workTotalRaw, MAX_TICK_DURATION_MS);
    request.trace.geckoAudioQueryObservedWorkMaxMs = bounded(request.workMaxRaw, MAX_TICK_DURATION_MS);
    request.trace.geckoAudioQueryObservedWorkMaxPhaseCode = phaseMs > 0 ? request.workPhaseCode : 'NONE';
    request.trace.geckoAudioQueryObservedWorkMaxPhaseMs = phaseMs;
    request.trace.geckoAudioQueryObservedWorkMaxScanNodes = request.workMaxScanNodes;
  };
  const finishTickWork = failed => {
    const work = activeTickWork;
    const end = workPhase('NONE');
    const sameIdentity = work.video === observedVideo && work.node === nodeGeneration && work.startupIdentity === startupTrace;
    const duration = workDuration(work.start, end);
    const valid = !failed && work.valid && sameIdentity && duration >= 0;
    const maxDuration = bounded(work.maxRawDuration, MAX_TICK_DURATION_MS);
    lastTickWork = valid ? { duration, maxCode: maxDuration > 0 ? work.maxCode : 'NONE',
      maxDuration, scanNodes: work.scanNodes } : phaseUnknown('UNKNOWN');
    // A state packet was already built/sent; these completed-task facts first appear next tick.
    const request = work.query;
    if (request) {
      const tail = workDuration(request.syncReturnedAt, end);
      const queryValid = valid && request.video === observedVideo && request.node === nodeGeneration &&
        request.epoch === audioEpoch && tail >= 0;
      const facts = queryValid ? lastTickWork : phaseUnknown('UNKNOWN');
      Object.assign(request.trace, {
        geckoAudioQueryTickDurationMs: facts.duration,
        geckoAudioQueryTickTailDurationMs: queryValid ? tail : -1,
        geckoAudioQueryTickMaxPhaseCode: facts.maxCode,
        geckoAudioQueryTickMaxPhaseDurationMs: facts.maxDuration,
        geckoAudioQueryTickScanNodes: facts.scanNodes,
      });
    }
    // Include every own interval task before the first Promise reaction, even after soft timeout.
    // No extra clock read: use the already measured full task and its phase maxima.
    recordObservedWork(work.windowRequest, work, end, valid);
    if (work.startup) {
      const startupDuration = workDuration(work.startupAt, end);
      if (!valid || startupDuration < 0 || work.startup !== startupTrace) {
        startupTrace.geckoStartupObserverTickCount = -1;
        startupTrace.geckoStartupObserverTotalDurationMs = -1;
        startupTrace.geckoStartupObserverMaxTickDurationMs = -1;
      } else if (startupTrace.geckoStartupObserverTickCount >= 0) {
        startupTrace.geckoStartupObserverTickCount = Math.min(MAX_QUERY_RVFC_COUNT,
          startupTrace.geckoStartupObserverTickCount + 1);
        startupTrace.geckoStartupObserverTotalDurationMs = Math.min(MAX_TICK_DURATION_MS,
          startupTrace.geckoStartupObserverTotalDurationMs + startupDuration);
        startupTrace.geckoStartupObserverMaxTickDurationMs = Math.max(
          startupTrace.geckoStartupObserverMaxTickDurationMs, startupDuration);
      }
    }
    activeTickWork = null;
  };
  const MAX_QUERY_RVFC_COUNT = 1000000;
  const MAX_QUERY_RVFC_GAP_MS = 86400000;
  let observedVideo = null;
  let nodeGeneration = 0;
  let callbackId = null;
  let callbackEpoch = 0;
  let callbackEvidence = null;
  let callbackSupported = false;
  let diagnosticsEnabled = false;
  let audioObservationAllowed = false;
  let audioObservationEnabled = false;
  let audioEpoch = 0;
  let audioBlocked = false;
  let audioInFlight = null;
  let audioCache = null;
  let lastAudioCallAt = -1;
  let advancingSince = -1;
  let audioVisibilityObserver = null;
  let audioQuerySeq = 0;
  let audioQueryTrace = null;
  // Two automatic diagnostic recoveries per document, never reset by node/epoch churn.
  // Playback is untouched. A synchronous slow/unknown call is never retried automatically.
  let audioRecoveryCount = 0;
  let audioRetry = null;
  let lastAudioEligibilityAt = -1;
  let firstAudioFault = null;
  let firstAudioFaultRequest = null;
  // Guard raw DOM time before comparisons: NaN/Infinity can otherwise bypass every gate.
  // The high-water mark belongs to this document and is not reset by node/epoch churn.
  const validAudioNow = now => Number.isFinite(now) && now >= 0 && now <= Number.MAX_SAFE_INTEGER &&
    now >= lastAudioEligibilityAt &&
    (lastTraceTickAt < 0 || (Number.isFinite(lastTraceTickAt) && now >= lastTraceTickAt)) &&
    (!audioRetry || now >= audioRetry.settledAt);
  const faultFacts = () => firstAudioFault || {
    geckoAudioFirstFaultSeq: -1, geckoAudioFirstFaultEpoch: -1, geckoAudioFirstFaultNode: -1,
    geckoAudioFirstFaultStartMs: -1, geckoAudioFirstFaultSyncCallMs: -1,
    geckoAudioFirstFaultReactionElapsedMs: -1, geckoAudioFirstFaultTimeoutLateMs: -1,
    geckoAudioFirstFaultJsTickCount: -1, geckoAudioFirstFaultMaxJsTickGapMs: -1,
    geckoAudioFirstFaultState: 'NOT_APPLICABLE', geckoAudioFirstFaultDisposition: 'NOT_APPLICABLE',
    geckoAudioFirstFaultClockStatus: 'NOT_APPLICABLE',
  };
  const preserveFirstFault = (request, status) => {
    if (firstAudioFault) return;
    firstAudioFaultRequest = request;
    const t = request.trace;
    firstAudioFault = {
      geckoAudioFirstFaultSeq: t.geckoAudioQuerySeq, geckoAudioFirstFaultEpoch: t.geckoAudioQueryEpoch,
      geckoAudioFirstFaultNode: t.geckoAudioQueryNode, geckoAudioFirstFaultStartMs: t.geckoAudioQueryStartMs,
      geckoAudioFirstFaultSyncCallMs: t.geckoAudioSyncCallMs,
      geckoAudioFirstFaultReactionElapsedMs: t.geckoAudioReactionElapsedMs,
      geckoAudioFirstFaultTimeoutLateMs: t.geckoAudioTimeoutLateMs,
      geckoAudioFirstFaultJsTickCount: t.geckoAudioQueryJsTickCount,
      geckoAudioFirstFaultMaxJsTickGapMs: t.geckoAudioQueryMaxJsTickGapMs,
      geckoAudioFirstFaultState: t.geckoAudioQueryState,
      geckoAudioFirstFaultDisposition: t.geckoAudioQueryDisposition,
      geckoAudioFirstFaultClockStatus: status,
    };
  };
  let lastTraceTickAt = -1;
  let traceTickGapMs = -1;
  const emptyStartupTrace = () => ({
    geckoNodeBoundMs: -1, geckoLoadedMetadataMs: -1, geckoPlayMs: -1,
    geckoPlayingMs: -1, geckoWaitingMs: -1, geckoCanPlayMs: -1,
    geckoFirstRvfcMs: -1, geckoFirstRvfcPtsUs: -1, geckoAttachLate: false,
    geckoStartupObserverTickCount: 0, geckoStartupObserverTotalDurationMs: 0,
    geckoStartupObserverMaxTickDurationMs: 0,
  });
  let startupTrace = emptyStartupTrace();
  const startupMilestone = field => {
    if (startupTrace[field] < 0) startupTrace[field] = bounded(performance.now());
  };
  const bindStartupResources = () => {
    if (!observedVideo) return;
    try {
      const resources = globalThis.__kantvStartupResources;
      if (resources && typeof resources.bind === 'function') resources.bind(startupTrace);
    } catch (_) { /* Resource timing is optional and cannot interrupt node/resource binding. */ }
  };
  const startupResourceFacts = applicable => {
    const unknown = {
      geckoStartupResourceObservationStatus: applicable ? 'UNSUPPORTED' : 'NOT_APPLICABLE',
      geckoStartupResourceCount: -1, geckoStartupResourceMaxDurationMs: -1,
      geckoStartupResourceMaxResponseWaitMs: -1, geckoStartupResourceMaxDownloadMs: -1,
      geckoStartupResourceDetailedCount: -1,
      geckoStartupResourceScannedCount: -1, geckoStartupResourceFilteredCount: -1,
      geckoStartupResourceMaxStartMs: -1, geckoStartupResourceMaxEndMs: -1,
      geckoStartupResourceMaxInitiatorCode: 'NOT_APPLICABLE',
    };
    try {
      const resources = globalThis.__kantvStartupResources;
      return resources && typeof resources.facts === 'function'
        ? resources.facts(applicable, observedVideo ? startupTrace : null) : unknown;
    } catch (_) { return unknown; }
  };
  const traceFacts = () => {
    const applicable = audioObservationEnabled;
    const documentNow = applicable ? performance.now() : -1;
    const documentNowMs = applicable ? bounded(documentNow) : -1;
    const documentTimeOriginMs = applicable ? bounded(performance.timeOrigin) : -1;
    return Object.assign({}, applicable ? startupTrace : emptyStartupTrace(), {
      geckoDocumentNowMs: documentNowMs,
      geckoDocumentTimeOriginMs: documentTimeOriginMs,
      geckoJsTickGapMs: applicable ? traceTickGapMs : -1,
      geckoBridgeLastTickDurationMs: applicable ? lastTickWork.duration : -1,
      geckoBridgeLastTickMaxPhaseCode: applicable ? lastTickWork.maxCode : 'NOT_APPLICABLE',
      geckoBridgeLastTickMaxPhaseDurationMs: applicable ? lastTickWork.maxDuration : -1,
      geckoBridgeLastTickScanNodes: applicable ? lastTickWork.scanNodes : -1,
      geckoStartupObserverTickCount: applicable && observedVideo ? startupTrace.geckoStartupObserverTickCount : -1,
      geckoStartupObserverTotalDurationMs: applicable && observedVideo ? startupTrace.geckoStartupObserverTotalDurationMs : -1,
      geckoStartupObserverMaxTickDurationMs: applicable && observedVideo ? startupTrace.geckoStartupObserverMaxTickDurationMs : -1,
      geckoAudioQuerySeq: -1, geckoAudioQueryEpoch: -1, geckoAudioQueryNode: -1,
      geckoAudioQueryState: applicable ? 'NOT_STARTED' : 'NOT_APPLICABLE',
      geckoAudioQueryStartMs: -1, geckoAudioSyncCallMs: -1, geckoAudioReactionElapsedMs: -1,
      geckoAudioTimeoutLateMs: -1, geckoAudioInFlight: false,
      geckoAudioQueryRvfcCount: -1, geckoAudioQueryMaxRvfcGapMs: -1,
      geckoAudioQueryJsTickCount: -1, geckoAudioQueryMaxJsTickGapMs: -1,
      geckoAudioRecoveryCount: applicable ? audioRecoveryCount : -1,
      geckoAudioRetryRemainingMs: applicable && audioRetry && validAudioNow(documentNow)
        ? bounded(Math.max(0, audioRetry.notBefore - documentNow)) : -1,
      geckoAudioQueryDisposition: applicable ? 'NONE' : 'NOT_APPLICABLE',
      geckoAudioQueryTickDurationMs: -1, geckoAudioQueryTickTailDurationMs: -1,
      geckoAudioQueryTickMaxPhaseCode: 'NOT_APPLICABLE',
      geckoAudioQueryTickMaxPhaseDurationMs: -1, geckoAudioQueryTickScanNodes: -1,
      geckoAudioQueryObservedWorkCount: -1, geckoAudioQueryObservedWorkTotalMs: -1,
      geckoAudioQueryObservedWorkMaxMs: -1, geckoAudioQueryObservedWorkMaxPhaseCode: 'NOT_APPLICABLE',
      geckoAudioQueryObservedWorkMaxPhaseMs: -1, geckoAudioQueryObservedWorkMaxScanNodes: -1,
    }, applicable && audioQueryTrace ? audioQueryTrace : {}, applicable ? faultFacts() : {}, startupResourceFacts(applicable));
  };
  const recordQueryFrameExecution = (video, executedAt) => {
    const request = audioInFlight;
    if (!request || request.video !== video || request.node !== nodeGeneration ||
        request.epoch !== audioEpoch || request.rvfcStartAt < 0 || executedAt < request.rvfcStartAt) return;
    const previousAt = request.rvfcLastAt >= 0 ? request.rvfcLastAt : request.rvfcStartAt;
    const gap = bounded(executedAt - previousAt, MAX_QUERY_RVFC_GAP_MS);
    if (gap >= 0) request.rvfcMaxGapMs = Math.max(request.rvfcMaxGapMs, gap);
    if (request.rvfcCount < MAX_QUERY_RVFC_COUNT) request.rvfcCount++;
    request.rvfcLastAt = executedAt;
    request.trace.geckoAudioQueryRvfcCount = request.rvfcCount;
    request.trace.geckoAudioQueryMaxRvfcGapMs = request.rvfcMaxGapMs;
  };
  // This debug API queries GetPosition internally; it is not a pure cached read.
  // Sparse calls, one unresolved promise across all node generations, and a per-node fuse
  // bound diagnostic work without changing play/pause/seek, source or audio routing.
  const AUDIO_INTERVAL_MS = 3000;
  const AUDIO_TIMEOUT_MS = 1000;
  const AUDIO_SLOW_MS = 100;
  const AUDIO_PAIR_SPAN_MS = 100;
  const AUDIO_MAX_AGE_MS = 5000;
  const audioUnknown = (status, duration = -1) => ({
    geckoAudioClockStatus: status,
    geckoAudioClockUs: -1,
    geckoAudioClockAgeMs: -1,
    geckoAudioClockCallDurationMs: duration,
    geckoPairedVideoPtsUs: -1,
    // Status distinguishes an unknown -1 sentinel from a measured signed -1us delta.
    geckoAudioMinusVideoUs: -1,
    geckoAvPairSpanMs: -1,
  });
  const invalidateAudio = () => {
    if (audioInFlight && !audioInFlight.workSettled) invalidateObservedWork(audioInFlight);
    audioEpoch++;
    audioRetry = null;
    if (!audioBlocked) audioCache = null;
    advancingSince = -1;
  };
  const visibleVideo = video => {
    if (!video || video.isConnected !== true) return false;
    try {
      const rect = video.getBoundingClientRect();
      if (!(rect.width > 0 && rect.height > 0)) return false;
      let element = video;
      for (let depth = 0; element && depth < 64; depth++, element = element.parentElement) {
        const style = window.getComputedStyle(element);
        if (style.display === 'none' || style.visibility === 'hidden' ||
            style.visibility === 'collapse' || Number(style.opacity) === 0) return false;
      }
      return element === null;
    } catch (_) { return false; }
  };
  const eligibleAudio = (video, checkVisibility = true) => !!(audioObservationEnabled && video &&
    document.visibilityState === 'visible' && !video.paused && !video.ended &&
    !video.seeking && !video.muted && Number.isFinite(video.volume) && video.volume > 0 &&
    video.readyState >= 3 && video.isConnected === true && (!checkVisibility || visibleVideo(video)));
  const safeUs = value => Number.isSafeInteger(value) && value >= 0;
  const audioFacts = () => {
    const now = performance.now();
    const video = observedVideo;
    if (!audioObservationEnabled) return audioUnknown('NOT_APPLICABLE');
    if (!eligibleAudio(video)) return audioUnknown('INSUFFICIENT_NOT_PLAYING');
    if (audioCache) {
      const age = audioCache.sampleAt >= 0 ? bounded(now - audioCache.sampleAt) : -1;
      if (audioCache.fields.geckoAudioClockStatus !== 'AVAILABLE') return audioCache.fields;
      return age <= AUDIO_MAX_AGE_MS
        ? Object.assign({}, audioCache.fields, { geckoAudioClockAgeMs: age })
        : audioUnknown('INSUFFICIENT_STALE_SAMPLE', audioCache.fields.geckoAudioClockCallDurationMs);
    }
    return audioUnknown(audioInFlight
      ? (audioInFlight.timedOut ? 'INSUFFICIENT_TIMEOUT' : 'INSUFFICIENT_PENDING')
      : 'INSUFFICIENT_NO_SAMPLE');
  };
  const sampleAudio = () => {
    const video = observedVideo;
    const now = performance.now();
    if (!validAudioNow(now)) return;
    lastAudioEligibilityAt = now;
    if (!eligibleAudio(video)) {
      invalidateAudio();
      return;
    }
    const frame = callbackEvidence;
    if (!frame || frame.mediaTimeUs < 0 || !Number.isFinite(frame.callbackAt) || frame.callbackAt < 0 ||
        frame.callbackAt > now || now - frame.callbackAt > AUDIO_PAIR_SPAN_MS ||
        !Number.isFinite(advancingSince) || advancingSince < 0 || advancingSince > now ||
        now - advancingSince < 1000 || audioInFlight ||
        (lastAudioCallAt >= 0 && now - lastAudioCallAt < AUDIO_INTERVAL_MS)) return;
    if (audioBlocked) {
      if (!audioRetry || audioRetry.video !== video || audioRetry.node !== nodeGeneration ||
          audioRetry.epoch !== audioEpoch || now < audioRetry.notBefore || audioRecoveryCount >= 2 ||
          typeof video.mozRequestDebugInfo !== 'function') return;
      audioRecoveryCount++;
      audioRetry = null;
      audioBlocked = false;
      audioCache = null;
    }
    if (typeof video.mozRequestDebugInfo !== 'function') {
      audioBlocked = true;
      audioCache = { fields: audioUnknown('INSUFFICIENT_UNSUPPORTED'), sampleAt: -1 };
      return;
    }
    const trace = {
      geckoAudioQuerySeq: ++audioQuerySeq,
      geckoAudioQueryEpoch: audioEpoch, geckoAudioQueryNode: nodeGeneration,
      geckoAudioQueryState: 'CALLING', geckoAudioQueryStartMs: -1,
      geckoAudioSyncCallMs: -1, geckoAudioReactionElapsedMs: -1,
      geckoAudioTimeoutLateMs: -1, geckoAudioInFlight: true, geckoAudioQueryDisposition: 'NONE',
      geckoAudioQueryRvfcCount: 0, geckoAudioQueryMaxRvfcGapMs: -1,
      geckoAudioQueryJsTickCount: 0, geckoAudioQueryMaxJsTickGapMs: -1,
      geckoAudioQueryTickDurationMs: -1, geckoAudioQueryTickTailDurationMs: -1,
      geckoAudioQueryTickMaxPhaseCode: 'UNKNOWN',
      geckoAudioQueryTickMaxPhaseDurationMs: -1, geckoAudioQueryTickScanNodes: -1,
      geckoAudioQueryObservedWorkCount: 0, geckoAudioQueryObservedWorkTotalMs: 0,
      geckoAudioQueryObservedWorkMaxMs: 0, geckoAudioQueryObservedWorkMaxPhaseCode: 'NONE',
      geckoAudioQueryObservedWorkMaxPhaseMs: 0, geckoAudioQueryObservedWorkMaxScanNodes: 0,
    };
    audioQueryTrace = trace;
    const request = {
      video, node: nodeGeneration, epoch: audioEpoch, callAt: now, frame, trace,
      rvfcStartAt: -1, rvfcLastAt: -1, rvfcCount: 0, rvfcMaxGapMs: -1,
      jsTickLastAt: -1, jsTickCount: 0, jsTickMaxGapMs: -1, jsTickInvalid: false, settledAt: -1, failureStatus: null,
      workSettled: false, workInvalid: false, workCount: 0, workTotalRaw: 0, workMaxRaw: 0,
      workPhaseRaw: 0, workPhaseCode: 'NONE', workMaxScanNodes: 0,
    };
    audioInFlight = request;
    if (activeTickWork) {
      activeTickWork.query = request;
      activeTickWork.windowRequest = request;
    }
    lastAudioCallAt = now;
    const invalidReason = () => {
      if (request.video !== observedVideo || request.node !== nodeGeneration) return 'IGNORED_NODE';
      if (request.epoch !== audioEpoch) return 'IGNORED_EPOCH';
      if (!eligibleAudio(observedVideo)) return 'IGNORED_NOT_PLAYING';
      return null;
    };
    const fail = (status, duration, disposition) => {
      const reason = invalidReason();
      if (reason) { trace.geckoAudioQueryDisposition = reason; return; }
      if (audioBlocked) { trace.geckoAudioQueryDisposition = 'IGNORED_TIMEOUT_FUSE'; return; }
      trace.geckoAudioQueryDisposition = disposition;
      audioBlocked = true;
      request.failureStatus = status;
      preserveFirstFault(request, status);
      audioCache = { fields: audioUnknown(status, duration), sampleAt: -1 };
    };
    const timerArmedAt = performance.now();
    const timer = setTimeout(() => {
      // Never clear in-flight on timeout: the underlying request is not cancellable.
      // An unresolved old request also prevents requests on a replacement node.
      request.timedOut = true;
      trace.geckoAudioQueryState = 'TIMED_OUT';
      trace.geckoAudioTimeoutLateMs = bounded(Math.max(0, performance.now() - timerArmedAt - AUDIO_TIMEOUT_MS));
      fail('INSUFFICIENT_TIMEOUT', bounded(performance.now() - now), 'TIMEOUT_FUSE');
    }, AUDIO_TIMEOUT_MS);
    let promise;
    const syncCallAt = performance.now();
    request.rvfcStartAt = syncCallAt;
    request.jsTickLastAt = syncCallAt;
    trace.geckoAudioQueryStartMs = bounded(syncCallAt);
    try {
      promise = video.mozRequestDebugInfo();
      request.syncReturnedAt = performance.now();
      trace.geckoAudioSyncCallMs = bounded(request.syncReturnedAt - syncCallAt);
      trace.geckoAudioQueryState = 'PENDING';
    } catch (_) {
      request.syncReturnedAt = performance.now();
      trace.geckoAudioSyncCallMs = bounded(request.syncReturnedAt - syncCallAt);
      trace.geckoAudioQueryDisposition = 'SYNC_THROW';
      promise = Promise.reject();
    }
    const reaction = state => {
      // Freeze own-task coverage before response inspection and all lifecycle/fuse early returns.
      const firstReaction = !request.workSettled;
      request.workSettled = true;
      if (firstReaction && (request.video !== observedVideo || request.node !== nodeGeneration || request.epoch !== audioEpoch)) {
        invalidateObservedWork(request);
      }
      // Observe settlement before every lifecycle/fuse early return; never inspect late info.
      if (trace.geckoAudioReactionElapsedMs < 0) {
        let reactedAt;
        try { reactedAt = performance.now(); }
        catch (error) {
          invalidateObservedWork(request);
          throw error;
        }
        if (firstReaction && (!Number.isFinite(reactedAt) || reactedAt < request.rvfcStartAt)) invalidateObservedWork(request);
        request.settledAt = reactedAt;
        if (firstReaction && (!Number.isFinite(reactedAt) || reactedAt < request.jsTickLastAt)) {
          request.jsTickInvalid = true;
          trace.geckoAudioQueryJsTickCount = -1;
          trace.geckoAudioQueryMaxJsTickGapMs = -1;
        }
        if (firstReaction && !request.jsTickInvalid && request.jsTickCount > 0 && request.video === observedVideo &&
            request.node === nodeGeneration && request.epoch === audioEpoch) {
          const gap = bounded(reactedAt - request.jsTickLastAt, MAX_QUERY_RVFC_GAP_MS);
          if (gap >= 0) request.jsTickMaxGapMs = Math.max(request.jsTickMaxGapMs, gap);
          trace.geckoAudioQueryJsTickCount = request.jsTickCount;
          trace.geckoAudioQueryMaxJsTickGapMs = request.jsTickMaxGapMs;
        } else if (firstReaction && (request.video !== observedVideo || request.node !== nodeGeneration || request.epoch !== audioEpoch)) {
          trace.geckoAudioQueryJsTickCount = -1;
          trace.geckoAudioQueryMaxJsTickGapMs = -1;
        }
        trace.geckoAudioQueryState = state;
        trace.geckoAudioReactionElapsedMs = bounded(reactedAt - syncCallAt);
        trace.geckoAudioQueryRvfcCount = request.rvfcCount;
        if (request.video === observedVideo && request.node === nodeGeneration && request.epoch === audioEpoch &&
            !request.rvfcLoopFailed) {
          const previousAt = request.rvfcLastAt >= 0 ? request.rvfcLastAt : request.rvfcStartAt;
          const tailGap = bounded(reactedAt - previousAt, MAX_QUERY_RVFC_GAP_MS);
          if (tailGap >= 0) request.rvfcMaxGapMs = Math.max(request.rvfcMaxGapMs, tailGap);
          trace.geckoAudioQueryMaxRvfcGapMs = request.rvfcMaxGapMs;
        } else {
          // The old request no longer has a same-node/same-epoch end boundary.
          trace.geckoAudioQueryMaxRvfcGapMs = -1;
        }
      }
    };
    Promise.resolve(promise).then(info => {
      reaction('RESOLVED');
      const reason = invalidReason();
      if (reason) { trace.geckoAudioQueryDisposition = reason; return; }
      if (audioBlocked) { trace.geckoAudioQueryDisposition = 'IGNORED_TIMEOUT_FUSE'; return; }
      const resolvedAt = performance.now();
      const duration = bounded(resolvedAt - now);
      if (resolvedAt - now > AUDIO_SLOW_MS) { fail('INSUFFICIENT_SLOW_CALL', duration, 'SLOW_CALL'); return; }
      const decoder = info && info.decoder;
      const state = decoder && decoder.stateMachine;
      const wrapper = state && state.mediaSink && state.mediaSink.audioSinkWrapper;
      const audio = wrapper && wrapper.audioSink;
      // Gecko 146 AudioSinkDebugInfo.isStarted defaults false and is not populated:
      // wrapper.isStarted is authoritative. Zero/default clocks cannot establish progress.
      if (!decoder || decoder.hasAudio !== true || decoder.hasVideo !== true ||
          !wrapper || wrapper.isStarted !== true || wrapper.isPlaying !== true ||
          wrapper.audioEnded !== false || !audio || audio.isPlaying !== true ||
          audio.audioEnded !== false || audio.hasErrored !== false ||
          !Number.isFinite(audio.outputRate) || audio.outputRate <= 0 ||
          !safeUs(audio.startTime) || !safeUs(audio.lastGoodPosition) ||
          audio.lastGoodPosition <= 0 || !safeUs(state.clock) || state.clock <= 0 ||
          !safeUs(audio.startTime + audio.lastGoodPosition)) {
        fail('INSUFFICIENT_INVALID_CLOCK', duration, 'INVALID_CLOCK'); return;
      }
      if (Math.abs(state.clock - (audio.startTime + audio.lastGoodPosition)) > 1000) {
        fail('INSUFFICIENT_CLOCK_MISMATCH', duration, 'CLOCK_MISMATCH'); return;
      }
      const span = bounded(resolvedAt - frame.callbackAt);
      if (span < 0 || resolvedAt - frame.callbackAt > AUDIO_PAIR_SPAN_MS) {
        trace.geckoAudioQueryDisposition = 'PAIR_STALE';
        audioCache = { fields: audioUnknown('INSUFFICIENT_PAIR_STALE', duration), sampleAt: -1 };
        return;
      }
      trace.geckoAudioQueryDisposition = 'APPLIED';
      // Keep the request's exact frame snapshot, never the later periodic videoPtsUs.
      audioCache = { sampleAt: now, fields: {
        geckoAudioClockStatus: 'AVAILABLE', geckoAudioClockUs: state.clock,
        geckoAudioClockAgeMs: duration, geckoAudioClockCallDurationMs: duration,
        geckoPairedVideoPtsUs: frame.mediaTimeUs,
        geckoAudioMinusVideoUs: state.clock - frame.mediaTimeUs, geckoAvPairSpanMs: span,
      } };
    }).catch(() => {
      reaction('REJECTED');
      fail('INSUFFICIENT_REJECTED', bounded(performance.now() - now),
        trace.geckoAudioQueryDisposition === 'SYNC_THROW' ? 'SYNC_THROW' : 'REJECTED_FUSE');
    }).finally(() => {
      clearTimeout(timer);
      trace.geckoAudioInFlight = false;
      if (firstAudioFaultRequest === request) {
        // Only the original fault's own settlement can enrich it; later queries cannot overwrite it.
        const t = request.trace;
        Object.assign(firstAudioFault, {
          geckoAudioFirstFaultState: t.geckoAudioQueryState,
          geckoAudioFirstFaultSyncCallMs: t.geckoAudioSyncCallMs,
          geckoAudioFirstFaultReactionElapsedMs: t.geckoAudioReactionElapsedMs,
          geckoAudioFirstFaultTimeoutLateMs: t.geckoAudioTimeoutLateMs,
          geckoAudioFirstFaultJsTickCount: t.geckoAudioQueryJsTickCount,
          geckoAudioFirstFaultMaxJsTickGapMs: t.geckoAudioQueryMaxJsTickGapMs,
        });
        firstAudioFaultRequest = null;
      }
      if (audioInFlight === request) {
        audioInFlight = null;
        if (!invalidReason() && !request.jsTickInvalid && audioBlocked && audioRecoveryCount < 2 &&
            trace.geckoAudioQueryState === 'RESOLVED' &&
            ['INSUFFICIENT_TIMEOUT', 'INSUFFICIENT_SLOW_CALL'].includes(request.failureStatus) &&
            trace.geckoAudioSyncCallMs >= 0 && trace.geckoAudioSyncCallMs <= AUDIO_SLOW_MS &&
            Number.isFinite(request.syncReturnedAt) && request.syncReturnedAt >= request.rvfcStartAt &&
            request.syncReturnedAt - request.rvfcStartAt <= AUDIO_SLOW_MS &&
            Number.isFinite(request.settledAt) && request.settledAt >= request.rvfcStartAt) {
          audioRetry = { video: request.video, node: request.node, epoch: request.epoch,
            settledAt: request.settledAt, notBefore: request.settledAt + (audioRecoveryCount === 0 ? 15000 : 30000) };
        }
      }
    });
  };
  document.addEventListener('visibilitychange', invalidateAudio);
  const clearCallback = () => {
    callbackEpoch++;
    if (observedVideo && callbackId !== null &&
        typeof observedVideo.cancelVideoFrameCallback === 'function') {
      try { observedVideo.cancelVideoFrameCallback(callbackId); } catch (_) {}
    }
    callbackId = null;
    callbackEvidence = null;
    invalidateAudio();
    audioBlocked = false;
    audioCache = null;
    lastAudioCallAt = -1;
  };
  const scheduleCallback = () => {
    if (!observedVideo || !callbackSupported || callbackId !== null) return;
    const video = observedVideo;
    const epoch = callbackEpoch;
    try {
      callbackId = video.requestVideoFrameCallback((now, metadata) => {
        if (video !== observedVideo || epoch !== callbackEpoch) return;
        const callbackExecutedAt = performance.now();
        recordQueryFrameExecution(video, callbackExecutedAt);
        callbackId = null;
        if (startupTrace.geckoFirstRvfcMs < 0) {
          startupTrace.geckoFirstRvfcMs = bounded(now);
          startupTrace.geckoFirstRvfcPtsUs = bounded(metadata.mediaTime * 1000000);
          try {
            const resources = globalThis.__kantvStartupResources;
            if (resources && typeof resources.freeze === 'function' &&
                Number.isFinite(callbackExecutedAt) && callbackExecutedAt >= 0) {
              resources.freeze(startupTrace, callbackExecutedAt);
            }
          } catch (_) { /* Resource timing is optional and cannot interrupt frame observation. */ }
        }
        if (eligibleAudio(video, false) && callbackEvidence &&
            metadata.presentedFrames > callbackEvidence.presentedFrames &&
            metadata.mediaTime * 1000000 > callbackEvidence.mediaTimeUs) {
          if (advancingSince < 0) advancingSince = bounded(now);
        } else {
          advancingSince = -1;
        }
        callbackEvidence = {
          callbackAt: bounded(now),
          presentedFrames: bounded(metadata.presentedFrames),
          mediaTimeUs: bounded(metadata.mediaTime * 1000000),
          presentationAt: bounded(metadata.presentationTime),
          expectedDelayUs: Number.isFinite(metadata.expectedDisplayTime) && Number.isFinite(now)
            ? Math.max(-1000000, Math.min(1000000,
                Math.round((metadata.expectedDisplayTime - now) * 1000))) : -1,
        };
        scheduleCallback();
      });
    } catch (_) {
      callbackSupported = false;
      callbackEvidence = null;
      const request = audioInFlight;
      if (request && request.video === video && request.node === nodeGeneration && request.epoch === audioEpoch) {
        request.rvfcLoopFailed = true;
        request.trace.geckoAudioQueryMaxRvfcGapMs = -1;
      }
    }
  };
  const resetMediaObservation = () => {
    clearCallback();
    nodeGeneration++;
    scheduleCallback();
  };
  const onLoadedMetadata = () => {
    // Initial metadata resets callback bookkeeping but preserves real pre-metadata milestones.
    startupMilestone('geckoLoadedMetadataMs');
    resetMediaObservation();
  };
  const onEmptied = () => {
    const boundAt = startupTrace.geckoNodeBoundMs;
    startupTrace = emptyStartupTrace();
    // A new media resource on the same DOM node is not a new DOM bind.
    startupTrace.geckoNodeBoundMs = boundAt;
    bindStartupResources();
    resetMediaObservation();
  };
  const onPlay = () => startupMilestone('geckoPlayMs');
  const onPlaying = () => startupMilestone('geckoPlayingMs');
  const onCanPlay = () => startupMilestone('geckoCanPlayMs');
  const onWaiting = () => { startupMilestone('geckoWaitingMs'); invalidateAudio(); };
  const bindVideo = video => {
    if (video === observedVideo) return;
    clearCallback();
    if (audioVisibilityObserver) audioVisibilityObserver.disconnect();
    audioVisibilityObserver = null;
    if (observedVideo) {
      observedVideo.removeEventListener('emptied', onEmptied);
      observedVideo.removeEventListener('loadedmetadata', onLoadedMetadata);
      observedVideo.removeEventListener('play', onPlay);
      observedVideo.removeEventListener('playing', onPlaying);
      observedVideo.removeEventListener('canplay', onCanPlay);
      observedVideo.removeEventListener('waiting', onWaiting);
      for (const event of ['pause', 'ended', 'seeking', 'volumechange']) {
        observedVideo.removeEventListener(event, invalidateAudio);
      }
    }
    observedVideo = video;
    startupTrace = emptyStartupTrace();
    if (video) {
      startupTrace.geckoNodeBoundMs = bounded(performance.now());
      startupTrace.geckoAttachLate = video.readyState >= 1 || video.currentTime > 0;
    }
    nodeGeneration++;
    bindStartupResources();
    callbackSupported = !!(video && typeof video.requestVideoFrameCallback === 'function');
    if (video) {
      video.addEventListener('emptied', onEmptied);
      video.addEventListener('loadedmetadata', onLoadedMetadata);
      video.addEventListener('play', onPlay);
      video.addEventListener('playing', onPlaying);
      video.addEventListener('canplay', onCanPlay);
      video.addEventListener('waiting', onWaiting);
      for (const event of ['pause', 'ended', 'seeking', 'volumechange']) {
        video.addEventListener(event, invalidateAudio);
      }
    }
    if (video && typeof MutationObserver === 'function') {
      // Watch only the selected node/ancestor chain, never the entire document subtree.
      // Even hide-then-show within one async request invalidates that request's epoch.
      const observeAncestors = () => {
        audioVisibilityObserver.disconnect();
        let element = video;
        for (let depth = 0; element && depth < 64; depth++, element = element.parentElement) {
          audioVisibilityObserver.observe(element, {
            attributes: true, attributeFilter: ['style', 'class', 'hidden'], childList: true,
          });
        }
      };
      audioVisibilityObserver = new MutationObserver(() => {
        if (video !== observedVideo) return;
        invalidateAudio();
        // The same selected video can move under a different, hidden ancestor.
        observeAncestors();
      });
      observeAncestors();
    }
    scheduleCallback();
  };
  addEventListener('pagehide', () => bindVideo(null));
  const frameFacts = frames => {
    const sampleNow = performance.now();
    const evidence = callbackEvidence;
    return {
      documentId: documentId,
      nodeGeneration: nodeGeneration,
      presentedFrames: evidence ? evidence.presentedFrames : -1,
      videoPtsUs: evidence ? evidence.mediaTimeUs : -1,
      videoTimingSource: callbackSupported ? (evidence ? 'GECKO_RVFC' : 'GECKO_RVFC_PENDING') :
        (frames >= 0 ? 'GECKO_PLAYBACK_QUALITY' : 'GECKO_CURRENT_TIME'),
      actualPresentedAvailability: callbackSupported
        ? (evidence ? 'BROWSER_COMPOSITOR_ONLY' : 'NO_CALLBACK_YET') : 'UNAVAILABLE',
      videoCallbackAgeMs: evidence && evidence.callbackAt >= 0
        ? bounded(sampleNow - evidence.callbackAt) : -1,
      videoPresentationAgeMs: evidence && evidence.presentationAt >= 0
        ? bounded(sampleNow - evidence.presentationAt) : -1,
      videoExpectedDisplayDelayUs: evidence ? evidence.expectedDelayUs : -1,
    };
  };
  addEventListener('message', (e) => {
    // 注意：不能用 e.source !== window 过滤——content script 的 window 与页面
    // window 在 Xray 隔离世界是不同对象，该检查恒误拒。页面消息只传递提示，
    // 诊断权限由已绑定当前 Gecko session 的 nativeMessaging 回复单独授予。
    const d = e.data;
    if (d && typeof d.__kantvAudioClockObserve === 'boolean') {
      const enabled = diagnosticsEnabled && audioObservationAllowed && d.__kantvAudioClockObserve;
      if (audioObservationEnabled !== enabled) invalidateAudio();
      audioObservationEnabled = enabled;
      return;
    }
    const presentation = d && d.__kantvPresentation;
    if (presentation &&
        ['AUTO_FULLSCREEN_ONLY', 'CSS_FIT_ONLY', 'AUTO_THEN_FIT'].includes(presentation.mode) &&
        Number.isFinite(presentation.requestCount) &&
        Number.isFinite(presentation.rebindCount) &&
        Number.isFinite(presentation.targetWidth) &&
        Number.isFinite(presentation.targetHeight) &&
        Number.isFinite(presentation.viewportWidth) &&
        Number.isFinite(presentation.viewportHeight) &&
        Number.isFinite(presentation.coveragePermille)) {
      native({
        kind: 'presentation',
        mode: presentation.mode,
        requestCount: Math.max(0, Math.floor(presentation.requestCount)),
        requestAccepted: presentation.requestAccepted === true,
        cssFitApplied: presentation.cssFitApplied === true,
        rebindCount: Math.max(0, Math.floor(presentation.rebindCount)),
        targetWidth: Math.max(0, Math.min(32768, Math.floor(presentation.targetWidth))),
        targetHeight: Math.max(0, Math.min(32768, Math.floor(presentation.targetHeight))),
        viewportWidth: Math.max(0, Math.min(32768, Math.floor(presentation.viewportWidth))),
        viewportHeight: Math.max(0, Math.min(32768, Math.floor(presentation.viewportHeight))),
        coveragePermille: Math.max(0, Math.min(1000, Math.floor(presentation.coveragePermille))),
      });
      return;
    }
    // 主域准备脚本的选台点击事实（央视频 SPA）：只接收布尔页面提示，不授予诊断能力。
    const labelState = d && d.__kantvLabelState;
    if (labelState && typeof labelState.found === 'boolean' && typeof labelState.clicked === 'boolean') {
      labelFound = labelState.found;
      labelClicked = labelState.clicked;
      return;
    }
    if (!d || typeof d.ep !== 'string') return;
    if (!/^https?:\/\/127\.0\.0\.1:\d+\/offer$/.test(d.ep)) return;
    if (typeof d.__kantvMirrorBatch === 'string') {
      native({ kind: 'offer', body: d.__kantvMirrorBatch });
      return;
    }
    const metrics = d.__kantvMirrorMetrics;
    if (!metrics || !Number.isFinite(metrics.workerSwitches) || !Number.isFinite(metrics.instanceSwitches)) return;
    native({
      kind: 'mirrorMetrics',
      workerSwitches: Math.max(0, Math.floor(metrics.workerSwitches)),
      instanceSwitches: Math.max(0, Math.floor(metrics.instanceSwitches)),
    });
  }, false);
  let ticks = 0;
  // 选台点击的落地事实（主域准备脚本经 postMessage 上报）：只缓存布尔，不含页面文本。
  let labelFound = false;
  let labelClicked = false;
  // 自动点开页面自己的登录入口，让二维码出现；有界且只点未弹出时（见下方节拍）。
  let loginEntryClicks = 0;
  const MAX_LOGIN_ENTRY_CLICKS = 3;
  // 按文字扫描得到、每 5 拍刷新一次的登录状态（避免每秒全量遍历 DOM）。
  let cachedLoginEntry = null;
  let cachedLoginBox = null;
  let cachedLoginRequired = false;
  let cachedLoginPoint = null;
  let loginEntryPrepared = null;
  // 登录面板（二维码框）的结构事实：是否存在、在视口的哪个位置、多大。
  // 只看结构与有界数值，不看画面、不含任何页面文本。
  let cachedLoginPanel = null;
  // 二维码元素本身 + 它祖先链的计算样式（只含 CSS 关键字，不含页面文字），用来定位是哪一层藏了它。
  let cachedQrStyle = '-';
  let cachedQrNode = null;
  let cachedQrLoaded = false;
  let cachedQrNatural = '-';
  let cachedQrSrcKind = '-';
  let cachedQrParent = '-';
  let qrPinned = false;
  let controlsHidden = false;
  // 实验用：页面**实际收到**的指针/点击事件计数。用来判定 app 合成的事件有没有进到页面里
  // （只看计数，不记录任何坐标之外的页面内容）。
  let domPointerDowns = 0;
  let domMouseDowns = 0;
  let domClicks = 0;
  document.addEventListener('pointerdown', () => { domPointerDowns++; }, true);
  document.addEventListener('mousedown', () => { domMouseDowns++; }, true);
  document.addEventListener('click', () => { domClicks++; }, true);
  setInterval(() => {
    const workStart = workNow();
    activeTickWork = { start: workStart, at: workStart, code: 'BIND', valid: workStart >= 0,
      maxRawDuration: 0, maxCode: 'NONE', scanNodes: 0, query: null,
      windowRequest: audioInFlight && !audioInFlight.workSettled ? audioInFlight : null,
      video: observedVideo, node: nodeGeneration, startupIdentity: startupTrace,
      startup: observedVideo && startupTrace.geckoFirstRvfcMs < 0 ? startupTrace : null,
      startupAt: workStart };
    let workFailed = false;
    ticks++;
    try {
      const traceTickAt = performance.now();
      traceTickGapMs = lastTraceTickAt >= 0 ? bounded(traceTickAt - lastTraceTickAt) : -1;
      lastTraceTickAt = traceTickAt;
      const query = audioInFlight;
      if (query && !query.workSettled && query.video === observedVideo && query.node === nodeGeneration &&
          query.epoch === audioEpoch && (!Number.isFinite(traceTickAt) || traceTickAt < query.jsTickLastAt)) {
        query.jsTickInvalid = true;
        query.trace.geckoAudioQueryJsTickCount = -1;
        query.trace.geckoAudioQueryMaxJsTickGapMs = -1;
      }
      if (query && !query.workSettled && !query.jsTickInvalid && query.video === observedVideo && query.node === nodeGeneration &&
          query.epoch === audioEpoch && query.jsTickLastAt >= 0 && traceTickAt >= query.jsTickLastAt) {
        query.jsTickCount = Math.min(MAX_QUERY_RVFC_COUNT, query.jsTickCount + 1);
        query.jsTickMaxGapMs = Math.max(query.jsTickMaxGapMs,
          bounded(traceTickAt - query.jsTickLastAt, MAX_QUERY_RVFC_GAP_MS));
        query.jsTickLastAt = traceTickAt;
        query.trace.geckoAudioQueryJsTickCount = query.jsTickCount;
        query.trace.geckoAudioQueryMaxJsTickGapMs = query.jsTickMaxGapMs;
      }
      const v = document.querySelector('video[data-kantv-video="1"]');
      bindVideo(v);
      const boundAt = workPhase('AUDIO');
      // The first bind task has no bound node before BIND; count only its post-bind work.
      if (!activeTickWork.video && v) {
        activeTickWork.video = observedVideo;
        activeTickWork.node = nodeGeneration;
        activeTickWork.startupIdentity = startupTrace;
        activeTickWork.startup = startupTrace;
        activeTickWork.startupAt = boundAt;
      }
      sampleAudio();
      workPhase('LOGIN_SCAN');
      // 第一财经登录信号：只上报布尔事实（页面是否有登录入口/认证 UI/明确要求登录），
      // 不采集任何账号、二维码或凭据内容。
      const visible = el => !!(el && el.getClientRects().length > 0);
      // 登录判定改为**按页面文字**：原来的选择器(.loginBox/.endplayer/.u-login)是按早先版本的
      // 页面写的，实测对不上现在的页面，于是 loginRequired 恒为 false，二维码永远不会被点开。
      // 扫描有界（最多 2500 个节点、只看短文本节点），且每 5 拍才做一次，不做每秒全量遍历。
      if (ticks % 5 === 1) {
        const nodes = document.querySelectorAll('div,a,span,p,li');
        const limit = Math.min(nodes.length, 2500);
        let matched = null;
        for (let i = 0; i < limit; i++) {
          activeTickWork.scanNodes++;
          const el = nodes[i];
          const text = el.textContent || '';
          if (text.length > 0 && text.length <= 200 && /试看已结束|完整收看请|试看\d+秒/.test(text)) {
            matched = el;
            break;
          }
        }
        cachedLoginEntry = matched
          ? (matched.closest('a') || (matched.querySelector && matched.querySelector('a')) || matched)
          : null;
        cachedLoginRequired = !!matched;
        // 二维码到底有没有弹出来？判据用**二维码的形状特征**：页面上接近正方形、边长≥100px
        // 的图片/画布，取其中最大者并报出它的视口坐标。整页容器不算（上一版就是被它骗了）。
        cachedLoginPanel = null;
        const qrNodes = document.querySelectorAll('img,canvas');
        for (let i = 0; i < Math.min(qrNodes.length, 300); i++) {
          activeTickWork.scanNodes++;
          const rect = qrNodes[i].getBoundingClientRect();
          if (rect.width < 100 || rect.height < 100) continue;
          const ratio = rect.width / rect.height;
          if (ratio < 0.85 || ratio > 1.18) continue;
          if (!cachedLoginPanel || rect.width > cachedLoginPanel.w) {
            cachedLoginPanel = {
              x: Math.round(rect.left), y: Math.round(rect.top),
              w: Math.round(rect.width), h: Math.round(rect.height),
            };
            cachedQrNode = qrNodes[i];
          }
        }
        // 判定"登录弹窗已出现"要严格：必须真的有个二维码图像，否则第 2 步文案会说
        // "请扫二维码"而屏幕上根本没有码（实测踩到过这个假阳性）。
        const box = document.querySelector('.lywrap.loginBox,.loginBox');
        cachedLoginBox = (box && box.querySelector('img,canvas')) ? box : null;
        // 登录入口的屏幕坐标（设备像素，= CSS px × devicePixelRatio）。只报两个有界整数，
        // 不含任何页面文字：app 需要用它在该位置**合成一次真实触摸**——很多站点的"登录"是
        // 绑了 click 的普通元素、键盘根本聚焦不到，只有真实手势才会弹出登录二维码。
        // 让"登录"能被键盘选中：它是普通元素（没有 tabindex），遥控器方向键永远选不中它，
        // 这也是用户点不到登录的根本原因。加 tabindex 并聚焦之后，用户按一次**真实确定键**
        // 即可激活——真实按键会产生站点要求的"用户激活"，合成事件不会。
        if (cachedLoginEntry && loginEntryPrepared !== cachedLoginEntry) {
          loginEntryPrepared = cachedLoginEntry;
          try {
            if (!cachedLoginEntry.hasAttribute('tabindex')) {
              cachedLoginEntry.setAttribute('tabindex', '0');
            }
            cachedLoginEntry.focus();
            // **必须由真实按键来触发**：`<div tabindex=0>` 按回车浏览器不会自动 click
            // （那只对按钮/链接/表单控件生效），所以遥控器按了也没反应。
            // 在 keydown 里替它 click 是可以的：此刻正在处理真实按键事件，页面持有
            // 用户激活（transient activation），站点要求的那个"真实手势"条件是满足的。
            const entry = cachedLoginEntry;
            entry.addEventListener('keydown', (event) => {
              if (event.key === 'Enter' || event.key === ' ' || event.keyCode === 13) {
                event.preventDefault();
                try { entry.click(); } catch (error) { /* 交给用户自己再试 */ }
              }
            }, true);
          } catch (error) { /* 元素不可聚焦就交给用户自己试，不算故障 */ }
        }
        cachedLoginPoint = null;
        if (cachedLoginEntry && typeof cachedLoginEntry.getBoundingClientRect === 'function') {
          const rect = cachedLoginEntry.getBoundingClientRect();
          const ratio = window.devicePixelRatio || 1;
          if (rect.width > 0 && rect.height > 0) {
            cachedLoginPoint = {
              x: Math.round((rect.left + rect.width / 2) * ratio),
              y: Math.round((rect.top + rect.height / 2) * ratio),
            };
          }
        }
      }
      // 二维码元素与其祖先链的计算样式：哪一层 display:none / visibility:hidden / opacity:0，
      // 一眼就能看出。只记 CSS 关键字与标签名，不含任何页面文字或地址。
      workPhase('QR_LAYOUT');
      cachedQrStyle = '-';
      if (cachedQrNode) {
        const parts = [];
        let cur = cachedQrNode;
        for (let d = 0; d < 5 && cur; d++) {
          const cs = window.getComputedStyle(cur);
          parts.push((cur.tagName || '?').toLowerCase() + ':' + cs.display + ':' +
            cs.visibility + ':' + cs.opacity + ':' + (cs.zIndex || '-'));
          cur = cur.parentElement;
        }
        cachedQrStyle = parts.join('>').slice(0, 200);
        // 图片到底加载出来没有：加载失败时元素仍有矩形、样式也"可见"，但什么都不会被画出来。
        // 只报布尔与尺寸（0 = 没加载），**不报 src**——那可能带登录令牌。
        const tag = (cachedQrNode.tagName || '').toLowerCase();
        cachedQrLoaded = tag === 'img'
          ? (!!cachedQrNode.complete && (cachedQrNode.naturalWidth | 0) > 0)
          : true;
        cachedQrNatural = tag === 'img'
          ? ((cachedQrNode.naturalWidth | 0) + 'x' + (cachedQrNode.naturalHeight | 0))
          : (tag + '-no-img');
        // 这张方形图到底是不是二维码？**不打印地址**（二维码接口常带登录令牌），
        // 只做有界分类：地址里是否含二维码/登录语义，以及它所在容器的类名首段。
        const src = String(cachedQrNode.currentSrc || cachedQrNode.src || '');
        cachedQrSrcKind = /qrcode|erweima|qr_|\/qr|getqr|showqr/i.test(src) ? 'qr'
          : (/code|login|auth/i.test(src) ? 'codeish' : 'other');
        const parent = cachedQrNode.parentElement;
        cachedQrParent = parent
          ? ((parent.tagName || '').toLowerCase() + '.' +
             String(parent.className || '').split(/\s+/)[0]).slice(0, 32)
          : '-';
      }

      // 二维码确实存在（div.scancode 里有已加载的二维码图），但按站点自己的布局它没有被画出来。
      // 不再猜原因：直接把那个面板**钉到屏幕正中、置于最前**，以"用户能扫到码"为准。
      // 只改位置与层级，不改内容，也不读取任何凭据。
      if (cachedQrNode && !qrPinned) {
        qrPinned = true;
        try {
          let panel = cachedQrNode;
          for (let d = 0; d < 6 && panel.parentElement; d++) {
            const cls = String(panel.className || '');
            if (/popup|scancode|m-layer|loginBox/i.test(cls)) break;
            panel = panel.parentElement;
          }
          // 注意：app 为页面适配加了 CSS 变换，而**在被变换的祖先下 position:fixed 会退化为
          // 相对该祖先定位**，所以"钉到屏幕正中"实际会落到别处（实测就是这么失败的）。
          // 改用不依赖定位的方式：把它滚动到可视区中央。
          panel.style.zIndex = '2147483647';
          if (typeof panel.scrollIntoView === 'function') {
            panel.scrollIntoView({ block: 'center', inline: 'center' });
          }
          if (cachedQrNode.scrollIntoView) {
            cachedQrNode.scrollIntoView({ block: 'center', inline: 'center' });
          }
        } catch (error) { /* 钉不住就保持原样，不阻塞用户 */ }
      }

      // 去掉网页播放器自带的操控栏：电视上遥控器操作不了它（按键归 app），留着只会遮挡画面。
      // 结构取自站点页面源码：div.videobox > div.controlsbox。只隐藏这一层，不动站点逻辑，
      // 也不影响 video 元素本身（app 自己按 videoSelector 找播放器）。
      workPhase('UI_ACTIONS');
      if (!controlsHidden && document.head) {
        try {
          const style = document.createElement('style');
          // 用全局选择器：实测这条栏并不在 .videobox 里面（是它的兄弟节点），
          // 原来的后代选择器匹配不上，所以操控栏又露了出来。
          style.textContent = '.controlsbox{display:none !important}';
          document.head.appendChild(style);
          controlsHidden = true;
        } catch (error) { /* 隐藏不了就维持原样，不阻塞播放 */ }
      }

      const loginEntry = cachedLoginEntry;
      const loginBox = cachedLoginBox;
      const loginSignals = {
        loginEntryAvailable: !!loginEntry,
        authenticatedUi: !!document.querySelector('.m-login .u-login img.f-avatar'),
        loginRequired: cachedLoginRequired,
        loginDialogVisible: visible(loginBox),
        // 有界计数：让报告能看出"自动点过几次"，而不是只有布尔。
        loginEntryClicks: loginEntryClicks,
        // 登录入口中心点（设备像素）；app 用它合成一次真实触摸。无坐标时传 -1。
        loginEntryX: cachedLoginPoint ? cachedLoginPoint.x : -1,
        loginEntryY: cachedLoginPoint ? cachedLoginPoint.y : -1,
        // 实验用计数：事件是否真的到达页面。
        domPointerDowns: domPointerDowns,
        domMouseDowns: domMouseDowns,
        domClicks: domClicks,
        // 二维码框的结构事实（有界数值）：-1 表示没找到候选面板。
        panelX: cachedLoginPanel ? cachedLoginPanel.x : -1,
        panelY: cachedLoginPanel ? cachedLoginPanel.y : -1,
        panelW: cachedLoginPanel ? cachedLoginPanel.w : -1,
        panelH: cachedLoginPanel ? cachedLoginPanel.h : -1,
        qrStyle: cachedQrStyle,
        qrLoaded: cachedQrLoaded,
        qrNatural: cachedQrNatural,
        qrSrcKind: cachedQrSrcKind,
        qrParent: cachedQrParent,
        viewportW: Math.round(window.innerWidth),
        viewportH: Math.round(window.innerHeight),
      };
      // 登录入口由**页面自己**渲染二维码，用户只需扫码。但 app 的遥控器按键要用于换台和菜单，
      // 一个都不会转发给页面，所以这个链接用户根本点不到——二维码永远弹不出来。
      // 因此这里替用户点一下他自己本来就要点的那一下：**只在"明确要求登录、而登录弹窗还没出现"
      // 时点，弹出来就停**；次数有界，绝不循环刷。仍然不采集任何账号、二维码或凭据内容。
      //
      // 关键点（2026-09-15 实测踩到）：点击目标**必须是按页面文字找到的那个节点**。
      // 早先这里写死了一组类名(.endplayer a/.u-login/.loginBox a)，那套类名只存在于老版页面，
      // 在第一财经页面上 querySelector 返回 null，于是**点击从来没有被发起过**——
      // 二维码当然永远不出现。检测和点击必须用同一个目标。
      const loginClickTarget = loginEntry;
      if (loginSignals.loginRequired && !loginSignals.loginDialogVisible &&
          loginEntryClicks < MAX_LOGIN_ENTRY_CLICKS) {
        // 页面自己的登录入口是 `<a onclick="ShowUserLogin()">登录</a>`——没有 href，只调用一个
        // **全局函数**（实测取自页面源码）。直接调用它，比模拟点击可靠得多：点击要穿过事件
        // 可信度与用户激活两道关卡，而这里走的是页面自己的登录流程入口。
        // 而且函数可调用就意味着**不必等试用结束（403）**，首开即可让二维码弹出来。
        // 内容脚本访问页面全局必须经由 wrappedJSObject（Xray 隔离）。
        let invoked = false;
        try {
          const pageWindow = window.wrappedJSObject || window;
          if (typeof pageWindow.ShowUserLogin === 'function') {
            pageWindow.ShowUserLogin();
            invoked = true;
          }
        } catch (error) { /* 页面没这个函数就退回点击 */ }
        if (!invoked && !loginClickTarget) {
          loginEntryClicks = MAX_LOGIN_ENTRY_CLICKS;
        } else if (!invoked) {
        try {
          // 完整事件序列：不少站点只认 mousedown/mouseup 组合，不认单独的 click()。
          // 目标节点本身或其可点击祖先都能收到（bubbles=true）。
          // 注意：这是合成事件，若站点要求真实用户手势（isTrusted），仍需遥控器介入——
          // 因此这条只是"尽量自动"，不是唯一通道。
          for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
            const Ctor = (type.startsWith('pointer') && typeof PointerEvent === 'function')
              ? PointerEvent : MouseEvent;
            loginClickTarget.dispatchEvent(new Ctor(type, {
              bubbles: true, cancelable: true, view: window,
            }));
          }
          loginEntryClicks++;
        } catch (error) {
          // 页面结构变了就不再重试：点击失败本身不是故障。
          loginEntryClicks = MAX_LOGIN_ENTRY_CLICKS;
        }
        }
        if (invoked) loginEntryClicks++;
      }
      workPhase('STATE_BUILD');
      const labelSignals = {
        labelFound: labelFound,
        labelClicked: labelClicked,
      };
      if (v) {
        // 媒体证据：只上报白名单化的有界数值；未知一律 -1，不把“未知”当零。
        let frames = -1;
        let droppedFrames = -1;
        try {
          if (typeof v.getVideoPlaybackQuality === 'function') {
            const quality = v.getVideoPlaybackQuality();
            if (quality && Number.isFinite(quality.totalVideoFrames)) {
              frames = bounded(quality.totalVideoFrames);
            }
            if (quality && Number.isFinite(quality.droppedVideoFrames)) {
              droppedFrames = bounded(quality.droppedVideoFrames);
            }
          }
        } catch (_) { /* Quality counters are optional; retain passive callback/clock evidence. */ }
        const errorCode = v.error && Number.isFinite(v.error.code)
          ? Math.max(0, Math.min(4, Math.floor(v.error.code))) : 0;
        let buffered = { positionMs: -1, durationMs: -1 };
        try { buffered = bufferedFacts(v); } catch (_) { /* Range may change during sampling. */ }
        const packet = Object.assign({
          kind: 'state',
          tick: ticks,
          nodeCount: 1,
          hasVideo: true,
          time: v.currentTime,
          paused: v.paused,
          ready: v.readyState,
          w: v.videoWidth,
          h: v.videoHeight,
          frames: frames,
          droppedFrames: droppedFrames,
          errorCode: errorCode,
          bufferedPositionMs: buffered.positionMs,
          bufferDurationMs: buffered.durationMs,
        }, frameFacts(frames), audioFacts(), traceFacts(), loginSignals, labelSignals);
        workPhase('NATIVE_SEND');
        native(packet);
      } else {
        const packet = Object.assign({ kind: 'state', tick: ticks, nodeCount: 0, noVideo: 1, hasVideo: false }, frameFacts(-1), audioFacts(), traceFacts(), loginSignals, labelSignals);
        workPhase('NATIVE_SEND');
        native(packet);
      }
    } catch (e) {
      workFailed = true;
      native({ kind: 'error', where: 'state' });
    } finally {
      finishTickWork(workFailed);
    }
  }, 1000);
  // Once per document. A missing, failed or false host response leaves collectors disabled.
  try {
    browser.runtime.sendNativeMessage('kantvAvProbe', { kind: 'diagnosticCapability' }).then(capability => {
      diagnosticsEnabled = !!(capability && capability.diagnosticsEnabled === true);
      audioObservationAllowed = diagnosticsEnabled && capability.audioObservationEnabled === true;
      if (audioObservationEnabled !== audioObservationAllowed) invalidateAudio();
      audioObservationEnabled = audioObservationAllowed;
      const resources = globalThis.__kantvStartupResources;
      if (resources && typeof resources.enable === 'function') resources.enable(diagnosticsEnabled);
    }, () => {});
  } catch (_) { /* No capability means no diagnostic collector. */ }
})();
