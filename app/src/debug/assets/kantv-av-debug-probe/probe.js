(() => {
  "use strict";
  // Match patterns omit ports; reject every origin except the dedicated local fixture.
  if (location.origin !== "http://127.0.0.1:18765" || location.pathname !== "/fixture.html") return;
  const mode = new URLSearchParams(location.search).get("mode") === "probe" ? "probe" : "baseline";
  const video = document.querySelector("video");
  if (!video) return;
  const startedAt = performance.now();
  let frames = 0, frameTime = null, frameAt = null, waitingCount = 0, stalledCount = 0;
  let sample = 0, inFlight = false;
  const send = data => browser.runtime.sendNativeMessage("kantvAvProbe", data).catch(() => {});
  const snapshot = kind => ({
    kind, mode, elapsedMs: performance.now() - startedAt,
    currentTime: video.currentTime, duration: video.duration, readyState: video.readyState,
    paused: video.paused, ended: video.ended, frames, mediaTime: frameTime,
    callbackAgeMs: frameAt === null ? null : performance.now() - frameAt,
    waitingCount, stalledCount,
  });
  const rvfcAvailable = typeof video.requestVideoFrameCallback === "function";
  if (rvfcAvailable) {
    const frame = (_now, metadata) => {
      frames += 1;
      frameTime = metadata.mediaTime;
      frameAt = performance.now();
      video.requestVideoFrameCallback(frame);
    };
    video.requestVideoFrameCallback(frame);
  }
  video.addEventListener("waiting", () => { waitingCount += 1; });
  video.addEventListener("stalled", () => { stalledCount += 1; });
  const apiAvailable = typeof video.mozRequestDebugInfo === "function";
  send({...snapshot("start"), apiAvailable, rvfcAvailable});
  const pick = (target, field, value) => {
    if (typeof value === "boolean" || typeof value === "number" && Number.isFinite(value)) target[field] = value;
  };
  const interval = setInterval(async () => {
    sample += 1;
    send({...snapshot("progress"), sample});
    if (mode === "probe" && apiAvailable && !inFlight) {
      inFlight = true;
      const callAt = performance.now();
      try {
        const info = await video.mozRequestDebugInfo();
        const decoder = info.decoder;
        const state = decoder?.stateMachine;
        const wrapper = state?.mediaSink?.audioSinkWrapper;
        const audio = wrapper?.audioSink;
        const result = {...snapshot("debug"), sample, status: "ok", debugCallMs: performance.now() - callAt};
        for (const field of ["hasAudio", "hasVideo"]) pick(result, field, decoder?.[field]);
        pick(result, "clockUs", state?.clock);
        pick(result, "stateMediaTimeUs", state?.mediaTime);
        if (typeof state?.state === "string" && /^[A-Za-z0-9_]{1,48}$/.test(state.state)) result.state = state.state;
        for (const [out, key] of [["wrapperIsPlaying", "isPlaying"], ["wrapperIsStarted", "isStarted"], ["wrapperAudioEnded", "audioEnded"]]) pick(result, out, wrapper?.[key]);
        for (const [out, key] of [["startTimeUs", "startTime"], ["lastGoodPositionUs", "lastGoodPosition"], ["audioIsPlaying", "isPlaying"], ["audioIsStarted", "isStarted"], ["audioEnded", "audioEnded"], ["outputRate", "outputRate"], ["hasErrored", "hasErrored"]]) pick(result, out, audio?.[key]);
        // lastGoodPosition is relative microseconds; it is not an absolute audio PTS.
        send(result);
      } catch (error) {
        send({...snapshot("debug"), sample, status: "failed", debugCallMs: performance.now() - callAt,
          errorType: /^[A-Za-z0-9_]{1,48}$/.test(error?.name) ? error.name : "UnknownError"});
      } finally { inFlight = false; }
    }
    if (sample >= 15) {
      clearInterval(interval);
      send({...snapshot("complete"), sample});
    }
  }, 3000);
})();
