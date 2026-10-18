// Deterministic async engine-clock observer; no browser/network/media dependencies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/kantv-gecko-mirror/bridge.js', 'utf8');
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const info = (clock = 2000000) => ({ decoder: { hasAudio: true, hasVideo: true,
  stateMachine: { clock, mediaSink: { audioSinkWrapper: {
    isStarted: true, isPlaying: true, audioEnded: false,
    audioSink: { startTime: 1000000, lastGoodPosition: clock - 1000000,
      // This default false is intentionally ignored (GV146 does not populate it).
      isStarted: false, isPlaying: true, audioEnded: false, hasErrored: false, outputRate: 48000 },
  } } }, privateUrl: 'https://private.invalid/token', decoderInstance: 'secret-address' } });
function harness(api = () => Promise.resolve(info()), timeOrigin = 1800000000000, resources, ResourceObserver) {
  let now = 0, tick, selected, callCount = 0, frameCount = 0, callback, failNextCallbackSchedule = false;
  let scan = () => [], selectHook = () => {}, sendHook = () => {}, clock = () => now;
  let intervalMs, clockReads = 0, capabilityReply;
  const timeoutDelays = [];
  const messages = [], timers = new Map(), listeners = new Map(), handlers = new Map();
  const doc = { visibilityState: 'visible', addEventListener: (k, f) => listeners.set(k, f),
    querySelector: s => { if (s.startsWith('video[')) { selectHook(); return selected; } return null; },
    querySelectorAll: s => scan(s) };
  const win = { devicePixelRatio: 1, getComputedStyle: e => e.style };
  win.top = win;
  const v = { currentTime: 2, paused: false, ended: false, seeking: false, muted: false, volume: 1,
    readyState: 4, videoWidth: 1280, videoHeight: 720, buffered: { length: 0 },
    isConnected: true, parentElement: null, style: { display: 'block', visibility: 'visible', opacity: '1' },
    getBoundingClientRect: () => ({ width: 1280, height: 720 }),
    addEventListener: (k, f) => handlers.set(k, f), removeEventListener: k => handlers.delete(k),
    requestVideoFrameCallback: f => {
      if (failNextCallbackSchedule) { failNextCallbackSchedule = false; throw new Error('synthetic callback reschedule failure'); }
      callback = f; return 1;
    }, cancelVideoFrameCallback() {},
    ...(api ? { mozRequestDebugInfo: () => { callCount++; return api(); } } : {}),
  };
  let mutation;
  const observedNodes = new Set();
  const context = { window: win, document: doc,
    __kantvStartupResources: resources === 'real' ? undefined : resources, PerformanceObserver: ResourceObserver,
    performance: { timeOrigin, now: () => { clockReads++; return clock(); } },
    addEventListener: (k, f) => listeners.set(k, f), setInterval: (f, delay) => { tick = f; intervalMs = delay; },
    setTimeout: (f, delay) => { timeoutDelays.push(delay); const key = Symbol(); timers.set(key, f); return key; },
    clearTimeout: k => timers.delete(k),
    MutationObserver: class { constructor(f) { mutation = f; } observe(node) { observedNodes.add(node); } disconnect() { observedNodes.clear(); } },
    browser: { runtime: { sendNativeMessage: (_, m) => {
      messages.push(m); sendHook(m);
      if (m.kind === 'diagnosticCapability') return { then: reply => { capabilityReply = reply; } };
      return Promise.resolve();
    } } },
  };
  vm.createContext(context);
  if (resources === 'real') vm.runInContext(
    fs.readFileSync('app/src/main/assets/kantv-gecko-mirror/startup-resource.js', 'utf8'), context);
  vm.runInContext(source, context);
  selected = v;
  const frame = (at, pts = 2, executedAt = at) => { now = executedAt; callback(at, { presentedFrames: ++frameCount,
    mediaTime: pts, presentationTime: at, expectedDisplayTime: at }); };
  const state = () => messages.filter(m => m.kind === 'state').at(-1);
  const enable = (value = true) => capabilityReply({ diagnosticsEnabled: value, audioObservationEnabled: value });
  const pageEnable = (value = true) => listeners.get('message')({ data: { __kantvAudioClockObserve: value } });
  const prime = (start = 0) => { now = start; tick(); frame(start, 1.9); frame(start + 40, 1.95); frame(start + 1040, 2); tick(); };
  return { v, doc, messages, timers, handlers, listeners, frame, state, enable, pageEnable, prime,
    tick: () => tick(), at: t => { now = t; }, replace: n => { selected = n; },
    failNextSchedule: () => { failNextCallbackSchedule = true; },
    mutation: () => mutation(), nodes: () => observedNodes, calls: () => callCount,
    scan: f => { scan = f; }, selectHook: f => { selectHook = f; }, sendHook: f => { sendHook = f; },
    clock: f => { clock = f; }, advance: delta => { now += delta; }, now: () => now,
    intervalMs: () => intervalMs, timeoutDelays, clockReads: () => clockReads,
    resetClockReads: () => { clockReads = 0; } };
}
(async () => {
  let h = harness(); h.prime(); await flush(); h.tick();
  assert.equal(h.calls(), 0, 'default disabled');
  assert.equal(h.state().geckoAudioClockStatus, 'NOT_APPLICABLE');
  h.pageEnable(); h.prime(1000); await flush(); h.tick();
  assert.equal(h.calls(), 0, 'page message alone cannot grant diagnostic capability');
  h.enable(false); h.pageEnable(); h.tick(); await flush();
  assert.equal(h.calls(), 0, 'explicit native false cannot be overridden by the page');
  h.enable(); h.prime(2000); await flush(); h.tick();
  assert.equal(h.calls(), 1); assert.equal(h.state().geckoAudioClockStatus, 'AVAILABLE');
  assert.equal(h.state().geckoAudioClockUs, 2000000);
  assert.equal(h.state().geckoAudioClockCallDurationMs, 0);
  assert.equal(h.state().geckoAudioMinusVideoUs, 0);
  assert.equal(h.state().geckoAvPairSpanMs, 0);
  assert(h.messages.every(m => !JSON.stringify(m).includes('private') && !JSON.stringify(m).includes('secret-address')));
  h.frame(3050, 10); h.tick();
  assert.equal(h.state().videoPtsUs, 10000000);
  assert.equal(h.state().geckoPairedVideoPtsUs, 2000000, 'exact earlier frame retained');
  assert.equal(h.state().geckoAudioMinusVideoUs, 0, 'no mixed later video PTS');
  assert.equal(h.calls(), 1, '3s rate bound');
  h.at(8041); h.tick(); assert.notEqual(h.state().geckoAudioClockStatus, 'AVAILABLE', 'cache expires');
  for (const change of [() => { h.doc.visibilityState = 'hidden'; }, () => { h.v.muted = true; },
    () => { h.v.seeking = true; }, () => { h.v.paused = true; }, () => { h.v.volume = 0; },
    () => { h.v.readyState = 2; }, () => { h.v.isConnected = false; },
    () => { h.v.style.visibility = 'hidden'; }, () => { h.v.style.opacity = '0'; },
    () => { h.v.parentElement = { style: { display: 'none', visibility: 'visible', opacity: '1' }, parentElement: null }; }]) {
    h = harness(); h.enable(); change(); h.prime(); await flush(); h.tick();
    assert.equal(h.calls(), 0); assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_NOT_PLAYING');
  }
  h = harness(); h.enable(); h.tick(); h.frame(0); h.frame(40); h.tick();
  assert.equal(h.calls(), 0, 'stable advancing startup required');
  h = harness(null); h.enable(); h.prime(); h.tick();
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_UNSUPPORTED');
  for (const [api, expected] of [
    [() => Promise.reject(new Error('private-token')), 'INSUFFICIENT_REJECTED'],
    [() => Promise.resolve({}), 'INSUFFICIENT_INVALID_CLOCK'],
    [() => Promise.resolve(info(0)), 'INSUFFICIENT_INVALID_CLOCK'],
    [() => { const i = info(); i.decoder.stateMachine.clock += 1001; return Promise.resolve(i); }, 'INSUFFICIENT_CLOCK_MISMATCH'],
    [() => { const i = info(); i.decoder.stateMachine.mediaSink.audioSinkWrapper.isStarted = false; return Promise.resolve(i); }, 'INSUFFICIENT_INVALID_CLOCK'],
    [() => { const i = info(); i.decoder.stateMachine.mediaSink.audioSinkWrapper.audioSink.outputRate = 0; return Promise.resolve(i); }, 'INSUFFICIENT_INVALID_CLOCK'],
  ]) {
    h = harness(api); h.enable(); h.prime(); await flush(); h.tick();
    assert.equal(h.state().geckoAudioClockStatus, expected);
    h.frame(7040); h.tick(); await flush(); assert.equal(h.calls(), 1, 'per-node fuse');
  }
  let resolve; h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
  h.frame(1050, 3); h.at(1055); resolve(info(1999999)); await flush(); h.tick();
  assert.equal(h.state().geckoPairedVideoPtsUs, 2000000);
  assert.equal(h.state().geckoAudioMinusVideoUs, -1, 'signed -1 is a measured value');
  assert.equal(h.state().geckoAvPairSpanMs, 15);
  assert.equal(h.state().geckoAudioClockAgeMs, 15);
  h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.tick();
  h.frame(0, 1.9); h.frame(40, 1.95); h.frame(1000, 2); h.at(1040); h.tick();
  h.at(1120); resolve(info()); await flush(); h.tick();
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_PAIR_STALE');
  h.frame(4039, 3); h.tick(); assert.equal(h.calls(), 1, 'pair retry respects 3s');
  h.frame(4040, 3.1); h.tick(); assert.equal(h.calls(), 2, 'stale pair may recover at next interval');
  resolve(info()); await flush();
  h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
  h.at(1141); resolve(info()); await flush(); h.tick();
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_SLOW_CALL');
  assert.equal(h.state().geckoAudioQueryRvfcCount, 0);
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, 101,
    'no-frame query includes start-to-settle boundary gap');
  h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
  h.at(2040); for (const f of [...h.timers.values()]) f(); h.frame(8040); h.tick();
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_TIMEOUT'); assert.equal(h.calls(), 1);
  h.handlers.get('emptied')(); h.prime(9000); assert.equal(h.calls(), 1, 'old unresolved request blocks new node');
  resolve(info()); await flush(); h.tick(); assert.notEqual(h.state().geckoAudioClockStatus, 'AVAILABLE');
  h = harness(); h.enable(); h.prime(); await flush(); h.tick();
  const parent = { style: { display: 'none', visibility: 'visible', opacity: '1' }, parentElement: null };
  h.v.parentElement = parent; h.mutation(); h.tick();
  assert(h.nodes().has(parent), 'reparented video watches its new ancestor');
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_NOT_PLAYING');
  parent.style.display = 'block'; h.mutation(); h.tick();
  assert.notEqual(h.state().geckoAudioClockStatus, 'AVAILABLE', 'unhide cannot restore old cache');
  for (const transition of ['visibility', 'mutation', 'seeking', 'disable', 'node', 'pagehide']) {
    h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
    if (transition === 'visibility') { h.doc.visibilityState = 'hidden'; h.listeners.get('visibilitychange')(); h.doc.visibilityState = 'visible'; }
    if (transition === 'mutation') { h.v.style.display = 'none'; h.mutation(); h.v.style.display = 'block'; h.mutation(); }
    if (transition === 'seeking') { h.v.seeking = true; h.handlers.get('seeking')(); h.v.seeking = false; }
    if (transition === 'disable') { h.enable(false); h.enable(); }
    if (transition === 'node') h.handlers.get('loadedmetadata')();
    if (transition === 'pagehide') h.listeners.get('pagehide')();
    resolve(info()); await flush(); h.tick();
    assert.notEqual(h.state().geckoAudioClockStatus, 'AVAILABLE', `late promise discarded: ${transition}`);
  }
  for (const message of h.messages) assert(!JSON.stringify(message).includes('private'));
  h = harness(); h.prime(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'NOT_APPLICABLE');
  h.enable(); h.prime(2000); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'RESOLVED');
  assert.equal(h.state().geckoAudioQuerySeq, 1);
  assert.equal(h.state().geckoAudioQueryDisposition, 'APPLIED');
  assert.equal(h.state().geckoAudioSyncCallMs, 0);
  assert.equal(h.state().geckoAudioReactionElapsedMs, 0);
  assert.equal(h.state().geckoAudioInFlight, false);
  assert.equal(h.state().geckoAudioTimeoutLateMs, -1);
  assert.equal(h.state().geckoAudioQueryRvfcCount, 0, 'zero qualifying rVFC callbacks is known');
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, 0, 'fast settlement closes the query boundary');
  assert.equal(h.state().geckoDocumentNowMs, 3040);
  assert.equal(h.state().geckoDocumentTimeOriginMs, 1800000000000);
  h = harness(() => Promise.resolve(info()), null); h.enable(); h.tick();
  assert.equal(h.state().geckoDocumentNowMs, 0, 'document performance.now zero is valid');
  assert.equal(h.state().geckoDocumentTimeOriginMs, -1, 'missing timeOrigin stays unknown');

  let lateInfoReads = 0;
  const forbiddenLateInfo = { get decoder() { lateInfoReads++; throw new Error('private-token'); } };
  h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'PENDING');
  assert.equal(h.state().geckoAudioInFlight, true);
  assert.equal(h.state().geckoAudioReactionElapsedMs, -1);
  h.at(3416); for (const f of [...h.timers.values()]) f(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'TIMED_OUT');
  assert.equal(h.state().geckoAudioTimeoutLateMs, 1376);
  assert.equal(h.state().geckoJsTickGapMs, 2376);
  assert.equal(h.state().geckoAudioClockCallDurationMs, 2376);
  assert.equal(h.state().geckoAudioQueryDisposition, 'TIMEOUT_FUSE');
  assert.equal(h.state().geckoAudioInFlight, true, 'timeout never clears unresolved request');
  h.at(3500); resolve(forbiddenLateInfo); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'RESOLVED');
  assert.equal(h.state().geckoAudioReactionElapsedMs, 2460);
  assert.equal(h.state().geckoAudioInFlight, false);
  assert.equal(h.state().geckoAudioQueryDisposition, 'IGNORED_TIMEOUT_FUSE');
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_TIMEOUT');
  assert.equal(lateInfoReads, 0, 'fused late response dictionary is never inspected');
  h.frame(8000); h.tick(); assert.equal(h.calls(), 1, 'late settle does not unlock per-node fuse');

  h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
  h.frame(1300, 2.01, 1311); h.tick();
  assert.equal(h.state().geckoAudioQueryRvfcCount, 1);
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, 271,
    'gap uses performance.now at JS callback execution, not the rVFC now argument');
  h.frame(1400, 2.02, 1419); h.tick();
  assert.equal(h.state().geckoAudioQueryRvfcCount, 2, 'frames continue during pending Promise');
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, 271);
  h.at(2040); for (const f of [...h.timers.values()]) f(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'TIMED_OUT');
  h.frame(2200, 2.03, 2250); h.tick();
  assert.equal(h.state().geckoAudioQueryRvfcCount, 3, 'timeout does not stop collecting the existing request');
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, 831);
  h.at(2300); resolve(info()); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'RESOLVED');
  assert.equal(h.state().geckoAudioQueryDisposition, 'IGNORED_TIMEOUT_FUSE');
  assert.equal(h.state().geckoAudioQueryRvfcCount, 3, 'late settlement retains the original request count');
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, 831, 'settle boundary included without unlocking fuse');
  assert.equal(h.state().geckoAudioInFlight, false);

  let reject;
  h = harness(() => new Promise((_, r) => { reject = r; })); h.enable(); h.prime();
  h.at(2040); for (const f of [...h.timers.values()]) f(); h.tick();
  h.at(2100); reject(new Error('private-token')); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'REJECTED');
  assert.equal(h.state().geckoAudioQueryDisposition, 'IGNORED_TIMEOUT_FUSE');
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_TIMEOUT', 'first fuse reason retained');
  assert.equal(h.state().geckoAudioInFlight, false);

  h = harness(() => { h.at(1047); throw new Error('private-token'); }); h.enable(); h.prime();
  await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'REJECTED');
  assert.equal(h.state().geckoAudioSyncCallMs, 7);
  assert.equal(h.state().geckoAudioQueryDisposition, 'SYNC_THROW');
  assert.equal(h.state().geckoAudioClockStatus, 'INSUFFICIENT_REJECTED');
  assert.equal(h.state().geckoAudioInFlight, false);

  for (const transition of ['mute', 'replace']) {
    h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
    const requestNode = h.state().geckoAudioQueryNode;
    if (transition === 'mute') { h.v.muted = true; h.handlers.get('volumechange')(); }
    else { h.replace({ ...h.v }); h.tick(); }
    h.at(1060); resolve(forbiddenLateInfo); await flush(); h.tick();
    assert.equal(h.state().geckoAudioQueryState, 'RESOLVED');
    assert.equal(h.state().geckoAudioInFlight, false);
    assert.equal(h.state().geckoAudioQueryDisposition, transition === 'mute' ? 'IGNORED_EPOCH' : 'IGNORED_NODE');
    assert.equal(h.state().geckoAudioQueryNode, requestNode, 'captured request identity retained');
    assert.notEqual(h.state().geckoAudioClockStatus, 'AVAILABLE');
  }
  assert.equal(lateInfoReads, 0);

  for (const transition of ['node', 'epoch']) {
    h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
    const requestNode = h.state().geckoAudioQueryNode;
    const requestEpoch = h.state().geckoAudioQueryEpoch;
    h.frame(1200, 2.01, 1210); h.tick();
    assert.equal(h.state().geckoAudioQueryRvfcCount, 1);
    if (transition === 'node') { h.replace({ ...h.v }); h.tick(); }
    else { h.v.muted = true; h.handlers.get('volumechange')(); h.v.muted = false; }
    h.frame(1300, 2.02, 1310); h.tick();
    assert.equal(h.state().geckoAudioQueryRvfcCount, 1, `${transition} change excludes later callbacks`);
    h.at(1320); resolve(info()); await flush(); h.tick();
    assert.equal(h.state().geckoAudioQueryNode, requestNode);
    assert.equal(h.state().geckoAudioQueryEpoch, requestEpoch);
    assert.equal(h.state().geckoAudioQueryRvfcCount, 1, 'valid pre-invalidation callbacks remain counted');
    assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, -1,
      'invalidated request has no comparable same-identity end boundary');
    assert.equal(h.state().geckoAudioQueryDisposition, transition === 'node' ? 'IGNORED_NODE' : 'IGNORED_EPOCH');
  }

  h = harness(() => new Promise(r => { resolve = r; })); h.enable(); h.prime();
  h.failNextSchedule(); h.frame(1200, 2.01, 1210); h.tick();
  assert.equal(h.state().geckoAudioQueryRvfcCount, 1, 'callback that observed the request remains counted');
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, -1,
    'failed rVFC resubscription makes the request gap unknown immediately');
  h.at(1320); resolve(info()); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryRvfcCount, 1, 'reschedule failure retains valid count');
  assert.equal(h.state().geckoAudioQueryMaxRvfcGapMs, -1,
    'Promise reaction cannot close a gap after the rVFC loop failed');

  h = harness(); h.enable(); h.v.readyState = 0; h.v.currentTime = 0; h.v.paused = true;
  h.at(100); h.tick(); assert.equal(h.state().geckoAttachLate, false);
  h.at(150); h.handlers.get('play')();
  h.at(160); h.handlers.get('canplay')();
  h.at(170); h.handlers.get('loadedmetadata')();
  h.frame(180, 0);
  h.at(190); h.handlers.get('playing')();
  h.at(200); h.handlers.get('waiting')();
  h.at(210); h.handlers.get('playing')(); h.handlers.get('loadedmetadata')(); h.tick();
  assert.equal(h.state().geckoNodeBoundMs, 100);
  assert.equal(h.state().geckoLoadedMetadataMs, 170);
  assert.equal(h.state().geckoPlayMs, 150);
  assert.equal(h.state().geckoPlayingMs, 190);
  assert.equal(h.state().geckoWaitingMs, 200);
  assert.equal(h.state().geckoCanPlayMs, 160);
  assert.equal(h.state().geckoFirstRvfcMs, 180);
  assert.equal(h.state().geckoFirstRvfcPtsUs, 0, 'first media PTS zero is valid');
  assert.equal(h.v.paused, true, 'startup event observation never plays/pauses media');
  h.at(300); h.handlers.get('emptied')(); h.tick();
  assert.equal(h.state().geckoNodeBoundMs, 100, 'same DOM bind is not relabeled by media reset');
  assert.equal(h.state().geckoFirstRvfcMs, -1);
  assert.equal(h.state().geckoPlayMs, -1);
  h = harness(); h.enable(); h.at(500); h.tick();
  assert.equal(h.state().geckoAttachLate, true);
  assert.equal(h.state().geckoLoadedMetadataMs, -1, 'readyState does not fabricate a media event');

  for (const message of h.messages) assert(!JSON.stringify(message).includes('private'));

  // Same issuing task: debug call takes 1ms, the following existing login scan takes 2259ms.
  // Completion facts cannot be placed in its own already-sent state packet.
  let finishQuery;
  h = harness(() => { h.advance(1); return new Promise(r => { finishQuery = r; }); });
  h.enable(); h.tick(); h.frame(0, 1.9); h.frame(40, 1.95);
  for (let i = 0; i < 4; i++) h.tick();
  h.frame(1040, 2);
  let textReads = 0, qrReads = 0;
  h.scan(selector => selector === 'div,a,span,p,li'
    ? Array.from({ length: 3000 }, () => ({ get textContent() {
        textReads++; if (textReads === 1) { h.advance(2259); finishQuery(info()); } return ''; } }))
    : Array.from({ length: 400 }, () => ({ getBoundingClientRect() { qrReads++; return { width: 0, height: 0 }; } })));
  h.tick();
  assert.equal(h.state().tick, 6);
  assert.equal(h.state().geckoAudioSyncCallMs, 1);
  assert.equal(h.state().geckoAudioQueryTickDurationMs, -1, 'current packet precedes own completion');
  assert.equal(h.state().geckoAudioQueryTickMaxPhaseCode, 'UNKNOWN');
  assert.equal(h.intervalMs(), 1000); assert.deepEqual(h.timeoutDelays, [1000]);
  assert.equal(textReads, 2500); assert.equal(qrReads, 300);
  assert.equal(h.calls(), 1); assert.equal(h.timers.size, 1, 'original pending timer retained');
  await flush(); h.scan(() => []); h.tick();
  assert.equal(h.state().geckoAudioQueryTickDurationMs, 2260);
  assert.equal(h.state().geckoAudioQueryTickTailDurationMs, 2259);
  assert.equal(h.state().geckoAudioQueryTickMaxPhaseCode, 'LOGIN_SCAN');
  assert.equal(h.state().geckoAudioQueryTickMaxPhaseDurationMs, 2259);
  assert.equal(h.state().geckoAudioQueryTickScanNodes, 2800);
  assert.equal(h.state().geckoBridgeLastTickDurationMs, 2260);
  assert.equal(h.state().geckoBridgeLastTickMaxPhaseCode, 'LOGIN_SCAN');
  assert.equal(h.state().geckoAudioReactionElapsedMs, 2260);
  assert.equal(h.state().geckoAudioQueryDisposition, 'SLOW_CALL', 'existing slow-call fuse unchanged');
  assert.equal(h.timers.size, 0, 'existing settlement clears timer');
  h.advance(10000); h.tick();
  assert.equal(h.calls(), 1, 'measuring work never unlocks existing fuse');
  assert.equal(h.state().geckoBridgeLastTickScanNodes, 0, 'non-scan tick visits no login/QR candidates');
  assert.equal(h.state().geckoBridgeLastTickMaxPhaseCode, 'NONE');
  assert.equal(h.state().geckoAudioQueryTickDurationMs, 2260, 'later tick cannot overwrite issuing-task facts');

  // Native synchronous send work is measured, but only the following packet can report it.
  h = harness(); h.enable(); h.sendHook(m => { if (m.kind === 'state') h.advance(17); });
  h.tick(); h.tick();
  assert.equal(h.state().geckoBridgeLastTickDurationMs, 17);
  assert.equal(h.state().geckoBridgeLastTickMaxPhaseCode, 'NATIVE_SEND');
  assert.equal(h.state().geckoBridgeLastTickMaxPhaseDurationMs, 17);

  // First bind counts only post-bind work. Ordinary non-playing startup changes audioEpoch
  // internally on every tick, and must not invalidate same-resource startup observations.
  h = harness(); h.enable(); h.v.readyState = 0; h.v.paused = true;
  h.selectHook(() => h.advance(30));
  h.scan(selector => { if (selector === 'div,a,span,p,li') h.advance(9); return []; });
  h.tick(); h.selectHook(() => {}); h.tick();
  assert.equal(h.state().geckoBridgeLastTickDurationMs, 39);
  assert.equal(h.state().geckoBridgeLastTickMaxPhaseCode, 'BIND');
  assert.equal(h.state().geckoStartupObserverTickCount, 1);
  assert.equal(h.state().geckoStartupObserverTotalDurationMs, 9, 'pre-bind work excluded');
  assert.equal(h.state().geckoStartupObserverMaxTickDurationMs, 9);
  h.at(100000); h.tick();
  assert.equal(h.state().geckoStartupObserverTickCount, 2);
  assert.equal(h.state().geckoStartupObserverTotalDurationMs, 9, 'between-task wait excluded');
  h.frame(100001); h.tick(); h.tick();
  assert.equal(h.state().geckoStartupObserverTickCount, 3, 'first rVFC stops startup accumulation');

  // An interrupted task reports unknown; its error follows the existing defensive path.
  h = harness(() => new Promise(r => { finishQuery = r; })); h.enable(); h.tick();
  h.frame(0, 1.9); h.frame(40, 1.95); for (let i = 0; i < 4; i++) h.tick(); h.frame(1040, 2);
  h.scan(() => { throw new Error('private scan failure'); }); h.tick();
  assert.equal(h.messages.at(-1).kind, 'error');
  h.scan(() => []); h.tick();
  assert.equal(h.state().geckoBridgeLastTickDurationMs, -1);
  assert.equal(h.state().geckoBridgeLastTickMaxPhaseCode, 'UNKNOWN');
  assert.equal(h.state().geckoAudioQueryTickDurationMs, -1);
  assert.equal(h.state().geckoAudioQueryTickMaxPhaseCode, 'UNKNOWN');
  assert.equal(h.calls(), 1); assert.equal(h.timers.size, 1);
  finishQuery(info()); await flush();

  // Resource and audio epoch changes inside issuing task cannot label replacement work
  // as the old query's timing; late settlement keeps its original captured identity.
  for (const change of ['resource', 'epoch']) {
    h = harness(() => new Promise(r => { finishQuery = r; })); h.enable(); h.tick();
    h.frame(0, 1.9); h.frame(40, 1.95); for (let i = 0; i < 4; i++) h.tick(); h.frame(1040, 2);
    h.scan(selector => {
      if (selector === 'div,a,span,p,li') {
        if (change === 'resource') h.handlers.get('loadedmetadata')();
        else h.handlers.get('volumechange')();
      } return [];
    });
    h.tick(); const capturedNode = h.state().geckoAudioQueryNode;
    h.scan(() => []); h.tick();
    assert.equal(h.state().geckoAudioQueryTickDurationMs, -1);
    assert.equal(h.state().geckoAudioQueryTickMaxPhaseCode, 'UNKNOWN');
    finishQuery(info()); await flush(); h.tick();
    assert.equal(h.state().geckoAudioQueryNode, capturedNode);
    assert.equal(h.state().geckoAudioQueryDisposition, change === 'resource' ? 'IGNORED_NODE' : 'IGNORED_EPOCH');
  }
  h = harness(); h.enable(); h.v.paused = true;
  h.scan(selector => { if (selector === 'div,a,span,p,li') h.handlers.get('loadedmetadata')(); return []; });
  h.tick(); h.scan(() => []); h.tick();
  assert.equal(h.state().geckoStartupObserverTickCount, -1, 'mid-task resource reset invalidates totals');

  // Invalid/backwards document clocks fail timing closed without adding timers or messages.
  for (const value of [NaN, -1]) {
    h = harness(); h.enable(); h.tick(); h.clock(() => value); h.tick();
    h.clock(() => h.now()); h.tick();
    assert.equal(h.state().geckoBridgeLastTickDurationMs, -1);
    assert.equal(h.state().geckoBridgeLastTickMaxPhaseCode, 'UNKNOWN');
    assert.equal(h.state().geckoStartupObserverTickCount, -1);
    assert.equal(h.calls(), 0); assert.equal(h.timeoutDelays.length, 0);
  }

  // Cover every own task that intersects the pending-query window, not just issuing tick.
  let settleWindow;
  h = harness(() => { h.advance(1); return new Promise(r => { settleWindow = r; }); });
  h.enable(); h.tick(); h.frame(0, 1.9); h.frame(40, 1.95); h.frame(1040, 2);
  h.selectHook(() => h.advance(1));
  h.v.getVideoPlaybackQuality = () => { h.advance(2); return { totalVideoFrames: 1 }; };
  h.resetClockReads(); h.tick();
  assert.equal(h.clockReads(), 16, 'issuing task uses the same schema19 clock reads');
  assert.equal(h.state().geckoAudioQueryObservedWorkCount, 0, 'current task is incomplete in its own packet');
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseCode, 'NONE');
  h.selectHook(() => {});
  h.v.getVideoPlaybackQuality = () => { h.advance(2000); return { totalVideoFrames: 2 }; };
  h.resetClockReads(); h.tick();
  assert.equal(h.clockReads(), 13, 'pending task accumulation adds no clock read');
  assert.equal(h.state().geckoAudioQueryTickDurationMs, 4);
  assert.equal(h.state().geckoAudioQueryObservedWorkCount, 1);
  assert.equal(h.state().geckoAudioQueryObservedWorkTotalMs, 4);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxMs, 4);
  h.v.getVideoPlaybackQuality = () => { h.advance(5); return { totalVideoFrames: 3 }; };
  for (const timer of h.timers.values()) timer();
  h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'TIMED_OUT');
  assert.equal(h.state().geckoAudioQueryObservedWorkCount, 2);
  assert.equal(h.state().geckoAudioQueryObservedWorkTotalMs, 2004);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxMs, 2000);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseCode, 'STATE_BUILD');
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseMs, 2000);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxScanNodes, 0);
  assert.equal(h.calls(), 1); assert.deepEqual(h.timeoutDelays, [1000]); assert.equal(h.intervalMs(), 1000);
  settleWindow(info()); await flush();
  h.v.getVideoPlaybackQuality = () => { h.advance(50); return { totalVideoFrames: 4 }; };
  h.tick(); const frozenWindow = h.state();
  assert.equal(frozenWindow.geckoAudioQueryObservedWorkCount, 3, 'soft timeout leaves subsequent task in window');
  assert.equal(frozenWindow.geckoAudioQueryObservedWorkTotalMs, 2009);
  assert.equal(frozenWindow.geckoAudioQueryDisposition, 'IGNORED_TIMEOUT_FUSE');
  h.tick();
  assert.equal(h.state().geckoAudioQueryObservedWorkCount, 3, 'reaction freezes before later tasks');
  assert.equal(h.state().geckoAudioQueryObservedWorkTotalMs, 2009);
  assert.equal(h.state().geckoBridgeLastTickDurationMs, 50, 'later sampled last tick cannot overwrite window max');
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxMs, 2000);
  h.handlers.get('emptied')(); h.prime(10000); const nextQuerySeq = h.state().geckoAudioQuerySeq;
  h.v.getVideoPlaybackQuality = () => ({ totalVideoFrames: 5 }); h.tick();
  assert.equal(nextQuerySeq, 2);
  assert.equal(h.state().geckoAudioQueryObservedWorkCount, 1);
  assert.equal(h.state().geckoAudioQueryObservedWorkTotalMs, 51);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxMs, 51, 'new request does not inherit old maximum');
  assert.equal(frozenWindow.geckoAudioQueryObservedWorkMaxMs, 2000, 'old emitted packet remains immutable');
  settleWindow(info()); await flush();

  // Highest phase and scan-node maxima are independent from the longest full task.
  h = harness(() => new Promise(r => { settleWindow = r; })); h.enable(); h.prime();
  h.selectHook(() => h.advance(500));
  h.v.getVideoPlaybackQuality = () => { h.advance(1500); return { totalVideoFrames: 1 }; };
  h.tick();
  h.selectHook(() => {});
  h.v.getVideoPlaybackQuality = () => { h.advance(1700); return { totalVideoFrames: 2 }; };
  h.tick();
  h.v.getVideoPlaybackQuality = () => ({ totalVideoFrames: 3 });
  h.scan(s => s === 'div,a,span,p,li' ? [{ textContent: '' }, { textContent: '' }, { textContent: '' }] : []);
  while (h.state().tick < 6) h.tick(); h.tick();
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxMs, 2000);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseMs, 1700,
    'highest phase comes from shorter 1700ms task, not longest 2000ms task');
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseCode, 'STATE_BUILD');
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxScanNodes, 3, 'scan maximum retained independently');
  settleWindow(info()); await flush();

  // Raw primitive accumulation is floored on output rather than once per task.
  h = harness(() => new Promise(r => { settleWindow = r; })); h.enable(); h.prime();
  h.v.getVideoPlaybackQuality = () => { h.advance(0.6); return { totalVideoFrames: 1 }; };
  h.tick(); h.tick(); h.v.getVideoPlaybackQuality = () => ({ totalVideoFrames: 2 }); h.tick();
  assert.equal(h.state().geckoAudioQueryObservedWorkTotalMs, 1);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxMs, 0);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseMs, 0);
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseCode, 'NONE');
  settleWindow(info()); await flush();
  for (const transition of ['node', 'epoch', 'clock', 'exception']) {
    h = harness(() => new Promise(r => { settleWindow = r; })); h.enable(); h.prime();
    if (transition === 'node') h.handlers.get('loadedmetadata')();
    else if (transition === 'epoch') h.handlers.get('volumechange')();
    else if (transition === 'clock') { h.clock(() => NaN); h.tick(); h.clock(() => h.now()); }
    else { while (h.state().tick < 5) h.tick(); h.scan(() => { throw Error('private'); }); h.tick(); h.scan(() => []); }
    h.tick();
    assert.equal(h.state().geckoAudioQueryObservedWorkCount, -1, `${transition} invalidates only window group`);
    assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseCode, 'UNKNOWN');
    assert.equal(h.state().geckoAudioQueryTickDurationMs, 0, 'valid immutable issuing-tick evidence retained');
    assert.equal(h.calls(), 1, 'invalidated unresolved request still blocks retries');
    settleWindow(info()); await flush(); h.tick();
    assert.equal(h.state().geckoAudioQueryObservedWorkCount, -1);
  }

  // A throwing reaction clock must poison only the optional window group, then preserve
  // the original throw -> catch -> rejected/fuse route and its existing clock-read count.
  h = harness(() => new Promise(r => { settleWindow = r; })); h.enable(); h.prime();
  let throwReactionClockOnce = true;
  h.clock(() => {
    if (throwReactionClockOnce) { throwReactionClockOnce = false; throw Error('private reaction clock'); }
    return h.now();
  });
  h.resetClockReads(); settleWindow(info()); await flush();
  assert.equal(h.clockReads(), 3, 'original failing reaction, catch reaction, rejection duration reads retained');
  assert.equal(h.calls(), 1); assert.equal(h.timers.size, 0);
  h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'REJECTED');
  assert.equal(h.state().geckoAudioQueryDisposition, 'REJECTED_FUSE');
  assert.equal(h.state().geckoAudioQueryTickDurationMs, 0);
  for (const field of ['Count', 'TotalMs', 'MaxMs', 'MaxPhaseMs', 'MaxScanNodes']) {
    assert.equal(h.state()[`geckoAudioQueryObservedWork${field}`], -1,
      'reaction exception invalidates every optional window numeric');
  }
  assert.equal(h.state().geckoAudioQueryObservedWorkMaxPhaseCode, 'UNKNOWN');
  h.frame(8040); h.tick(); assert.equal(h.calls(), 1, 'clock exception keeps original per-node fuse');

  // Optional helper integration uses node identity and the callback execution clock.
  // Actual manifest script order in one shared global: resource helper then bridge.
  let resourceDelivery, resourceDisconnected = 0;
  class ResourceObserver {
    constructor(callback) { resourceDelivery = callback; }
    observe(options) { assert.deepEqual(JSON.parse(JSON.stringify(options)), { type: 'resource', buffered: true }); }
    takeRecords() { return []; }
    disconnect() { resourceDisconnected++; }
  }
  h = harness(undefined, undefined, 'real', ResourceObserver); h.enable(); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'OBSERVING');
  h.handlers.get('loadedmetadata')(); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'OBSERVING', 'initial metadata does not invalidate document resource window');
  h.at(20);
  resourceDelivery({ getEntries: () => [{ initiatorType: 'fetch', startTime: 0, duration: 10,
    requestStart: 1, responseStart: 5, responseEnd: 10,
    get name() { throw Error('private URL must never be read'); } }] });
  h.frame(40, 2, 55); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'COMPLETE');
  assert.equal(h.state().geckoStartupResourceCount, 1);
  assert.equal(h.state().geckoStartupResourceMaxDurationMs, 10);
  assert.equal(h.state().geckoStartupResourceMaxResponseWaitMs, 4);
  assert.equal(h.state().geckoStartupResourceMaxDownloadMs, 5);
  assert.equal(h.state().geckoStartupResourceDetailedCount, 1);
  assert.equal(h.state().geckoStartupResourceScannedCount, 1);
  assert.equal(h.state().geckoStartupResourceFilteredCount, 0);
  assert.equal(h.state().geckoStartupResourceMaxStartMs, 0);
  assert.equal(h.state().geckoStartupResourceMaxEndMs, 10);
  assert.equal(h.state().geckoStartupResourceMaxInitiatorCode, 'FETCH');
  assert.equal(resourceDisconnected, 1, 'first actual rVFC freezes shared helper');
  h.handlers.get('emptied')(); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'INVALIDATED', 'new resource on same DOM invalidates window');
  h.replace({ ...h.v }); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'INVALIDATED');
  assert.equal(h.state().geckoStartupResourceCount, -1, 'second bind cannot inherit first-node document resources');
  h = harness(undefined, undefined, 'real', ResourceObserver); h.enable(); h.replace(null); h.tick();
  h.replace(h.v); h.tick(); h.handlers.get('loadedmetadata')(); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'OBSERVING', 'no-video task cannot pre-bind a null scope');
  h.at(30001); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'EMPTY', 'same scope reaches existing 30s deadline after metadata');
  const resourceCalls = [];
  const resources = {
    bind: node => resourceCalls.push(['bind', node]),
    freeze: (node, at) => resourceCalls.push(['freeze', node, at]),
    facts: applicable => ({ geckoStartupResourceObservationStatus: applicable ? 'EMPTY' : 'NOT_APPLICABLE',
      geckoStartupResourceCount: applicable ? 0 : -1, geckoStartupResourceMaxDurationMs: -1,
      geckoStartupResourceMaxResponseWaitMs: -1, geckoStartupResourceMaxDownloadMs: -1,
      geckoStartupResourceDetailedCount: applicable ? 0 : -1 }),
  };
  h = harness(undefined, undefined, resources); h.enable(); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'EMPTY');
  const resourceScope = resourceCalls[0][1];
  assert.equal(typeof resourceScope, 'object', 'resource lifecycle identity is private startup scope');
  h.handlers.get('loadedmetadata')();
  h.frame(40, 2, 55); h.frame(60, 2.1, 75); h.tick();
  assert.deepEqual(resourceCalls, [['bind', resourceScope], ['freeze', resourceScope, 55]],
    'initial metadata retains resource scope; freeze once at JS execution');
  h.handlers.get('emptied')(); h.tick();
  assert.notEqual(resourceCalls.at(-1)[1], resourceScope, 'emptied binds a new resource scope');
  h.enable(false); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'NOT_APPLICABLE');
  h = harness(); h.enable(); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'UNSUPPORTED', 'missing helper is safe');
  h = harness(undefined, undefined, { bind() { throw Error('private'); },
    freeze() { throw Error('private'); }, facts() { throw Error('private'); } });
  h.enable(); h.prime(); await flush(); h.tick();
  assert.equal(h.state().geckoStartupResourceObservationStatus, 'UNSUPPORTED');
  assert.equal(h.state().geckoAudioClockStatus, 'AVAILABLE', 'helper failure cannot change media/query observer');
  h = harness(); h.enable(); h.tick(); h.clock(() => { throw Error('private clock'); }); h.tick();
  h.clock(() => h.now()); h.tick();
  assert.equal(h.state().geckoBridgeLastTickDurationMs, -1);
  assert.equal(h.state().geckoStartupObserverTickCount, -1, 'throwing clock also fails closed');
  h = harness(); h.enable(); h.scan(() => { throw Error('private'); }); h.tick();
  h.scan(() => []); h.tick();
  assert.equal(h.state().geckoStartupObserverTickCount, -1, 'interrupted startup task poisons cumulative group');
  for (const message of h.messages) assert(!JSON.stringify(message).includes('private'));
  // v21: successful recovery does not erase the first diagnostic failure.
  let firstCompletion;
  h = harness(() => h.calls() === 1 ? new Promise(resolve => { firstCompletion = resolve; }) : Promise.resolve(info(3000000)));
  h.enable(); h.prime(); h.at(2040); [...h.timers.values()][0]();
  h.at(2177); firstCompletion(info()); await flush(); h.frame(17177, 3); h.tick(); await flush(); h.tick();
  assert.equal(h.calls(), 2); assert.equal(h.state().geckoAudioClockStatus, 'AVAILABLE');
  assert.equal(h.state().geckoAudioQuerySeq, 2); assert.equal(h.state().geckoAudioFirstFaultSeq, 1);
  assert.equal(h.state().geckoAudioFirstFaultClockStatus, 'INSUFFICIENT_TIMEOUT');

  // v21: original timeout attribution survives a real late reaction and bounded recovery.
  let complete, apiResponses = [];
  h = harness(() => new Promise(resolve => { complete = resolve; apiResponses.push(resolve); }));
  h.enable(); h.prime(); h.at(2173); [...h.timers.values()][0]();
  h.at(2177); complete(info()); await flush(); h.tick();
  assert.equal(h.state().geckoAudioFirstFaultSeq, 1);
  assert.equal(h.state().geckoAudioFirstFaultReactionElapsedMs, 1137);
  assert.equal(h.state().geckoAudioFirstFaultTimeoutLateMs, 133);
  assert.equal(h.state().geckoAudioFirstFaultDisposition, 'TIMEOUT_FUSE');
  assert.equal(h.state().geckoAudioFirstFaultJsTickCount, 0);
  assert.equal(h.state().geckoAudioFirstFaultMaxJsTickGapMs, -1, 'no tick means unknown gap, not proof of DOM block');
  h.frame(17176, 3); h.tick(); assert.equal(h.calls(), 1, '15s backoff has not expired');
  h.frame(17177, 3.1); h.tick(); assert.equal(h.calls(), 2);
  assert.equal(h.state().geckoAudioRecoveryCount, 1);
  h.at(18177); [...h.timers.values()][0](); complete(info()); await flush();
  h.frame(48176, 4); h.tick(); assert.equal(h.calls(), 2, 'second recovery waits 30s');
  h.frame(48177, 4.1); h.tick(); assert.equal(h.calls(), 3);
  h.at(49177); [...h.timers.values()][0](); complete(info()); await flush();
  h.frame(100000); h.tick(); assert.equal(h.calls(), 3, 'document recovery budget remains exhausted');
  assert.equal(h.state().geckoAudioRecoveryCount, 2);
  assert.equal(h.state().geckoAudioRetryRemainingMs, -1);
  assert.equal(h.state().geckoAudioFirstFaultReactionElapsedMs, 1137, 'later faults cannot overwrite first cause');
  assert.equal(h.state().geckoAudioFirstFaultSeq, 1);
  h.replace({ ...h.v }); h.prime(101000); assert.equal(h.state().geckoAudioRecoveryCount, 2, 'node churn cannot reset document budget');
  assert.equal(h.intervalMs(), 1000); assert(h.timeoutDelays.every(ms => ms === 1000));

  // Timeout remains the original fault, but an eventual rejection never qualifies for recovery.
  let rejectLate;
  h = harness(() => new Promise((_, reject) => { rejectLate = reject; }));
  h.enable(); h.prime(); h.at(2040); [...h.timers.values()][0]();
  h.at(2177); rejectLate(); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryState, 'REJECTED');
  assert.equal(h.state().geckoAudioFirstFaultState, 'REJECTED');
  assert.equal(h.state().geckoAudioFirstFaultDisposition, 'TIMEOUT_FUSE');
  assert.equal(h.state().geckoAudioFirstFaultClockStatus, 'INSUFFICIENT_TIMEOUT');
  assert.equal(h.state().geckoAudioFirstFaultReactionElapsedMs, 1137);
  assert.equal(h.state().geckoAudioRetryRemainingMs, -1);
  assert.equal(h.state().geckoAudioRecoveryCount, 0);
  h.frame(17177, 3); h.tick(); h.frame(50000, 4); h.tick();
  assert.equal(h.calls(), 1, 'timeout then native REJECTED retains permanent diagnostic fuse');
  assert.equal(h.state().geckoAudioRecoveryCount, 0);

  // An already armed retry must not spend budget on nonfinite or regressed DOM time.
  for (const badNow of [NaN, Infinity, -Infinity, -1, 2100, Number.MAX_SAFE_INTEGER + 1]) {
    h = harness(() => new Promise(resolve => { complete = resolve; }));
    h.enable(); h.prime(); h.at(2040); [...h.timers.values()][0]();
    h.at(2177); complete(info()); await flush(); h.tick();
    assert.equal(h.state().geckoAudioRetryRemainingMs, 15000);
    h.frame(3000, 3); h.clock(() => badNow); h.tick();
    assert.equal(h.calls(), 1, `armed retry cannot use invalid current clock ${badNow}`);
    assert.equal(h.state().geckoAudioRecoveryCount, 0, 'invalid time does not consume document budget');
    assert.equal(h.state().geckoAudioRetryRemainingMs, -1, 'invalid time cannot report an expired deadline');
    assert.equal(h.state().geckoAudioFirstFaultSeq, 1);
    h.clock(() => h.now()); h.frame(17176, 3.1); h.tick(); assert.equal(h.calls(), 1);
    h.frame(17177, 3.2); h.tick(); assert.equal(h.calls(), 2, 'valid exact 15s boundary still launches');
    assert.equal(h.state().geckoAudioRecoveryCount, 1);
  }
  h = harness(() => new Promise(resolve => { complete = resolve; }));
  h.enable(); h.prime(); h.at(2040); [...h.timers.values()][0]();
  h.at(2177); complete(info()); await flush(); h.tick();
  h.at(20000); h.tick(); assert.equal(h.calls(), 1, 'stale frame delays recovery while recording valid current time');
  h.frame(19999, 3); h.tick();
  assert.equal(h.calls(), 1, 'finite time after deadline but before previous sample cannot recover');
  assert.equal(h.state().geckoAudioRecoveryCount, 0);
  assert.equal(h.state().geckoAudioRetryRemainingMs, -1);
  h.frame(20000, 3.1); h.tick(); assert.equal(h.calls(), 2, 'nonregressed valid time can recover');

  h = harness(() => new Promise(resolve => { complete = resolve; }));
  h.enable(); h.prime(); h.at(2040); [...h.timers.values()][0]();
  h.frame(50000); h.tick(); assert.equal(h.calls(), 1, 'unsettled native request never overlaps, even after backoff');
  h.handlers.get('emptied')(); h.prime(51000); complete(info()); await flush();
  h.frame(70000); h.tick(); assert.equal(h.calls(), 1, 'old epoch settlement cannot arm recovery');
  assert.equal(h.state().geckoAudioRecoveryCount, 0, 'old epoch does not consume budget');

  h = harness(() => { h.advance(101); return Promise.resolve(info()); });
  h.enable(); h.prime(); await flush(); h.frame(50000); h.tick();
  assert.equal(h.calls(), 1, 'synchronous slow call retains permanent protection');
  assert.equal(h.state().geckoAudioRetryRemainingMs, -1);
  h = harness(() => { h.advance(100.4); return Promise.resolve(info()); });
  h.enable(); h.prime(); await flush(); h.frame(50000, 3); h.tick();
  assert.equal(h.state().geckoAudioFirstFaultSyncCallMs, 100, 'wire milliseconds are truncated');
  assert.equal(h.calls(), 1, 'raw synchronous duration over 100ms cannot bypass protection through rounding');
  assert.equal(h.state().geckoAudioRetryRemainingMs, -1);
  h = harness(() => { h.clock(() => 0); return new Promise(resolve => { complete = resolve; }); });
  h.enable(); h.prime(); h.clock(() => h.now()); h.at(2040); [...h.timers.values()][0]();
  h.at(2177); complete(info()); await flush(); h.frame(50000, 3); h.tick();
  assert.equal(h.state().geckoAudioFirstFaultSyncCallMs, -1);
  assert.equal(h.calls(), 1, 'unknown synchronous duration cannot qualify as safe recovery');
  assert.equal(h.state().geckoAudioRetryRemainingMs, -1);
  h = harness(() => Promise.reject()); h.enable(); h.prime(); await flush(); h.frame(50000); h.tick();
  assert.equal(h.calls(), 1, 'rejected query is not automatically retried');

  h = harness(() => new Promise(resolve => { complete = resolve; }));
  h.enable(); h.prime(); h.at(1600); h.tick(); h.at(1800); complete(info()); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryJsTickCount, 1);
  assert.equal(h.state().geckoAudioQueryMaxJsTickGapMs, 560, 'call-to-tick and tick-to-first-reaction same DOM clock');
  h.at(1900); h.tick(); assert.equal(h.state().geckoAudioQueryJsTickCount, 1, 'first reaction freezes heartbeat');
  assert.equal(h.state().geckoAudioQueryMaxJsTickGapMs, 560);
  for (const m of h.messages) assert(!JSON.stringify(m).includes('private'));

  h = harness(() => new Promise(resolve => { complete = resolve; }));
  h.enable(); h.prime(); h.clock(() => NaN); h.tick(); h.clock(() => h.now());
  h.at(1800); complete(info()); await flush(); h.tick();
  assert.equal(h.state().geckoAudioQueryJsTickCount, -1, 'an invalid intermediate clock poisons completed heartbeat evidence');
  assert.equal(h.state().geckoAudioQueryMaxJsTickGapMs, -1);
  assert.equal(h.state().geckoAudioRetryRemainingMs, -1, 'invalid DOM clock cannot establish a recovery deadline');
  const debugSource = fs.readFileSync('app/src/debug/assets/kantv-av-production-probe/bridge.js', 'utf8');
  assert.equal(debugSource.replaceAll("sendNativeMessage('kantvAvProbe',", "sendNativeMessage('kantv',"), source,
    'debug bridge differs only in its original native message host');
  assert(!debugSource.includes("sendNativeMessage('kantv',"));
  assert(!source.includes('AudioContext')); assert(!source.includes('captureStream'));
  console.log('PASS: sparse clock calls, exact pair, lifecycle/fuse, query settlement/timer trace, event latches, same-task phases/tail/startup, query-window task coverage, privacy');
})().catch(e => { console.error(e); process.exitCode = 1; });
