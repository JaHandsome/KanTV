# 枢纽客户端（HUB CONTRACT v1）实现说明

本文件记录 kantv 客户端侧「枢纽」的实现边界、复用关系、验收结果与已知限制。
契约正文见 [`server/docs/HUB_CONTRACT_V1.md`](../server/docs/HUB_CONTRACT_V1.md)；任何契约变更登记见
[`server/docs/HUB_CONTRACT_CHANGELOG.md`](../server/docs/HUB_CONTRACT_CHANGELOG.md)。

## 1. 三个通道与落地位置

| 通道 | 契约 | 客户端落地 |
|---|---|---|
| ① 投递 | §3 | `catalog/HubSubscriptionClient.kt`（GET + If-None-Match）→ `catalog/HubSubscriptionParser.kt`（含 `signed_params` / `drm_uuid`，**只消费服务端 `url_key`**）→ `catalog/HubSubscriptionCoordinator.kt` → `catalog/CatalogRepository.applyHubSubscription` |
| ② 回流 | §4 | `diagnostics/PlaybackFeedbackCollector.kt`（采集 + `notices` 编码）→ `diagnostics/PlaybackFeedbackUploader.kt`（results/notices 队列 + POST + 节流 + Worker） |
| ③ 能力协商 | §5 | `catalog/HubModels.kt` 的 `HubCaps`、`HubRouteType`、`HubNotice`（封闭集合）；`hub_schema` 版本闸门在 `catalog/HubRefreshPolicy.kt` |

线路身份的映射在 `playback/HubRouteSelector.kt` 与 `playback/ClearKeyPlaybackSupport.kt`；
本机主键与身份键规则收敛在 `catalog/HubChannelIdentity.kt`。

## 2. 复用的既有资产（未重写）

| 既有资产 | 本次如何使用 |
|---|---|
| `catalog/PlaylistRefreshScheduler/Worker` | 枢纽成为**首选来源**，仍沿用「失败保留上次成功版本」的语义 |
| `catalog/CatalogRepository` | 新增 `applyHubSubscription`，复用既有的 owner/source 合并机制 |
| `data/M3uPlaylistImporter` | 新增可选的服务端别名表注入；命中即按 `channel_id` 归因，未命中行为不变 |
| `playback/AdaptivePlaybackEngine` | **未改动**其已定型结论（翡翠台 Media3 / TVB 财经 VLC）；VLC 兜底仍由它执行 |
| `OfficialWebLiveActivity` / `SupportedWebLivePage` | 就是 `web-embed` 与「本地官网内嵌目录」的执行者 |
| `diagnostics/DiagnosticUploadWorker` | token 注入与 WorkManager 模式照抄；回流独立成 Worker，避免互相阻塞 |
| `diagnostics/StallDetector` | 停帧信号复用：`PlaybackUiState.Recovering`（由既有帧进度门驱动）等价于一次停帧 |
| `update/LanUpdateClient` | 佐证 `192.168.1.10` 已可达；端点/token 的构建期注入模式一致 |

## 3. url_key 向量与强制门禁（v1.1 §2.5）

v1.1 要求：服务端提供 `GET /api/hub/v1/urlkey-vectors`（**≥40 组**），客户端逐条断言自身实现；
**不一致即视为未通过，禁止上线**。同时 **枢纽链路上客户端不得自行计算 `url_key`**
（`routes[].url_key` 由服务端保证非空）。

### 3.1 门禁命令（契约正文或 `catalog/UrlKey.kt` 变更后必跑）

```bash
# 1) 权威向量（含真实地址 → 只落 /tmp，不入库）
curl -s 'http://192.168.1.10:8420/api/hub/v1/urlkey-vectors' > /tmp/server_vectors.json
# 2) 真实订阅 payload：用于取服务端当前的 signed_params（本地实现的名单必须来自服务端）
curl -s 'http://192.168.1.10:8420/api/hub/v1/subscription?client=kantv&schema=1&caps=hls-ts,hls-aes128,flv,dash-clearkey,web-embed' > /tmp/hub_payload.json
# 3) 门禁：必须 0 失败
./gradlew :app:testDebugUnitTest --tests '*UrlKeyTest*' \
  -Dkantv.urlkey.vectors=/tmp/server_vectors.json \
  -Dkantv.hub.payload=/tmp/hub_payload.json
# 4) 真实 payload 冒烟（不能替代第 3 步）
./gradlew :app:testDebugUnitTest --tests '*HubServerPayloadSmokeTest*' -Dkantv.hub.payload=/tmp/hub_payload.json
```

> ⚠️ 若第 1 步返回 `404`，说明服务端尚未落地该端点；此时第 3 步会退化为仓库内的**脱敏回归基线**
> （`urlkey_vectors.json` + `urlkey_signed_params.json`，54 条 / 名单取自契约 §2.2 记录的服务端配置）。
> 基线**不是**权威；端点上线的第一件事就是复跑第 3 步。

### 3.2 修前 / 修后对照（v1 → v1.1）

`QueryOrder` 与排序分支已按 §2.4 **删除**。对照可用独立的 v1 算法模拟器复现：

```bash
python3 tools/legacy_urlkey_v1_reference.py app/src/test/resources/urlkey_vectors.json
# vectors=54 v1_mismatched=29 (53.7%)   ← 修前
./gradlew :app:testDebugUnitTest --tests '*UrlKeyTest*'   # 修后：0 失败
```

v1 的四处错误（均由本轮单测锁死）：

| # | v1 行为 | v1.1 正确行为 |
|---|---|---|
| 1 | query **按 key 字典序排序** | **不排序**（§2.4 明确撤回；排序会改变服务端 `url_hash`） |
| 2 | 参数名**后缀匹配**（`hdnts`、`__ts`、`signal` 被误删） | **精确匹配**（小写后相等） |
| 3 | `kept` 为空时无条件丢 `?`（尾随 `?`、空参数段丢失） | **`hit` 不变量**：零命中时逐字节原样返回 |
| 4 | 名单**硬编码**、无 host 作用域；authority 只小写 host | 名单**由服务端 `signed_params` 下发**；authority **整体小写**；host 作用域按 §2.2 |

### 3.3 非枢纽来源的本地计算

`UrlKey.urlKey(url, signedParams)` 只用于**非枢纽来源**（如用户自建 M3U）。
`signedParams` 必须来自服务端 payload；**缺失即视为本地计算不可用**，绝不回落到硬编码名单。
重新生成脱敏基线：

```bash
python3 tools/generate_urlkey_vectors.py > app/src/test/resources/urlkey_vectors.json
python3 tools/generate_urlkey_vectors.py --signed-params > app/src/test/resources/urlkey_signed_params.json
```

## 4. 语义决定与解释

### 4.1 「内置清单降级为 fallback」（T8）的落地方式

- asset 文件保留，`.gitignore` 例外不变，**不删除任何频道条目**。
- 一旦枢纽订阅成功，`applyHubSubscription` 会把来源 owner 中的
  `apk-bundled-default` 一并移除：内置清单因此不再参与目录，只在
  「从未成功拉取过服务端订阅」时被播种（`HubRefreshPolicy.shouldSeedBundledFallback`）。
- 这会让「只能由内置清单提供、且服务端未投递」的频道从目录中消失。这与
  「不再是主来源」一致，但确实是有用户可感知的变化，故在此显式记录。

### 4.2 route 永不推断

`mimeType` 由**服务端声明的 route** 决定（`HubRouteSelector.mimeTypeFor`），
不使用 URL 后缀。封闭集合外的 route（如 `dash-mpegts`）记为 `UNKNOWN` 并丢弃该线路，
不猜测、不降级使用。

### 4.3 ClearKey 的两个源码级坑

`Media3PlaybackEngine` 只在 `request.drm != null` 时走新的 DRM 路径：

1. `DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(<uuid>, FrameworkMediaDrm.DEFAULT_PROVIDER)`
   —— 必须**显式**指定 uuid（builder 默认是 Widevine，会黑屏）。
   uuid 取值：服务端 `routes[].drm_uuid`（v1.1 加法字段）优先，缺失回落 `C.CLEARKEY_UUID`。
2. player 由 `ExoPlayer.Builder(context, hubMediaSourceFactory)` 构造，且
   `hubMediaSourceFactory.setDrmSessionManagerProvider { … }`；DRM 内容用
   `player.setMediaItem(...)`（`setMediaSource` 会绕过 provider）。
3. 非 DRM 内容仍走原有 `setMediaSource` + HLS 专用 extractor 路径，避免影响现有播放质量。

这两个坑的判定被做成纯值对象，可在 JVM 单测里回归（`HubRouteSelectorTest`）。

### 4.4 播放中不应用（契约 §6）

`PlaybackActivityFlag` 是主线程与后台 Worker 之间的唯一桥梁；Worker 在播放中拉到新订阅时
记录为 `Deferred`（缓存 payload / etag，但不重建目录、不推进已应用水位、不写别名表），
播放停止后由前台调用 `applyDeferredIfAny` 补应用。

### 4.5 归因纪律（v1.1 §2.5 / §4）

- 枢纽链路**只消费**服务端 `routes[].url_key`；为空即 `attributable = false`，
  该线路仍可播放，但**不产生回流记录**（`PlaybackRequest.hubUrlKey` 为 null，
  `PlaybackFeedbackCollector` 直接跳过）。
- 本地 `UrlKey` 只服务**非枢纽来源**，且名单必须来自服务端 `signed_params`；
  名单缺失时本地计算整体不可用（不回落硬编码）。
- 协议级事件走 §4 `notices[]`（`schema_unsupported` 等），不再受「results 必须有非空 channel_id」限制。

## 5. v1.1 状态与遗留（全部为服务端侧未落地项）

以下 1–3 项**不是客户端缺陷**，而是服务端本轮修复尚未上线导致的验证盲区；端点上线的第一件事是复跑门禁。

1. **`GET /api/hub/v1/urlkey-vectors` 仍返回 `404`**（实测 2026-09-11）。
   因此权威向量门禁暂退化为仓库内的脱敏回归基线（54 条）；修前/修后对照见 §3.2。
2. **订阅响应仍无 `signed_params`**（实测 `signedParams=false`），
   也无法覆盖签名参数路径 —— 这正是审查指出的「门禁与 bug 同时被挡住」。
   客户端已按 v1.1 实现消费逻辑（缺失即本地计算不可用），单测用脱敏基线覆盖该路径。
3. **订阅响应仍无 `drm_uuid`、仍不投递 `dash-clearkey` / `web-embed` route，也仍不下发 `fallback`**
   （实测 `caps=dash-clearkey` → 0 条；103/103 频道无 fallback）。
   因此 ClearKey 真机出画与 fallback 第 ① 步仍无验证对象；
   兜底实际只剩契约 §3 的第 ② 步（客户端本地官网内嵌目录），已实现。
4. **§1.1 步骤 3 的 t2s 覆盖有限。** `TraditionalToSimplified` 是纯 Kotlin 字符级映射（142 条，
   覆盖频道/栏目/省市词汇）。v1.1 已明确「覆盖面差异须登记契约变更，不得单方面扩大映射」。

## 6. P1 待办（本次不做）

- 设备维度能力上报：`device` → `caps` 自动推导，而不是硬编码 `HubCaps.declared`。
- 能力自动探测：真实试播一次 `dash-clearkey` 成功后再声明该 cap。
- 用枢纽 `groups` 定义 UI 分组（本次沿用既有分组展示）。
- 两端共享一份 t2s 表（或改用 ICU），消除 §1.1 步骤 3 的覆盖差异。
- 回流采集对齐 `StallDetector` 的原始信号（本次用 `Recovering` 状态近似停帧）。
- 回流队列上限与老化策略（当前为 FIFO + results 批量上限 12 / notices 上限 8）。
- MPD 文本注入 ClearKey 的降级方案（本次只实现内联 `clearkey`）。
- 通用 `web-embed` 地址的容器放开。

## 7. 验收执行记录

本轮 v1.1 修复的 9 条验收与上一轮 P0 交付均已通过（两份一次性验收记录已于 2026-09-25 清理）。
