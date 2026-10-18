# 枢纽契约变更记录（HUB CONTRACT CHANGELOG）

> **规则**：`docs/HUB_CONTRACT_V1.md` 是**冻结正文**，两端**不得单方面改写**。
> 任何变更都记在这里，并视为**破坏性变更**，必须两端同步后才能落地。
> 同一 `hub_schema` 内只允许**加法**（新增可选字段）；删除、重命名或语义替换必须提升 `hub_schema`。
> 破坏性变更在两端都落地前，客户端必须保持「拒绝应用新值、保留上一次成功结果」的行为。
> 客户端读到标"待两端冻结"的条目时，**以 `docs/urlkey_vectors.json` 的机器可读向量为准**
> （v1.1 §2.5 已把这条写进正文：向量不一致即视为未通过、禁止上线）。
>
> **条目模板（F7 起所有新条目必须使用；v1 时期的自由格式记录保留在本文第二节作历史证据）**：
>
> ```
> ### <日期> — <标题>（<变更类型>）
> - 变更类型：破坏性 | 加法 | 实现说明
> - 提出方：
> - 章节：
> - 变更前：
> - 变更后：
> - 客户端同步状态：
> - 服务端同步状态：
> - 备注：
> ```

---

## 一、变更登记（统一模板）

### 2026-09-27 — §5.1 澄清 caps= 空串视同缺省（实现说明，文档澄清不改语义）
- 变更类型：实现说明（纯文档澄清，**不改任何语义**；QA 审计 P2-8 / ARCH 审计 P2-1 合并清偿）
- 提出方：QA 审计 P2-8（`docs/archive/AUDIT_HUB_QA_2026-09-11.md` 项 6 措辞瑕疵）+ ARCH 审计 P2-1（`app/hub.py:2` docstring 版本号订正）
- 章节：§5.1（新增一条澄清 bullet）；`app/hub.py` 模块 docstring（v1 → v1.1）
- 变更前：§5.1 只写「`caps` 缺省即视为未声明任何能力」，未明写 `caps=`（存在但空串）
  的处置；`app/hub.py` docstring 仍写 "HUB CONTRACT v1 的服务端一半"
- 变更后：§5.1 明写「`caps=`（参数存在但值为空串）视同缺省，与未携带 caps 参数
  完全同义（fail-closed）」——与既有实现（`parse_caps('') == parse_caps(None)` == 空集合，
  fail-closed）逐字对齐，零行为变化；docstring 订正为 v1.1
- 客户端同步状态：无需同步（实现本已如此，仅正文追认）
- 服务端同步状态：无需同步（无代码行为变更）
- 备注：契约正文按惯例不升 `hub_schema`（纯澄清，无字段/语义变化）

### 2026-09-27 — 审计澄清批②：§1.1 缺口登记 / §2.2 陷阱注 / §2.5 fail-closed / §3 示例收紧 / §4 notices 边界（实现说明，不改语义）
- 变更类型：实现说明（纯文档澄清，**不改任何语义、不提升 `hub_schema`**）
- 提出方：ARCH/QA 审计（2026-09-11）剩余条目清偿：QA P1-1、ARCH P1-4(B4)、ARCH P2-2、
  ARCH P2-4、ARCH P2-5、ARCH P1-5(B5/B2)、QA P2-9
- 章节：§1.1 / §2.2 / §2.5 / §3（示例 + `drm_uuid` + `fallback` 规则）/ §3.1 / §4
- 变更前：见 `docs/archive/AUDIT_HUB_ARCH_2026-09-11.md` / `docs/archive/AUDIT_HUB_QA_2026-09-11.md` 对应条目
- 变更后：
  - §1.1 登记「带画质/备用噪声后缀的名字两端归因不一致」为**已知缺口**，修正留待下一
    schema 窗口（QA P1-1，有界：仅影响本地 M3U 噪声名的回流归因，不影响播放）
  - §2.2 补 userinfo / 大写 scheme 两个陷阱注；`tools/export_urlkey_vectors.py` 补 2 组向量
    （`http://u:p@edgeware.tvb.com/x.mpd?sig=A&ts=1`、`HTTPS://edgeware.tvb.com/x.mpd?sig=A&ts=1`）（ARCH P2-2）
  - §2.5 明写向量端点非 200 / 缺字段 / 任一向量不符 ⇒ 一律未通过、禁止上线（fail-closed）（ARCH P1-4/B4）
  - §3 示例删去 `drm_uuid`/`fallback`（实现一律省略，示例与实现对齐）；`drm_uuid` 规则补
    「不得把 Widevine/PlayReady UUID 当 `drm_uuid`」（ARCH P1-5/B5/B2）
  - §3 `fallback` 规则注明 v1.1 一律省略、该兜底层当前实际不存在（ARCH P2-5）
  - §3.1 注明 `web-embed` 当前为死枚举（QA P2-9）
  - §4 `notices.code` 注明 `caps_unknown`/`other` 为预留占位，客户端本版只需发
    `schema_unsupported`（ARCH P2-4）
- 客户端同步状态：无需同步（无语义变化；客户端 `UrlKey` 实现对新增 2 组向量已逐字节通过）
- 服务端同步状态：无需同步（无代码行为变更）
- 备注：正文 sha1 变为 `7e4d064d11dd7ef396d96eaf7fe171bbc3ced004`；HANDOFF 现行指针已同步

### 2026-09-27 — feedback 响应截断计数拆分（加法，服务端先落地）
- 变更类型：加法（`hub_schema` 保持 2，按 §5.2 不升版本；响应计数 dict 新增可选字段）
- 提出方：QA 审计 P2-2（`docs/archive/AUDIT_HUB_QA_2026-09-11.md` 项 13 / 复现清单）
- 章节：§4（feedback 响应计数）
- 变更前：响应计数 dict 的 `truncated` = results 截断数 + notices 截断数（合并语义），
  客户端无法区分两类超限
- 变更后：`truncated` **保持旧语义不变**（两者之和，旧客户端兼容）；新增
  `results_truncated` / `notices_truncated` 分别计数。仅响应 dict 加法，无落库变更
- 客户端同步状态：无需同步（旧客户端忽略新字段即可）
- 服务端同步状态：已实现（`app/hub.py record_feedback`；test_hub.py
  TestF6Notices 拆分断言）
- 备注：纯加法，不改变任何既有字段语义，不拒绝旧客户端请求

### 2026-09-26 — signed_params 新增 fingerprint 字段（加法，两端同步）
- 变更类型：加法（`hub_schema` 保持 2，按 §5.2 不升版本）
- 提出方：P0-2 方案 C 实施（url_key 双身份彻底去耦；裁决 QA/ARCH 审计分歧）
- 章节：§2.2（名单指纹定义）、§3（响应示例）
- 变更前：`signed_params` 仅 `{global[], by_host{}}`；名单变更后客户端无法感知
  服务端 `url_key` 语义已切换（服务端全表重算 url_key 后旧 payload 的 url_key 全成陈旧值）
- 变更后：`signed_params` 增加 `fingerprint`（sha1 前 12 位小写 hex，生效名单的
  稳定指纹；名单任何生效变更即变化，书写顺序/JSON 键序不变化）。指纹变化 =
  服务端已按新名单全表重算 `url_key`。客户端义务：仅解析与留痕，**不得**用指纹
  重算 url_key；指纹在响应体内，随名单变化即改变 ETag，下一次拉取必获新 payload。
  客户端未持久 url_key 归因缓存（payload 即服务端原文），故无清理动作。
- 客户端同步状态：已实现（HubSignedParams.fingerprint 解析 + 变化回调留痕 +
  锚点持久化，HubSubscriptionCoordinatorTest/HubSubscriptionParserTest）
- 服务端同步状态：已实现（`urlkey.signed_params_fingerprint` 与
  `schema.ensure_url_keys_fresh` 的 meta 锚点同算法同源；hub.py 投递；
  test_signed_params_fingerprint.py / test_hub.py F3）
- 备注：配套机制（不在契约内）：服务端启动与调度 tick 检测指纹变化即全表按当前
  名单重算 url_key（含撞键消解，复用 v5 裁定 4/5 策略）；ingest 查重增加
  url_hash 兜底匹配使陈旧 key 行就地自愈。部署后首次启动将触发一次全表重建
  （幂等，指纹不变则跳过）。

### 2026-09-11 — 契约升级 v1 → v1.1（破坏性，两端同步）
- 变更类型：破坏性（订正 v1 正文错误）
- 提出方：第三方审查（D1–D9 + 缺口 A/B）
- 章节：§0 §1 §2 §3 §4 §5
- 变更前：v1 正文（繁体 channel_id、query 排序、后缀匹配、10 项最小名单、caps 缺省未定义、dash 分支无 drm 排除）
- 变更后：v1.1 正文
- 客户端同步状态：待同步
- 服务端同步状态：已实现
- 备注：新 sha1 基线 = `79236cbbd59a1bd184312609bf5fb5f9b80c035c`

  **v1.1 相对 v1 的实质变化（逐条可核）**：

  | # | v1 原文 | v1.1 |
  |---|---|---|
  | 1 | §1 "channel_id 以繁体为准（例 `無線新聞台`、`翡翠台`）" | §1 改为"即 config 目标键；**本项目当前实现为简体**；客户端**不得假设繁简形态**，一律以服务端下发为准" |
  | 2 | §2 "query 参数按 key 字典序排序" | §2.1 明确 path/query **原样保留：不重排、不重编码、不去字符**；§2.4 **禁止项**列出"不得排序"及理由（`url_hash` 唯一索引） |
  | 3 | §2 "参数名后缀匹配" | §2.3 步骤5 "**小写后精确命中**" |
  | 4 | §2 最小名单 10 项（写死在正文） | §2.2 名单**由服务端下发**（§3 `signed_params`），两级合并（全局 + host 作用域），生效条件 `host == k` 或 `host.endswith("." + k)` |
  | 5 | §2 "query 顺序被打乱 → url_key 必须相同" | §2.4 **明确撤回**（与禁止排序冲突） |
  | 6 | §3 无 `signed_params` / `drm_uuid` | §3 新增两个加法字段；并明确 `routes[].url_key` **必填非空** |
  | 7 | §3 `quality` 有示例值、`fallback` 有示例 | §3 明确 `quality` **恒 null**（服务端不提供）、`fallback` **可选**（未知则省略） |
  | 8 | §4 无 `notices` | §4 新增 `notices[]`（协议级事件），解决"§5.2 要求上报但 §4 无字段承载"的结构性矛盾 |
  | 9 | §5.1 caps 缺省未定义 | §5.1 明确：**缺省即未声明任何能力**，服务端 fail-closed |
  | 10 | §3.1 dash 分支只看 `encryption` | §3.1 写入 fail-closed 条件：`drm ∉ {widevine, playready, cenc-unknown}` |
  | 11 | §2 只给公式、无端点 | §2.5 新增 `GET /api/hub/v1/urlkey-vectors`（只读，≥40 组），客户端必须逐条断言 |

- **回归证据（§2.4 撤回项的落地验证）**：`signed_url_params` **未加排序**；
  `ChannelParser.resolve_signed_params` / `normalize_url` / `url_hash` / `strip_signed_params`
  **本轮零改动**（`app/parser.py` md5 与上一轮部署值完全一致），既有全部 url_key 相关单测保持全绿。
  `tools/hub_urlkey_divergence.py` 可复跑复核：若哪天有人"顺手加排序"，生产库会有
  **3221/17873 行（18.0%）** 的 url_key 发生漂移。

### 2026-09-11 — 补充登记（2026-09-11 审计补登）
- 变更类型：实现说明（补齐上面 11 行表的**漏登项**，不改契约语义）
- 提出方：第三方审计（QA 严过关 / 架构师 高见远）
- 章节：§0 §1 §2 §3 §4 §5
- 变更前：上面 11 行表**没有虚报**（11 条全真），但**漏登**了下列 12 条实质变更
- 变更后：逐条补登如下（每条均可回溯到 v1 / v1.1 正文的逐字 diff）
- 客户端同步状态：见上表（v1.1 正文即权威）
- 服务端同步状态：已实现
- 备注：**diff 方法**——v1 = `提示词_服务端_iptv-nas.md` 第三节 ```markdown 块（sha1 `73aa0e0bf48573f1238e144005a871a33ff40cdb`）；v1.1 = `修复提示词_服务端_iptv-nas.md` 第三节 ```markdown 块（sha1 `79236cbbd59a1bd184312609bf5fb5f9b80c035c`）。⚠️ 提取陷阱：正文内含嵌套 ```json，必须取 **"## 四、" 之前的最后一条 ``` 围栏**作为结束（取第一条会截断）。

  | # | 章节 | v1 → v1.1（补登） | 与 11 行表的关系 |
  |---|---|---|---|
  | B1 | §0 术语表 | `url_key` 权威方 **`双方共算` → `服务端`**；并**新增术语行** `signed_params \| 时效签名参数名单（两级） \| 服务端` | **未登记**（本轮最关键漏登） |
  | B2 | 文档头 | 新增引用：v1.1 变更见 `HUB_CONTRACT_CHANGELOG.md`；**v1.1 正文本身即为当前权威，无需再读 CHANGELOG 才能正确实现** | **未登记** |
  | B3 | §1（别名/归一） | 新增 t2s 说明：t2s 由**客户端**本地实现（字符级即可）；若两端覆盖面不一致导致归因差异，**应登记为契约变更，不得单方面扩大映射** | **未登记** |
  | B4 | §2 公式 | `strip_signed_params(url)` → **`strip_signed_params(url, signed_params)`**（签名增加名单实参，与 §2.2/§2.3 配套） | 未登记（§11 行只讲了名单来源） |
  | B5 | §2.3 步骤 6 | 新增**关键不变量**：未命中任何签名参数时**必须返回 `u` 本身（逐字节，含 `?` 与其中的空段）** | **未登记** |
  | B6 | §3 规则 | **`groups[].id` 由服务端定义**（示例 `hk_tw` → `港澳台`、`order` 4 → 3）；客户端不得假设其取值形态，只做透传与展示 | **未登记**（D8 的正文落地） |
  | B7 | §3 示例 | `kid` 由 `<hex>` 标注为 **`<hex，可空>`** | **未登记**（示例层细化） |
  | B8 | §4 规则 | **`results[].url_key` 必填非空**；无法归因（`channel_id` 为 `null` 或无 `url_key`）**不得上报** | **未登记** |
  | B9 | §4 规则 | 未知 `notices[].code` 服务端**必须接受并计数**（不得返回 4xx） | 未登记（第 8 行只讲了 `notices` 字段存在） |
  | B10 | §4.2 | `hub_token` **不得进入任何镜像层** | **未登记**（安全条款） |
  | B11 | §5.1 | `coverage` 明确区分 **`missing`（库中完全无线路）** vs `unsupported_for_client`（有线路但被 caps 过滤） | 未登记（第 9 行只讲了 caps 缺省） |
  | B12 | §5.2 | 新增**加法条款**：服务端补充字段（`signed_params`/`drm_uuid`/`notices`/统计字段等）均属合法加法，**不提升 `hub_schema`** | **未登记** |

> 说明：B4–B7、B9、B11 属"实现细节/示例层"细化，B2 属阅读指引；**B1 / B3 / B6 / B8 / B10 / B12 为架构师逐字 diff 点名的漏登项**。上表 11 行与本表 12 行合并后，v1 → v1.1 的实质变更即**完整**。

### 2026-09-11 — 第三方审计（QA 严过关 / 架构师 高见远）：文档与工具一致性修复（实现说明）
- 变更类型：实现说明（不改变契约语义，仅修复文档/留档脚本的自相矛盾与写生产风险）
- 提出方：第三方审计（QA 严过关 / 架构师 高见远）
- 章节：文档与工具（`docs/HANDOFF.md`、`docs/HUB_CONTRACT_CHANGELOG.md`、`_hub_accept.sh`、`_hub_accept_v11.sh`）
- 变更前：
  1. `docs/HANDOFF.md` §9.3 契约 sha1 仍写 v1 的 `73aa0e0b…`，与 §0/§11 冲突；
  2. §0/§4 文件计数写"34/34 一致"（实为 **33/34**，唯一差异 `tools/import_seed.py`）；
  3. §9.2"NAS 侧待回收"计数停留在 09-11 19:45；
  4. 本文第一节变更表漏登 12 条实质变更（见"补充登记"）；
  5. `_hub_accept.sh` 留有过期 sha1 期望，且两脚本每次执行都会 **POST 写生产 `playback_reports` / `hub_notices`**
- 变更后：
  1. 改为完整 sha1 `79236cbbd59a1bd184312609bf5fb5f9b80c035c`；
  2. 改为 **33/34 一致；唯一差异 `tools/import_seed.py` 为既有差异**，并把"功能等价"改为中性的"差异内容未经逐行比对，暂按既有差异登记"；
  3. 更新为 **2026-09-11 21:00 CST 实测**（11 app + 8 config + 8 db = 27 项 ≈ 163 MB；保留 p8/p9/p10 三代可回收 ≈ 92 MB）；
  4. 见本节"补充登记"表（B1–B12），并新增本条审计登记；
  5. `_hub_accept.sh` 过期 sha1 改为**可配置变量**且不匹配时打印"历史期望已过期…"，两脚本头部加历史留档提示、**默认 dry-run**（跳过所有 POST 并标注"DRY-RUN：已跳过 N 个写操作"），仅 `--allow-write` 才真正 POST
- 客户端同步状态：不适用（不涉及契约语义）
- 服务端同步状态：不适用（不涉及服务端实现）
- 备注：两份审计报告 —— `docs/archive/AUDIT_HUB_QA_2026-09-11.md`、`docs/archive/AUDIT_HUB_ARCH_2026-09-11.md`。
  本次修复摘要 = **FIX-1**（HANDOFF §9.3 契约 sha1 过期）/ **FIX-2**（§0·§4 文件计数 33/34 + import_seed 中性措辞）/ **FIX-3**（§9.2 回收计数实测刷新）/ **FIX-4**（本节补登 12 条 + 本条登记）/ **FIX-5**（两验收脚本：dry-run 写开关 + 过期 sha1 可配置 + 留档提示）。

### 2026-09-11 — F5：`routes[].drm_uuid` **未确证，本轮不投递**（实现说明）
- 变更类型：实现说明（v1.1 §3 已定义该字段为"可选，未核实则省略"）
- 提出方：第三方审查 F5（对端需要它才能开 MPD 门禁）
- 章节：§3（`dash-clearkey` 的 `drm_uuid`）
- 变更前：字段不存在
- 变更后：字段**存在但恒不投递**（`dash-clearkey` 线路也不带该键）
- 客户端同步状态：待同步（客户端按 v1.1 §3 走回落分支）
- 服务端同步状态：**已核实，结论 = 无法从 MPD 取值 ⇒ 省略**
- 备注：**实测证据（2026-09-11，`tools/probe_drm_uuid.py` 在 NAS 上跑，跟随 302 后 HTTP 200）**：

  | 频道 | 清单大小 | 实际声明的 `ContentProtection@schemeIdUri` |
  |---|---|---|
  | TVB翡翠台 | 117,863 B | `mp4protection:2011`(value=cenc) · `edef8ba9-…`(Widevine) · `9a04f079-…`(PlayReady, MSPR 2.0) |
  | TVB明珠台 | 62,247 B | 同上 |
  | TVB Plus | 1,006,615 B | 同上 |
  | 无线新闻 | 同上 | 同上 |
  | **含 ClearKey schemeIdUri `e2719d58-…`？** | — | **4/4 全部为「否」** |

  ⇒ **TVB 的 MPD 根本没有声明 ClearKey 的 schemeIdUri**，因此不存在"该 MPD 实际使用的
  ClearKey schemeIdUri"可供原样透传。把 Widevine/PlayReady 的 uuid 当 `drm_uuid` 发出去
  会**直接误导客户端**（那正是黑屏的原因），故按契约"否则省略"处理。

  ⚠️ 特别说明：**不得**拿 `sources.keyformat` 里的 `urn:uuid:e2719d58-…` 顶替。那是**我们自己的**
  KODIPROP 映射常量（`discovery.CLEARKEY_KEYFORMAT`），不是 MPD 声明值 —— 契约要求的是后者。
  （旁证：`cenc:default_KID` 实测与库内 `kid` 一致，如翡翠台 `0958b9c6…b8ed`。）

- **给客户端的明确指令（本轮最重要的产出之一）**：`dash-clearkey` 一律使用 ClearKey 的**固定**
  system UUID `e2719d58-a985-b3c9-781a-b030af78d30e`（Media3 的 `C.CLEARKEY_UUID`），
  **不要**从 MPD 里挑 uuid（挑到的是 Widevine/PlayReady）。这不是猜测：
  ClearKey 是 system 而非 DRM 厂商，其 uuid 由定义固定；且 Media3 对
  `mp4protection:2011` + **非零 `default_KID`** 合成的 Common PSSH 有显式特例，请求 ClearKey 时能命中
  （`DefaultDrmSessionManager.getSchemeDatas`，见 `docs/archive/HK_PLAYER_TEST_VECTOR_2026-09-11.md` §3）。
- **门禁前置条件的结论**：F5 已"核实完成"，结论是**该字段对本项目无适用值**。
  ⇒ `url_lists[].enabled` 的开启**不依赖**该字段，而依赖客户端 L1 播放验证（HANDOFF §7 P0）。

### 2026-09-11 — F6：`notices[]` 独立落表 `hub_notices`（实现说明 / DDL_V8）
- 变更类型：实现说明（v1.1 §4 允许"复用 `playback_reports`"或"新建表"，要求写明选择）
- 提出方：第三方审查 F6（§5.2 要求上报、§4 却无字段承载）
- 章节：§4（`notices`）
- 变更前：无
- 变更后：新增表 `hub_notices`（`id, reported_at, client, app, session, code, detail, raw_json`）
  + 两个索引（`(reported_at)`、`(code)`），保留期 90 天（`cleanup` 同档裁剪）；
  `feedback_summary` 增加 `notices` 聚合；响应新增加法字段
  `notices` / `unknown_notice_code` / `notice_rejected`（§5.2 允许，**不提升 `hub_schema`**）
- 客户端同步状态：待同步（客户端按 §4 上报 `notices`）
- 服务端同步状态：已实现（接受、计数、落库、聚合）
- 备注：**为什么选"新建表"而不是复用 `playback_reports`**：
  1. `playback_reports.outcome` 的取值域是 `ok|partial|fail`（v1.1 §4），notice 会让该列出现第四种语义，
     后续任何按 `outcome` 聚合的查询都要额外过滤；
  2. notice 的语义单位是 `code`，必须**可查询可计数** —— `schema_unsupported` 意味着
     "客户端正在拒绝应用订阅"，是要人工介入的信号；塞进 `raw_json` 等于没有可观测性；
  3. 表名即语义：`playback_reports` 是"真机播放结果"，notice 不是播放结果。
  代价：必须 bump 一次 schema（`SCHEMA_VERSION 7 → 8`），因为旧版本号已被生产库占用、改旧 DDL 永不生效。

### 2026-09-11 — F2 / F3 / F4 落地说明（实现说明）
- 变更类型：实现说明（把 v1.1 正文的新增要求落到代码）
- 提出方：第三方审查 F2/F3/F4
- 章节：§3（`routes[].url_key` 非空、`signed_params`）、§2.5（向量端点）
- 变更前：`routes[].url_key` 可能为 `null`（`sources.url_key` 的碰撞落败行刻意置 NULL）；
  无 `signed_params`；向量只能靠文件分发（`GET /docs/urlkey_vectors.json` → 404）
- 变更后：
  1. **F2** `app/hub.py::_route_entry()` 对 `url_key` 缺失/长度非法者**现场补算**
     （用权威实现 `ChannelParser.url_key` + `resolve_signed_params`，绝不另写算法）；
     `url` 为空的行**不投递**并记 log；
  2. **F3** 投递 `signed_params = {global, by_host}`，小写去重，**与 `config/settings.json` 同源**（不建副本）；
  3. **F4** 新增 `GET /api/hub/v1/urlkey-vectors`（只读、无鉴权、带 `ETag`、**不参与 `seq`**），
     内容 = `docs/urlkey_vectors.json`；文件缺失 404 / 损坏 500（**绝不返回半截数组**）。
     `docker-compose.yml` 因此新增只读挂载 `./docs:/app/docs:ro`（单一事实来源，刷新向量无需重建镜像）。
- 客户端同步状态：待同步
- 服务端同步状态：已实现
- 备注：F2 是**与客户端 F1 配对的安全网** —— 只要本项成立，客户端永远不会进入"本地兜底计算 url_key"
  的分支；那条分支正是 v1 两端 url_key 分歧的来源。

### 2026-09-11 — 客户端侧验收与复算记录（实现说明）
- 变更类型：实现说明（客户端侧证据归档，不改契约语义）
- 提出方：kantv 客户端
- 备注：以下记录原存于客户端侧副本 `kantv/docs/HUB_CONTRACT_CHANGELOG.md`，2026-09-25 单仓库化后
  双份漂移达 501 行，故收敛回本文件为唯一权威，客户端侧改为指针文件。

**v1 落盘偏差（已修正，C6）**：v1 正文落盘时去掉了列表项内的反引号与加粗，语义全等但 sha1 不同
（少 180 字符）。v1.1 起改为**程序化逐字提取**，验收条件为「与来源文本块逐字节相等」且
「不删任何 markdown 标记」。客户端侧副本与来源文本块**逐字节相等**（已断言），
sha1 `79236cbbd59a1bd184312609bf5fb5f9b80c035c`，263 行 / 7364 字节。

**客户端复算记录（全部可复现）**：

| 项 | 命令 | 结果 |
|---|---|---|
| 契约逐字 | 程序化提取 + 逐字节比较 + sha1 | 相等；sha1 `79236cbb…`；263 行 |
| 修前（v1 算法） | `python3 tools/legacy_urlkey_v1_reference.py app/src/test/resources/urlkey_vectors.json` | 脱敏基线 **29/54 不一致（53.7%）** |
| 修后（v1.1 实现） | `./gradlew :app:testDebugUnitTest --tests '*UrlKeyTest*'` | **0 失败**（19 用例；脱敏基线 54 条逐条断言） |
| **权威向量门禁** | `curl …/urlkey-vectors` → `-Dkantv.urlkey.vectors` | **已执行：59 组 / 0 失败**；修前 v1 算法在同一向量上错 **22/59（37.3%）** |
| 真实 payload 复算 | `-Dkantv.hub.payload=/tmp/hub_payload.json` | 103 频道 / 205 线路；`signedParams=true`；**`locallyVerified=205 mismatched=0`** |
| 本地兜底已拆 | `*HubSubscriptionParserTest*` / `*HubRefreshPolicyTest*` | 通过（缺 `url_key` → 不可归因、仍可播放、不产生回流） |

**服务端 v1.1 上线进度（客户端 2026-09-11 20:38 实测）**：

| 项 | 状态 | 证据 |
|---|---|---|
| `GET /api/hub/v1/urlkey-vectors` | ✅ 已上线 | `200`，59 组，格式全部合法（40 位小写 hex） |
| `signed_params` | ✅ 已上线 | global 8 项 + `edgeware.tvb.com` 作用域 5 项，与 §2.2 一致 |
| `routes[].url_key` 非空 | ✅ 已保证 | 205/205 非空 |
| `notices` 接收与计数 | ✅ 已上线 | `/feedback/summary` → `notices.by_code{schema_unsupported}=1`、`unknown_code{never_seen_before}=1` |
| `routes[].drm_uuid` | ❌ 未上线 | 205 条线路中出现 **0** 次（F5 已核实：TVB MPD 无 ClearKey schemeIdUri，见本节 F5 条目） |
| `dash-clearkey` 线路 | ❌ 未投递 | `caps=dash-clearkey` → 0 条；`clearkey` 出现 0 次 |
| `fallback` | ❌ 未投递 | 103/103 频道无 `fallback` |

**已关闭的缺口（v1 记录 → v1.1 处置，客户端实现侧）**：

| v1 缺口 | 内容 | v1.1 处置 | 客户端 |
|---|---|---|---|
| A | §5.2 要求上报协议级事件，但 §4 无字段承载，且禁止 `channel_id` 为 null 的 results | §4 新增 `notices[]`（加法） | 已实现（`HubNotice` + `FeedbackNoticeQueue` + `HubFeedbackUploadWorker.reportNotice`） |
| B | `dash-clearkey` 未声明 DRM scheme UUID | §3 新增 `routes[].drm_uuid`（加法，可选）；缺失回落 `C.CLEARKEY_UUID` | 已实现（`ClearKeyPlaybackSupport.FALLBACK_UUID`；`drmUuid` 优先） |
| C | 服务端向量不可分发 | §2.5 新增 `GET /api/hub/v1/urlkey-vectors` | 门禁命令就绪（端点已上线，见上表） |
| D | §2 query 排序：契约正文与线上实现不一致 | §2.4 **撤回排序**并明确禁止 | 已实现（`QueryOrder` 与排序分支**已删除**） |
| E | §1 声明 `channel_id` 以繁体为准，线上为简体 | §1 改为「不得假设繁简」 | 无需改动（客户端从不假设繁简） |

### 2026-09-18 — 回流新增「画面冻结」字段组（加法，**两端冻结**）
- 变更类型：加法（可选字段；缺省不影响旧记录与旧客户端）
- 提出方：客户端（kantv）2026-09-18；服务端确认稿 `docs/REPLY_TO_CLIENT_2026-09-18_FREEZE_FIELDS_CONFIRM.md`（一次性交接文档，已于 2026-09-25 清理）
- 章节：§4（通道②：回流）
- 变更前：`results[]` 只有 `stalls` / `stall_seconds` / `play_seconds`。
  `stalls` 是**播放器自身**的卡顿检测，实测只到秒级：2026-09-17 `66ecb1f5…` 会话上报
  `stalls=2 / stall_seconds=2.0`，而客户端侧「画面推进计数」同期测得 **13.1 分钟**画面冻结。
  ⇒ 现有字段**看不见"画面不推进"**这一类缺陷（本次 TVB翡翠台 事件的全部争议即源于此）。
- 变更后（**最终字段清单，两端冻结**）：
  | 字段 | 类型 | 语义 | 必填 |
  |---|---|---|---|
  | `active_seconds` | number ≥ 0 | 本次附着期间"用户实际在看"的墙钟秒数（排除熄屏/切后台/用户暂停） | 有冻结字段则必填 |
  | `freeze_seconds` | number ≥ 0 | `active_seconds` 窗口内画面不推进的**累计**秒数（阈值 6 s ⇒ **保守下限**） | 同上 |
  | `freeze_segments` | integer ≥ 0 | 达到 **6 s** 的冻结**段数** | 同上 |
  | `freeze_longest_seconds` | number ≥ 0 | 最长的**一段**冻结秒数（段长按"画面恢复推进"计） | 同上 |
  | `picture_progress_events` | integer ≥ 0 | 本次附着期间"画面推进"**事件计数**（事件驱动，无固定采样间隔） | 同上 |
  | `freeze_long_segments` | integer ≥ 0 | 达到 **45 s** 的冻结段数（纯报表口径，不参与不变式） | 否 |

  **契约常量**：`FREEZE_THRESHOLD_SECONDS = 6`（冻结判定，两端冻结）；
  `FREEZE_REPORT_THRESHOLD_SECONDS = 45`（仅 `freeze_long_segments` 使用）。

  **不变式（服务端入库校验：违反只计数、绝不 4xx）**：
  1. `0 ≤ freeze_seconds ≤ active_seconds`
  2. `0 ≤ freeze_longest_seconds ≤ freeze_seconds`
  3. `freeze_segments == 0 ⟺ freeze_longest_seconds == 0`；`freeze_segments ≥ 1 ⟹ freeze_longest_seconds ≥ 6`
  4. 五个字段**整组给齐或整组不给**（缺任一 → 整组视为未上报，不做部分采信）

  **本轮相对"提案中"版本的四处修正（均为客户端实现事实驱动）**：
  1. **分母另立**：`play_seconds` 是"附着墙钟"（含缓冲/暂停/熄屏），不达标 → 新增 `active_seconds`；
     `play_seconds` 语义**保持不变**，以免历史口径漂移；
  2. **阈值 6 s**（客户端检测器 `FRAME_PROGRESS_TIMEOUT_MS` 已上线值），非原建议的 5 s：
     阈值的价值在"写死"而非取值，不为 1 秒让两端改代码；原"45 s"只是**报表口径**，不是检测阈值；
  3. **三个基础字段必须同一口径**（均 6 s）；"45 s 以上长冻结"另立 `freeze_long_segments`，
     不与基础三字段混用（混用会使不变式校验报冲突）；
  4. **撤下 `picture_progress_total`**（原建议为条件式：仅当语义是"总采样点数"才成立）。
     客户端推进检测是**事件驱动、无固定采样间隔**，反算不适用 → 改为 `picture_progress_events`。
- 客户端同步状态：**已实现（未发布）**。对端定稿 `kantv/docs/reply-to-iptv-nas-2026-09-18-freeze-fields-final.md`（一次性交接文档，已于 2026-09-25 清理）的
  两条补充**全部接受、无冲突**，并追加两条口径（见下方「第三轮：定稿补充」）。
- 服务端同步状态：**已实现并上线**（2026-09-18 08:29 +0800，生产库已迁移到 schema v9）：
  1. `DDL_V9`（`app/db.py`）：`playback_reports` 追加 7 列（6 个客户端字段 + 服务端派生列
     `freeze_contract_ok`），逐列判存在的守卫式迁移（与 v5 同因）；
  2. 落库（`app/hub.py::record_feedback` + `_freeze_contract`）：整组/缺项/违反三态判定，
     **违反只计数、绝不 4xx**；响应新增加法字段 `freeze_ok` / `freeze_violation` / `freeze_partial`；
  3. 聚合（`feedback_summary`）：按 `channel × url_key` 出冻结率、段数、最长段、契约违反计数，
     全局 `totals` 同步；**未上报的记录不进分子也不进分母**；
  4. 单测 `tests/test_hub_freeze_fields.py`（18 例）+ 全量离线单测 547 例全绿；
     迁移在**生产库副本**上预演过（19,408 sources / 27 reports / 211 lineups / 12,229 verifications
     全部无损，19 → 26 列，重复迁移幂等，历史行保持 NULL）。
  状态页 `render_html` 的冻结率**图形化展示：明确不做**（用户决定，2026-09-18）——
  本服务的消费者只有 kantv 一个，状态页没有读者。冻结数据的出口是
  `feedback/summary`（JSON）与库内 SQL，供人工/AI 复核时查询，不再投入界面工作。

  **第三轮：定稿补充（2026-09-18，客户端定稿 + 服务端核实）**

  | # | 补充口径 | 服务端核实结论 |
  |---|---|---|
  | A | **起播等待不计入冻结**：`freeze_*` 的基准从**首帧**开始（起播慢由既有 `first_frame_ms` 表达，不混入冻结） | **接受，无需改代码**。不变式 `freeze_seconds ≤ active_seconds` 仍成立（冻结只在 active 窗口内、且起点更晚）。语义记于此，避免以后把"起播 40 s"读成"冻结 40 s"。 |
  | B | **`active_seconds` 含缓冲等待**（= 屏幕亮 + 前台 + 当前频道 + 未主动暂停），故对端主张同一附着内恒有 `freeze_seconds ≥ stall_seconds` | **接受，但不作为入库不变量**。该关系的**前提是 `stall_seconds` 不含起播等待**——已用库内数据实测：起播 >10 s 的 9 条记录 `stall_seconds` **全为 0**（最大起播 20.6 s），前提成立。若客户端日后把起播计入 `stall_seconds`，此关系即失效；故只作解读约定、不做校验，避免误报。 |
  | C | 对端已接入我方 202 响应里的 `freeze_ok` / `freeze_violation` / `freeze_partial` 自检计数（容错：非 JSON 或缺字段按 0，不影响上报成功判定） | **接受**。这正是把三态判定开放给客户端的目的。 |
  | D | 对端复核我方接收侧：`playback_reports` 26 列、schema v9、`feedback/summary` 含冻结聚合 | **与我方自检一致**（2026-09-18 10:06 +0800 复核）。 |

  **一处口径歧义（已提请注意，服务端按自己的冻结口径实现）**：对端 §4/§7 写作
  "新增 **6 个字段**（含可选 `freeze_long_segments`）…缺任一视为整组未上报"，
  而冻结的规格是 **5 个基础字段成组** + `freeze_long_segments` **独立可选**。
  两者在"总是发 6 个或 0 个"时线上行为一致，但若客户端因故取不到 45 s 口径就整组不发，
  会**静默丢掉全部冻结数据**。服务端维持 5 字段成组（`FREEZE_FIELDS`）+
  可选字段独立（`FREEZE_OPTIONAL_FIELDS`），并已在单测中覆盖"可选字段单独出现不触发缺项"。
  已请对端按此实现（详见致对端短笺）。
  **对端已于同日改毕并回话"无需回话"**：成组固定为 5 个字段，缺任一才整组未上报；
  45 s 口径取不到时**只省这一个字段**，并已加回归用例锁定（见下条 E）。

  **E. 45 s 字段"本次取不到"的表示法（对端实现选择，服务端已接受并锁定）**

  | 表示 | 状态 | 服务端行为 |
  |---|---|---|
  | 键**缺席** | **规范形式（推荐）** | 落 NULL |
  | 值 **-1**（对端哨兵） | 已接受的等价形式（对端 2026-09-18 实现） | 落 NULL —— **与缺席等价** |
  | 值 **0** | "本次确实没有 ≥45 s 的段" | 落 0（**与"取不到"严格区分**） |

  服务端 `app/hub.py::_freeze_contract` 对负值一律归 NULL，是**有意为之**：
  若当成不变式违反或原样落库，都会污染"确实没有长冻结"的语义。
  已加单测 `test_报表字段的_1哨兵等价于键缺席` 锁定三种表示的行为；
  改动此逻辑前必须先改本条目。
  当前该接口返回中性值（`totals.freeze_reported=0`），对端字段上线后自动有数。
- 备注：
  - **冻结段的结束时刻 = "画面重新推进"（或该次附着结束），不是"触发恢复动作"的时刻。**
    若按后者，每段都会被记成 ≈6 s，客户端本次实测的 13.1 分钟长冻结**一次都不会出现在回流里**
    （恢复动作本身要花时间）。此条已写入确认稿，属实现要求。
  - **归属粒度**：一条 `results[]` = **一次连续附着**（session × url_key）；切线路/重连必须 finish + begin。
    客户端现状为"一次会话一条"，将按此改为每次有效换线先 finish 再 begin。
    这也解释了为何服务端库里看不到"21:39 切走即恢复"——该证据来自客户端逐分钟采样，粒度比回流细。
  - **§4.1 仍然有效**：新增字段让"画面冻结"首次**可以在服务端库内直接坐实**，处置速度会大幅提升；
    但**自动**删除/降级/拉黑**仍被 §4.1 禁止**。客户端确认：不做任何自动动作，
    其"冻结→自愈"走**本机实时检测**这条独立通道。
  - **假阳性风险（已登记）**：真实广播本身就存在静止画面（静止图、字幕版、黑场），
    单纯"画面不推进"会误计。判据的可靠性依赖**同频道同一时段跨线路对照**——
    这正是"阈值与时间基准必须写死"的原因：不写死则两份报告不可对照，对照法失效。
  - **"一直缓冲的线"的口径洞（已在确认稿中提示）**：若 `active_seconds` 排除缓冲等待，
    则一条几乎全程缓冲的线会有很小的 `active_seconds` 与接近 0 的 `freeze_seconds`，反而显得干净。
    服务端解读时须把 `stall_seconds` 与冻结率**一起看**。

### 2026-09-26 — hub_schema 1 → 2（版本治理，追认 v1.1 语义订正，两端同步）
- 变更类型：破坏性（版本治理；对既有字段的语义订正正式版本化）
- 提出方：P0-1 审查结论（docs/archive/AUDIT_HUB_ARCH_2026-09-11 §P0-1），用户拍板升 2
- 章节：§2.4 §5.2（并同步 §3 GET 示例、`hub_schema` 两处 JSON 示例的取值）
- 变更前：`hub_schema = 1`。v1.1 在 schema 1 内对**既有字段**做了四处语义订正——① §1 `channel_id` 繁简（以服务端下发为准、当前为简体）；② §2.1/§2.3 `url_key` 算法（query 原样保留、参数名小写精确命中、名单改由服务端下发）；③ §5.1 `caps` 缺省 fail-closed；④ §3 `groups[].id` 取值域（config 中文分组键）——违反 §5.2「同一 `hub_schema` 内只做加法」与 §2.4，且两端缺少「对端是 v1 还是 v1.1」的判据。
- 变更后：`hub_schema = 2`。**schema 1 = v1 语义；schema 2 = v1.1 语义**（本正文自 v1.1 起的全部订正归入 schema 2）。§5.2 增补规则：**语义订正（对既有字段的破坏性/订正性修改）必须提升 `hub_schema`**，v1.1 事件作为先例记录；§2.4 同步记录该先例。
- 客户端同步状态：本提交同步（`BuildConfig.HUB_SCHEMA` 与 feedback 纯模块常量升 2；「支持值=2 接受、遇 3 拒绝」行为测试同步更新）
- 服务端同步状态：本提交同步（`app/hub.py::HUB_SCHEMA` 升 2；NAS 部署由用户控制发布节奏，不在本任务范围）
- 备注：**两端发布必须同步**。旧 App（hub_schema=1）遇到新服务端（=2）将按 §5.2「高于自身支持值即拒绝应用」停用枢纽目录、保留上一次成功结果并上报 `schema_unsupported`——该后果用户已知情接受。`GET` 的 `schema=` 参数仍只记日志、不参与协商（本条目不改变该行为）；`POST /feedback` 的 `hub_schema` 不校验属 P2-7，另案处理。

---

## 二、历史证据：v1 首轮实现的偏差清单（D1–D9）

> 以下 9 条是 2026-09-11 首轮实现时"契约正文 vs 服务端实现"的实测冲突记录。
> **它们全部已由 v1.1 正文处置**（见右列）。保留于此作为决策过程的证据链：
> 说明"为什么会升到 v1.1"，以及每条结论当时用的实测数字。

| # | 当时的偏差 | 实测数字 / 证据 | v1.1 处置 |
|---|---|---|---|
| D1 | 契约 §1 写"channel_id **以繁体为准**"（例 `無線新聞台`/`翡翠台`），实际目标键是简体 `无线新闻`/`TVB翡翠台` | 验收 #3 的原文命令返回 `[]`；`aliases` 覆盖繁体（输出见 `_hub_accept_out.txt`） | **已订正**：§1 明确"以服务端下发为准，简体" |
| D2 | 契约 §2 要求 query **排序**，服务端权威实现**不排序** | 生产库 **3221/17873 行（18.0%）** 得到不同 url_key；排序会改 `url_hash`（唯一索引） | **已订正**：§2.1 明确原样保留；§2.4 禁止项 |
| D3 | 契约 §2 要求参数名**后缀匹配**，服务端为**精确匹配** + 两级名单 | 仅后缀匹配额外影响 **570 行（3.2%）** | **已订正**：§2.2 名单下发、§2.3 精确匹配 |
| D4 | 契约 §2 最小名单**漏了** `ext_start_limit`/`int_start_limit`/`c` | 按最小名单实现，TVB MPD 的 url_key **每天变一个**（`bc510a04…` vs `22d5208a…`）；服务端两级名单跨日恒定（`114e77e0…`） | **已订正**：§2.2 名单由服务端下发 |
| D5 | T6 的「query 顺序打乱 → url_key 相同」 | 与 D2 同源，客观上无法在该架构下满足 | **已撤回**：§2.4 |
| D6 | `quality` 契约有示例值；`fallback` 契约有示例 | 库中无画质元数据；未做 `web_routes` 配置化 | **已订正**：§3 明确 `quality` 恒 null、`fallback` 可选 |
| D7 | 契约未定义 `caps` **缺省**语义 | 服务端已取 fail-closed | **已订正**：§5.1 |
| D8 | `groups[].id` 示例为 `hk_tw`，实际用 config 中文分组键 | — | **已订正**：§3 明确 id 由服务端定义 |
| D9 | v1 的 dash 分支只看 `encryption` | `encryption='cenc'` 同时覆盖 ClearKey 与 Widevine/PlayReady | **已写入正文**：§3.1 fail-closed 条件 |

**度量工具**（可复跑，只读）：`tools/hub_urlkey_divergence.py`
—— `python tools/hub_urlkey_divergence.py <库路径>`；
NAS 上 `cd <deployment-root>/iptv-nas && python3 tools/hub_urlkey_divergence.py data/iptv.db`
（本地 v2 快照 10126 行同法度量得 679 / 143 行，同向结论）。

---

## 三、本轮仍未做（登记为待办，见 HANDOFF §7 P6）

| # | 项 | 说明 |
|---|---|---|
| 1 | `url_lists[].enabled` 门禁 | **需用户决策**（第三方审查 S7）；前置条件 = 客户端 L1 播放验证通过（**不依赖** F5） |
| 2 | 回流接入 `verifier` 作为仲裁 | v1.1 §4.1 仍禁止自动动作 |
| 3 | 设备维度兼容矩阵 | 数据已在 `playback_reports`（`device_model`/`android_api`） |
| 4 | `web_routes` 配置化（下发 `fallback`） | 已知可用：`无线新闻` → `https://news.tvb.com/tc/live/83` |
| 5 | `git init` | `.gitignore` 已就位（F8） |
| 6 | 契约 §3 下一版建议明确 `drm_uuid` "无适用值时"的措辞 | 本轮 F5 结论应写回正文，免得下次再核实一遍 |
