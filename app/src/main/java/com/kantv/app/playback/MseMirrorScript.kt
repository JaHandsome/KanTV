package com.kantv.app.playback

/**
 * 注入隐藏 WebView 的 MSE 镜像钩子脚本（幂等安装）。
 *
 * 行为：
 * - 钩 [MediaSource.addSourceBuffer] 记录每个 SourceBuffer 的 MIME（区分音/视频轨）；
 * - 钩 [SourceBuffer.appendBuffer]：ftyp 开头 = init 段，其余 = fragment，
 *   以 base64 入队（window.__kantvMirror.q）；
 * - **Worker 域覆盖**（2026-09-13 实测新增）：UA 升到现代 Chrome 后，CCTV 官网页把
 *   MediaSource 搬进 DedicatedWorker（解密 WASM 与 appendBuffer 全在 worker 域，
 *   主域 hook 下视频推进 8 分钟 append 计数为 0 实锤）。因此包装 Worker 构造器：
 *   对 blob worker 同步读源、前置注入 worker 域钩子（钩 MediaSource/
 *   ManagedMediaSource），worker 内 append 经 postMessage 桥回主域队列；跨域
 *   https worker（无法读源）优雅降级为不注入。
 * - 静音页面 video 元素（镜像模式下音频由原生播放器输出，避免双声）；
 * - 暴露 [drainScript] 供 Kotlin 轮询取走队列。
 *
 * 隐私纪律：不读取/不上报页面 URL 与请求头，只镜像已解密的媒体字节。
 */
object MseMirrorScript {

    fun installScript(): String = """
        (function () {
            if (window.__kantvMirror) return 'already';
            var queue = [];
            window.__kantvMirror = {
                q: queue,
                videoState: { readyState: -1, paused: true, currentTime: 0 }
            };
            function toB64(u8) {
                var s = '';
                for (var i = 0; i < u8.length; i += 3) {
                    s += String.fromCharCode(u8[i], u8[i + 1] || 0, u8[i + 2] || 0);
                }
                return btoa(s);
            }
            function trackOf(mime) {
                return mime && mime.indexOf('audio') >= 0 ? 'A' : 'V';
            }
            function isInit(u8) {
                return u8.length > 12 && u8[4] === 102 && u8[5] === 116 && u8[6] === 121 && u8[7] === 112;
            }
            function push(item) {
                queue.push(item);
                while (queue.length > 80) queue.shift();
            }
            // 2026-09-13 实测：页面切线时新旧两个 MediaSource 实例交错 append
            // （init track_id=3 与 fragment track_id=20 交叉，ffprobe 实锤），
            // 混流会让解码器拿到不配对的 init/fragment。策略：init 一到即切换
            // 锁定的实例；非当前实例的 fragment 丢弃。
            function pushTracked(item) {
                if (item.i === 1) {
                    window.__kantvMirror.activeMs = item.m;
                } else if (!window.__kantvMirror.activeMs) {
                    window.__kantvMirror.activeMs = item.m;
                } else if (item.m !== window.__kantvMirror.activeMs) {
                    return;
                }
                push(item);
            }
            var msCounter = 0;
            function makeAppendWrapper(mimeHolder, msId) {
                var origAppend = mimeHolder.appendBuffer;
                mimeHolder.appendBuffer = function (data) {
                    try {
                        var u8 = data instanceof ArrayBuffer ? new Uint8Array(data)
                            : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                        if (u8.length > 16) {
                            pushTracked({
                                m: msId,
                                t: trackOf(mimeHolder.__kantvMime),
                                i: isInit(u8) ? 1 : 0,
                                s: u8.length,
                                d: toB64(u8)
                            });
                        }
                    } catch (e) { }
                    return origAppend.apply(this, arguments);
                };
            }
            var origAdd = MediaSource.prototype.addSourceBuffer;
            MediaSource.prototype.addSourceBuffer = function (mime) {
                if (!this.__kantvMsId) this.__kantvMsId = 'm' + (++msCounter);
                var sb = origAdd.call(this, mime);
                try { sb.__kantvMime = mime; } catch (e) { }
                makeAppendWrapper(sb, this.__kantvMsId);
                return sb;
            };
            if (window.ManagedMediaSource && !window.ManagedMediaSource.prototype.__kantvHooked) {
                var mmsProto = window.ManagedMediaSource.prototype;
                mmsProto.__kantvHooked = 1;
                var origMmsAdd = mmsProto.addSourceBuffer;
                if (origMmsAdd) {
                    mmsProto.addSourceBuffer = function (mime) {
                        if (!this.__kantvMsId) this.__kantvMsId = 'm' + (++msCounter);
                        var sb = origMmsAdd.call(this, mime);
                        try { sb.__kantvMime = mime; } catch (e) { }
                        makeAppendWrapper(sb, this.__kantvMsId);
                        return sb;
                    };
                }
            }
            // ---- worker 域钩子（同一函数源码既在主域执行，也注入 worker 预执行）----
            var workerHookFn = function () {
                if (self.__kantvWorkerHook) return;
                self.__kantvWorkerHook = 1;
                function wToB64(u8) {
                    var s = '';
                    for (var i = 0; i < u8.length; i += 3) {
                        s += String.fromCharCode(u8[i], u8[i + 1] || 0, u8[i + 2] || 0);
                    }
                    return btoa(s);
                }
                function wTrackOf(mime) {
                    return mime && mime.indexOf('audio') >= 0 ? 'A' : 'V';
                }
                function wIsInit(u8) {
                    return u8.length > 12 && u8[4] === 102 && u8[5] === 116 && u8[6] === 121 && u8[7] === 112;
                }
                function wPost(item) {
                    try { self.postMessage({ __kantvMirrorItem: item }); } catch (e) { }
                }
                function hookProto(proto) {
                    if (!proto || proto.__kantvHooked) return;
                    var origAdd = proto.addSourceBuffer;
                    if (!origAdd) return;
                    proto.__kantvHooked = 1;
                    var wMsCounter = 0;
                    proto.addSourceBuffer = function (mime) {
                        if (!this.__kantvMsId) this.__kantvMsId = 'w' + (++wMsCounter);
                        var sb = origAdd.call(this, mime);
                        try { sb.__kantvMime = mime; } catch (e) { }
                        var msId = this.__kantvMsId;
                        var origAppend = sb.appendBuffer;
                        sb.appendBuffer = function (data) {
                            try {
                                var u8 = data instanceof ArrayBuffer ? new Uint8Array(data)
                                    : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                                if (u8.length > 16) {
                                    wPost({
                                        m: msId,
                                        t: wTrackOf(sb.__kantvMime),
                                        i: wIsInit(u8) ? 1 : 0,
                                        s: u8.length,
                                        d: wToB64(u8)
                                    });
                                }
                            } catch (e) { }
                            return origAppend.apply(this, arguments);
                        };
                        return sb;
                    };
                }
                if (self.MediaSource) hookProto(self.MediaSource.prototype);
                if (self.ManagedMediaSource) hookProto(self.ManagedMediaSource.prototype);
            };
            var WORKER_PRELUDE = '(' + workerHookFn.toString() + ')();';
            function workerPreludeUrl() {
                try {
                    return URL.createObjectURL(new Blob([WORKER_PRELUDE], { type: 'application/javascript' }));
                } catch (e) { return null; }
            }
            var origWorker = window.Worker;
            if (origWorker && !origWorker.__kantvWrapped) {
                var WrappedWorker = function (url, opts) {
                    var instance;
                    try {
                        var srcUrl = (typeof url === 'string') ? url : (url && url.toString ? url.toString() : '');
                        if (srcUrl.indexOf('blob:') === 0) {
                            var hooked = null;
                            try {
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', srcUrl, false);
                                xhr.send(null);
                                if (xhr.status === 200 || xhr.status === 0) {
                                    hooked = URL.createObjectURL(new Blob(
                                        [WORKER_PRELUDE, '\n', xhr.responseText],
                                        { type: 'application/javascript' }));
                                }
                            } catch (e) { }
                            instance = hooked ? new origWorker(hooked, opts) : new origWorker(url, opts);
                        } else {
                            instance = new origWorker(url, opts);
                        }
                    } catch (e) {
                        instance = new origWorker(url, opts);
                    }
                    try {
                        instance.addEventListener('message', function (ev) {
                            var d = ev && ev.data;
                            // 必须走 pushTracked：worker 域的 init/fragment 同样要过
                            // 实例过滤（否则切线时新旧实例交叉污染，init/frag 不配对）。
                            if (d && d.__kantvMirrorItem) pushTracked(d.__kantvMirrorItem);
                        });
                    } catch (e) { }
                    return instance;
                };
                WrappedWorker.__kantvWrapped = 1;
                try { Object.setPrototypeOf(WrappedWorker, origWorker); } catch (e) { }
                window.Worker = WrappedWorker;
            }
            function probeVideo() {
                try {
                    var v = document.querySelector('video');
                    if (v) {
                        if (!v.muted) v.muted = true;
                        window.__kantvMirror.videoState = {
                            readyState: v.readyState,
                            paused: v.paused,
                            currentTime: v.currentTime
                        };
                    }
                } catch (e) { }
            }
            setInterval(probeVideo, 1000);
            probeVideo();
            return 'installed';
        })();
    """.trimIndent()

    /** 取走队列：返回 JSON 数组字符串（已清空队列）。 */
    fun drainScript(): String = """
        (function () {
            var m = window.__kantvMirror;
            if (!m || !m.q.length) return '[]';
            var out = JSON.stringify(m.q);
            m.q.length = 0;
            return out;
        })();
    """.trimIndent()

    /**
     * Gecko 宿主的镜像安装脚本（2026-09-13）。与 [installScript] 的差异：
     * - GeckoSession 没有 evaluateJavascript，脚本经 `loadUri("javascript:")` 注入
     *   主域——因此 **不返回值**（非 void 返回值会替换整个文档，页面会被杀掉）；
     * - 数据不进队列由 Kotlin drain，而是攒批（200ms / 512KB）后 postMessage 给
     *   同页的 WebExtension content script（assets/kantv-gecko-mirror/bridge.js），
     *   后者通过原生消息转发行协议；[endpoint] 的 /offer 形状仅作桥接标记。
     *   行协议：`V|I|<size>|<base64>`（V/A=轨道，I/F=init/fragment）。
     * - 页面状态探测与 video.play()/静音维持移入 content script（DOM 层共享）。
     * MSE/Worker 钩子与实例锁逻辑与 WebView 版完全一致（实证过的坑原样保留）。
     */
    fun geckoInstallScript(endpoint: String): String = """
        (function () {
            if (window.__kantvMirror) return;
            var ENDPOINT = ${'"'}$endpoint${'"'};
            var batch = [];
            var batchBytes = 0;
            var post = null;
            try { post = window.postMessage.bind(window); } catch (e) { }
            function flush() {
                if (!batch.length || !post) return;
                var payload = batch.join('\n');
                batch = [];
                batchBytes = 0;
                try {
                    post({ __kantvMirrorBatch: payload, ep: ENDPOINT }, window.location.origin);
                } catch (e) { }
            }
            function toB64(u8) {
                var s = '';
                for (var i = 0; i < u8.length; i += 3) {
                    s += String.fromCharCode(u8[i], u8[i + 1] || 0, u8[i + 2] || 0);
                }
                return btoa(s);
            }
            function trackOf(mime) {
                return mime && mime.indexOf('audio') >= 0 ? 'A' : 'V';
            }
            function isInit(u8) {
                return u8.length > 12 && u8[4] === 102 && u8[5] === 116 && u8[6] === 121 && u8[7] === 112;
            }
            function pushLine(line) {
                batch.push(line);
                batchBytes += line.length;
                if (batchBytes >= 524288) flush();
            }
            // 起播关键路径不等 200ms 攒批:前两条(通常是 init + 首分片)到达即 flush。
            var immediateFlushesLeft = 2;
            function emit(msId, track, isInitFlag, u8) {
                pushLine(track + '|' + (isInitFlag ? 'I' : 'F') + '|' + u8.length + '|' + toB64(u8));
                if (immediateFlushesLeft > 0 && batch.length) {
                    immediateFlushesLeft--;
                    flush();
                }
            }
            // 实例锁：页面切线时新旧 MediaSource 交错 append，init 一到即锁定
            // 实例，非当前实例的 fragment 丢弃。Worker 的本地 MediaSource 编号会
            // 从 w1 重新开始，因此 workerId + msId 才是页面级唯一键。
            var activeMs = null;
            var activeWorkerId = null;
            var workerSwitches = 0;
            var instanceSwitches = 0;
            function reportMirrorMetrics() {
                if (!post) return;
                try {
                    post({ __kantvMirrorMetrics: {
                        workerSwitches: workerSwitches,
                        instanceSwitches: instanceSwitches
                    }, ep: ENDPOINT }, window.location.origin);
                } catch (e) { }
            }
            function activateInstance(instanceKey, workerId) {
                if (activeMs !== instanceKey) {
                    if (activeMs !== null) {
                        instanceSwitches++;
                        if (workerId !== null && workerId !== activeWorkerId) workerSwitches++;
                        reportMirrorMetrics();
                    }
                    activeMs = instanceKey;
                    activeWorkerId = workerId;
                }
            }
            function emitTracked(msId, track, isInitFlag, u8) {
                if (isInitFlag) {
                    activateInstance(msId, null);
                } else if (activeMs === null) {
                    activateInstance(msId, null);
                } else if (msId !== activeMs) {
                    return;
                }
                emit(msId, track, isInitFlag, u8);
            }
            var msCounter = 0;
            function makeAppendWrapper(mimeHolder, msId) {
                var origAppend = mimeHolder.appendBuffer;
                mimeHolder.appendBuffer = function (data) {
                    try {
                        var u8 = data instanceof ArrayBuffer ? new Uint8Array(data)
                            : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                        if (u8.length > 16) {
                            emitTracked(msId, trackOf(mimeHolder.__kantvMime), isInit(u8), u8);
                        }
                    } catch (e) { }
                    return origAppend.apply(this, arguments);
                };
            }
            var origAdd = MediaSource.prototype.addSourceBuffer;
            MediaSource.prototype.addSourceBuffer = function (mime) {
                if (!this.__kantvMsId) this.__kantvMsId = 'm' + (++msCounter);
                var sb = origAdd.call(this, mime);
                try { sb.__kantvMime = mime; } catch (e) { }
                makeAppendWrapper(sb, this.__kantvMsId);
                return sb;
            };
            if (window.ManagedMediaSource && !window.ManagedMediaSource.prototype.__kantvHooked) {
                var mmsProto = window.ManagedMediaSource.prototype;
                mmsProto.__kantvHooked = 1;
                var origMmsAdd = mmsProto.addSourceBuffer;
                if (origMmsAdd) {
                    mmsProto.addSourceBuffer = function (mime) {
                        if (!this.__kantvMsId) this.__kantvMsId = 'm' + (++msCounter);
                        var sb = origMmsAdd.call(this, mime);
                        try { sb.__kantvMime = mime; } catch (e) { }
                        makeAppendWrapper(sb, this.__kantvMsId);
                        return sb;
                    };
                }
            }
            // ---- worker 域钩子（CCTV 官网页把 MediaSource 搬进 DedicatedWorker）----
            // worker 内 append 编码为行协议对象 postMessage 回主域，主域走同一实例锁。
            var workerHookFn = function () {
                if (self.__kantvWorkerHook) return;
                self.__kantvWorkerHook = 1;
                function wToB64(u8) {
                    var s = '';
                    for (var i = 0; i < u8.length; i += 3) {
                        s += String.fromCharCode(u8[i], u8[i + 1] || 0, u8[i + 2] || 0);
                    }
                    return btoa(s);
                }
                function wTrackOf(mime) {
                    return mime && mime.indexOf('audio') >= 0 ? 'A' : 'V';
                }
                function wIsInit(u8) {
                    return u8.length > 12 && u8[4] === 102 && u8[5] === 116 && u8[6] === 121 && u8[7] === 112;
                }
                function wPost(line) {
                    try { self.postMessage({ __kantvMirrorLine: line }); } catch (e) { }
                }
                function hookProto(proto) {
                    if (!proto || proto.__kantvHooked) return;
                    var origAdd = proto.addSourceBuffer;
                    if (!origAdd) return;
                    proto.__kantvHooked = 1;
                    var wMsCounter = 0;
                    proto.addSourceBuffer = function (mime) {
                        if (!this.__kantvMsId) this.__kantvMsId = 'w' + (++wMsCounter);
                        var sb = origAdd.call(this, mime);
                        try { sb.__kantvMime = mime; } catch (e) { }
                        var msId = this.__kantvMsId;
                        var origAppend = sb.appendBuffer;
                        sb.appendBuffer = function (data) {
                            try {
                                var u8 = data instanceof ArrayBuffer ? new Uint8Array(data)
                                    : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                                if (u8.length > 16) {
                                    wPost(wTrackOf(sb.__kantvMime) + '|' + (wIsInit(u8) ? 'I' : 'F') +
                                        '|' + u8.length + '|' + wToB64(u8) + '|' + msId);
                                }
                            } catch (e) { }
                            return origAppend.apply(this, arguments);
                        };
                        return sb;
                    };
                }
                if (self.MediaSource) hookProto(self.MediaSource.prototype);
                if (self.ManagedMediaSource) hookProto(self.ManagedMediaSource.prototype);
            };
            function workerPrelude(workerId) {
                var prelude = 'self.__kantvWorkerId=' + JSON.stringify(workerId) + ';(' + workerHookFn.toString() + ')();';
                return prelude;
            }
            var origWorker = window.Worker;
            if (origWorker && !origWorker.__kantvWrapped) {
                var workerCounter = 0;
                var WrappedWorker = function (url, opts) {
                    var instance;
                    // 每个 WrappedWorker 获得页面级唯一编号；不要把 worker 域局部 w1
                    // 当成主页面唯一 MediaSource ID。
                    var workerId = 'worker-' + (++workerCounter);
                    try {
                        var srcUrl = (typeof url === 'string') ? url : (url && url.toString ? url.toString() : '');
                        if (srcUrl.indexOf('blob:') === 0) {
                            var hooked = null;
                            try {
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', srcUrl, false);
                                xhr.send(null);
                                if (xhr.status === 200 || xhr.status === 0) {
                                    hooked = URL.createObjectURL(new Blob(
                                        [workerPrelude(workerId), '\n', xhr.responseText],
                                        { type: 'application/javascript' }));
                                }
                            } catch (e) { }
                            instance = hooked ? new origWorker(hooked, opts) : new origWorker(url, opts);
                        } else {
                            instance = new origWorker(url, opts);
                        }
                    } catch (e) {
                        instance = new origWorker(url, opts);
                    }
                    try {
                        instance.addEventListener('message', function (ev) {
                            var d = ev && ev.data;
                            if (d && d.__kantvMirrorLine) {
                                // 行格式：track|I/F|size|b64|本地msId。worker 域已完成
                                // b64 编码；锁键必须拼入页面级 workerId。
                                var parts = d.__kantvMirrorLine.split('|');
                                if (parts.length === 5) {
                                    var instanceKey = workerId + ':' + parts[4];
                                    var line = parts[0] + '|' + parts[1] + '|' + parts[2] + '|' + parts[3];
                                    if (parts[1] === 'I') {
                                        activateInstance(instanceKey, workerId);
                                        pushLine(line);
                                    } else if (activeMs === null) {
                                        activateInstance(instanceKey, workerId);
                                        pushLine(line);
                                    } else if (instanceKey === activeMs) {
                                        pushLine(line);
                                    }
                                }
                            }
                        });
                    } catch (e) { }
                    return instance;
                };
                WrappedWorker.__kantvWrapped = 1;
                try { Object.setPrototypeOf(WrappedWorker, origWorker); } catch (e) { }
                window.Worker = WrappedWorker;
            }
            setInterval(flush, 200);
        })();
    """.trimIndent()
}
