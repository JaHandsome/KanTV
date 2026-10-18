// Passive candidate resource timings. Entries are never attributed to a media request.
// No names, headers, bodies, or bytes are read; cross-origin timing detail may be absent.
(() => {
  if (window.top !== window) return;
  const MAX_ENTRIES = 128;
  const MAX_SCANNED = 1024;
  const MAX_MS = 86400000;
  const WINDOW_MS = 30000;
  const empty = status => ({
    geckoStartupResourceObservationStatus: status,
    geckoStartupResourceCount: -1,
    geckoStartupResourceMaxDurationMs: -1,
    geckoStartupResourceMaxResponseWaitMs: -1,
    geckoStartupResourceMaxDownloadMs: -1,
    geckoStartupResourceDetailedCount: -1,
    geckoStartupResourceScannedCount: -1,
    geckoStartupResourceFilteredCount: -1,
    geckoStartupResourceMaxStartMs: -1,
    geckoStartupResourceMaxEndMs: -1,
    geckoStartupResourceMaxInitiatorCode: 'NOT_APPLICABLE',
  });
  const duration = n => Number.isFinite(n) && n >= 0 && n <= MAX_MS ? Math.floor(n) : -1;
  let result = empty('NOT_APPLICABLE');
  let enabled = false;
  let started = false;
  let observer = null;
  let node = null;
  let stopped = false;
  let count = 0;
  let examined = 0;
  let filtered = 0;
  let detail = 0;
  let maxDuration = -1;
  let maxWait = -1;
  let maxDownload = -1;
  let maxRawDuration = -1;
  let maxStart = -1;
  let maxEnd = -1;
  let maxInitiator = 'NONE';
  let overflow = false;
  const installedAt = performance.now();
  const facts = status => ({
    geckoStartupResourceObservationStatus: status,
    geckoStartupResourceCount: count,
    geckoStartupResourceMaxDurationMs: maxDuration,
    geckoStartupResourceMaxResponseWaitMs: maxWait,
    geckoStartupResourceMaxDownloadMs: maxDownload,
    geckoStartupResourceDetailedCount: detail,
    geckoStartupResourceScannedCount: examined,
    geckoStartupResourceFilteredCount: filtered,
    geckoStartupResourceMaxStartMs: maxStart,
    geckoStartupResourceMaxEndMs: maxEnd,
    geckoStartupResourceMaxInitiatorCode: maxInitiator,
  });
  const initiatorCode = type => {
    switch (type) {
      case 'fetch': return 'FETCH';
      case 'xmlhttprequest': return 'XMLHTTPREQUEST';
      case 'video': return 'VIDEO';
      case 'audio': return 'AUDIO';
      default: return 'NONE';
    }
  };
  const collect = (entries, endAt = installedAt + WINDOW_MS) => {
    for (let i = 0; i < entries.length; i++) {
      if (overflow) break;
      examined++;
      const entry = entries[i];
      const code = entry ? initiatorCode(entry.initiatorType) : 'NONE';
      const start = code !== 'NONE' ? entry.startTime : -1;
      const elapsed = code !== 'NONE' ? entry.duration : -1;
      const validTiming = duration(start) >= 0 && duration(elapsed) >= 0;
      const completedAt = validTiming ? start + elapsed : -1;
      if (code === 'NONE' || !validTiming ||
          duration(completedAt) < 0 || completedAt > endAt) {
        filtered++;
      } else {
        count++;
        // Compare raw values so sub-ms differences do not pick the wrong entry.
        if (elapsed > maxRawDuration) {
          maxRawDuration = elapsed;
          maxDuration = duration(elapsed);
          maxStart = duration(start);
          maxEnd = duration(completedAt);
          maxInitiator = code;
        }
        const request = entry.requestStart;
        const response = entry.responseStart;
        const end = entry.responseEnd;
        // Zeroed restricted fields are unknown, rather than evidence of a zero network wait.
        if (duration(request) >= 0 && duration(response) >= 0 && duration(end) >= 0 &&
            request > 0 && response >= request && end >= response) {
          detail++;
          maxWait = Math.max(maxWait, duration(response - request));
          maxDownload = Math.max(maxDownload, duration(end - response));
        }
      }
      if (count >= MAX_ENTRIES || examined >= MAX_SCANNED) overflow = true;
    }
  };
  const stop = (status, at) => {
    if (stopped) return;
    stopped = true;
    if (observer) {
      try { collect(observer.takeRecords(), at); } catch (_) { /* Only already observed entries remain. */ }
      observer.disconnect();
    }
    result = facts(overflow ? 'OVERFLOW' : (count ? status : 'EMPTY'));
  };
  globalThis.__kantvStartupResources = {
    enable(value) {
      enabled = value === true;
      if (!enabled) {
        if (observer) observer.disconnect();
        observer = null;
        result = empty('NOT_APPLICABLE');
        return;
      }
      if (started) return;
      started = true;
      result = empty('UNSUPPORTED');
      try {
        if (typeof PerformanceObserver === 'function' &&
            (!Array.isArray(PerformanceObserver.supportedEntryTypes) ||
             PerformanceObserver.supportedEntryTypes.includes('resource'))) {
          observer = new PerformanceObserver(list => {
            if (stopped) return;
            if (performance.now() - installedAt >= WINDOW_MS) {
              collect(list.getEntries(), installedAt + WINDOW_MS);
              stop('BOUNDED', installedAt + WINDOW_MS);
              return;
            }
            collect(list.getEntries());
            if (overflow) stop('OVERFLOW', performance.now());
          });
          observer.observe({ type: 'resource', buffered: true });
          result = empty('OBSERVING');
        }
      } catch (_) {
        if (observer) observer.disconnect();
        observer = null;
        result = empty('UNSUPPORTED');
      }
    },
    bind(generation) {
      if (generation === null || generation === undefined) return;
      if (node === null) { node = generation; return; }
      if (generation !== node) {
        if (observer) observer.disconnect();
        stopped = true;
        node = generation;
        result = empty('INVALIDATED');
      }
    },
    freeze(generation, at) {
      if (generation === node && observer && !stopped) {
        const deadline = installedAt + WINDOW_MS;
        stop(at >= deadline ? 'BOUNDED' : 'COMPLETE', Math.min(at, deadline));
      }
    },
    facts(applicable, generation) {
      if (!enabled) return empty('NOT_APPLICABLE');
      if (observer && !stopped && performance.now() - installedAt >= WINDOW_MS) {
        stop('BOUNDED', installedAt + WINDOW_MS);
      }
      if (!applicable) return empty('NOT_APPLICABLE');
      if (node !== null && generation !== node) return empty('INVALIDATED');
      // Until completion, counts are explicitly partial; duration maxima are also partial.
      if (observer && !stopped) return facts('OBSERVING');
      return result;
    },
  };
})();
