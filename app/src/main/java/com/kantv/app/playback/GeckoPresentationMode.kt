package com.kantv.app.playback

import org.json.JSONObject

/**
 * Gecko 官网页的视频呈现实验。正常构建固定先请求标准网页全屏，失败再用 CSS 铺满；
 * 只有 diagnostic 构建可以通过固定枚举 extra 做 A/B。
 */
enum class GeckoPresentationMode(
    val attemptsAutomaticFullscreen: Boolean,
    val appliesCssImmediately: Boolean,
    val fallsBackToCss: Boolean,
) {
    AUTO_FULLSCREEN_ONLY(true, false, false),
    CSS_FIT_ONLY(false, true, false),
    AUTO_THEN_FIT(true, false, true),
    ;

    companion object {
        const val EXTRA = "kantvGeckoPresentationMode"

        /** 未登录阶段：不要 CSS 铺满，否则登录二维码会被裁到画面外。 */
        const val EXTRA_LOGIN_PENDING = "kantvLoginPending"

        fun resolve(
            diagnosticsEnabled: Boolean,
            requested: String?,
            loginPending: Boolean = false,
        ): GeckoPresentationMode {
            // 未登录时**不能**用 CSS 铺满：网页全屏在 GeckoView 上必然被拒，于是每次都退到
            // CSS 铺满，页面被放大裁切——而站点的登录二维码恰好落在被裁掉的那部分，
            // 用户根本扫不到（实测：二维码元素已加载、位置在布局视口内，却不在屏幕上）。
            // 所以登录阶段只试全屏：失败就按页面原样显示；登录完成后恢复既定策略。
            if (loginPending) return AUTO_FULLSCREEN_ONLY
            if (!diagnosticsEnabled) return AUTO_THEN_FIT
            return entries.firstOrNull { it.name == requested } ?: AUTO_THEN_FIT
        }
    }
}

internal enum class GeckoBackAction {
    EXIT_WEB_FULLSCREEN,
    OPEN_MENU,
}

internal object GeckoBackPolicy {
    fun action(webFullscreenActive: Boolean): GeckoBackAction =
        if (webFullscreenActive) GeckoBackAction.EXIT_WEB_FULLSCREEN else GeckoBackAction.OPEN_MENU
}

/**
 * Produces a provider-selector-scoped script; emitted telemetry contains fixed fields only.
 *
 * [suppressNativePlayerChrome] 只由荔枝网（GDTV）打开：该页面同时会渲染 video
 * 原生 controls 和 Prism/Video.js 控制层（LIVE/音量/全屏），并且被遥控器
 * 聚焦的网页元素会保留黄色 `:focus` 框。两者都不是应用自己的遥控菜单，正常播放
 * 时应在页面层隐藏；默认 false 时脚本不创建样式、不改动 video、也不注册维持循环，
 * 其他官网页面的呈现行为不变。
 */
internal object GeckoPresentationScript {
    fun build(
        mode: GeckoPresentationMode,
        playerSelector: String,
        videoSelector: String,
        suppressNativePlayerChrome: Boolean = false,
    ): String {
        val modeValue = JSONObject.quote(mode.name)
        val playerValue = JSONObject.quote(playerSelector)
        val videoValue = JSONObject.quote(videoSelector)
        val suppressValue = suppressNativePlayerChrome.toString()
        return """
            (function () {
                var existing = window.__kantvPresentationController;
                if (existing) {
                    existing.scan();
                    return;
                }
                var MODE = $modeValue;
                var PLAYER_SELECTOR = $playerValue;
                var VIDEO_SELECTOR = $videoValue;
                var SUPPRESS_CHROME = $suppressValue;
                var boundVideo = null;
                var boundPlayer = null;
                var bindCount = 0;
                var requestCount = 0;
                var requestAccepted = false;
                var cssFitApplied = false;
                var requestedVideos = new WeakSet();
                var fitStyle = null;
                var chromeStyle = null;

                function safeInt(value, maximum) {
                    if (!Number.isFinite(value)) return 0;
                    return Math.max(0, Math.min(maximum, Math.round(value)));
                }

                function report() {
                    var rect = boundVideo ? boundVideo.getBoundingClientRect() : null;
                    var viewportWidth = safeInt(window.innerWidth, 32768);
                    var viewportHeight = safeInt(window.innerHeight, 32768);
                    var targetWidth = rect ? safeInt(rect.width, 32768) : 0;
                    var targetHeight = rect ? safeInt(rect.height, 32768) : 0;
                    var viewportArea = viewportWidth * viewportHeight;
                    var targetArea = targetWidth * targetHeight;
                    var coverage = viewportArea > 0
                        ? safeInt(targetArea * 1000 / viewportArea, 1000) : 0;
                    window.postMessage({ __kantvPresentation: {
                        mode: MODE,
                        requestCount: requestCount,
                        requestAccepted: requestAccepted,
                        cssFitApplied: cssFitApplied,
                        rebindCount: Math.max(0, bindCount - 1),
                        targetWidth: targetWidth,
                        targetHeight: targetHeight,
                        viewportWidth: viewportWidth,
                        viewportHeight: viewportHeight,
                        coveragePermille: coverage
                    }}, '*');
                }

                function applyFit() {
                    if (!boundVideo || !boundPlayer) return;
                    if (!fitStyle) {
                        fitStyle = document.createElement('style');
                        fitStyle.id = 'kantv-presentation-fit';
                        fitStyle.textContent =
                            'html,body{margin:0!important;overflow:hidden!important;background:#000!important;}' +
                            '[data-kantv-player="1"]{position:fixed!important;inset:0!important;' +
                            'width:100vw!important;height:100vh!important;max-width:none!important;' +
                            'max-height:none!important;margin:0!important;padding:0!important;' +
                            'transform:none!important;background:#000!important;z-index:2147483000!important;}' +
                            '[data-kantv-player="1"] [data-kantv-video="1"],' +
                            '[data-kantv-player="1"][data-kantv-video="1"]{' +
                            'display:block!important;width:100%!important;height:100%!important;' +
                            'max-width:none!important;max-height:none!important;margin:0!important;' +
                            'object-fit:contain!important;object-position:center center!important;}';
                        (document.head || document.documentElement).appendChild(fitStyle);
                    }
                    cssFitApplied = true;
                    requestAnimationFrame(report);
                }

                function ensureChromeStyle() {
                    if (chromeStyle) return;
                    chromeStyle = document.createElement('style');
                    chromeStyle.id = 'kantv-native-player-chrome';
                    chromeStyle.textContent =
                        ':focus,:focus-visible{outline:none!important;}' +
                        '::-moz-focus-inner{border:0!important;}' +
                        'video::-webkit-media-controls,' +
                        'video::-webkit-media-controls-enclosure,' +
                        'video::-webkit-media-controls-panel,' +
                        '.vjs-control-bar,.vjs-big-play-button,' +
                        '#J_prismPlayer>[id^="J_prismPlayer_component_"]{display:none!important;}';
                    (document.head || document.documentElement).appendChild(chromeStyle);
                }

                function suppressNativeChrome() {
                    if (!SUPPRESS_CHROME) return;
                    ensureChromeStyle();
                    var videos = document.querySelectorAll(VIDEO_SELECTOR);
                    for (var i = 0; i < videos.length; i++) {
                        var video = videos[i];
                        try {
                            if (video.controls) video.controls = false;
                            if (video.hasAttribute && video.hasAttribute('controls')) {
                                video.removeAttribute('controls');
                            }
                        } catch (e) { }
                    }
                }

                function requestAutomaticFullscreen(video) {
                    if (requestedVideos.has(video)) return;
                    requestedVideos.add(video);
                    requestCount++;
                    if (typeof video.requestFullscreen !== 'function') {
                        requestAccepted = false;
                        if (MODE === 'AUTO_THEN_FIT') applyFit(); else report();
                        return;
                    }
                    try {
                        var result = video.requestFullscreen();
                        if (!result || typeof result.then !== 'function') {
                            requestAccepted = !!document.fullscreenElement;
                            if (!requestAccepted && MODE === 'AUTO_THEN_FIT') applyFit(); else report();
                            return;
                        }
                        result.then(function () {
                            requestAccepted = true;
                            report();
                        }).catch(function () {
                            requestAccepted = false;
                            if (MODE === 'AUTO_THEN_FIT') applyFit(); else report();
                        });
                    } catch (e) {
                        requestAccepted = false;
                        if (MODE === 'AUTO_THEN_FIT') applyFit(); else report();
                    }
                }

                function uniqueVisibleVideo() {
                    var candidates = document.querySelectorAll(VIDEO_SELECTOR);
                    var visible = [];
                    for (var i = 0; i < candidates.length; i++) {
                        var rect = candidates[i].getBoundingClientRect();
                        var style = window.getComputedStyle(candidates[i]);
                        if (rect.width > 0 && rect.height > 0 &&
                            style.display !== 'none' && style.visibility !== 'hidden') {
                            visible.push(candidates[i]);
                        }
                    }
                    return visible.length === 1 ? visible[0] : null;
                }

                function scan() {
                    if (SUPPRESS_CHROME) suppressNativeChrome();
                    // Never guess between a live element and an advert/preview video.
                    var video = uniqueVisibleVideo();
                    if (!video) return;
                    var player = video.closest ? video.closest(PLAYER_SELECTOR) : null;
                    if (!player) player = document.querySelector(PLAYER_SELECTOR) || video;
                    if (video !== boundVideo || player !== boundPlayer) {
                        if (boundVideo) boundVideo.removeAttribute('data-kantv-video');
                        if (boundPlayer) boundPlayer.removeAttribute('data-kantv-player');
                        boundVideo = video;
                        boundPlayer = player;
                        bindCount++;
                        video.setAttribute('data-kantv-video', '1');
                        player.setAttribute('data-kantv-player', '1');
                        if (MODE === 'CSS_FIT_ONLY') applyFit();
                        else requestAutomaticFullscreen(video);
                    }
                }

                window.__kantvPresentationController = { scan: scan };
                if (SUPPRESS_CHROME) setInterval(suppressNativeChrome, 1000);
                // MutationObserver 在繁忙直播页每秒可触发数百次;scan 里有强制布局
                // (getBoundingClientRect/getComputedStyle),必须节流, trailing 250ms。
                var scanScheduled = false;
                function scheduleScan() {
                    if (scanScheduled) return;
                    scanScheduled = true;
                    setTimeout(function () {
                        scanScheduled = false;
                        scan();
                    }, 250);
                }
                var observer = new MutationObserver(scheduleScan);
                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class', 'style', 'hidden']
                });
                window.addEventListener('resize', report, false);
                document.addEventListener('fullscreenchange', report, false);
                scan();
            })();
        """.trimIndent()
    }
}
