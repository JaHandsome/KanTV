# iptv-nas 系统架构设计

> 架构师：Bob（高见远）｜基于 PRD v1.0（许清楚）｜技术约束：Python 3.8+ 纯标准库、sqlite3 唯一持久层、NAS 无人值守
>
> 本文档覆盖 PRD P0 全部 6 条需求（P0-1 ~ P0-6），并为 P1/P2 预留扩展点。

---

## 1. 关键技术决策（回答 PRD 留给架构师的 4 个问题）

| # | 问题 | 决策 | 理由 |
|---|---|---|---|
| Q7 | 源发现通道深度：是否递归挖掘新仓库 | **P0 = 固定仓库清单（repos.json）+ jsDelivr 首选通道 + ghproxy 备用通道 + 本地种子导入；递归挖掘放 P1** | ① PRD 优先级原则"先数量后质量"，固定清单（已知活仓 11 个，单 Guovin 就有 1619 条）已足够支撑候选池 ≥ 2000 的 P0 指标；② 递归挖掘（解析 README/订阅列表→发现新仓库）引入不可控的解析面与死链放大效应，P0 阶段收益/风险比不划算；③ 架构上把"发现通道"抽象为 Provider 接口，P1 新增 `RepoMinerProvider` 即可插拔，不动核心 |
| Q8 | sqlite 三域分库/分表 | **单库单文件（data/iptv.db）+ 三域分表 + meta 版本表实现迁移**。候选池域（sources/repos）、验证结果域（verifications）、健康历史域（health_checks/health_events）各成体系，外加线路表 lineups | ① 单库满足"升级保留数据"最简单：代码升级 = 替换 app/ 目录，data/ 不动；db.py 内建 `meta.schema_version` 逐版本迁移（ALTER/建表幂等），比多库简单一个量级；② 三域分表满足低耦合：discovery 只写 sources/repos，verifier 只写 verifications，health 只写 health_*，builder 只读 lineups；③ WAL 模式 + 单写连接，读写不互相阻塞 |
| Q9 | 探活与验证任务并行还是排队 | **统一排队（单调度线程串行编排任务类型，网络 IO 内部并发）** | ① 全局网络并发 ≤ 16 是硬约束（P0-6），两类任务并行则各自 16 连接 = 32，超限；排队后共享一个 NetClient 并发闸门，上限天然受控；② sqlite 单写连接，并行任务只会增加锁竞争；③ 探活任务轻（首字节探测，秒级）优先插队：队列按"探活 > 验证 > 发现"优先级取任务，验证任务按批可中断续跑（批内先收集整批结果、非熔断时逐条落库），不会饿死探活 |
| Q10 | HTTP 服务与调度器单/双进程 | **单进程多线程：ThreadingHTTPServer（daemon 线程/请求）+ 1 个调度线程 + 1 个共享网络线程池** | ① 任务是 IO 密集（urllib 等待网络时释放 GIL），CPU 占用低，http.server 每请求一线程完全够用；② 长任务隔离不靠进程靠"原子文件替换"：HTTP handler 只读 data/output/ 下的静态文件，调度线程写 `.tmp` 后 `os.replace()`，读写永不交叉，无共享可变状态；③ 双进程要多一套守护/日志/IPC，违背低资源原则；④ 进程级可靠性交给外部守护（systemd Restart=always / Windows 计划任务），主线程 `serve_forever()` 常驻，调度线程内所有异常捕获在任务粒度，不冒泡杀进程 |

**补充决策（无编号，但同样关键）**：

- **零第三方依赖**：所有网络请求用 urllib.request（默认验证 TLS 证书链及主机名、UA 伪装、超时），并发用 concurrent.futures.ThreadPoolExecutor——与现有已验证脚本完全一致的技术路线。
- **验证算法直接移植而非重写**：`probe_sources5.py` 的 TS PMT/PES 解析、fMP4 盒嗅探、FLV tag 遍历、嵌套 m3u8 跟随、ENDLIST 点播识别、多分片重试是实测可用的核心资产，逐函数移植进 `verifier.py`（补充 probe_flv 中缺失的 `import struct`）。
- **输出格式 100% 兼容**：`builder.py` 原样保留 iptv_pipeline.py 的 ORDER/GROUPS/classify()/PREVERIFIED/EPG 台标模板/多线路 `#` 拼接/`#genre#` 分组，仅把"数据来源"从 JSON 文件换成 sqlite 查询，电视端零改动。
- **死源规则前置**（P1-5 的最小子集提前到 P0，因为它直接省探活预算）：`rules.json` 含网关段黑名单（39.134/39.135）、域名黑名单（vpstv/kylintv/freetv.fun/tvbox6.icu/live.264788.xyz）、flaky 域名降权（t.061899.xyz），在 discovery 入库阶段即拦截，规则外置可编辑。
- **配置全外置**：settings.json（周期/并发/端口）、channels.json（85 频道清单+分组排序）、repos.json（种子仓库）、rules.json（死源规则）。改配置下个周期生效，不改代码。

---

## 2. 总体架构

### 2.1 架构风格

**单进程多线程分层架构**：`入口层（main）→ 服务层（HTTP / Scheduler）→ 业务层（Discovery / Verifier / HealthMonitor / Builder）→ 基础层（DB / NetClient / Config / Log）`。业务四模块互不 import 对方（只通过 DB 与共享配置交互），可独立单测——满足 PRD"模块间低耦合"。

```
┌─────────────────────────── iptv-nas 单进程 ────────────────────────────┐
│  main.py                                                                │
│    ├─ HttpServer (ThreadingHTTPServer, daemon threads)                  │
│    │     GET /live.txt /live.m3u /tvbox_lives_snippet.json /healthz    │
│    │     只读 data/output/ 下的文件（原子替换保证不读到半截）              │
│    └─ Scheduler 线程 (daemon, while True)                               │
│          按周期+优先级出任务 → 共享 ThreadPoolExecutor(16) 网络 IO        │
│                                                                        │
│  业务层：SourceDiscovery → Verifier → HealthMonitor → OutputBuilder     │
│          （依次只通过 sqlite 交换数据，互不直接调用）                     │
│                                                                        │
│  基础层：Database(WAL+单写连接+版本迁移) · NetClient(超时/重试/并发闸门) │
│          Config(JSON) · logutil(RotatingFileHandler)                    │
└──────────────────────────────────────────────────────────────────────┘
        ↑ 外网(jsDelivr/ghproxy/直播源)              ↑ 局域网(TVBox/电视)
```

### 2.2 模块职责表

| 模块 | 文件 | 职责 | 对应 PRD |
|---|---|---|---|
| Config | app/config.py | 加载/合并/校验 4 个 JSON 配置，提供默认值 | 全局 |
| LogUtil | app/logutil.py | RotatingFileHandler（10MB×5，≤100MB 上限），stdout 双写 | P0-6 |
| Database | app/db.py | sqlite 连接管理（WAL、busy_timeout）、建表、schema_version 迁移、数据清理（90 天） | P0-2/3、升级保留 |
| NetClient | app/net.py | urllib 封装：超时、UA、ssl context、仅瞬态网络/指定 HTTP 状态重试+指数退避（策略拒绝/永久状态立即失败）、全局并发闸门（Semaphore 16）、jsDelivr/ghproxy URL 构造 | P0-1/2/6 |
| ChannelParser | app/parser.py | M3U/TXT 双格式解析、频道名归一化（key_of）、URL 归一化（url_hash）、classify() 关键词分类 | P0-1 |
| SourceDiscovery | app/discovery.py | 通道 Provider（jsDelivr/ghproxy/本地种子）拉取仓库清单 → 解析 → 规则过滤 → 去重入库候选池 | P0-1 |
| Verifier | app/verifier.py | 移植 probe_sources5：HTTP 实连 → m3u8 逐级解析 → 分片实收 → 媒体级验证（TS PMT/PES、fMP4、FLV），写 verifications，更新 sources 状态 | P0-2 |
| HealthMonitor | app/health.py | 轻量探活（首字节）+ 健康状态机（ok/flaky/dead）+ 连续 N 周期恢复回归 + 线路重排（写 lineups） | P0-3 |
| OutputBuilder | app/builder.py | 从 lineups 构建 live.txt/live.m3u/tvbox snippet，格式兼容旧脚本，.tmp+os.replace 原子替换 | P0-5 |
| Delivery | app/delivery.py | 投递路由叶子（无副作用）：`route_of`（T3 映射，含 `flv→flv`）+ `media3_deliverable_route`（主力 media3 可投递子集），builder/hub 共享，杜绝路由集合漂移 | 投递集合契约 |
| HttpServer | app/httpserver.py | ThreadingHTTPServer 路由 4 个只读端点，正确 Content-Type | P0-5 |
| Scheduler | app/scheduler.py | 周期编排（发现 6h/验证 2h/探活 30min/每次变更后 rebuild），任务互斥防重入，优先级队列，异常隔离 | P0-4 |
| import_seed | tools/import_seed.py | 一次性把《全国频道实测结果.json》《gateway_map.json》导入 sqlite 候选池 | 冷启动 |

---

## 3. 文件列表（目录树）

```
D:\tv\iptv-nas\
├── app\
│   ├── main.py              # 入口：装配 Config/DB/NetClient → 启动 HttpServer(前台) + Scheduler(daemon)
│   ├── config.py            # 配置加载与默认值合并
│   ├── db.py                # sqlite 封装：连接、WAL、建表迁移、清理
│   ├── net.py               # 网络封装：超时/重试/退避/并发闸门/CDN 通道 URL
│   ├── logutil.py           # 日志轮转
│   ├── parser.py            # M3U/TXT 解析 + 频道/URL 归一化 + classify
│   ├── discovery.py         # 源发现：Provider 通道 + 规则过滤 + 去重入库
│   ├── verifier.py          # 候选源多级验证（移植 probe_sources5）
│   ├── health.py            # 探活 + 健康状态机 + 线路重排
│   ├── delivery.py          # 投递路由叶子（route_of + media3_deliverable_route，builder/hub 共享）
│   ├── builder.py           # 输出构建（移植 iptv_pipeline.build 格式）
│   ├── httpserver.py        # HTTP 订阅服务
│   └── scheduler.py         # 定时调度与任务互斥
├── config\
│   ├── settings.json        # 周期、并发、端口、路径、阈值（含 FlakyHosts 等）
│   ├── channels.json        # 85 目标频道清单 + 分组 + 排序 + 别名表
│   ├── repos.json           # 种子仓库清单（11 个已知活仓，jsDelivr 路径）
│   └── rules.json           # 死源规则库：网关段黑名单/域名黑名单/flaky 降权
├── data\
│   ├── iptv.db              # sqlite（唯一持久层，升级不覆盖）
│   ├── output\
│   │   ├── live.txt         # TVBox 订阅（原子替换产物）
│   │   ├── live.m3u
│   │   └── tvbox_lives_snippet.json
│   └── seed\                # 冷启动种子（拷贝自现有实测数据，import_seed 读取）
│       ├── 全国频道实测结果.json
│       └── gateway_map.json
├── deploy\
│   ├── systemd\iptv-nas.service      # Linux NAS 进程守护（Restart=always）
│   ├── windows\start_iptv.bat        # 开发机/Windows NAS 启动脚本
│   └── nas_task.md                   # 群晖/威联通计划任务部署说明
├── tools\
│   └── import_seed.py       # 种子数据一次性导入 sqlite
├── tests\
│   ├── test_parser.py       # 解析/归一化/分类单测（本地样本，无网络）
│   ├── test_db.py           # 建表/迁移/去重单测
│   ├── test_verifier.py     # 媒体判定单测（内置 TS/fMP4/FLV 十六进制样本字节）
│   ├── test_builder.py      # 输出格式兼容性测试（与旧脚本产物逐行比对）
│   └── test_health.py       # 状态机迁移单测
└── docs\
    ├── PRD.md / ARCHITECTURE.md / archive/TASKS.md（任务分解，已完成）
    ├── class-diagram.mermaid
    └── sequence-diagram.mermaid
```

---

## 4. 数据结构设计

### 4.1 sqlite 表结构（建表 SQL，域划分见注释）

```sql
-- db.py 执行；PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
-- 所有时间戳为 TEXT，ISO 8601 UTC（如 2025-01-15T08:30:00Z）

CREATE TABLE IF NOT EXISTS meta (            -- 元数据域（升级保留数据的锚点）
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);                                            -- 初始写入 schema_version=1

-- ============ 域 1：候选池（discovery 写，verifier/health 更新状态） ============
CREATE TABLE IF NOT EXISTS sources (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  url           TEXT NOT NULL,                -- 原始 URL（保留 query，探活用）
  url_hash      TEXT NOT NULL,                -- sha1(归一化URL)，去重键
  channel_name  TEXT NOT NULL,                -- 发现时仓库标注的原始频道名
  channel_key   TEXT NOT NULL,                -- 归一化频道键，如 CCTV5+/广东卫视（''=未匹配目标频道）
  origin        TEXT NOT NULL DEFAULT '',     -- 来源标识：jsdelivr:user/repo@branch | ghproxy:... | seed:xxx | gateway_map
  status        TEXT NOT NULL DEFAULT 'candidate',
                  -- candidate(待验证) / active(已上线) / flaky(时通时断) / dead(下线) / blacklisted(规则拉黑)
  av            TEXT,                          -- video / audio-only / 不稳定 / 未判定(...)，验证后填
  score         REAL NOT NULL DEFAULT 0,       -- host 评分(移植 revival host_score) + 质量分(P1 扩展)
  first_seen    TEXT NOT NULL,
  last_seen     TEXT NOT NULL,                 -- 最近一次被 discovery 再次发现（活跃仓库的源应被再次确认）
  last_verified TEXT,                          -- 最近一次完整验证时间（24h 缓存的 P1 依据）
  fail_streak   INTEGER NOT NULL DEFAULT 0,    -- 探活连续失败计数
  ok_streak     INTEGER NOT NULL DEFAULT 0,   -- 探活连续成功计数（dead→active 恢复判定）
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sources_url ON sources(url_hash);
CREATE INDEX IF NOT EXISTS idx_sources_channel ON sources(channel_key, status, score DESC);
CREATE INDEX IF NOT EXISTS idx_sources_candidate ON sources(status, score DESC) WHERE status='candidate';

CREATE TABLE IF NOT EXISTS repos (            -- 种子仓库清单（repos.json 导入；P1 RepoMiner 可自动插入新行）
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  repo      TEXT NOT NULL,                    -- user/repo
  branch    TEXT NOT NULL DEFAULT 'main',
  path      TEXT NOT NULL,                    -- 仓库内文件路径
  channel   TEXT NOT NULL DEFAULT 'jsdelivr',-- 通道：jsdelivr / ghproxy
  enabled   INTEGER NOT NULL DEFAULT 1,
  last_fetch TEXT,
  last_ok    TEXT,                            -- 最近一次成功拉取时间（通道健康观测）
  note      TEXT DEFAULT '',
  UNIQUE(repo, branch, path, channel)
);

-- ============ 域 2：验证结果（verifier 追加，只写不改） ============
CREATE TABLE IF NOT EXISTS verifications (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id   INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  ts          TEXT NOT NULL,
  result      TEXT NOT NULL,                  -- ok / abnormal / fail
  av          TEXT,                          -- video / audio-only / 不稳定 / 未判定(...)
  note        TEXT,                           -- probe 详情（如 video(TS:H.264视频)），截断至 80 字符
  duration_ms INTEGER                        -- 本次验证总耗时（P1 质量分的原始数据）
);
CREATE INDEX IF NOT EXISTS idx_verif_source ON verifications(source_id, ts);

-- ============ 域 3：健康历史（health 追加；排查依据；90 天清理） ============
CREATE TABLE IF NOT EXISTS health_checks (    -- 每次探活的原始结果
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id  INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  ts         TEXT NOT NULL,
  alive      INTEGER NOT NULL,               -- 1/0
  latency_ms INTEGER,                        -- 首字节耗时
  reason     TEXT                            -- 失败原因：Timeout / HTTP 403 / DNS ...
);
CREATE INDEX IF NOT EXISTS idx_checks_source ON health_checks(source_id, ts);

CREATE TABLE IF NOT EXISTS health_events (    -- 状态机跃迁事件（上线/下线/波动）
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  ts        TEXT NOT NULL,
  event     TEXT NOT NULL,                    -- online / offline / flaky / blacklist / reverify-pass
  reason    TEXT                              -- 触发原因（如 连续3次探活失败 / 连续2次恢复）
);

-- ============ 线路表（health 重排写入，builder 只读） ============
CREATE TABLE IF NOT EXISTS lineups (
  channel_key TEXT NOT NULL,
  source_id   INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  rank        INTEGER NOT NULL,               -- 0=首选，1..N-1=备选（TVBox '#' 拼接顺序）
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (channel_key, source_id)
);
```

> P2 的 `stats_daily`（每日汇总表）暂不建表，schema_version=2 迁移时再加——meta 表机制天然支持。

### 4.2 关键数据流转（sqlite 为唯一媒介）

```
repos.json ──导入──> repos 表
repos 表 ──discovery 拉取解析──> sources 表 (candidate, url_hash 去重)
sources(candidate) ──verifier──> verifications 表 + sources.av/status=active
sources(active/flaky) ──health 探活──> health_checks + health_events + sources.status 跃迁
health 状态跃迁 ──> lineups 重排 ──builder──> data/output/*.{txt,m3u,json}（原子替换）
```

---

## 5. 核心类与接口（classDiagram）

```mermaid
classDiagram
    direction TB

    class Config {
        +dict settings
        +dict channels      # order/groups/aliases
        +list rules         # blacklist_hosts/blacklist_domains/flaky_hosts
        +list repos
        +load() Config
        +http_base() str    # http://ip:port
    }
    class Database {
        -sqlite3.Connection conn
        +connect(path) Database
        +migrate()          # 按 meta.schema_version 逐版本执行 DDL
        +execute/executemany(sql, params)
        +query(sql, params) list~dict~
        +cleanup(retention_days)  # 清理 health_checks/health_events
        +close()
    }
    class NetClient {
        -Semaphore gate      # 全局并发闸门 ≤16
        -dict ssl_ctx
        +fetch(url, timeout, limit, ua) (status, bytes, content_type)
        +fetch_retry(url, retries, backoff) bytes   # 仅瞬态网络/指定 HTTP 状态重试；策略拒绝/永久状态立即失败
        +jsdelivr_url(repo, branch, path) str
        +ghproxy_url(repo, branch, path) str
    }
    class ChannelParser {
        +parse_m3u(text) list~Entry(name, group, url, ua)~
        +parse_txt(text) list~Entry~
        +normalize_channel(name) str   # key_of: CCTV-5→CCTV5, 别名归并
        +classify(name) str            # 央视/卫视/广东/港澳台（移植 iptv_pipeline）
        +normalize_url(url) str
        +url_hash(url) str             # sha1(归一化)
        +host_score(host) int          # 移植 revival_channels
    }
    class SourceDiscovery {
        -NetClient net
        -Database db
        -ChannelParser parser
        -list providers
        +run() dict                    # {discovered, new, dup, blocked}
        -fetch_repo(repo_row) text
        -ingest(entries, origin)
        -apply_rules(entry) bool       # 死源规则拦截
    }
    class Verifier {
        -NetClient net
        -Database db
        +run(batch_size, time_budget_s) dict  # 按 score 取 candidate 批次；先收集整批再判定熔断
        +probe(url, ua) dict           # 移植 probe_sources5.probe
        -probe_m3u8(url, ua) dict
        -probe_flv(url, ua) dict
        -sniff_segment(url, ua, depth) str
        -parse_ts(data) / ts_judge(data) / mp4_judge(data)
        +record(source_id, result)     # 非熔断时逐条落库（可中断续跑依据）
    }
    class HealthMonitor {
        -NetClient net
        -Database db
        +run() dict                    # 一轮探活全部 active/flaky 源
        -probe_alive(url, ua) (alive, latency_ms, reason)  # 首字节轻探
        -on_check(source, alive)       # 状态机跃迁判定
        +rebuild_lineups()             # 重排 lineups（每频道取 top N）
    }
    class OutputBuilder {
        -Database db
        -Config cfg
        +build() dict                  # {channels, files}
        -render_txt(items) str         # #genre# 分组 + '#' 多线路拼接
        -render_m3u(items) str         # EPG/台标模板
        -render_snippet() str
        -atomic_write(path, content)   # .tmp + os.replace
    }
    class HttpServer {
        -ThreadingHTTPServer httpd
        +serve_forever()
        -handle(req)                   # live.txt/m3u/snippet/healthz 只读 output/
    }
    class Scheduler {
        -Database db
        -ThreadPoolExecutor pool
        -dict last_run / running       # 任务互斥防重入
        +loop()                        # while True: 到期任务按优先级入队
        -tick_discovery() -tick_verify() -tick_probe() -tick_rebuild()
    }

    Config --> Database : 提供 db 路径
    Config --> SourceDiscovery : repos/rules
    Scheduler --> SourceDiscovery : 每 6h
    Scheduler --> Verifier : 每 2h 增量
    Scheduler --> HealthMonitor : 每 30min
    Scheduler --> OutputBuilder : 状态变更后
    SourceDiscovery --> ChannelParser : 解析/归一化
    SourceDiscovery --> Database : 写 sources/repos
    SourceDiscovery --> NetClient : 拉仓库文件
    Verifier --> NetClient : 实连/分片
    Verifier --> Database : 写 verifications/sources
    HealthMonitor --> NetClient : 首字节探活
    HealthMonitor --> Database : 写 health_*/lineups
    OutputBuilder --> Database : 读 lineups/sources
    HttpServer ..> OutputBuilder : 只读其原子替换产物
```

完整 Mermaid 源码另存于 `docs/class-diagram.mermaid`。

---

## 6. 核心流程（时序描述）

### 6.1 源发现 → 入库（P0-1，每 6h，含冷启动种子导入）

```
Scheduler.tick_discovery（互斥锁内）
 1. SourceDiscovery.run():
    a. db.query("SELECT * FROM repos WHERE enabled=1") 得仓库清单
       （首次运行：config/repos.json 11 个活仓导入 repos 表；
        另有 SeedProvider 读 data/seed/全国频道实测结果.json + gateway_map.json，
        import_seed.py 也会一次性做这件事——冷启动候选池即刻有 ~850+739 条）
    b. 对每个仓库：url = net.jsdelivr_url(repo, branch, path)
       data = net.fetch_retry(url, retries=2)          # 失败则 ghproxy_url 重试；再失败记 last_fetch，跳过
       （单通道失效时其余通道独立供源 → repos.last_ok 区分记录）
    c. entries = parser.parse_m3u(data) 或 parse_txt(data)
    d. 对每条 entry：
       - url_hash = parser.url_hash(entry.url)；若已存在 sources → 仅更新 last_seen（重复入库率=0 靠 UNIQUE 索引）
       - apply_rules()：命中 rules.json 黑名单（39.134/39.135 网段、vpstv 等域名）→ status=blacklisted 入库但不进验证队列
       - channel_key = parser.normalize_channel(entry.name)；
         channel_key 不在 channels.json 目标清单内 → 仍入库（channel_key=''），P0 验证只消费目标频道
       - INSERT INTO sources(status='candidate', score=parser.host_score(host))
    e. 返回统计 {discovered, new, dup, blocked}，写日志
```

### 6.2 候选验证 → 入库上线（P0-2，每 2h 增量批）

```
Scheduler.tick_verify（互斥锁内，time_budget=25 分钟防超时，batch_size=200）
 1. batch = db.query("SELECT * FROM sources WHERE status='candidate' AND channel_key != ''
                       ORDER BY score DESC LIMIT 200")
 2. 提交 ThreadPoolExecutor(16)：
    r = verifier.probe(url, ua)     # 移植 probe_sources5：
      - .flv → probe_flv（tag 9 视频判定）
      - 否则 probe_m3u8：实连→非 #EXTM3U 即 fail；
        #EXT-X-STREAM-INF 逐级跟随(≤3 层)并收集 CODECS；
        #EXT-X-ENDLIST → fail(点播)；无分片 → fail；
        取最后一个分片 sniff_segment（TS: PAT→PMT 流类型→PES 计数；
        fMP4: ftyp/moof/styp 盒 + avcC/hvcC 嗅探；ADTS/ID3 纯音频；
        嵌套 m3u8 跟随；失败换第一个分片重试）；
        #EXT-X-MAP init 段补充判定
      - av: video / audio-only / 不稳定 / 未判定
 3. 批内先收集结果（超时即停止收集并 cancel，不再边到边落库），再做完整性 + 熔断判定：
    complete = 未超时 且 计划结果全部正常收齐（future 异常不计入）
    circuit  = complete 且 结果数 >=3 且全部 alive=False 且失败原因均为传输层异常
               （NetResolveError/SSLError/SSLEOFError/URLError/OSError/超时…；
                HTTP 状态、HTML 错误页、通用 NetPolicyError 等非传输原因一律否决）
 4. circuit 为真（首次验证整批 DNS/连接/TLS 失败）：零落库——不写 verifications、
    不改 sources 的 status/fail_streak/backoff_until/last_verified，记 warning，
    返回 stats.circuit_open=True；避免把"本次整体断网"逐源写成 fail 与指数退避
 5. 非熔断（含超时/结果不完整）：对已收集结果逐条落库（可中断续跑依据）：
    INSERT verifications(result, av, note, duration_ms)
    UPDATE sources: av=..., status = 'active'(result=ok 且 av=video)
                    或保持 candidate + last_verified（audio-only/未判定 不上线）
 6. 本批跑完或时间预算到 → 触发 OutputBuilder.build()（下个 tick 也行，简单起见当轮触发一次 rebuild 标记）
```

### 6.3 探活 → 容灾 → 重排 → 重建输出（P0-3/P0-5，每 30min）

```
Scheduler.tick_probe（互斥锁内）
 1. targets = sources WHERE status IN ('active','flaky')
 2. 并发首字节探活（fetch limit=512, timeout=8）：
    #EXTM3U 开头 或 FLV 开头 或 HTTP 200 有 body → alive
 3. 对每个结果 on_check(source, alive)：
    alive:  ok_streak+1, fail_streak=0
            若 status='flaky' 且 ok_streak>=2 → status='active' + health_events('online','连续2次恢复')
    dead:   fail_streak+1, ok_streak=0
            fail_streak==2 → status='flaky' + event('flaky')
            fail_streak>=3 → status='dead' + event('offline','连续3次探活失败')   # 失效源下线
            （dead 源降频仍被探活：P0 简化——dead 源每 N 轮（4×30min）探一次，恢复通道保留）
    INSERT health_checks(alive, latency_ms, reason)
 4. HealthMonitor.rebuild_lineups():
    对每个目标频道 key:
      cands = sources WHERE channel_key=key AND status='active' AND av='video'
             ORDER BY score DESC LIMIT max_lines(默认3)
      DELETE+INSERT lineups(channel_key, source_id, rank)
 5. OutputBuilder.build():
    items = lineups JOIN sources（按 channels.json 的分组顺序 ORDER/GROUPS 渲染）
    live.txt: 组名,#genre# + 频道名,url1#url2#url3     # 移植 iptv_pipeline.build 格式
    live.m3u: #EXTM3U + tvg-url(EPG 模板) + tvg-logo(gitee suxuang) + group-title
    tvbox_lives_snippet.json: url 指向 http://<host>:<port>/live.txt
    每个文件: 写 data/output/xxx.tmp → os.replace() 原子替换
```

### 6.4 HTTP 订阅（P0-5，常驻）

```
电视/TVBox → GET http://<NAS-IP>:8420/live.txt
  ThreadingHTTPServer 为该请求起 daemon 线程 → 读 data/output/live.txt（只读，永不与写者冲突）
  → 200, Content-Type: text/plain; charset=utf-8, Cache-Control: no-cache
GET /healthz → 200 'ok'（守护脚本/探活可用）
未知路径 → 404。无任何写操作接口（安全性）。
```

### 6.5 启动与崩溃恢复（P0-6）

```
main.py:
 1. Config.load() → 2. db = Database.connect(data/iptv.db); db.migrate()
 3. 若 sources 表为空 → 自动执行 import_seed 逻辑（种子兜底冷启动）
 4. OutputBuilder.build()   # 先用现有数据重建输出，HTTP 立即可用
 5. 起 Scheduler daemon 线程（首轮立即 tick_probe → tick_verify → tick_discovery）
 6. HttpServer.serve_forever()  # 主线程阻塞，进程由 systemd/计划任务守护
任何 tick 内部 try/except 到任务粒度：单仓库拉取失败/单源验证异常只记日志，不中断整轮，不杀进程。
```

时序图完整 Mermaid 源码另存于 `docs/sequence-diagram.mermaid`。

---

## 7. 共享约定（模块间接口契约，工程师必读）

1. **时间**：sqlite 内一律 ISO 8601 UTC 字符串（`datetime.now(timezone.utc).isoformat()`），比较用字符串序。
2. **av 判定枚举**：`video` / `audio-only` / `不稳定` / `未判定(...)`——与旧脚本 `频道源统计.json` 的取值一致，前端/日志不解析中文括号内容，只做 startswith。
3. **sources.status 枚举**：`candidate → active ⇄ flaky → dead →(ok_streak≥2)→ active`；`blacklisted` 为终态（仅人工清 rules 后重启）。状态跃迁必须写 health_events。
4. **URL 归一化**：strip 空白 + 去 fragment(#后) + host 小写；**不动 query**（现有源大量依赖 query 鉴权）。url_hash = sha1(归一化结果)。入库去重只认 url_hash（UNIQUE 索引兜底，重复 INSERT 用 `INSERT OR IGNORE` + 更新 last_seen）。
5. **网络**：所有外网请求必须走 `NetClient`（超时默认 8s、全局并发 ≤16、UA Chrome 伪装、TLS 证书链及主机名验证）；任何异常在调用方捕获，禁止向上冒泡出 tick。
6. **输出原子性**：data/output/ 下文件只允许 `atomic_write()` 写（.tmp + os.replace），HttpServer/其他模块只读。
7. **sqlite 访问**：单连接 + `check_same_thread=False` + 每次操作短事务（WAL 模式）；写操作统一经 db.execute（内部加 threading.Lock，避免 sqlite 内部锁）。
8. **频道键**：channel_key 必须取自 channels.json 的 85 键（CCTV1..17、省级卫视…），归一化规则移植 revival_channels.key_of + WEISHI 精确匹配 + channels.json aliases。
9. **配置热加载**：每次 tick 开始时重读 config/（JSON 小文件，开销可忽略），改配置下个周期生效。
10. **日志**：模块名作 logger name（discovery/verifier/health/builder/scheduler/http），关键动作（发现计数/验证结论/上下线/构建产物统计）必打 INFO，异常堆栈 ERROR。

---

## 8. 资源与容错控制（对照 P0-6）

| 约束 | 实现手段 |
|---|---|
| 内存 ≤ 200MB | 无大对象常驻：网络读上限 384KB/请求（与探针一致）；sqlite 游标分批；单进程 |
| 空闲 CPU ≤ 1% | 调度线程 sleep 到点，无自旋；探针解析限制在 376KB 数据内（移植原实现） |
| 网络并发 ≤ 16 | NetClient 全局 Semaphore + ThreadPoolExecutor(16)（settings 可调） |
| CPU 不打满单核 | 验证批 time_budget=25min、batch=200；探针为 IO 等待为主，GIL 释放 |
| 日志 ≤ 100MB | RotatingFileHandler 10MB×5 个备份 |
| 外网全挂不影响本机服务 | HTTP 只读本地文件，与网络域完全隔离；tick 异常全捕获 |
| 升级保留数据 | app/ 与 data/ 分离；meta.schema_version 幂等迁移 |
| 无人值守 | systemd Restart=always / Windows 计划任务开机启动；/healthz 供外部监控 |

---

## 9. P0 需求覆盖对照表

| PRD | 架构落点 |
|---|---|
| P0-1 自动源发现 | discovery.py 双通道 Provider + repos 表 + url_hash 去重 + 种子导入（冷启动即有 ~1600+ 条） |
| P0-2 候选验证入库 | verifier.py 移植 probe_sources5 全套媒体级验证；批内先收集再判定，非熔断时逐条落库=可中断续跑；整批传输层失败零落库熔断 |
| P0-3 健康监控容灾 | health.py 状态机（flaky 缓冲 + 连续 3 失败下线 + 连续 2 成功回归）+ health_events 历史表 |
| P0-4 定时调度 | scheduler.py 周期互斥编排（6h/2h/30min 可配） |
| P0-5 HTTP 订阅输出 | httpserver.py + builder.py（格式兼容旧产物，原子替换） |
| P0-6 低资源 NAS 适配 | 全标准库 + §8 控制表 + deploy/ 守护方案 |

## 10. 不明确处与假设（Anything UNCLEAR）

1. **假设 NAS 为 Linux 系（群晖/威联通/Debian）**：deploy 优先 systemd；若实为 Windows NAS 则用 `deploy/windows/start_iptv.bat` + 计划任务（两者都交付，PRD 待确认问题 1 未回）。
2. **假设沿用 192.168.1.10:8420**：settings.json 默认 `0.0.0.0:8420`（本机 IP 变化不影响监听，snippet 里的 host 单独配置）。
3. **假设 TVBox 多线路上限 3 条**：旧脚本输出 2 条，PRD 要求 ≥2 冗余，设计 max_lines=3（可配）折中数量与验证开销。
4. **guovin/iptv-api@gd 等 11 个仓库的具体文件路径**：需要 import 时逐个确认 jsDelivr 上的真实路径（如 Guovin/iptv-api@gd/output/result.m3u）——已作为 repos.json 的初始数据问题移交给工程师在 T03 实测校准。
5. **递归仓库挖掘、质量分、状态页、告警**（P1）：架构已留 Provider 接口 / verifications.duration_ms / HttpServer 静态路由挂点，不在 P0 实现。
