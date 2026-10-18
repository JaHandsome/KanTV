# 枢纽契约 HUB CONTRACT v1.1（冻结）

> 本契约是两个项目唯一的耦合面。除本契约定义的三个通道外，两端不得相互假设任何内部实现。
> v1.1 相对 v1 的变更见 `HUB_CONTRACT_CHANGELOG.md`；v1.1 正文本身即为当前权威，无需再读 CHANGELOG 才能正确实现。

## 0. 术语

| 术语 | 定义 | 权威方 |
|---|---|---|
| channel_id | 频道的稳定唯一标识 | 服务端 |
| url_key | 线路的稳定唯一标识 | 服务端 |
| route | 一条线路的播放路线类型 | 服务端 |
| caps | 客户端声明的容器/加密能力集合 | 客户端 |
| signed_params | 时效签名参数名单（两级） | 服务端 |

## 1. 频道身份

`channel_id` 即服务端 `config/channels.json` 中的目标键（channel_key）。

- **本项目当前实现为简体**（例：`无线新闻`、`TVB翡翠台`、`TVB明珠台`）。繁体写法由 `aliases` 覆盖。
- 客户端**不得假设** `channel_id` 的繁简形态或命名规则，**一律以服务端下发的 `channels[].channel_id` 为准**。
- 服务端投递时必须为每个频道提供 `channel_id` 与 `display`。
- 客户端内部一律以 `channel_id` 为主键；**禁止以显示名作为主键**。
- 客户端遇到无法归因的频道，`channel_id` 记为 `null`；该频道仍可播放，但不参与回流。

### 1.1 别名归一化（两端必须同算法）

给定任意频道名 `n`：

1. `n` 精确命中 `aliases[].name` → 返回其 `channel_id`
2. `n` 精确命中某频道的 `display` 或 `channel_id` → 返回该 `channel_id`
3. 将 `n` 做繁转简（t2s）后重复步骤 1–2 → 命中则返回
4. 均未命中 → 返回 `null`

服务端投递时附带完整的 `aliases` 数组（`name -> channel_id`）。客户端必须使用服务端下发的这一份，**不得本地维护第二份别名表**。

> t2s 由客户端本地实现（字符级即可，不做完整转换）；若发现两端 t2s 覆盖面不一致导致归因差异，应登记为契约变更，不得单方面扩大映射。

> 已知缺口（2026-09-27 登记，QA 审计 P1-1）：本算法对带画质/备用噪声后缀的名字
> （如 `翡翠台高清`、`TVB翡翠台（备用）`、`凤凰中文[1920*1080]`）返回 `null`，
> 而服务端内部归一（`parser.normalize_channel`）会先剥离噪声再命中 ⇒ 同一名字两端归因可能不同。
> 后果有界：客户端本地 M3U 的此类线路 `channel_id=null`（可播放但不参与回流），不造成播放故障。
> 修正（§1.1 增加去噪步骤，或服务端下发含去噪变体的归一表）属**语义变更**，须提升 `hub_schema`，
> 留待下一 schema 窗口处理。

## 2. 线路身份 url_key

    url_key = sha1( strip_signed_params( normalize_url( url ), signed_params ) )   # 小写十六进制，40 字符

**服务端实现为权威。** 本节给出可逐字节复现的精确定义；客户端实现必须与之完全一致。

### 2.1 normalize_url(url)

1. 去掉首尾空白（strip）。
2. 结果为空 → 返回空串。
3. 截断到第一个 `#` 之前（丢弃 fragment）。
4. 若匹配 `^(https?)://([^/]+)(.*)$`（**忽略大小写**；`[^/]+` 要求至少一个字符）：
   返回 `scheme小写 + "://" + 第2组整体小写 + 第3组原样`。
   —— 第2组是 **authority 整体**（含 userinfo 与 port），**整体小写**；**path 与 query 原样保留：不重排、不重编码、不去字符**。
5. 否则（非 http(s)、或 `://` 后无字符）：返回第 3 步的结果**原样**。

### 2.2 signed_params（时效签名参数名单）

名单**由服务端下发**（§3 `signed_params`），两级合并：

- **全局**：`signed_params.global[]`
- **host 作用域**：遍历 `signed_params.by_host`（键 `k`）。取

      host = 从【原始 url】中匹配 `https?://([^/]+)`（**区分大小写**）得到的第 1 组
             → 再 .lower() → 按第一个 ':' 截断以去掉端口

  匹配失败时取 `url` 的前 30 个字符。**生效条件**：`host == k` 或 `host.endswith("." + k)`。
- 合并结果去重后的**小写参数名集合**。

  > 两个**已知且双方一致**的陷阱（ARCH 审计 P2-2；不得"修正"，向量已覆盖）：
  > ① host 按**第一个 `:`** 截断 —— 含 userinfo 的 URL（`http://u:p@edgeware.tvb.com/…`）
  >   host 会被截为 `u`，host 作用域名单**失效**（`sig`/`ts` 不剥离）；
  > ② host 正则 `https?://` **区分大小写** —— 大写 scheme（`HTTPS://…`）失配时 host 退化为
  >   `url[:30]`，同样丢失 host 作用域剥离 ⇒ 该类 URL 的 `url_key` 随签名轮换每天变化。
  > 真实 TVB 清单均为小写且无 userinfo，当前低风险。
- **`fingerprint`（v1.1 加法字段，2026-09-26）**：生效名单的稳定指纹
  （sha1 前 12 位小写 hex；规范形 = 全局名单去重小写排序 + host 键去空白小写、
  每 host 参数去重小写排序、键字典序）。名单任何生效变更（增删参数、大小写/空白、
  host 键）都会改变指纹；书写顺序与 JSON 键序不会改变指纹。
  语义：**指纹变化 = 服务端已按新名单全表重算 `url_key`**（陈旧 key 已消解后投递）。
  客户端不得用指纹重算 url_key，仅作「服务端 url_key 语义已切换」的信号留痕；
  指纹在响应体内，随名单变化即改变 ETag，下一次投递拉取必获新 payload（自愈）。

> 服务端当前配置：全局 `expires, expire, expires_at, token, securitykey, sign, signature, msisdn`；
> `edgeware.tvb.com` 追加 `sig, ts, c, ext_start_limit, int_start_limit`。

### 2.3 strip_signed_params(url, signed_params)

令 `u = normalize_url(url)`。

1. `u` 为空、或**不含 `?`** → 返回 `u`
2. 以第一个 `?` 切分为 `base` 与 `query`
3. 若 `base`（小写后）不以 `http://` / `https://` 开头 → 返回 `u`
4. 名单为空 → 返回 `u`
5. 按 `&` 切分 `query`，逐段处理：
   - 段为空字符串 → 丢弃
   - **参数名** = 段中第一个 `=` 之前的部分，**小写后精确命中**名单 → 记 `hit = true`，丢弃该段
   - 其余 → **原样保留**（顺序、编码、大小写均不变）
6. **若 `hit == false` → 返回 `u` 本身（逐字节，含 `?` 与其中的空段）。** ← 关键不变量
7. 否则：`kept` 非空 → 返回 `base + "?" + kept.join("&")`；`kept` 为空 → 返回 `base`（**不带 `?`**）

### 2.4 禁止项（v1 曾写错，v1.1 撤回）

- ❌ **不得对 query 排序**。`normalize_url` 与 `url_hash`（`sources` 表唯一索引键）共用；排序会改变 17873 行的 `url_hash`，导致下一轮发现把**同一物理源**重复插入。v1 曾误写"按 key 字典序排序"，实测差异 **18.0%（3221/17873）**。
- ❌ v1 曾要求"query 顺序被打乱 → url_key 必须相同"。该要求与上一条冲突，**已撤回**。
- 若将来确需顺序无关，须提升 `hub_schema` 并新增 `url_key_v2` + 全量重建（破坏性变更，本版不做）。
- **先例（2026-09-26，见 CHANGELOG）**：v1.1 对既有字段的语义订正——§1 `channel_id` 繁简、§2.1/§2.3 `url_key` 算法、§5.1 `caps` 缺省、§3 `groups[].id`——均属破坏性变更，按本条款精神将 `hub_schema` 提升至 `2` 追认。

### 2.5 一致性校验（强制）

- 服务端提供只读端点 `GET /api/hub/v1/urlkey-vectors`，返回 `[{ "url": …, "url_key": … }]`，**≥40 组**。
- 客户端必须用该端点内容逐条断言自身实现；**不一致即视为未通过，禁止上线**。
- 客户端在**任一**情况下不得跳过断言：向量端点返回非 200 / 元素缺 `url_key` 字段 /
  任一向量不符 ⇒ **一律视为未通过、禁止上线**（fail-closed，ARCH 审计 P1-4/B4）。
- `routes[].url_key` 由服务端保证**非空**（见 §3）。客户端**不得**在枢纽链路上自行计算 `url_key`。

## 3. 通道①：投递（服务端 → 客户端）

    GET /api/hub/v1/subscription
        ?client=kantv
        &schema=2
        &caps=hls-ts,hls-aes128,flv,dash-clearkey,web-embed
        &app=0.1.8
        &device=tcl-q10m-pro

- 只读，**无需鉴权**；服务端必须保持只监听局域网。
- 响应 `Content-Type: application/json`，带 `ETag` 与 `X-Hub-Seq`。
- 客户端使用 `If-None-Match`；返回 `304` 时不得重建目录。

响应体：

```json
{
  "hub_schema": 2,
  "seq": 12345,
  "generated_at": "2026-09-11T19:00:00+08:00",
  "server": { "app": "1.3.0", "caps_supported": ["hls-ts","hls-aes128","flv","dash-clearkey","web-embed"] },
  "groups": [ { "id": "港澳台", "title": "🌊港澳台", "order": 3 } ],
  "signed_params": {
    "global": ["expires","expire","expires_at","token","securitykey","sign","signature","msisdn"],
    "by_host": { "edgeware.tvb.com": ["sig","ts","c","ext_start_limit","int_start_limit"] },
    "fingerprint": "605077873046"
  },
  "channels": [
    {
      "channel_id": "无线新闻",
      "display": "无线新闻",
      "group_id": "港澳台",
      "logo": "https://…png",
      "epg_id": "无线新闻",
      "coverage": "ok",
      "routes": [
        {
          "route": "dash-clearkey",
          "url": "https://…/index.mpd?token=…",
          "url_key": "<40 位，必填非空>",
          "container": "dash",
          "encryption": "clearkey",
          "kid": "<hex，可空>",
          "clearkey": { "k": "<hex>", "kid": "<hex>" },
          "quality": null,
          "score": 92,
          "verified_at": "2026-09-11T18:30:00+08:00"
        }
      ]
    }
  ],
  "aliases": [ { "name": "無線新聞台", "channel_id": "无线新闻" } ]
}
```

> 注：示例不含 `drm_uuid` 与 `fallback` —— 两者当前**一律省略**（见下方规则），示例与实现对齐（ARCH 审计 P1-5）。

规则：

- `routes` 按 `score` 降序；服务端**只投递客户端 `caps` 覆盖的 route**。
- **`routes[].url_key` 必填且非空。** 服务端必须在投递前保证（缺失时现场补算；URL 本身为空则该行不投递）。
- `routes[].drm_uuid` **可选**，仅当 route 为 `dash-clearkey` 且服务端**已核实**该 MPD 的 `ContentProtection@schemeIdUri` 时提供；否则省略，客户端回落 Media3 的 `C.CLEARKEY_UUID`。（当前 TVB MPD 均不含 ClearKey `schemeIdUri`，故服务端**一律省略**。）**不得**把 Widevine/PlayReady 的 UUID 当作 `drm_uuid`。
- `groups[].id` **由服务端定义**（本项目为中文分组键），客户端不得假设其取值形态，只做透传与展示。
- `coverage` 取值 `ok | partial | missing | unsupported_for_client`。`missing` 时 `routes` 可为空。
- `quality`：**v1/v1.1 服务端不提供**，恒为 `null`。客户端不得依赖。
- `fallback` **可选**。服务端仅在**已知**该频道官网地址时才下发；否则省略。客户端在 `routes` 全部失败后，依次尝试：① 服务端下发的 `fallback`；② 客户端本地的官网内嵌目录。**现状（ARCH 审计 P2-5）**：v1.1 服务端**一律省略** `fallback`（官网目录未配置化），客户端本地目录亦未配置化 ⇒ 该兜底层当前**实际不存在**；字段保留为后续版本预留。
- `signed_params`：v1.1 新增的**加法字段**，供客户端实现本地 `url_key`（用于非枢纽来源的 M3U）；枢纽链路上客户端仍必须使用服务端下发的 `url_key`。
- 客户端**不得**依据 `url` 自行推断 route；一律以服务端 `route` 字段为准。

### 3.1 route 枚举（v1.1，封闭集合）

| route | 含义 | 期望客户端引擎 |
|---|---|---|
| `hls-ts` | HLS / MPEG-TS 直连 | Media3，VLC 兜底 |
| `hls-aes128` | HLS + AES-128 | Media3（原生支持） |
| `flv` | HTTP-FLV | Media3，VLC 兜底 |
| `dash-clearkey` | DASH + ClearKey | Media3（原生支持，需显式指定 ClearKey UUID） |
| `web-embed` | 官网网页内嵌 | 客户端 WebView 容器 |

`hls-sample-aes`、`dash-mpegts` 明确不属于本契约封闭集合，服务端不得投递。

**`web-embed` 当前为死枚举**（QA 审计 P2-9）：`fallback` 未配置化（见 §3），服务端不会产出该 route；保留在封闭集合中为后续版本预留，客户端声明该 cap 无副作用。

**`dash` 分支必须 fail-closed**：仅当 `container = dash` 且 `encryption ∈ {clearkey, cenc}` **且** `drm ∉ {widevine, playready, cenc-unknown}` 时才是 `dash-clearkey`；否则不投递。（`encryption='cenc'` 同时覆盖 ClearKey 与 Widevine/PlayReady，只看 `encryption` 会把 Widevine 投成 ClearKey ⇒ 客户端 `drm_error` 黑屏。）

## 4. 通道②：回流（客户端 → 服务端）

    POST /api/hub/v1/feedback
    Authorization: Bearer <hub_token>
    Content-Type: application/json

请求体：

```json
{
  "hub_schema": 2,
  "client": "kantv",
  "app": "0.1.8",
  "device": { "model": "TCL Q10M Pro", "android_api": 30, "abi": "arm64-v8a" },
  "session": "<uuid>",
  "reported_at": "2026-09-11T19:05:00+08:00",
  "notices": [ { "code": "schema_unsupported", "detail": "client supports 1, server sent 2" } ],
  "results": [
    {
      "channel_id": "TVB翡翠台",
      "url_key": "<40 位，必填非空>",
      "route": "hls-ts",
      "outcome": "ok",
      "engine": "media3",
      "first_frame_ms": 1820,
      "stalls": 0,
      "stall_seconds": 0,
      "play_seconds": 300,
      "fail_reason": null,
      "observed_at": "2026-09-11T19:00:00+08:00"
    }
  ]
}
```

- `outcome`：`ok | partial | fail`
- `fail_reason`：`no_frame | timeout | http_403 | http_404 | decode_error | drm_error | network | unknown`
- **`notices`（v1.1 新增，可选，加法字段）**：承载**协议级事件**，用于解决 v1 中"§5.2 要求上报但 §4 无字段承载"的结构性矛盾。
  - `code` 封闭集合：`schema_unsupported | caps_unknown | other`。本版仅定义 `schema_unsupported` 的语义；`caps_unknown` 与 `other` 为**预留占位** —— 客户端本版只需发 `schema_unsupported`，服务端对预留/未知 code 一律接受并计数、不解读（ARCH 审计 P2-4）。
  - 服务端**必须接受并计数**，不得因未知 `code` 返回 4xx
- 客户端**只上报 `url_key`（哈希），不上报原始 URL**，天然脱敏。
- `results[].url_key` **必填非空**。无法归因的结果（`channel_id` 为 `null`、或无 `url_key`）**不得上报**。
- 响应 `202 Accepted`：`{ "accepted": 12, "unknown_channel": 0, "unknown_url_key": 1 }`
- 服务端对未知 `channel_id` / `url_key` / `notices[].code` **必须接受并计数**，不得返回 4xx。

### 4.1 回流信号的地位（重要，防止引入新假阴性）

回流是**高置信度但低样本量**的信号。本版规则：

- 回流数据**只用于记录与只读聚合**，**不得**作为自动删除、降级或拉黑任何源的依据。
- 服务端提供 `GET /api/hub/v1/feedback/summary`：按 `channel_id` 聚合「探测判定 vs 真机结果」，输出冲突清单供人工复核。
- 既有纪律「失败不删除已有源」继续有效。

### 4.2 hub_token

- 由服务端生成（32–128 字符），写入其自身配置，**不得进入任何镜像层**。
- **仅**用于 `POST /api/hub/v1/feedback`；所有 `GET` 端点不需鉴权。
- 客户端通过构建期环境变量注入，**不得硬编码、不得提交仓库**。

## 5. 通道③：能力协商

### 5.1 caps 枚举（v1.1，封闭集合）

`hls-ts`、`hls-aes128`、`flv`、`dash-clearkey`、`web-embed`

- 客户端在 `GET` 请求的 `caps` 参数中声明（逗号分隔，可为子集）。
- **`caps` 缺省即视为「未声明任何能力」**：服务端取 **fail-closed** —— 所有频道 `coverage = "unsupported_for_client"`、`routes` 为空（响应仍是合法 JSON，`hub_schema` 照常返回）。客户端**必须**显式声明 `caps`。
- `caps=`（参数存在但值为**空串**，如 `?caps=`）**视同缺省**，与未携带 `caps` 参数完全同义（fail-closed，见上一条）。
- 服务端只投递被覆盖的 route。
- 当某频道在客户端 caps 下无任何可用 route，但库中存在线路 → `coverage = "unsupported_for_client"`；库中完全无线路 → `coverage = "missing"`。
- 服务端遇到未知 caps **必须忽略**（向前兼容），不得报错。

### 5.2 版本协商

- `hub_schema` 为整数，当前 `2`（2026-09-26 自 1 提升，见 CHANGELOG）。
- **语义映射**：`hub_schema = 1` 对应 **v1 语义**；`hub_schema = 2` 对应 **v1.1 语义**（本正文自 v1.1 起的全部订正归入 schema 2）。
- 服务端承诺：**同一 `hub_schema` 内只做加法**（可新增可选字段，不得删除或重命名字段）。
- **语义订正（对既有字段的破坏性/订正性修改）必须提升 `hub_schema`**，不得以「订正正文错误」为由留在原版本内（先例：v1.1 曾在 `hub_schema = 1` 内订正 `channel_id` 繁简、`url_key` 算法、`caps` 缺省、`groups[].id` 四处语义，违反本规则，自 `hub_schema = 2` 起追认纠正）。
- 客户端遇到 `hub_schema` 高于自身支持值时：**拒绝应用**、保留上一次成功结果、并通过 `notices` 上报 `{ "code": "schema_unsupported" }`。
- 服务端补充字段（`signed_params`、`drm_uuid`、`notices`、`accepted` 之外的统计字段等）均属合法加法，**不提升 `hub_schema`**。

## 6. 时效与失败处理

- 客户端拉取频率沿用每小时；服务端 `seq` 未变时可返回 `304`。
- 客户端**在播放中不得应用新订阅**。
- 客户端拉取失败时**必须保留上一次成功结果**，不得清空。
- 客户端首次启动无任何订阅时，使用内置 fallback 清单，并立即尝试拉取。
