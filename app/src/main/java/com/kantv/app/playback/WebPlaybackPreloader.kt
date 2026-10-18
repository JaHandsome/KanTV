package com.kantv.app.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * 进程级"官网直播页预热器"（2026-09-20 无感启动）。
 *
 * 背景：央视频等官网页面在电视上要先编译/实例化 WASM 播放器，从导航到出画要 20~110 秒；
 * 每次冷打开都要重付这笔账。预热器在**用户还没打开频道时**就用进程默认的 GeckoRuntime
 * （与播放宿主同一个运行时）在后台把页面加载好、把播放器初始化掉；播放宿主接管后，
 * 用户感知的等待从几十秒降到几秒。
 *
 * 两个入口：
 * 1. [warm]：开机时预热"上次看的频道"（由 MainActivity 在目录还没准备好之前就启动）；
 * 2. [retain]：播放宿主退出时把**活着的会话**交给预热器暂存，同一频道再次打开直接复用
 *    （页面还在播放、WASM 还是热的，不重新加载）。
 *
 * 纪律：
 * - 预热/暂存期间**静音**：页面**提交到目标域之后**注入一次常驻静音器（页面内
 *   MutationObserver+interval 维持；提交前不注入任何 javascript:，避免打断进行中的
 *   主导航——仓库历史里有"注入风暴"把页面导航打翻的先例）。
 * - 接管必须"真的加载了"：只有页面已提交到目标 URL 的会话才允许被 [take] 拿走；
 *   抢跑/落地页不符一律关闭并回退冷启动（接管绝不补导航，补了 WASM 就白预热了）。
 * - 单槽：同一时刻最多留一个会话；换目标先释放旧的（内存上限天然受控）。
 * - 空闲 2 分钟或内存吃紧（宿主 onTrimMemory）即释放，不长期占内存。
 * - 所有操作都在主线程（GeckoSession 的主线程亲缘），调用方也都在主线程。
 */
object WebPlaybackPreloader {
    /** 官网页面的桌面 Firefox UA——与播放宿主完全一致（页面据此走带 WASM 解密的官方分支）。 */
    const val DESKTOP_FIREFOX_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:146.0) Gecko/20100101 Firefox/146.0"

    /** 接管结果：会话句柄 + 预热/暂存期真实落地 URL（宿主用它初始化 `currentUrl`）。 */
    data class AdoptedSession(val session: GeckoSession, val landedUrl: String)

    private const val EXTENSION_ID = "kantv-mirror@kantv.local"
    private const val EXTENSION_LOCATION = "resource://android/assets/kantv-gecko-mirror/"
    private const val IDLE_RELEASE_MS = 2L * 60L * 1_000L
    private const val MUTE_INTERVAL_MS = 1_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var url: String? = null
    private var slotStartedAtMs = 0L
    /** 页面已提交到目标 URL（导航成功落地），之后才允许静音注入与接管。 */
    private var navigated = false
    private var lastLocation: String? = null
    /** 常驻静音器是否已注入当前文档;新文档落地后需重装。 */
    private var muteInstalled = false

    private val muteTick = object : Runnable {
        override fun run() {
            val current = session
            if (current != null && current.isOpen) {
                // 页面提交后一次性安装常驻静音器(页面内 interval+MutationObserver 维持),
                // 不再每秒走一次 javascript: 导航管道(历史实现每拍一次 loadUri,
                // 每次都要过 Gecko 导航栈)。新文档落地(onLocationChange 复位标志)后重装。
                if (navigated && !muteInstalled) {
                    muteInstalled = true
                    runCatching { current.loadUri(MUTE_INSTALL_SCRIPT) }
                }
                handler.postDelayed(this, MUTE_INTERVAL_MS)
            }
        }
    }

    private val noopMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            app: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? = null
    }

    /**
     * 预热 [pageUrl]。若已有同 URL 且已加载完成的会话则只续期；否则释放旧槽并开新会话。
     * 播放中（[PlaybackActivityFlag] 活跃）不预热：那时要么用户在播同一个频道（接管/暂存
     * 路径负责），要么正在看别的——预热只服务于"还没开始看"的场景，避免双份会话。
     * 返回是否已存在同目标会话（true = 本次调用前就已经在预热）。
     */
    fun warm(context: Context, pageUrl: String): Boolean {
        checkMainThread()
        if (url == pageUrl && session?.isOpen == true) {
            scheduleIdleRelease()
            return true
        }
        if (PlaybackActivityFlag.isActive()) return false
        release()
        val runtime = obtainRuntime(context) ?: return false
        val created = GeckoSession()
        runCatching {
            created.settings.userAgentOverride = DESKTOP_FIREFOX_UA
            created.settings.useTrackingProtection = false
        }
        // 后台自动播放必须放行：否则页面会卡在"等待用户手势"而永远到不了 readyState≥2，
        // 预热就白做了。声音由常驻静音器在页面提交后兜底压掉。
        created.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                target: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                val autoplay = permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                return GeckoResult.fromValue(
                    if (autoplay) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
                )
            }
        }
        // 极简导航监听：只记录"页面是否真的提交到了目标 URL"。接管校验靠它。
        created.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                target: GeckoSession,
                newUrl: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (target === created && session === created && newUrl != null) {
                    lastLocation = newUrl
                    // 新文档落地后旧的页面内静音器随之销毁,下一拍重装。
                    muteInstalled = false
                    if (newUrl.startsWith(pageUrl.substringBefore('#'))) navigated = true
                }
            }
        }
        val opened = runCatching { created.open(runtime) }.isSuccess
        if (!opened) {
            runCatching { created.close() }
            return false
        }
        session = created
        url = pageUrl
        navigated = false
        lastLocation = null
        muteInstalled = false
        slotStartedAtMs = SystemClock.elapsedRealtime()
        // 扩展是 runtime 级一次性安装；装好后立刻导航（保证 content script 在 document_start
        // 注入，与播放宿主同口径）。消息 delegate 绑一个空实现：桥的 native 消息有去处即可，
        // 真正处理由播放宿主接管会话后重绑。
        runCatching {
            runtime.webExtensionController
                .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
                .accept({ extension ->
                    if (extension != null && session === created) {
                        runCatching {
                            created.webExtensionController.setMessageDelegate(extension, noopMessageDelegate, "kantv")
                        }
                    }
                    handler.post { if (session === created) created.loadUri(pageUrl) }
                }, { _ ->
                    handler.post { if (session === created) created.loadUri(pageUrl) }
                })
        }
        handler.removeCallbacks(muteTick)
        handler.post(muteTick)
        scheduleIdleRelease()
        return false
    }

    /**
     * 接管 [pageUrl] 的预热/暂存会话。**只有页面已真正提交到目标 URL** 才命中；
     * 否则（抢跑中还没导航、落地页不符、会话已死）一律关闭槽位返回 null，调用方走冷启动。
     * 接管绝不补导航——补了 WASM 就白预热了。
     *
     * 返回值带上落地 URL，交给宿主当 `currentUrl` 的初始事实（见 [AdoptedSession]）。
     */
    fun take(pageUrl: String): AdoptedSession? {
        checkMainThread()
        val current = session ?: return null
        // 没有落地 URL 就无法证明"接管的是目标页"：按拒收处理（并交出槽位所有权）。
        val landed = lastLocation ?: run {
            release()
            return null
        }
        val adoptable = PreloaderAdoptionPolicy.canAdopt(
            slotUrl = url,
            pageUrl = pageUrl,
            slotIsOpen = current.isOpen,
            navigated = navigated,
            landedUrl = landed,
        )
        if (!adoptable) {
            release()
            return null
        }
        handler.removeCallbacks(muteTick)
        session = null
        url = null
        navigated = false
        lastLocation = null
        muteInstalled = false
        slotStartedAtMs = 0L
        return AdoptedSession(current, landed)
    }

    /**
     * 播放宿主退出（回列表/退后台）时把还活着的会话暂存起来。同一频道再次打开会经
     * [take] 直接复用：页面保持加载与播放（静音），不重新初始化 WASM。
     *
     * [landedUrl] 是宿主当时的**真实落地 URL**（宿主 navigation delegate 记录的
     * `currentUrl`）：页面若已在播放期间跳到别处（登录页/错误页/站点重定向），
     * 暂存后下次 [take] 会因落地 URL 不匹配而拒绝，绝不会把错页接管给用户。
     */
    fun retain(liveSession: GeckoSession, pageUrl: String, landedUrl: String?) {
        checkMainThread()
        if (!liveSession.isOpen) return
        // 幂等：同一会话重复暂存（无 onStart 间隔的极端时序）只续期，不把活会话关掉。
        if (session === liveSession) {
            scheduleIdleRelease()
            return
        }
        // 页面还没真正落地（宿主还没观测到任何导航提交）：不值得暂存。
        // 宿主已经把句柄交出来了（它自己不再引用），拒收必须由这里关闭，
        // 否则这个 open 的会话会失去所有者，一直占着内容进程。
        if (!PreloaderAdoptionPolicy.canRetain(landedUrl)) {
            runCatching { liveSession.close() }
            return
        }
        release()
        session = liveSession
        url = pageUrl
        navigated = true
        lastLocation = landedUrl
        muteInstalled = false
        slotStartedAtMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(muteTick)
        handler.post(muteTick)
        scheduleIdleRelease()
    }

    /** 立即释放槽位（换目标、内存压力、超时、接管未命中）。 */
    fun release() {
        checkMainThread()
        handler.removeCallbacks(muteTick)
        session?.let { runCatching { it.close() } }
        session = null
        url = null
        navigated = false
        lastLocation = null
        muteInstalled = false
        slotStartedAtMs = 0L
    }

    private fun scheduleIdleRelease() {
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private val idleRelease = Runnable {
        val current = session
        if (current != null && current.isOpen &&
            SystemClock.elapsedRealtime() - slotStartedAtMs >= IDLE_RELEASE_MS
        ) {
            release()
        }
    }

    private fun obtainRuntime(context: Context): GeckoRuntime? =
        runtime ?: runCatching { GeckoRuntime.getDefault(context.applicationContext) }
            .getOrNull()?.also { runtime = it }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebPlaybackPreloader 只在主线程使用" }
    }

    /** 常驻静音器：注入一次后由页面内 MutationObserver+interval 维持（不再有每秒一次的
     *  javascript: 导航管道开销）。接管方先置 window.__kantvPreloadMute=0 再解除静音，
     *  否则常驻静音器会在 1 秒内重新压掉。 */
    private const val MUTE_INSTALL_SCRIPT =
        "javascript:void(function(){if(window.__kantvPreloadMute)return;window.__kantvPreloadMute=1;" +
            "function m(){if(!window.__kantvPreloadMute)return;var vs=document.querySelectorAll('video');" +
            "for(var i=0;i<vs.length;i++){if(!vs[i].muted)vs[i].muted=true;}}" +
            "new MutationObserver(m).observe(document.documentElement,{childList:true,subtree:true});" +
            "setInterval(m,1000);m();})()"
}

/**
 * 接管/暂存的纯判定（不触碰 GeckoSession，便于单测锁定"错页与重定向必须拒绝"）。
 *
 * 两条纪律：
 * 1. 只有**真实落地到目标页**的会话才允许被接管；
 * 2. 没有落地 URL 的会话不得进槽位，且调用方必须负责关闭它（所有权不留空隙）。
 */
internal object PreloaderAdoptionPolicy {
    /** 保留站点追加 query 的既有行为，但不接受相似路径；目标显式指定 query 时必须匹配。 */
    fun landingMatches(pageUrl: String, landedUrl: String?): Boolean {
        if (landedUrl.isNullOrEmpty()) return false
        val expected = pageUrl.substringBefore('#')
        val landed = landedUrl.substringBefore('#')
        return landed == expected || ('?' !in expected && landed.startsWith("$expected?"))
    }

    fun canAdopt(
        slotUrl: String?,
        pageUrl: String,
        slotIsOpen: Boolean,
        navigated: Boolean,
        landedUrl: String?,
    ): Boolean = slotUrl == pageUrl && slotIsOpen && navigated && landingMatches(pageUrl, landedUrl)

    fun canRetain(landedUrl: String?): Boolean = !landedUrl.isNullOrEmpty()
}
