# KANTV 家用 NAS 诊断接收器

这是供电视端上传**受限诊断摘要**并从局域网读取正式更新包的最小 HTTP 服务。诊断写入只接受 `POST /v1/reports`；更新只读端点为 `GET /v1/releases/latest` 与 `GET /v1/releases/apk/<清单中的文件名>`；另提供不含数据的 `GET /health`。没有报告或发布目录的读取、列举、删除、上传接口。服务不记录 Authorization 和请求体。

旧诊断接收器曾部署到 home-nas；本文件描述的更新端点仍须单独发布后才会生效，本地代码或测试不代表 NAS 已更新。

## 本地启动与停止

Python 3.11+ 即可，不需要第三方包。诊断写入令牌与更新下载令牌必须彼此独立，并通过环境变量或仅管理员可读的文件提供。绝不要把真实令牌写进 compose 文件、命令历史或仓库：

```sh
umask 077
python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > receiver-token
python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > update-token
export DIAGNOSTICS_RECEIVER_TOKEN_FILE=./receiver-token
export UPDATE_RECEIVER_TOKEN_FILE=./update-token
python3 receiver.py --storage-dir ./local-reports --release-dir ./local-releases
```

健康检查为 `GET http://127.0.0.1:8091/health`。停止时按 `Ctrl-C`；已保存报告会留在 `local-reports/`，不会自动清理。

容器示例使用 `docker-compose.example.yml`。应先分别创建 `secrets/diagnostics_receiver_token` 与 `secrets/update_receiver_token`，并设置 `DIAGNOSTICS_LAN_IP` 和只读宿主目录 `KANTV_RELEASE_DIR`。本地直接运行时令牌使用 `0600`；部分 NAS 的 Docker Compose 会把文件型 secret 直接绑定进容器，非 root 服务账号需要源文件可读，此时父目录保持 `0700`、目录内令牌使用 `0644`，宿主机其他账号仍无法穿过父目录。端口只绑定内网 IP；不要暴露到公网或设置路由器端口转发。

活跃报告区（存储目录顶层普通 `*.json`）上限是 2000 份或 512 MiB。合法新报告入库会超过任一门限时，服务在同一写入锁内自动把整批活跃报告移动到唯一的 `archive-*` 子目录，再保存新报告；等于字节上限仍可入库，达到数量上限后下一个新报告触发归档。归档按需触发，无定时任务，不增加上限，也不自动删除旧证据。整批移动避免满后每次上传都只搬一份。单份规范化报告已超过活跃字节上限时仍返回 `507 storage_limit_reached`。

归档使用同卷重命名，保留原始文件内容与权限；归档目录权限为 `0700`。移动前先持久化 `manifest.json`（`0600`），列出本批文件名、字节数、SHA-256，再逐份移动并同步目录。归档失败、真实磁盘耗尽或写入失败返回 `507 storage_unavailable`，日志只含固定事件名和 errno；成功归档日志仅含数量、字节数及 `duration_ms`。部分移动失败时，报告仍分别留在活跃区和归档区，已移动报告的幂等索引立即生效；服务重启后也能重新识别。清单描述计划移动的整个批次，失败批次可能尚有文件位于活跃区。离线恢复或回滚时，先按清单逐份核对这两处的大小与 SHA-256，再将归档报告移回活跃区；若同名文件已存在，应核对后停止处理冲突，绝不可覆盖或删除证据。

相同 UUID 的重传会同时检查活跃文件和已归档文件：所有副本内容都与规范化报告相同才返回 `200 stored:false`，任何副本不同返回 `409`，不会重新入库或触发归档。启动仅扫描一层 `archive-*` 目录建立文件名索引，不读取/校验历史报告内容；运行中只增量更新索引，只对容量触发的归档批次计算校验和。因此兼容 `archive-20261012T112345+0800` 等人工归档，仅把严格小写 UUID 文件名的普通 `.json` 当作历史报告，忽略 `manifest.json`、`recovery-result.json`、`verification.json` 等元数据。归档目录或 UUID 报告是符号链接等非预期类型时启动拒绝，运行中也不跟随符号链接。管理员对报告目录的移动、修改或恢复须停服操作，完成后重启以重建索引。

目录同步失败时即使文件已经移动/入库，仍返回 `507`；同内容重试会先补同步，持续同步失败仍拒收，恢复后返回 `200`。整批归档在写入锁内执行，可能超过客户端 10 秒读取超时；客户端应保留原 UUID 重试，由幂等检查确认结果。最大 512 MiB 批次在 NAS 的归档耗时尚未测定，应通过 `duration_ms` 观察实际性能。

归档只释放活跃区的数量/字节额度，不释放磁盘空间。所有历史文件与索引仍会增长；管理员需监控真实磁盘、启动耗时及内存，并按自己的保留期和备份策略离线管理历史证据。服务只有一个进程拥有该存储目录，不能多个接收器共用一个报告卷。若保留量使启动扫描或索引内存不可接受，再评估独立存储方案；当前家用单实例边界不引入数据库或后台线程。

> 2026-09-17 调整：原上限为 200 份 / 50 MiB。电视端按播放中每分钟一份的节奏产生报告，200 份只够约半天，卷在 2026-09-15 19:19 被填满后，其后所有上传都被 `507` 拒收（拒收在服务端不留痕：访问日志按隐私设计关闭，只有格式不合法才会记 `400`）。排障时应先核对卷内**顶层** `*.json` 的数量与字节数是否已达上限，再怀疑别的环节。

## 只读更新契约

发布目录只允许管理员离线写入，容器以 `:ro` 挂载。正式发布必须同时放置两个单 ABI APK：`latest.json` 固定指向 `arm64-v8a`（兼容不带 ABI 头的旧客户端），`latest-armeabi-v7a.json` 指向 32 位包；新版客户端通过 `X-KanTV-ABI` 请求对应通道。清单可从 `release-manifest.example.json` 复制后填写实际值。`apkPath` 只能是无目录的 ASCII `.apk` 文件名，字段必须严格完整，APK 必须是普通文件、大小与清单一致且不超过 256 MiB，并且 `lib/<abi>/` 必须恰好只含通道对应 ABI。服务不接受发布写入，不跟随 APK 符号链接，也不暴露目录。

更新端点只接受独立更新 Bearer token；诊断 token 无法下载 APK。APK 响应固定 `Content-Length` 并支持单段 `Range: bytes=start-end`。Android 端仍会独立验证 SHA-256、包名、递增 versionCode，以及下载 APK 与当前安装 APK 的签名证书一致，任何一项失败都不进入系统安装流程。

Android 构建可用 `KANTV_UPDATE_ENDPOINT`（字面 RFC1918 地址，路径固定为 `/v1/releases/latest`）和 `KANTV_UPDATE_TOKEN` 注入更新配置。正式签名需同时设置 `KANTV_RELEASE_KEYSTORE_FILE`、`KANTV_RELEASE_KEYSTORE_PASSWORD`、`KANTV_RELEASE_KEY_ALIAS`、`KANTV_RELEASE_KEY_PASSWORD`；值不进入仓库或日志。设置后 `release` 与 `diagnostic` 都使用同一 `formalRelease` 签名；未设置时本地 debug/diagnostic 仍可构建。正式产物应另跑 `./gradlew signingReport`，将 diagnostic 的 SHA-256 与 `latest.json` 对照。

正式构建和打包统一使用：

```bash
python3 tools/lan_release/build_dual_abi.py
python3 tools/lan_release/lan_release.py pack-dual \
  --arm64-apk app/build/outputs/dual-abi/app-release-arm64-v8a.apk \
  --v7a-apk app/build/outputs/dual-abi/app-release-armeabi-v7a.apk \
  --release-dir /path/to/release
python3 tools/lan_release/lan_release.py verify-dual --release-dir /path/to/release
```

`pack-dual` 要求两个包的包名、版本号和签名完全一致，先写不可变 APK，再切 32 位清单，最后切换 legacy `latest.json`；禁止只更新其中一个架构。

## 上传契约

`Content-Type` 必须为 `application/json`，`Authorization` 必须精确为 `Bearer <token>`，请求必须提供合法 `Content-Length` 且不使用 chunked 编码，单请求最多 512 KiB。`reportId` 是标准小写 UUID；相同 UUID 与相同的规范化报告返回 `200`，内容不同返回 `409`。当前支持 schema 10–21，字段集中在 [schema.py](schema.py)，已与 Android 诊断版对齐。每个新 schema 保留既有版本的 exact shape 校验；schema 18、19、20、21 的新增字段不改变更旧版本的字段集合校验。Schema 20 增加 Gecko query pending 窗口内自有同步 tick 累计与有限 DOCUMENT-wide resource 候选计数/时间字段；它们不改变播放行为或频率，resource 候选也不归因到媒体请求。字段含义与本次验证边界见[Schema 20 证据记录](../../docs/evidence/2026-10-13-query-window-resource-schema20.md)。

运行边界测试：

```sh
python3 -m unittest -v test_receiver.py
```


schema 14 保留 10–13 的严格兼容校验，只新增以下五个必填样本字段：

- `probeObservationStatus`：`VALID` 是页面 tick 与身份有效且 Android 接收年龄不超过 5 秒；`INSUFFICIENT_NO_SAMPLE`、`INSUFFICIENT_STALE_SAMPLE`、`INSUFFICIENT_TICK_NOT_ADVANCING`、`INSUFFICIENT_RUNTIME_CHANGED`、`INSUFFICIENT_NO_IDENTITY` 分别标明采集缺失原因。其他内核用 `NOT_APPLICABLE`。同节点 readiness 变化不会递增真实 `nodeGeneration`；真实文档、节点或媒体资源换代才重置诊断首帧基线。旧桥仍保留登录/看门狗兼容事实，但不标有效诊断观测。
- `avSyncAssessment`：网页原生媒体用 `UNKNOWN_NO_MEDIA_ELEMENT_AUDIO_OUTPUT_TIMESTAMP`，其他未评估路径用 `UNKNOWN_NOT_EVALUATED`。网页没有可旁观的标准音频输出时间戳，配置延迟 0 不能证明同步。
- `videoCallbackAgeMs`、`videoPresentationAgeMs`：rVFC 回调与浏览器提交合成器证据相对网页 `performance.now()` 的年龄；-1 表示未取得证据。
- `videoExpectedDisplayDelayUs`：rVFC 的 expectedDisplayTime 相对回调 now 的有界差值（±1 秒）；没有回调时为 -1，需结合 `NO_CALLBACK_YET` 与回调年龄判断可用性；有回调时允许负差值（含 -1）和合法 0。它不表示物理屏幕显示时间，也不代表音画 offset。

复用 `presentedFrames`（rVFC compositor 回调计数）、`videoPtsUs`（metadata mediaTime）、`videoTimingSource`（`GECKO_RVFC` / `GECKO_RVFC_PENDING` / `GECKO_PLAYBACK_QUALITY` / `GECKO_CURRENT_TIME`）、`actualPresentedAvailability`（`BROWSER_COMPOSITOR_ONLY`，尚无回调时 `NO_CALLBACK_YET`）与 `jsSampleAgeMs`（Android 接收年龄）。支持 rVFC 但尚未收到 callback 时呈现计数保持 -1，仍保留实际取得的质量计数 fallback。质量计数/媒体时钟是明确 fallback，不包装为物理呈现证据。网页时钟没有填入 Android 纳秒字段；网页不接管 AudioContext、不改变音频路由。

前台启动 30 秒仍未观察到首帧会保留 `NO_FIRST_FRAME_30S`，即使观测不足；此时必须结合 `probeObservationStatus`，不能解读为已证明物理无画面。已起播后采集陈旧会暂停播放停滞判断；同节点新鲜观测的冻结/缓冲仍保留 15 秒判断。原生像素看门狗的阈值、校准和预算保持原值，真实身份换代才获得新启动窗口。

schema 15 严格保留 10–14 的旧字段集合；`probeObservationStatus` 新增 `INSUFFICIENT_SUSPENDED`，明确后台/会话关闭已作废旧观测，恢复前台需新的有效 tick。周期、事件与会话结束出口统一刷新观测年龄与可用性。新增七个必填样本字段（数值未知通常为 `-1`）：

- `geckoAudioClockStatus`：`AVAILABLE` 仅表示取得有效、新鲜的 Gecko 引擎音频时钟；其余固定状态明确区分未采样、非播放、等待、API 不支持、拒绝、超时、慢调用、无效时钟、时钟不一致、配对陈旧、缓存陈旧及原生观测失效。其他播放路径为 `NOT_APPLICABLE`。
- `geckoAudioClockUs`：Gecko 146 `stateMachine.clock`，只有存在有声且启动播放的音频 sink，并且与 `audioSink.startTime + lastGoodPosition` 相差不超过 1,000 µs 才可用。它是引擎媒体时间轴游标，不能代替 Android AudioTrack 或扬声器输出时间戳；`audioSink.isStarted` 是该版本未填充的默认字段，不参与可用判定。
- `geckoAudioClockAgeMs`：从读取发起到报告采样的保守年龄，包含网页缓存年龄与 Android 接收后的年龄；相同 tick 重复读取不会重复累加。可用值不超过 5,000 ms。
- `geckoAudioClockCallDurationMs`：该次异步读取耗时。超过 100 ms 后停止本节点采样；1,000 ms 未返回标记超时，仍保留未完成请求锁，不能通过超时发出重叠请求。
- `geckoPairedVideoPtsUs`：读取发起时固定的同一 rVFC 帧 PTS，与周期报告中后来更新的 `videoPtsUs` 独立。
- `geckoAudioMinusVideoUs`：有符号的 `geckoAudioClockUs - geckoPairedVideoPtsUs`；真实差值可以是 `-1`，必须依赖 `geckoAudioClockStatus` 区分未知。正值表示引擎音频游标在媒体时间轴更靠后，不能推断实际声音领先物理画面。
- `geckoAvPairSpanMs`：固定视频回调到音频读取返回的窗口上界；超过 100 ms 不报可用。细微偏差可能落在此窗口内，不能据此武断判定同步或不同步。有效配对只标 `UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED`；物理 A/V 始终为 UNKNOWN。

仅原生网页策略默认启用；策略消息是固定布尔提示，不是认证边界。采样限于白名单域的顶层文档、连接且元素及祖先可见的唯一标记视频、非暂停/结束/跳转、非静音、音量大于零、就绪且帧稳定推进至少一秒时，每约三秒读一次。最多一个未完成请求跨节点共享；节点/资源换代、隐藏/移除、跳转、音量变化及策略切换使旧结果失效。API 不支持、拒绝、超时、慢调用、默认零或无效/不一致时钟会停止本节点采样，配对窗口陈旧可按原三秒节拍重采。只转发上述数值与固定枚举，不转发完整 debug 字典、URL、解码器标识、EME 信息或内存地址。

`mozRequestDebugInfo()` 内部会查询音频位置并可能维护 sink 时钟状态，不能声称零副作用的纯缓存旁观。采样器通过稀疏调用、单请求锁与失败熔断限制这项工作；没有新增播放、暂停、跳转、音频路由或源切换操作。离线检查通过不能代替真机长期播放和用户音画验收。

被动 observer 的离线验证（仓库根目录，已安装 Node，无新增依赖）：`node tools/test_gecko_frame_observer.cjs` 与 `node tools/test_gecko_audio_observer.cjs`。服务端须先部署 schema 15 接收能力，再发布 schema 15 APK；编译/模拟测试不能代替 Gecko 真机回调、可见音画与切台验收。


schema 16 保留 10–15 的 exact shape 与原枚举，新增原生 Media3 诊断字段。Gecko 的新原生字段均为 `NOT_APPLICABLE` / `-1`，已有 Gecko 观察行为不变。

- `nativeAudioClockStatus`：`AVAILABLE` 或固定 `INSUFFICIENT_*` 状态。未知时配对数值全部 `-1`；`nativeAvGeneration` 保留所观察的引擎代次。
- `nativeAudioStreamOffsetUs` 是被动观察到的真实 `AudioSink.setOutputStreamOffsetUs`（可正可负）；`nativeAudioClockUs` = 同次原始 sink 游标减该 offset。没有硬编码 offset，也不用 `player.currentPosition` 替代音频输出游标。
- `nativePairedVideoPtsUs` 是固定配对的视频 metadata PTS；`nativeAudioMinusVideoUs` 是有符号差值，测得的 `-1` 合法。`nativeAvPairSpanUs` 为两次采集间隔，最多 100000µs；`nativeAudioClockAgeMs` 使用同一 nanoTime 时钟，最多 1500ms。
- `nativeVideoReleaseDelayUs` 为配对帧的计划 release 相对 metadata callback 的时间，范围 ±1000000µs。视频是 about-to-render，音频是 sink playback estimate；两者均不代表屏幕光学呈现或扬声器声学输出，`avSyncAssessment` 始终为 `UNKNOWN_PHYSICAL_OUTPUT_NOT_OBSERVED`。
- 当前配对适用于已证实仅一个且身份稳定的 MediaPeriod。多 period、period 身份变化或已观测 offset 改变，本代持续证据不足，明确新代后才能重新建立配对；不会把过渡期视频和音频跨域相减。同 period seek/flush/discontinuity 清配对样本、保留有效 offset；暂停/缓冲清样本，恢复要求新采集。真实多 period 流需要支持时，应增加视频 processed offset 的正式被动接口，再放宽该边界。
- `nativeLiveOffsetMs`、`nativeLiveWindowDurationMs`、`nativeLiveMediaSequence`、`nativeLastMediaLoadDurationMs`、`nativeLastMediaLoadBytes`、`nativeLastLoadErrorCode` 是最近 timeline/state/load 回调的只读上下文；`nativeLiveContextAgeMs` 标出观测年龄。加载错误仅保留 HTTP 数字或 `-1`。原有 `timeMs` / `bufferedPositionMs` / `bufferDurationMs` 表示相同上下文中的 player 位置与缓冲，独立于 AV sink 时钟。

时间域依据为 Media3 1.11 官方源码：[AudioSink offset API](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioSink.java)、[DefaultAudioSink playback estimate](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)、[video metadata normalization](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java)、[same-period renderer offset](https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayerImplInternal.java)。默认 factory 的 video 创建方法是 private，本版保留 factory 和 renderer 实例策略，没有复制上游创建逻辑。接收端须先具备 schema 16 校验，APK 发布与真实设备可见音画/切台仍是独立验收。


schema 17 保留 schema 10–16 的 exact shape。新增 49 个被动字段，定义集中在 Android 的 `M3uLoadDiagnosticEvidence` / `GeckoTraceDiagnosticEvidence` 与接收端白名单；验证记录见 [开播、加载与查询诊断](../../docs/evidence/2026-10-12-startup-load-query-schema17.md)。

- Native MEDIA task 与 DataSource 摘要独立：task 含开始/结束、媒体跨度、轨道、重试及 HTTP 数字；transfer 含 open/read/close 耗时、次数、字节及真正 EOF。transfer 的 `UNASSIGNED` 明确不能用于推导某 MEDIA task 的下载/解析占比。只在同一个稳定 period 中比较媒体范围与直播窗口。
- Gecko 区分生命周期首次事件、首次 rVFC、请求同步调用时间、Promise reaction、软超时送达延迟和未完成请求。超时后保留原熔断；迟到结果只记录处理原因，不重新读取时钟或触发重试。
- App 按空 samples 报告完整 envelope 的实际 UTF-8 字节动态缩减样本预算（最高 300 KiB），预留 8 KiB 有界 header 变化、封口与恢复条目，最多 119 条；分片失败保留旧片并记录错误。接收端请求上限仍为 512 KiB，旧报告与自动归档保留。

这些字段用于划分等待阶段和排除错误归因；AV 输出仍须区分引擎估计、屏幕实际呈现与扬声器实际输出，不能从模拟器或内部差值推断物理音画正常。

schema 18 保留 schema 10–17 的 exact shape，并新增 14 个被动诊断字段：9 个 `nativeMediaRequest*` 字段与 5 个 `gecko*` trace 字段。字段白名单、默认值和范围以 [schema.py](schema.py) 为准。

- `nativeMediaRequestMatchStatus`、`nativeMediaRequestCoverage`、`nativeMediaRequestOutcome`、`nativeMediaRequestOpenDurationUs`、`nativeMediaRequestReadDurationUs`、`nativeMediaRequestCloseDurationUs`、`nativeMediaRequestReadCallCount`、`nativeMediaRequestBytes`、`nativeMediaRequestEofSeen` 描述当前 `nativeLoadLast*` 对应的 Media `DataSource` 请求。仅 `MATCHED` 表示按同一 `DataSpec` 身份关联到该请求；此时 coverage 固定为 `REQUEST_ONLY`，且 load task 已完成、请求有终态、次数和字节数有效。它只覆盖请求的 open/read/close，不覆盖整个加载任务、解析或解码时间。其他匹配状态保持 UNKNOWN/NA 与数值 -1；不能把 `UNASSIGNED` transfer task 当作该 Media 请求的证据。
- `geckoDocumentNowMs` 与 `geckoDocumentTimeOriginMs` 是 state 包内的网页 performance 时钟值；缺失时为 -1，不能用 `Date.now()` 推算 timeOrigin，也不能直接与 Android 时钟相减。`geckoStateReceivedEpochMs` 是宿主接收该包时的 epoch 锚点，只说明消息到达时刻；IPC 与调度延迟未知，不能据此推算绝对首帧时间。
- `geckoAudioQueryRvfcCount` 与 `geckoAudioQueryMaxRvfcGapMs` 属于同一 `geckoAudioQuerySeq/node/epoch`。count 统计查询期间实际执行的 JavaScript rVFC 回调，使用回调执行时的 `performance.now()`；不使用 rVFC 参数里的时间戳。gap 描述查询起止边界与这些回调之间的最大可观测间隔。-1 表示该组证据未知；count 未知时 gap 也必须未知。超时后仍处于 pending 的查询可以继续累计原始计数，直到已有 Promise reaction settle；这不解除熔断、不发起重试。
- 这些计时只描述 JavaScript 可见的 Promise 等待与回调执行，不提供 Gecko C++ 队列或内部请求完成时刻，不能单独定位阻塞位置或证明根因。新字段沿现有 state 包传递，不增加消息频率、timer 或播放动作。

schema 18 接收端须先部署，再发布 schema 18 APK；本地 wire 与 NAS 实际接收兼容性均须核验。接收端支持与 Android 编译、模拟验证、真实 Gecko 回调及用户音画验收是不同证据，不能相互替代。


schema19保留10–18的exact shape，新增12个Gecko自有tick phase字段与6个document-wide资源时序字段，详见[分段证据记录](../../docs/evidence/2026-10-13-gecko-phase-resource-schema19.md)。phase仅计自有同步任务的经过时长：上一完整tick和发起该query的tick分开，query尾从同步API返回起算，startup累计不含任务间等待/Promise/rVFC。它不等于CPU耗时，也不自动代表其他网页/浏览器内部工作。异常/时钟无效/身份换代整组未知。

资源最多检查128条，首rVFC或30秒截止，初次metadata保留scope、emptied/真实DOM换代失效。COMPLETE只说明该有界资源窗口封口，所有条目无法归属媒体；EMPTY不等于无网络请求，OVERFLOW是部分计数，跨源受限细分保持-1。资源类型不可用为UNSUPPORTED，不读取/转发name、URL、头、响应正文或字节数。接收端先部署，兼容性核验与签名APK及真实电视验收分别记录。


## Schema 21：有界诊断恢复与同域分段

Schema21 增加22个必填白名单字段，schema20及更旧版本的精确字段集合保持原样。首次故障身份、原始归因及其后真正反应补齐的时间属于同一请求；后续查询不能覆盖它。`geckoAudioFirstFault{Seq,Epoch,Node,StartMs,SyncCallMs,ReactionElapsedMs,TimeoutLateMs,JsTickCount,MaxJsTickGapMs,State,Disposition,ClockStatus}` 保存首故障，当前查询仍使用原有字段。首故障时间、query tick 均为同 document `performance.now()` 域；反应是 JS reaction，不能称 native 精确 resolve。

`geckoAudioQueryJsTickCount/MaxJsTickGapMs` 只复用已有1秒 tick，第一次 reaction 冻结。count为0表示期间没有执行该 tick，gap=-1表示未知；这不证明 DOM 阻塞。count>0时，max gap包含call→tick、tick间和最后tick→reaction，同一查询身份才有效。

自动恢复只针对已实际 reaction/settled 的异步 timeout/slow 诊断故障，要求同步 API 调用测值在0..100ms、节点/epoch仍匹配、原请求不再in-flight。沿原1秒tick分别等待15/30秒，每document最多2次，由`geckoAudioRecoveryCount`与`geckoAudioRetryRemainingMs`观察。后台/节点/epoch换代作废待恢复资格，但不重置document恢复预算；原有新节点首次查询行为保留。同步慢、同步未知、拒绝、无有效音频时钟、预算耗尽继续熔断。无新timer/Promise/dispatch，无播放/解码策略修改；首故障仍可随后续样本上传。

`geckoCaptureSequence/ApiCallMs/IssueToListenerMs/ClassifyMs/PostLagMs` 使用Android `elapsedRealtime()`。ApiCall测同步`capturePixels`入口至返回（失败则至异常捕获）；IssueToListener仍混合compositor/readback、UI复制及listener排队；Classify包括既有格点分类及位图回收；PostLag测既有二次handler.post→处理入口。listener失败无分类，Classify=-1；未完成端点为-1。新sequence只阻止迟到旧结果污染新观测，不改变原看门狗处理。`geckoBridgePostLagMs` 是已有state post到应用入口的Host排队时长，不包含IPC之前的等待。不可跨DOM/Android时钟相减，也不能把这些Host分段归因为精确PixelCopy或native等待。

实际电视验证、接收端NAS部署与正式签名发布由主控另行完成；本地回归和APK构建不代表这些验收通过。

Schema21 另接受固定事实 kind `BUFFERING_STARTED`、`BUFFERING_ENDED`；旧schema20 kind集合不扩大。布防与短buffer恢复不改名为`WATCHDOG_TRIGGERED`，真实watchdog事实保留该kind。持久化/采样节奏由既有Native诊断策略独立处理，此白名单与序列化不自行触发fault或恢复。

## Schema 22：像素采集安全阻断

Schema22 新增必填固定枚举 `visualSampleBlockedReason`：`NONE`、`DEVICE_GPU_READBACK_UNSAFE`、`DEBUG_CONTROL`、`unknown`。10–21 的精确字段集合保持原样，不能携带此字段。阻断时必须同时报告 `visualSampleOutcome=NO_SAMPLE`、`visualSampleCallbackMs=-1`、`millisSincePictureVerified=-1`；未采集像素不能当作画面正常或静止。

BA001、`Build.HARDWARE=amlogic`、API30 的真机开／关／再开对照中，开启 PixelCopy 时每次抓帧均伴随 Mali `DMA_BUF_NOT_MAPPED / TRANSLATION_FAULT / READ`，关闭后该错误消失。此设备组合在全部构建变体中禁止原生 SurfaceView 和 Window 的诊断 PixelCopy，检查发生在宿主访问、位图分配和原生调用之前。其他设备采集行为保持原样，播放、解码与音画时间戳采集继续运行。固件变更后需在同设备重新验证读取安全性，才能解除保护。

该对照确认抓帧触发 GPU 错误，未证明历史 GL 内存不足是物理内存耗尽或具体分配器泄漏。Android11 的 PixelCopy 原生读取可同步等待；小目标位图和 Java 回调超时不能限制驱动工作量，Window 回退也不构成安全的 CPU 读取路径。
