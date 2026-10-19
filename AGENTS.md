# Repository Guidelines

## Project Structure & Module Organization

KanTV is a single-module Android TV application. Production Kotlin code lives in `app/src/main/java/com/kantv/app`, grouped by concerns such as `catalog/`, `epg/`, `playback/`, and `diagnostics/`. Android resources are under `app/src/main/res`; distributable, non-sensitive assets belong in `app/src/main/assets`.

`server/` is the iptv-nas list & hub service (Python 3 standard library only, zero pip deps) that runs in a Docker container on the home NAS (port 8420) and feeds this app subscriptions/lineups and receives playback feedback. It moved into this repo on 2026-09-25; its code of record is the NAS production deployment. Server tests run offline with `python3 server/tests/run_offline.py`. The frozen two-side contract v1.1 lives at `server/docs/HUB_CONTRACT_V1.md` (single canonical copy since 2026-09-25; `docs/HUB_CONTRACT_V1.md` is now just a pointer). Register changes per `server/docs/HUB_CONTRACT_CHANGELOG.md`. Server runtime data and credentials (`server/data/`, `server/config/settings.json`, `server/config/hub_client_token.txt`, signed-URL artifacts) never enter the repo; see the ignore rules and `server/AGENTS.md`.

Local unit tests mirror the production packages in `app/src/test/java`. Variant-specific code is isolated in `app/src/debug`, `app/src/diagnostic`, `app/src/release`, `app/src/tvAcceptance`, and `app/src/tvbGeckoProbe`. Keep product decisions and validation records in `docs/`, browser-only interaction samples in `prototype/`, and repeatable analysis utilities in `tools/`. `reference/` and `.tools/` contain local research material and are intentionally ignored.

## Build, Test, and Development Commands

- `./gradlew :app:testDebugUnitTest` runs the JUnit 4 unit-test suite.
- `./gradlew :app:assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:lintDebug` runs Android Lint against the debug variant.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` is the standard pre-PR check.
- Hub contract gate (required whenever `server/docs/HUB_CONTRACT_V1.md` §2 or `catalog/UrlKey.kt` changes):
  fetch the server's authoritative vectors and the `signed_params` they were produced with, then run
  `./gradlew :app:testDebugUnitTest --tests '*UrlKeyTest*' -Dkantv.urlkey.vectors=/tmp/server_vectors.json -Dkantv.hub.payload=/tmp/hub_payload.json`.
  0 failures is the acceptance condition; the vector files contain real URLs, so they stay in `/tmp`.
- Hub live smoke (run before reporting hub work as done):
  `./gradlew :app:testDebugUnitTest --tests '*HubServerPayloadSmokeTest*' -Dkantv.hub.payload=/tmp/hub_payload.json`
  It does not replace the vector gate: live routes are currently all zero-hit shapes.

Use JDK 17. The Gradle wrapper supplies the required Gradle version. Diagnostic builds may read `KANTV_DIAGNOSTIC_ENDPOINT` and `KANTV_DIAGNOSTIC_TOKEN`; never hard-code either value.

## Coding Style & Naming Conventions

Follow the official Kotlin style configured in `gradle.properties`: four-space indentation, trailing commas in multiline declarations, and focused files. Use `PascalCase` for classes and objects, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Keep playback policy and state transitions testable outside Android UI classes.

## Testing Guidelines

Name test files `*Test.kt` and test methods by observable behavior, for example `staleErrorCannotReplaceNewChannelState`. Add regression tests for playback lifecycle, source fallback, parsing, persistence, and privacy changes. A green emulator build does not prove TV compatibility; report unit tests, APK build, emulator checks, and each real-device check separately.

For a suspected regression, first run the pre-change build as a control on the *same* environment before digging into environment-specific hypotheses (simulator fingerprint, UA, codec, etc.). The 2026-09-28 央视频 case was settled by exactly such a control — the pre-refactor build failed identically on the same emulator — after hours of speculation without one.

## 已验收的播放稳定基线

电视真机验收由用户负责，助手不主动安排、催验或将其列为助手未收尾任务；用户反馈问题时，查看已有日志和必要的单次、限量状态排查，不增加监测负担。分工细则见 [播放稳定性约定](docs/PLAYBACK_STABILITY.md)，以下基线、回滚和发布授权边界继续适用。

用户于 2026-10-14 将 **0.2.40（versionCode 53，com.kantv.app）** 固定为播放稳定基线：M3U 原生播放逻辑来自 **0.2.37（50）**，保留当时最新且正常的 Gecko，正式包重诊断关闭。用户已在 **5X** 实际验收通过；这不代表其他设备、所有频道或音画偏移的自动测量已通过。基线身份、产物哈希、源码归档和后续验证要求统一见 [播放稳定性约定](docs/PLAYBACK_STABILITY.md)；原始签名包、源码归档与家庭验收原件私有保留。

- 修改 M3U 或 Gecko 播放行为、缓冲、音频输出、解码/渲染、HLS 代理、换源与恢复、播放生命周期、相关依赖或监测调度前，先在同一 **5X、频道、实际线路** 上运行基线对照。记录版本/包哈希、线路指纹、启动周期、输出设置、网络条件和操作步骤；只保存脱敏证据。基线异常时先重新判断环境和来源，不直接归因候选代码。
- 候选包重复相同操作与播放观察窗口。实际线路改变、盒子重启或输出/网络条件变化时不能当作有效同条件对照；记录差异并重新验证。覆盖本次影响的首次播放、持续播放、换台或前后台恢复，至少完整越过已知故障窗口，不以短暂出声/出画替代持续播放检查。
- 原始签名 APK 是正式对照产物。不得覆盖或删除基线 APK、源码归档与清单，也不得清空订阅、频道或应用数据来强行降级。如果安装限制必须重建对照，仅允许基于归档源码调整安装/发布编号和既有构建环境注入，播放源码、依赖、Gecko 和监测开关必须保持一致并核对哈希；明确标记为“重建对照包”，不能称为原始基线 APK。
- **不得增加播放监测负担。** 正式包保持 `KANTV_DIAGNOSTICS=false`；保留基线已有的必要错误处理与低频播放保护。不得新增或恢复持续采集器、提高采样频率、逐帧音画配对、持续日志拉取、周期性 PSS/全 TS 扫描、PixelCopy/GPU 画面读回或性能追踪来代替真实验收。诊断仅使用已有错误信息和必要的单次、限量状态读取；不得因工具或 Skill 建议自行放宽此约束。
- 每次播放变更分别报告本地检查、基线/候选真实设备对照和实际播放验收。软件时间戳、构建成功、HTTP 下载、自动检查与播放器存活不等于音画同步验收。播放候选未完成对照与实际验收前不得替换家庭稳定更新入口；本地准备、隔离验证和保留回滚包可继续执行。
- 新版本号、构建、CI 或短时运行成功不会自动更新此基线。只有用户明确验收并要求替换稳定基线后，才新增基线记录并更新当前指针；旧版归档与验收证据必须保留。

## Commit & Pull Request Guidelines

History uses Conventional Commit-style prefixes such as `feat:` and `fix:`. Write a short imperative subject describing one logical change. PRs should explain the user-visible result, list exact commands run, link the relevant issue or requirement, and include screenshots for UI changes. Call out untested devices or playback paths explicitly.

## Security & Configuration

Do not commit playlists, stream URLs, request headers, credentials, diagnostic tokens, APKs, or `app/src/main/assets/bundled-default/`. Logs, screenshots, fixtures, and reports must remain redacted. Hub artifacts follow the same rule: authoritative `url_key` vectors and live subscription payloads contain real URLs, so they stay outside the repo and are injected through `-Dkantv.urlkey.vectors` / `-Dkantv.hub.payload`; only redacted fixtures may be committed.

**Credentials stay with the tools.** Whenever a task needs a credential (Keychain items, NAS tokens, `.env*`, registry logins, API keys), let the tool perform the authenticated action itself instead of extracting the secret into the agent's own view. The test is one question: "does this put the secret in my hands?" If yes, it is out of bounds. Text an agent can see enters the session context and is sent with model requests, and a leaked credential cannot be un-leaked. The standing Home LAN authorization below is the only existing exception; any further exception needs explicit, per-incident approval, must read once, passes the value only through the process environment, and never prints, logs, or writes it anywhere.

GitHub 仓库为 `JaHandsome/KanTV`。拟公开历史从经过隐私核对的通用快照开始，旧家庭历史、准备阶段旧二进制与稳定标签仅在私有归档保留，禁止重新推送旧引用，不使用 `push --all` 或 `--mirror`。本机可保留私有历史引用；只推送明确审核的目标分支。历史 v53 文件哈希见 `docs/PLAYBACK_STABLE_SOURCE_V53.json`；它描述历史基线，不表示当前开发树相同。发布前运行 `python3 tools/check_public_tree.py` 和 `python3 tools/check_public_history.py`；历史检查必须使用完整历史，不能通过浅克隆、ignore 或替换引用隐藏旧内容。独立拟公开镜像另外运行历史检查的 `--all`。默认 FFmpeg 制品及配套源码由 `app/libs/media3-ffmpeg-provenance.properties` 绑定，禁止回退到私有旧库或把其重新加入公开历史。

公开历史的 author 和 committer 使用 GitHub noreply 邮箱（`@users.noreply.github.com` 或 GitHub 的 `noreply@github.com`）。本项目自动提交显式使用 `JaHandsome <JaHandsome@users.noreply.github.com>`，不得继承全局个人邮箱；历史门禁同时核对全部祖先的两类邮箱，拒绝时只输出提交 hash，不打印地址。未来有意保留其他公开贡献者邮箱时，须先明确其公开意图并调整此策略，不能通过跳过门禁发布。

## Deployment Authorization

Public source preparation does not authorize a deployment, token read, credential rotation, public endpoint exposure or stable-entry switch. Use only the current user's explicit deployment scope. Keep credentials with authenticated tools and process environments; never echo or commit them. Preserve rollback artifacts and complete real-device acceptance separately from builds and downloads.
