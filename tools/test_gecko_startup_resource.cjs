const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/kantv-gecko-mirror/startup-resource.js', 'utf8');
assert.equal(source, fs.readFileSync('app/src/debug/assets/kantv-av-production-probe/startup-resource.js', 'utf8'));
function harness(supported = true, entryTypes, enabled = true) {
  let now = 0, callback, disconnected = false, records = [], constructed = 0;
  const window = {}; window.top = window;
  const context = { window, performance: { now: () => now } };
  if (supported) context.PerformanceObserver = class {
    constructor(fn) { constructed++; callback = fn; }
    observe(options) { assert.deepEqual(JSON.parse(JSON.stringify(options)), { type: 'resource', buffered: true }); }
    takeRecords() { const result = records; records = []; return result; }
    disconnect() { disconnected = true; }
  };
  if (supported && entryTypes) context.PerformanceObserver.supportedEntryTypes = entryTypes;
  vm.runInNewContext(source, context);
  if (enabled) context.__kantvStartupResources.enable(true);
  return { api: context.__kantvStartupResources, at: n => { now = n; },
    entries: entries => callback({ getEntries: () => entries }),
    queued: entries => { records = entries; }, disconnected: () => disconnected, constructed: () => constructed };
}
const entry = (start = 10, elapsed = 100, detailed = true, initiatorType = 'fetch') => ({
  initiatorType,
  startTime: start, duration: elapsed, requestStart: detailed ? start + 1 : 0,
  responseStart: detailed ? start + 21 : 0, responseEnd: detailed ? start + elapsed : 0,
  get name() { throw Error('URL must never be read'); },
  get transferSize() { throw Error('byte counters are outside this contract'); },
  get encodedBodySize() { throw Error('byte counters are outside this contract'); },
  get decodedBodySize() { throw Error('byte counters are outside this contract'); },
});
const unknownFields = f => {
  assert.equal(Object.keys(f).length, 11);
  for (const [key, value] of Object.entries(f)) {
    if (key === 'geckoStartupResourceObservationStatus') continue;
    assert.equal(value, key === 'geckoStartupResourceMaxInitiatorCode' ? 'NOT_APPLICABLE' : -1, key);
  }
};
const knownFields = f => {
  assert.equal(Object.keys(f).length, 11);
  assert.equal(f.geckoStartupResourceScannedCount, f.geckoStartupResourceCount + f.geckoStartupResourceFilteredCount);
  assert(f.geckoStartupResourceScannedCount >= 0 && f.geckoStartupResourceScannedCount <= 1024);
  assert(f.geckoStartupResourceCount >= 0 && f.geckoStartupResourceCount <= 128);
  assert(f.geckoStartupResourceDetailedCount <= f.geckoStartupResourceCount);
  if (f.geckoStartupResourceCount === 0) {
    assert.equal(f.geckoStartupResourceDetailedCount, 0);
    assert.equal(f.geckoStartupResourceMaxDurationMs, -1);
    assert.equal(f.geckoStartupResourceMaxResponseWaitMs, -1);
    assert.equal(f.geckoStartupResourceMaxDownloadMs, -1);
    assert.equal(f.geckoStartupResourceMaxStartMs, -1);
    assert.equal(f.geckoStartupResourceMaxEndMs, -1);
    assert.equal(f.geckoStartupResourceMaxInitiatorCode, 'NONE');
  } else {
    assert(f.geckoStartupResourceMaxStartMs >= 0);
    assert(f.geckoStartupResourceMaxEndMs >= f.geckoStartupResourceMaxStartMs);
    assert(Math.abs(f.geckoStartupResourceMaxEndMs - f.geckoStartupResourceMaxStartMs - f.geckoStartupResourceMaxDurationMs) <= 1);
    assert(['FETCH', 'XMLHTTPREQUEST', 'VIDEO', 'AUDIO'].includes(f.geckoStartupResourceMaxInitiatorCode));
  }
};
let disabled = harness(true, undefined, false);
disabled.api.bind(1);
assert.equal(disabled.constructed(), 0, "disabled must not construct any PerformanceObserver");
assert.equal(disabled.api.facts(true, 1).geckoStartupResourceObservationStatus, 'NOT_APPLICABLE');
disabled.api.enable(false);
assert.equal(disabled.api.facts(true, 1).geckoStartupResourceObservationStatus, 'NOT_APPLICABLE');
let h = harness(); h.api.bind(1); h.entries([entry(), entry(200, 90, false)]);
let f = h.api.facts(true, 1);
assert.equal(f.geckoStartupResourceObservationStatus, 'OBSERVING');
assert.equal(f.geckoStartupResourceCount, 2);
assert.equal(f.geckoStartupResourceDetailedCount, 1);
assert.equal(f.geckoStartupResourceMaxResponseWaitMs, 20);
assert.equal(f.geckoStartupResourceMaxDownloadMs, 79);
knownFields(f);
h.queued([entry(500, 500)]); h.api.freeze(1, 400);
assert.equal(h.api.facts(true, 1).geckoStartupResourceCount, 2, 'post-first-frame queued completion excluded');
assert.equal(h.api.facts(true, 1).geckoStartupResourceFilteredCount, 1);
knownFields(h.api.facts(true, 1));
assert.equal(h.api.facts(true, 1).geckoStartupResourceObservationStatus, 'COMPLETE');
assert(h.disconnected());
h.api.bind(2); assert.equal(h.api.facts(true, 2).geckoStartupResourceObservationStatus, 'INVALIDATED');
assert.equal(h.api.facts(true, 2).geckoStartupResourceCount, -1);
unknownFields(h.api.facts(true, 2));
assert.equal(h.api.facts(false, 2).geckoStartupResourceObservationStatus, 'NOT_APPLICABLE');
unknownFields(h.api.facts(false, 2));
h = harness(); h.api.bind(1); h.entries([entry(10, 100, false)]); h.api.freeze(1, 500);
assert.equal(h.api.facts(true, 1).geckoStartupResourceMaxResponseWaitMs, -1, 'restricted detail is unknown');
h = harness(false); h.api.bind(1); assert.equal(h.api.facts(true, 1).geckoStartupResourceObservationStatus, 'UNSUPPORTED');
unknownFields(h.api.facts(true, 1));
h = harness(); h.api.bind(1); h.at(30001);
assert.equal(h.api.facts(true, 1).geckoStartupResourceObservationStatus, 'EMPTY'); assert(h.disconnected());
h = harness(); h.api.bind(1); h.entries([entry()]); h.at(30001);
assert.equal(h.api.facts(true, 1).geckoStartupResourceObservationStatus, 'BOUNDED');
h = harness(); h.api.bind(1); h.entries(Array.from({ length: 200 }, () => entry()));
assert.equal(h.api.facts(true, 1).geckoStartupResourceObservationStatus, 'OVERFLOW');
assert.equal(h.api.facts(true, 1).geckoStartupResourceCount, 128); assert(h.disconnected());
h = harness(); h.api.bind(1); h.entries(Array.from({ length: 1100 }, () => entry(NaN)));
assert.equal(h.api.facts(true, 1).geckoStartupResourceObservationStatus, 'OVERFLOW', 'invalid entries also consume the bounded work budget');
assert.equal(h.api.facts(true, 1).geckoStartupResourceCount, 0);
assert.equal(h.api.facts(true, 1).geckoStartupResourceScannedCount, 1024);
assert.equal(h.api.facts(true, 1).geckoStartupResourceFilteredCount, 1024);
knownFields(h.api.facts(true, 1));
h = harness(); h.api.bind({}); h.at(40000);
assert.equal(h.api.facts(false, {}).geckoStartupResourceObservationStatus, 'NOT_APPLICABLE'); assert(h.disconnected());
h = harness(); const scope = {}; h.api.bind(scope); h.at(40000);
assert.equal(h.api.facts(true, {}).geckoStartupResourceObservationStatus, 'INVALIDATED'); assert(h.disconnected());
h = harness(); const deadlineScope = {}; h.api.bind(deadlineScope); h.at(40000); h.entries([entry(10, 100), entry(31000, 100)]);
assert.equal(h.api.facts(true, deadlineScope).geckoStartupResourceObservationStatus, 'BOUNDED');
assert.equal(h.api.facts(true, deadlineScope).geckoStartupResourceCount, 1); assert(h.disconnected());
assert.equal(h.api.facts(true, deadlineScope).geckoStartupResourceFilteredCount, 1);
h = harness(); const lateFrameScope = {}; h.api.bind(lateFrameScope);
h.queued([entry(31000, 100)]); h.at(35000); h.api.freeze(lateFrameScope, 35000);
assert.equal(h.api.facts(true, lateFrameScope).geckoStartupResourceCount, 0);
assert.equal(h.api.facts(true, lateFrameScope).geckoStartupResourceObservationStatus, 'EMPTY');
assert.equal(h.api.facts(true, lateFrameScope).geckoStartupResourceFilteredCount, 1);
knownFields(h.api.facts(true, lateFrameScope));
assert(h.disconnected());
h = harness(true, ['mark']); const unsupportedScope = {}; h.api.bind(unsupportedScope);
assert.equal(h.api.facts(true, unsupportedScope).geckoStartupResourceObservationStatus, 'UNSUPPORTED');
assert.equal(h.api.facts(true, unsupportedScope).geckoStartupResourceCount, -1);
unknownFields(h.api.facts(true, unsupportedScope));

// A page's static resources cannot exhaust the candidate budget.
h = harness(); h.api.bind(1);
h.entries([...Array.from({ length: 500 }, () => entry(10, 9000, true, 'img')),
  entry(200, 200, true, 'fetch'), entry(500, 1181, true, 'xmlhttprequest'),
  entry(1700, 40, true, 'video'), entry(1800, 20, true, 'audio')]);
f = h.api.facts(true, 1);
assert.equal(f.geckoStartupResourceObservationStatus, 'OBSERVING');
assert.equal(f.geckoStartupResourceCount, 4);
assert.equal(f.geckoStartupResourceFilteredCount, 500);
assert.equal(f.geckoStartupResourceScannedCount, 504);
assert.equal(f.geckoStartupResourceMaxDurationMs, 1181);
assert.equal(f.geckoStartupResourceMaxStartMs, 500);
assert.equal(f.geckoStartupResourceMaxEndMs, 1681);
assert.equal(f.geckoStartupResourceMaxInitiatorCode, 'XMLHTTPREQUEST');
knownFields(f); h.api.freeze(1, 3000);
assert.equal(h.api.facts(true, 1).geckoStartupResourceObservationStatus, 'COMPLETE');

// Both exact limits stop immediately, without looking at the next entry.
const unread = { get initiatorType() { throw Error('entry beyond budget must not be inspected'); } };
for (const entries of [Array.from({ length: 128 }, () => entry()),
  Array.from({ length: 1024 }, (_, i) => i % 2 ? entry(Symbol(), 10, false) : entry(10, 100, true, 'script'))]) {
  h = harness(); h.api.bind(1); h.queued([unread]); h.entries([...entries, unread]);
  f = h.api.facts(true, 1);
  assert.equal(f.geckoStartupResourceObservationStatus, 'OVERFLOW');
  assert(h.disconnected()); knownFields(f);
  assert.equal(f.geckoStartupResourceScannedCount, entries.length);
  assert.equal(f.geckoStartupResourceCount, entries.length === 128 ? 128 : 0);
  h.entries([unread]);
  assert.deepEqual(h.api.facts(true, 1), f, 'stopped callbacks cannot alter the frozen result');
}

// Invalid timing and all unrecognized types are counted, with no free text exposed.
h = harness(); h.api.bind(1);
const privateType = 'private-resource-type-never-serialize';
const rejected = ['img', 'css', 'script', 'link', 'other', privateType, undefined, null, {}].map(type => ({
  initiatorType: type,
  get startTime() { throw Error('filtered types must not read timing'); },
  get name() { throw Error('URL must never be read'); },
}));
h.entries([...rejected, null, entry(NaN), entry(-1), entry(86400000, 1),
  entry(10, Infinity), entry(10, -1), entry(31000, 10)]);
h.api.freeze(1, 1000); f = h.api.facts(true, 1);
assert.equal(f.geckoStartupResourceObservationStatus, 'EMPTY');
assert.equal(f.geckoStartupResourceFilteredCount, 16);
assert(!JSON.stringify(f).includes(privateType)); knownFields(f);

// Raw duration determines the selected entry; exact raw ties keep the first.
h = harness(); h.api.bind(1);
h.entries([entry(10.2, 100.1, false, 'video'), entry(200.9, 100.9, false, 'audio'),
  entry(400.2, 100.9, false, 'fetch')]);
h.api.freeze(1, 1000); f = h.api.facts(true, 1);
assert.equal(f.geckoStartupResourceMaxDurationMs, 100);
assert.equal(f.geckoStartupResourceMaxStartMs, 200);
assert.equal(f.geckoStartupResourceMaxEndMs, 301);
assert.equal(f.geckoStartupResourceMaxInitiatorCode, 'AUDIO');
assert.equal(f.geckoStartupResourceMaxResponseWaitMs, -1);
assert.equal(f.geckoStartupResourceMaxDownloadMs, -1); knownFields(f);
h = harness(); h.api.bind(1); h.entries([entry(0, 0, false, 'video')]);
h.api.freeze(1, 1000); f = h.api.facts(true, 1);
assert.equal(f.geckoStartupResourceMaxStartMs, 0);
assert.equal(f.geckoStartupResourceMaxEndMs, 0);
assert.equal(f.geckoStartupResourceMaxDurationMs, 0); knownFields(f);

// Null binding before a video must not create a scope; initial metadata retains it.
h = harness(); h.api.bind(null); const startupScope = {}; h.api.bind(startupScope);
h.entries([entry()]); h.api.bind(startupScope); h.api.freeze(startupScope, 500);
assert.equal(h.api.facts(true, startupScope).geckoStartupResourceObservationStatus, 'COMPLETE');
assert.equal(h.api.facts(true, startupScope).geckoStartupResourceCount, 1);
assert.equal(h.api.facts(true, null).geckoStartupResourceObservationStatus, 'INVALIDATED');
h.api.bind({}); unknownFields(h.api.facts(true, {}));
console.log('PASS: candidate filtering, scan/accept limits, raw timing selection, privacy, restricted timing, lifecycle and deadline');
