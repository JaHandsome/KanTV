package com.kantv.app.playback

import org.json.JSONObject

/**
 * 原生页（NATIVE_PAGE）准备脚本（主域 DOM 操作，经 `loadUri("javascript:")` 注入）：
 * 央视频 SPA 需要按标签点选目标频道，外加 `video.play()` 重试。
 *
 * `label == null`（pid 深链页面）时**不装点击循环**：深链已是权威选择，
 * 模糊点击反而可能在深链选中之后把频道切错。点击事实（找到/点中）经
 * `__kantvLabelState` postMessage 报给 content script，只含布尔，不含页面文本。
 */
internal object NativePagePrepareScript {

    fun build(
        label: String?,
        videoSelector: String,
        muteWebAudio: Boolean,
        diagnosticsEnabled: Boolean = false,
    ): String {
        val labelLiteral = label?.let { JSONObject.quote(it) } ?: "null"
        val selectorLiteral = JSONObject.quote(videoSelector)
        val channelClick = if (label == null) {
            ""
        } else {
            """
                |var clicked = false, exactMisses = 0, labelFound = false, labelClicked = false;
                |function normText(s) { return String(s || '').replace(/\s+/g, ''); }
                |function reportLabelState() {
                |    try {
                |        window.postMessage({ __kantvLabelState: { found: labelFound, clicked: labelClicked } }, '*');
                |    } catch (e) { }
                |}
                |if (!clicked) {
                |    var items = document.querySelectorAll(
                |        '.tv-main-con-r-list-left-imga,.tv-main-con-r-list-left-imgb');
                |    var target = null;
                |    for (var i = 0; i < items.length; i++) {
                |        if (normText(items[i].textContent) === normText(label)) { target = items[i]; break; }
                |    }
                |    // 精确匹配连续落空 30 次后放宽为包含匹配（页面文案可能带前后缀）。
                |    if (!target && exactMisses >= 30 && normText(label).length > 0) {
                |        for (var j = 0; j < items.length; j++) {
                |            if (normText(items[j].textContent).indexOf(normText(label)) >= 0) { target = items[j]; break; }
                |        }
                |    }
                |    if (target) {
                |        exactMisses = 0;
                |        if (!labelFound) { labelFound = true; reportLabelState(); }
                |        try { target.scrollIntoView({ block: 'nearest' }); } catch (e) { }
                |        target.click();
                |        clicked = true;
                |        labelClicked = true;
                |        reportLabelState();
                |    } else {
                |        exactMisses++;
                |    }
                |}
            """.trimMargin()
        }
        return """
            (function () {
                if (window.__kantvPrepare) return;
                window.__kantvPrepare = 1;
                // Fixed strategy hint only, not an authentication boundary; no media operation.
                window.postMessage({ __kantvAudioClockObserve: ${diagnosticsEnabled && !muteWebAudio} }, '*');
                var label = $labelLiteral;
                var tries = 0;
                function uniqueVisibleVideo() {
                    var candidates = document.querySelectorAll($selectorLiteral);
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
                var timer = setInterval(function () {
                    tries++;
                    try {
                        $channelClick
                        var v = uniqueVisibleVideo();
                        if (v) {
                            if ($muteWebAudio && !v.muted) v.muted = true;
                            if (!$muteWebAudio && v.muted) v.muted = false;
                            if (!v.ended && v.paused) v.play().catch(function () { });
                            // 已稳定出流即收工:不再每秒扫 DOM 直到 5 分钟上限;
                            // 中途卡顿由原生进度看门狗接管。
                            if (!v.paused && !v.ended && v.readyState >= 3) {
                                clearInterval(timer);
                                return;
                            }
                        }
                    } catch (e) { }
                    // 起播重试窗口：官网首启实测有 108 秒才出帧的会话，原来 120 秒的上限太紧。
                    // 延长到 5 分钟（成本只是每秒一次极轻的 DOM 检查），期间会话重建由
                    // 进度看门狗负责，两条路径互为兜底。
                    if (tries >= 300) clearInterval(timer);
                }, 1000);
            })();
        """.trimIndent()
    }
}
