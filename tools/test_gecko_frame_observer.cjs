// Passive observer behavior under a controlled DOM. No browser/media/network dependencies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/kantv-gecko-mirror/bridge.js', 'utf8');
let selected = null;
let now = 100;
let tick;
const messages = [];
const listeners = new Map();
const window = { devicePixelRatio: 1 };
window.top = window;
const context = {
  window,
  performance: { timeOrigin: 1800000000000, now: () => now },
  document: {
    addEventListener() {},
    querySelector: selector => selector.startsWith('video[') ? selected : null,
    querySelectorAll: () => [],
  },
  addEventListener: (name, callback) => listeners.set(name, callback),
  setInterval: callback => { tick = callback; },
  browser: { runtime: { sendNativeMessage: (_, message) => { messages.push(message); return Promise.resolve(); } } },
};
vm.runInNewContext(source, context);
function video(rvfc = true, quality = true) {
  let sequence = 0;
  const callbacks = new Map();
  const handlers = new Map();
  const cancelled = [];
  const v = {
    currentTime: 0, readyState: 0, videoWidth: 1280, videoHeight: 720, paused: true,
    buffered: { length: 0 },
    addEventListener: (name, callback) => handlers.set(name, callback),
    removeEventListener: name => handlers.delete(name),
    ...(quality ? { getVideoPlaybackQuality: () => ({ totalVideoFrames: 10, droppedVideoFrames: 2 }) } : {}),
    ...(rvfc ? {
      requestVideoFrameCallback: callback => { const id = ++sequence; callbacks.set(id, callback); return id; },
      cancelVideoFrameCallback: id => { cancelled.push(id); callbacks.delete(id); },
    } : {}),
  };
  return { v, callbacks, handlers, cancelled };
}
const state = () => messages.filter(m => m.kind === 'state').at(-1);
const first = video();
selected = first.v;
tick();
assert.equal(state().presentedFrames, -1);
assert.equal(state().videoCallbackAgeMs, -1);
assert.equal(state().videoTimingSource, 'GECKO_RVFC_PENDING');
assert.equal(state().actualPresentedAvailability, 'NO_CALLBACK_YET');
assert.equal(state().frames, 10, 'pending callback retains available quality fallback');
const oldCallback = [...first.callbacks.values()][0];
oldCallback(100, { presentedFrames: 0, mediaTime: 1.25, presentationTime: 99, expectedDisplayTime: 100 });
assert.equal(messages.filter(m => m.kind === 'state').length, 1, 'no per-frame native message');
now = 120;
tick();
assert.equal(state().presentedFrames, 0, 'metadata supplies a real zero');
assert.equal(state().videoPtsUs, 1250000);
assert.equal(state().videoCallbackAgeMs, 20);
assert.equal(state().videoPresentationAgeMs, 21);
assert.equal(state().videoExpectedDisplayDelayUs, 0);
assert.equal(state().actualPresentedAvailability, 'BROWSER_COMPOSITOR_ONLY');
const generation = state().nodeGeneration;
first.v.readyState = 4;
first.v.paused = false;
tick();
assert.equal(state().nodeGeneration, generation, 'ready state is not node identity');
first.handlers.get('emptied')();
tick();
assert.equal(state().presentedFrames, -1);
assert.equal(state().videoPtsUs, -1);
assert.equal(state().nodeGeneration, generation + 1);
oldCallback(120, { presentedFrames: 999, mediaTime: 99 });
tick();
assert.equal(state().presentedFrames, -1, 'late callback from prior resource is ignored');
first.handlers.get('loadedmetadata')();
tick();
assert.equal(state().nodeGeneration, generation + 2);
const second = video(false);
selected = second.v;
tick();
assert(first.cancelled.length >= 3);
assert.equal(first.handlers.size, 0);
assert.equal(state().videoTimingSource, 'GECKO_PLAYBACK_QUALITY');
assert.equal(state().presentedFrames, -1);
assert.equal(state().frames, 10);
selected = video(false, false).v;
tick();
assert.equal(state().videoTimingSource, 'GECKO_CURRENT_TIME');
assert.equal(state().frames, -1);
listeners.get('message')({ data: { __kantvLabelState: { found: true, clicked: false } } });
tick();
assert.equal(state().labelFound, true);
assert.equal(state().labelClicked, false);
selected = null;
tick();
assert.equal(state().labelFound, true);
assert.equal(state().labelClicked, false);
assert.equal(state().nodeCount, 0);
assert.equal(state().videoPtsUs, -1);
assert.equal(state().videoCallbackAgeMs, -1);
listeners.get('pagehide')();
for (const message of messages.filter(m => m.kind === 'state')) {
  assert(!('videoPlannedReleaseTimeNs' in message));
  assert(!('videoCallbackNanoTime' in message));
  assert(!JSON.stringify(message).includes('https://'));
}
for (const forbidden of ['AudioContext', 'createMediaElementSource', 'captureStream', 'mozPresentedFrames']) {
  assert(!source.includes(forbidden), forbidden);
}
assert(source.includes('if (window.top !== window) return;'));
console.log('PASS: callback cache, cleanup, resource/node identity, fallback, clock-domain and privacy');
