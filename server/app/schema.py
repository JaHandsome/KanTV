# -*- coding: utf-8 -*-
"""sqlite 迁移 DDL 与迁移钩子（schema v1..v10 的唯一事实来源）。

本模块从 app.db 原样搬出（纯搬移、零行为变更）：SCHEMA_VERSION、DDL_V1..V10、
逐列守卫的 ADD COLUMN 钩子（_apply_ddl_v5/v9/v10）、v5 回填后置钩子
（_post_migrate_v5）与 _MIGRATION_POST。app.db 以 re-export 保持旧 import
路径可用（测试与 tools 大量 from app.db import DDL_*/_apply_ddl_v5 等）。

url_key 计算直连 app.urlkey（契约冻结算法的唯一事实来源），
不再经 app.parser.ChannelParser 间接调用（parser 侧兼容委托保留，仅供旧调用方）。
"""
import json

from app import urlkey
from app.logutil import get_logger

log = get_logger('schema')

SCHEMA_VERSION = 10

# v2（P1）：失败候选退避截止时间 + status/channel_key 复合索引（探活查询走索引）
DDL_V2 = [
    'ALTER TABLE sources ADD COLUMN backoff_until TEXT',
    'CREATE INDEX IF NOT EXISTS idx_sources_status '
    'ON sources(status, channel_key)',
]

# v3（P0 收口 R3）：判定语义 6 分类 + 能力门禁所需 8 列 + 索引 + profile 回填。
#
# profile 列语义（P0 唯一常量集见下方 DELIVERABLE_PROFILES / NON_DELIVERABLE_PROFILES）：
#   - 前三者 = 投递通道（决定进哪几个输出文件）；
#   - 后两者 = 不可投递态（"为什么不可投递"由 status='drm' + verdict_reason 承载）。
#
# ⚠️ 回填必要性（缺一则迁移后 live.txt 静默清空）：
#   1) profile 列带 DEFAULT 'direct' —— SQLite 的 ADD COLUMN 会把默认值应用到所有既有行，
#      使旧行立即可被 profile IN (DELIVERABLE_PROFILES) 命中；
#   2) 迁移末尾追加幂等 UPDATE ... WHERE profile IS NULL —— 不依赖 ADD COLUMN 的隐式行为；
#   3) discovery.ingest() 按 encryption/drm 派生 profile（derive_profile）并**显式写入** ——
#      不再依赖列默认值。安全字段不能用 fail-open 默认：若某路径绕过 record() 把 DRM 源
#      直接置为 active，'direct' 会被渲染进 live.txt/live.m3u（裸 MPD URL 无密钥 => 黑屏）。
#      冷启动种子源无加密，派生结果同样是 'direct'，不丢；
#   4) status._data_invariant() 统计 profile IS NULL 的 active+video 行并告警（可观测）。
DDL_V3 = [
    # —— 8 个新列（PRD FR-3 / AC-3.1） ——
    'ALTER TABLE sources ADD COLUMN container TEXT',        # 'hls'|'flv'|'dash'
    'ALTER TABLE sources ADD COLUMN encryption TEXT',       # ''|'aes128'|'sample-aes'|'cenc'|'cbcs'
    'ALTER TABLE sources ADD COLUMN drm TEXT',              # ''|'clearkey'|'widevine'|'playready'|'cenc-unknown'
    'ALTER TABLE sources ADD COLUMN keyformat TEXT',        # HLS KEYFORMAT / MPD schemeIdUri
    'ALTER TABLE sources ADD COLUMN kid TEXT',              # 32 位小写 hex；P0 恒 NULL，R6 起填充
    # ↓↓ 必须带 DEFAULT：SQLite 的 ADD COLUMN 会把默认值应用到所有既有行 ↓↓
    "ALTER TABLE sources ADD COLUMN profile TEXT DEFAULT 'direct'",  # 投递画像
    'ALTER TABLE sources ADD COLUMN caps_required TEXT',    # JSON 字符串：客户端能力需求
    'ALTER TABLE sources ADD COLUMN verdict_reason TEXT',   # fail/need_* 的具体原因
    # —— 索引（命中 _select_batch / _coverage / builder / reevaluate 的过滤） ——
    'CREATE INDEX IF NOT EXISTS idx_sources_container ON sources(container, status)',
    'CREATE INDEX IF NOT EXISTS idx_sources_status_profile ON sources(status, profile)',
    # —— 数据回填（双保险；WHERE profile IS NULL 无匹配时为空操作） ——
    "UPDATE sources SET profile='direct' WHERE profile IS NULL",
]

# v4（P0 收口缺陷修复）：回填 container。
#
# ⚠️ 为什么必须 bump 到 v4、不能改 DDL_V3：
#   NAS 上的库已把 meta.schema_version 记为 3；migrate() 由版本号守卫，
#   current(3) >= 3 会直接跳过 DDL_V3 —— 修改 DDL_V3 对已迁移的库【永不生效】。
#   故 container 回填必须作为新版本 v4 交付，任何"顺手优化回 v3"都会静默失效。
#   仅回填 NULL/空值（幂等，可重复执行）。
#
# 背景：DDL_V3 的 `ADD COLUMN container TEXT` 无默认值 -> 历史行 container 为 NULL；
#   而 _select_batch 只选 candidate，已 active 的源永不再深度探测 -> container 永久 NULL，
#   builder 若原样输出会得到空 container（违反 live.media3.json 必填契约）。
_DDL_V4_PATH = ("lower(trim(CASE WHEN instr(url,'?')>0 "
                "THEN substr(url,1,instr(url,'?')-1) ELSE url END))")
DDL_V4 = [
    "UPDATE sources SET container = CASE "
    "WHEN %s LIKE '%%.mpd' THEN 'dash' "
    "WHEN %s LIKE '%%.flv' THEN 'flv' "
    "ELSE 'hls' END "
    "WHERE container IS NULL OR container = ''" % (_DDL_V4_PATH, _DDL_V4_PATH),
]

# v5（P1 阻塞点）：url_key 时效签名归一化 —— 物理源身份键（纯附加列 + 两个索引）。
#
#   url_key          : sha1(strip_signed_params(normalize_url(url)))  —— 去重身份键
#   key_source       : 'raw' | 'normalized'（审计：url_key 是否等于字面 url_hash）
#   url_observed_at  : 该 url 字面最后一次被观察到的时间（供签名刷新通道判新鲜度）
#
# ⚠️ 为什么必须 bump 到 v5、不能改 v3/v4：migrate() 由 meta.schema_version 守卫，
#   NAS 已记 v4 -> current(4) >= 4 会跳过旧 DDL。任何"顺手改回旧版本"永不生效。
#
# ⚠️ 回填必须用 Python：SQLite 无 sha1()，且 host 作用域参数需 Python 计算 ——
#   ADD COLUMN 不带 DEFAULT -> 历史行 url_key 为 NULL（NULL 在 IN/UNIQUE 中永不匹配），
#   不回填则跨日签名轮换时插入重复行、去重彻底失效。回填由后置钩子完成。
#
# ⚠️ 重入性：DDL 在 sqlite3 下可能自动提交；ADD COLUMN 无 IF NOT EXISTS，
#   迁移中断后重跑会 "duplicate column name" 永久卡死 -> 必须逐列 _column_exists 守卫。
_V5_ADD_COLUMNS = (
    ('url_key', 'ALTER TABLE sources ADD COLUMN url_key TEXT'),
    ('key_source', 'ALTER TABLE sources ADD COLUMN key_source TEXT'),
    ('url_observed_at', 'ALTER TABLE sources ADD COLUMN url_observed_at TEXT'),
)
# 非唯一索引先建（利于回填 UPDATE 走索引）；唯一身份索引由后置钩子在碰撞消解后建。
_V5_NONUNIQUE_INDEX = 'CREATE INDEX IF NOT EXISTS idx_sources_url_key ON sources(url_key)'
DDL_V5 = [stmt for _col, stmt in _V5_ADD_COLUMNS] + [_V5_NONUNIQUE_INDEX]
# 碰撞消解落败行状态（team-lead 裁定 5：非破坏性保留，绝不 DELETE）。
# 落败行 url_key 置 NULL（脱离身份空间，唯一索引不冲突），status 转本值，
# 历史（verifications/health_checks/lineups）由 ON DELETE CASCADE 未触发而完整保留。
COLLISION_ORPHAN_STATUS = 'collision-orphan'

# v6（HK MPD 路线）：内联 ClearKey 密钥材料列（纯附加 ADD COLUMN，无需回填）。
#
#   drm_key : 清单 #KODIPROP 的 license_key 原始 JSON（base64url 原样）；仅 drm='clearkey'
#             行非空，历史行恒 NULL = "无内联密钥"。
#
# ⚠️ 为什么必须 bump 到 v6、不改旧 DDL：migrate() 由 meta.schema_version 守卫，
#   NAS 已记 v5 -> current(5) >= 5 会跳过旧 DDL；任何"顺手改回旧版本"永不生效。
# ⚠️ 密钥与 url 同为"清单产物"、同生命周期（每日轮换）——必须在 ingest() 的签名轮换
#   刷新分支里与 url 同源同刻更新，否则"URL 是今天的、密钥是昨天的"→ 客户端黑屏。
#   密钥非机密（ClearKey 为公开密钥），库仅在 NAS 本地。
DDL_V6 = [
    'ALTER TABLE sources ADD COLUMN drm_key TEXT',
]

# v7（枢纽 Hub，HUB CONTRACT v1）：客户端真机回流表（纯新增表 + 2 个索引，**无回填**）。
#
#   playback_reports : 客户端（kantv）上报的"真机播放结果"原始记录，**只增不改**。
#
# ⚠️ 为什么必须 bump 到 v7、不能改旧 DDL：migrate() 由 meta.schema_version 守卫，
#   生产库已记 v6 -> current(6) >= 6 会跳过旧 DDL；任何"顺手改回 v6"永不生效。
#   本版本全部 DDL 均为 IF NOT EXISTS，天然幂等可重入（新增表无 ADD COLUMN 风险）。
#
# ⚠️ 语义红线（HUB_CONTRACT_V1 §4.1）：回流是**高置信度但低样本量**的信号。
#   本表数据**只用于记录与只读聚合**，绝不作为自动删除 / 降级 / 拉黑任何源的依据。
#   "失败不删除已有源"的既有纪律在 v1 继续有效。
#
# ⚠️ 保留期：与历史表一致（>=90 天，由 cleanup(retention_days) 裁剪）；见 HANDOFF「枢纽」节。
DDL_V7 = [
    """CREATE TABLE IF NOT EXISTS playback_reports (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  reported_at    TEXT NOT NULL,
  client         TEXT,
  app            TEXT,
  device_model   TEXT,
  android_api    INTEGER,
  session        TEXT,
  channel_id     TEXT,
  url_key        TEXT,
  route          TEXT,
  outcome        TEXT,
  engine         TEXT,
  first_frame_ms INTEGER,
  stalls         INTEGER,
  stall_seconds  REAL,
  play_seconds   REAL,
  fail_reason    TEXT,
  observed_at    TEXT,
  raw_json       TEXT
)""",
    # 聚合主键序（feedback_summary 按 channel_id + url_key 分组）
    'CREATE INDEX IF NOT EXISTS idx_pr_channel_url_key ON playback_reports(channel_id, url_key)',
    # 保留期裁剪 + 时间序查询
    'CREATE INDEX IF NOT EXISTS idx_pr_reported_at ON playback_reports(reported_at)',
]

# v8（枢纽 v1.1 §4）：客户端**协议级事件**表（`notices[]`；纯新增表 + 1 个索引，**无回填**）。
#
#   hub_notices : 客户端上报的协议级事件（如 schema_unsupported / caps_unknown）。
#
# ⚠️ 为什么**不**复用 playback_reports 塞 notice（HUB_CONTRACT_V1 v1.1 §4 允许两种做法）：
#   1) playback_reports 的 `outcome` 取值域是 ok|partial|fail（契约 §4），notice 会让该列
#      出现第四种语义，后续任何按 outcome 聚合的查询都要额外过滤；
#   2) notice 的语义单位是 `code`（schema_unsupported 等），必须**可查询、可计数**：
#      `schema_unsupported` 意味着"客户端正在拒绝应用订阅"，是要人工介入的信号，
#      塞进 raw_json 会让它只能靠 JSON 解析才看得见，等于没有可观测性；
#   3) 表名即语义：`playback_reports` 是"真机播放结果"，notice 不是播放结果。
#   代价是必须 bump 一次 schema 版本（旧版本号已被生产库占用，改旧 DDL 永不生效）。
#
# ⚠️ 保留期：与历史表同档（retention_days，>=90 天），由 cleanup() 裁剪。
DDL_V8 = [
    """CREATE TABLE IF NOT EXISTS hub_notices (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  reported_at TEXT NOT NULL,
  client      TEXT,
  app         TEXT,
  session     TEXT,
  code        TEXT,
  detail      TEXT,
  raw_json    TEXT
)""",
    'CREATE INDEX IF NOT EXISTS idx_notices_reported_at ON hub_notices(reported_at)',
    'CREATE INDEX IF NOT EXISTS idx_notices_code ON hub_notices(code)',
]

# v10（分片吞吐纳入线路评分）：吞吐探测历史表 + sources.base_score 快照列。
#
#   throughput_checks : 每次分片计时探测一条原始记录（health 写，追加不改）；
#                       ratio = 分片下载耗时 / 分片时长（中位），>1 即"下不满"。
#
# ⚠️ 为什么必须 bump 到 v10、不能改旧 DDL：migrate() 由 meta.schema_version 守卫，
#   生产库已记 v9 -> current(9) >= 9 会跳过旧 DDL；任何"顺手改回 v9"永不生效。
#   本版本表 DDL 均为 IF NOT EXISTS，天然幂等可重入；base_score 为 ADD COLUMN，
#   与 v5/v9 同因必须逐列 _column_exists 守卫（迁移中断重跑防 "duplicate column name"）。
#
# ⚠️ 为什么吞吐分必须落到 sources.score（而不是只改 lineups rank）：
#   hub.py payload 路由与 App 侧选线都按 score 降序 —— 只改 lineups rank 会被
#   score 排序盖掉。故 apply_throughput_scores() 直接折算 score 本身，
#   base_score 列是折算前的"纯净基线"快照（首次运行幂等回填现有 score），
#   保证吞吐因子只作用于基线、不会把上一次的折算结果再折算一次（防复利漂移）。
#
# ⚠️ 为什么窗口内"跑不满"样本必须一票决定因子（throughput.throughput_window_factor）：
#   中位会把单次实测劣化稀释掉 —— 2026-09-24 TVB 翡翠台 58a87b65 窗口
#   [1.28, 0.78, 0.27] 中位 0.78 -> 因子 0.9 -> score 81，仍是投递阵容首位，
#   而 ratio>=1 意味着下载慢于实时（直播必卡），不是待平均的噪声。
#
# ⚠️ ratio 的 NULL 语义：全部探测分片下载失败 -> ratio=NULL、ok=0，
#   与"成功但很慢"（ratio 很大）是两件完全不同的事 —— 聚合时必须分开对待
#   （apply_throughput_scores 把失败按 2.0 罚分计入窗口，而不是忽略）。
DDL_V10 = [
    """CREATE TABLE IF NOT EXISTS throughput_checks (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id       INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  ts              TEXT NOT NULL,
  seg_duration_ms INTEGER,
  download_ms     INTEGER,
  ratio           REAL,
  ok              INTEGER NOT NULL,
  reason          TEXT
)""",
    'CREATE INDEX IF NOT EXISTS idx_tc_source_ts ON throughput_checks(source_id, ts)',
]

# sources.base_score：吞吐折算基线（v10 新增列，逐列守卫 + 幂等回填见 _apply_ddl_v10）。
_V10_ADD_COLUMNS = (
    ('base_score', 'ALTER TABLE sources ADD COLUMN base_score REAL'),
)

# ---------- v10 迁移钩子（逐列守卫 + 基线回填，幂等可重入） ----------
def _apply_ddl_v10(conn):
    """执行 v10（幂等可重入）：ADD COLUMN 逐列判存在；base_score 回填为当前 score。

    与 v5/v9 同因 —— ADD COLUMN 不幂等，迁移中断重跑会因"列已存在"卡死，
    故逐列 _column_exists 守卫。回填仅 NULL 行（WHERE base_score IS NULL），
    重复执行不产生二次副作用。
    """
    for col, stmt in _V10_ADD_COLUMNS:
        if not _column_exists(conn, 'sources', col):
            conn.execute(stmt)
    conn.execute('UPDATE sources SET base_score=score WHERE base_score IS NULL')

# v9（枢纽：回流「画面冻结」字段组，HUB_CONTRACT_CHANGELOG 2026-09-18「两端冻结」）：
#   playback_reports 加 7 列，**全部 ADD COLUMN、无回填**。
#
# ⚠️ 为什么必须 bump 到 v9、不能改旧 DDL：migrate() 由 meta.schema_version 守卫，
#   生产库已记 v8 -> current(8) >= 8 会跳过旧 DDL；任何"顺手改回 v8"永不生效。
#
# ⚠️ 为什么用"逐列判存在"而不是裸 ALTER：与 v5 同因 —— ADD COLUMN 不幂等，
#   若某次迁移在 commit 前中断，重跑会因"列已存在"整体失败，把库卡在半迁移态。
#
# ⚠️ NULL 的语义（本表最要紧的一点）：旧行与新列全为 NULL = **该版本客户端根本没上报冻结字段**，
#   与"上报了、且值为 0"是两件完全不同的事。任何聚合都必须用 `freeze_seconds IS NOT NULL`
#   把两者分开，否则会把"没数据"算成"没有冻结"——那正是这次 TVB翡翠台 争议的翻版。
#
# ⚠️ `freeze_contract_ok` 是**服务端派生列，客户端不得上报**（三态）：
#   NULL = 未上报冻结字段；1 = 五个字段整组齐且满足全部不变式；0 = 整组齐但违反不变式。
#   "违反只计数、绝不 4xx"是两端冻结时给客户端的承诺，该列就是那个计数器。
#
# 常量与不变式与 `docs/HUB_CONTRACT_CHANGELOG.md` 同源（FREEZE_THRESHOLD_SECONDS=6）：
#   ① 0 ≤ freeze_seconds ≤ active_seconds
#   ② 0 ≤ freeze_longest_seconds ≤ freeze_seconds
#   ③ freeze_segments == 0 ⟺ freeze_longest_seconds == 0；freeze_segments ≥ 1 ⟹ longest ≥ 6
#   ④ 五个字段整组给齐或整组不给
# 保留期：与历史表同档（retention_days），由 cleanup() 裁剪。
_V9_ADD_COLUMNS = [
    ('active_seconds', 'ALTER TABLE playback_reports ADD COLUMN active_seconds REAL'),
    ('freeze_seconds', 'ALTER TABLE playback_reports ADD COLUMN freeze_seconds REAL'),
    ('freeze_segments', 'ALTER TABLE playback_reports ADD COLUMN freeze_segments INTEGER'),
    ('freeze_longest_seconds',
     'ALTER TABLE playback_reports ADD COLUMN freeze_longest_seconds REAL'),
    ('picture_progress_events',
     'ALTER TABLE playback_reports ADD COLUMN picture_progress_events INTEGER'),
    ('freeze_long_segments',
     'ALTER TABLE playback_reports ADD COLUMN freeze_long_segments INTEGER'),
    ('freeze_contract_ok',
     'ALTER TABLE playback_reports ADD COLUMN freeze_contract_ok INTEGER'),
]

DDL_V9 = [stmt for _col, stmt in _V9_ADD_COLUMNS]
# 域 0：元数据
DDL_V1 = [
    """CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
)""",
    # 域 1：候选池
    """CREATE TABLE IF NOT EXISTS sources (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  url           TEXT NOT NULL,
  url_hash      TEXT NOT NULL,
  channel_name  TEXT NOT NULL,
  channel_key   TEXT NOT NULL,
  origin        TEXT NOT NULL DEFAULT '',
  status        TEXT NOT NULL DEFAULT 'candidate',
  av            TEXT,
  score         REAL NOT NULL DEFAULT 0,
  first_seen    TEXT NOT NULL,
  last_seen     TEXT NOT NULL,
  last_verified TEXT,
  fail_streak   INTEGER NOT NULL DEFAULT 0,
  ok_streak     INTEGER NOT NULL DEFAULT 0
)""",
    "CREATE UNIQUE INDEX IF NOT EXISTS uq_sources_url ON sources(url_hash)",
    "CREATE INDEX IF NOT EXISTS idx_sources_channel ON sources(channel_key, status, score DESC)",
    "CREATE INDEX IF NOT EXISTS idx_sources_candidate ON sources(status, score DESC) WHERE status='candidate'",
    """CREATE TABLE IF NOT EXISTS repos (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  repo      TEXT NOT NULL,
  branch    TEXT NOT NULL DEFAULT 'main',
  path      TEXT NOT NULL,
  channel   TEXT NOT NULL DEFAULT 'jsdelivr',
  enabled   INTEGER NOT NULL DEFAULT 1,
  last_fetch TEXT,
  last_ok    TEXT,
  note      TEXT DEFAULT '',
  UNIQUE(repo, branch, path, channel)
)""",
    # 域 2：验证结果
    """CREATE TABLE IF NOT EXISTS verifications (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id   INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  ts          TEXT NOT NULL,
  result      TEXT NOT NULL,
  av          TEXT,
  note        TEXT,
  duration_ms INTEGER
)""",
    "CREATE INDEX IF NOT EXISTS idx_verif_source ON verifications(source_id, ts)",
    # 域 3：健康历史
    """CREATE TABLE IF NOT EXISTS health_checks (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id  INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  ts         TEXT NOT NULL,
  alive      INTEGER NOT NULL,
  latency_ms INTEGER,
  reason     TEXT
)""",
    "CREATE INDEX IF NOT EXISTS idx_checks_source ON health_checks(source_id, ts)",
    """CREATE TABLE IF NOT EXISTS health_events (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  ts        TEXT NOT NULL,
  event     TEXT NOT NULL,
  reason    TEXT
)""",
    # 线路表
    """CREATE TABLE IF NOT EXISTS lineups (
  channel_key TEXT NOT NULL,
  source_id   INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  rank        INTEGER NOT NULL,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (channel_key, source_id)
)""",
]


# ---------- v5 迁移钩子（纯附加迁移，幂等可重入） ----------
def _column_exists(conn, table, column):
    """PRAGMA table_info 判列存在（迁移重入守卫）。

    table 为内部常量（'sources'），无注入面。PRAGMA 不支持参数绑定，故字符串拼接。
    """
    return any(r[1] == column for r in conn.execute('PRAGMA table_info(%s)' % table))


def _apply_ddl_v5(conn):
    """执行 DDL_V5（幂等可重入）：ADD COLUMN 逐列判存在；索引一律 IF NOT EXISTS。"""
    for col, stmt in _V5_ADD_COLUMNS:
        if not _column_exists(conn, 'sources', col):
            conn.execute(stmt)
    conn.execute(_V5_NONUNIQUE_INDEX)


def _apply_ddl_v9(conn):
    """执行 DDL_V9（幂等可重入）：ADD COLUMN 逐列判存在（与 v5 同因，见 DDL_V9 注释）。"""
    for col, stmt in _V9_ADD_COLUMNS:
        if not _column_exists(conn, 'playback_reports', col):
            conn.execute(stmt)


def _key_source(uk, url):
    """审计标记：url_key 剥离过签名 -> 'normalized'；与字面 url_hash 相同 -> 'raw'。"""
    return 'normalized' if uk != urlkey.url_hash(url) else 'raw'


def _post_migrate_v5(conn, settings=None):
    """v5 后置钩子：回填 url_key + 碰撞消解 + 建唯一索引（幂等、可重入）。

    幂等语义：连跑两次不产生二次副作用 —— 已计算的行（url_key 非 NULL）跳过重算与 UPDATE；
    已转 collision-orphan 的行跳过（其 url_key 保持 NULL，不再参与身份）。
    碰撞消解为【非破坏性】（team-lead 裁定 4/5）：幸存者优先保留 `active` 状态行，
    其余仅置空 url_key + 转 collision-orphan，**绝不 DELETE**（历史全量保留）。
    """
    # utcnow 属 app.db（C 组，本批不动）；惰性 import 避免 db<->schema 顶层循环 import，
    # 且保持 app.db.utcnow 的 mock.patch 点在调用期解析（与原行为一致）。
    from app.db import utcnow
    settings = settings if isinstance(settings, dict) else {}
    now = utcnow()
    rows = [dict(r) for r in conn.execute(
        'SELECT id, url, url_key, status, last_seen, last_verified FROM sources')]

    groups = {}      # uk -> [row, ...]
    needs_key = 0    # 本次需要计算并写回的行数
    orphans = 0      # 既存 collision-orphan 行数（重入时被跳过）
    for r in rows:
        url = r.get('url') or ''
        uk = r.get('url_key')
        if not uk:
            if r.get('status') == COLLISION_ORPHAN_STATUS:
                orphans += 1
                continue
            uk = urlkey.url_key(
                url, urlkey.resolve_signed_params(url, settings))
            needs_key += 1
        groups.setdefault(uk, []).append(r)

    collisions = 0
    for uk, g in groups.items():
        if len(g) == 1:
            r = g[0]
            if r.get('url_key'):        # 已回填 -> 幂等跳过
                continue
            url = r.get('url') or ''
            conn.execute(
                'UPDATE sources SET url_key=?, key_source=?, url_observed_at=? WHERE id=?',
                (uk, _key_source(uk, url), now, r['id']))
            continue
        # 碰撞组：幸存者 = active 优先 -> last_seen 最大 -> id 最大（裁定 4）
        collisions += 1
        keep = max(g, key=lambda r: (1 if r.get('status') == 'active' else 0,
                                     r.get('last_seen') or '', r['id']))
        keep_id = keep['id']
        losers = [r['id'] for r in g if r['id'] != keep_id]
        keep_url = keep.get('url') or ''
        conn.execute(
            'UPDATE sources SET url_key=?, key_source=?, url_observed_at=? WHERE id=?',
            (uk, _key_source(uk, keep_url), now, keep_id))
        log.warning('url_key 碰撞消解: key=%s 保留 id=%s (status=%s), 转孤儿 id=%s',
                    uk[:12], keep_id, keep.get('status'), losers)
        conn.executemany(
            'UPDATE sources SET url_key=NULL, status=?, verdict_reason=? WHERE id=?',
            [(COLLISION_ORPHAN_STATUS,
              'url_key collision merged into id=%d' % keep_id, i) for i in losers])

    # 唯一身份索引（碰撞已消解；collision-orphan 的 url_key 为 NULL，互不冲突）
    conn.execute('CREATE UNIQUE INDEX IF NOT EXISTS uq_sources_url_key ON sources(url_key)')
    if needs_key or collisions:
        log.info('v5 回填完成: 计算 %d 行, 碰撞组 %d, 既存孤儿 %d', needs_key, collisions, orphans)


# ---------- P0-2 方案 C：名单指纹 + 启动重建（无 DDL 变更，不加 schema 版本） ----------
# url_key 是「可按当前生效名单重算的派生列」：名单（含 settings.json 覆盖）变更后
# 存量行 url_key 变陈旧（refresh 匹配不上 → INSERT OR IGNORE 被 url_hash 唯一索引
# 静默吞掉 / 签名轮换行重复插入），由指纹守卫在调度 tick（含启动首轮）触发全表重算。
# meta 键名：生效名单指纹锚点。
SIGNED_PARAMS_FP_META_KEY = 'signed_params_fingerprint'
# ARCH P1-6：最近一次全表 url_key 重建的统计锚点（JSON：时间 + recomputed/
# unchanged/collisions）。无人值守 NAS 没人看日志——补算是否发生、撞键多少，
# 必须能在 /api/status 直接看到。由 ensure_url_keys_fresh 在**同一事务**落盘。
URL_KEY_REBUILD_STATS_META = 'url_key_rebuild_last'


def rebuild_url_keys(conn, settings):
    """全表按当前生效名单重算 url_key（含撞键消解）。返回统计 dict（幂等可重入）。

    与 _post_migrate_v5 同一纪律：
    - 非破坏性（裁定 5）：撞键落败行仅 url_key 置 NULL + 转 collision-orphan，绝不 DELETE；
    - 幸存者规则（裁定 4）：active 优先 -> last_seen 最大 -> id 最大；
    - collision-orphan 行（含历史落败者）保持脱离身份空间，不参与重算。
    幂等：重算结果与当前名单一致时全部行跳过（零 UPDATE）；可安全重复执行。
    调用方负责事务与指纹 meta 落盘（见 ensure_url_keys_fresh）。
    """
    from app.db import utcnow
    settings = settings if isinstance(settings, dict) else {}
    now = utcnow()
    rows = [dict(r) for r in conn.execute(
        'SELECT id, url, url_key, status, last_seen FROM sources')]

    # 1) 每行按当前名单重算 new key，按 new key 分组（撞键消解必须覆盖
    #    「本行 key 未变但别行重算进来撞它」的情形，故全体行都参与分组）
    groups = {}
    unchanged = 0
    for r in rows:
        if not r.get('url_key') and r.get('status') == COLLISION_ORPHAN_STATUS:
            continue                       # 历史落败者保持孤儿（与 v5 幂等语义一致）
        url = r.get('url') or ''
        uk = urlkey.url_key(url, urlkey.resolve_signed_params(url, settings))
        r['_new_key'] = uk
        groups.setdefault(uk, []).append(r)

    # 2) 落库：单行者 key 变了才 UPDATE；撞键组按裁定 4 消解
    recomputed = 0
    collisions = 0
    for uk, g in groups.items():
        if len(g) == 1:
            r = g[0]
            if r['_new_key'] == r.get('url_key'):
                unchanged += 1
                continue
            conn.execute(
                'UPDATE sources SET url_key=?, key_source=?, url_observed_at=? WHERE id=?',
                (uk, _key_source(uk, r.get('url') or ''), now, r['id']))
            recomputed += 1
            continue
        collisions += 1
        keep = max(g, key=lambda r: (1 if r.get('status') == 'active' else 0,
                                     r.get('last_seen') or '', r['id']))
        losers = [r for r in g if r['id'] != keep['id']]
        conn.execute(
            'UPDATE sources SET url_key=?, key_source=?, url_observed_at=? WHERE id=?',
            (uk, _key_source(uk, keep.get('url') or ''), now, keep['id']))
        recomputed += 1
        log.warning('url_key 重建撞键消解: key=%s 保留 id=%s (status=%s), 转孤儿 id=%s',
                    uk[:12], keep['id'], keep.get('status'), [r['id'] for r in losers])
        conn.executemany(
            'UPDATE sources SET url_key=NULL, status=?, verdict_reason=? WHERE id=?',
            [(COLLISION_ORPHAN_STATUS,
              'url_key collision merged into id=%d' % keep['id'], r['id'])
             for r in losers])
    stats = {'recomputed': recomputed, 'unchanged': unchanged, 'collisions': collisions}
    if recomputed or collisions:
        log.info('url_key 全表重建完成: %s', stats)
    return stats


def ensure_url_keys_fresh(db, settings):
    """指纹守卫：生效名单指纹与 meta 锚点一致 -> 返回 None（零操作）；
    不一致 -> 全表重算 url_key（含撞键消解）并把新指纹写入 meta，返回统计 dict。

    幂等：指纹不变时永远零操作，调用方可以每 tick 安全调用。
    指纹变化即名单变化（含 settings.json 热重载），此时存量行 url_key 全部按
    当前名单重算，系统对任何原因的陈旧 key 自愈。
    """
    fp = urlkey.signed_params_fingerprint(settings)
    if db.get_meta(SIGNED_PARAMS_FP_META_KEY) == fp:
        return None
    from app.db import utcnow   # 惰性 import，与 rebuild_url_keys 同因（见该函数注释）
    with db.transaction():
        stats = rebuild_url_keys(db.conn, settings)
        db.conn.execute('INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)',
                        (SIGNED_PARAMS_FP_META_KEY, fp))
        db.conn.execute('INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)',
                        (URL_KEY_REBUILD_STATS_META,
                         json.dumps(dict(stats, at=utcnow()), sort_keys=True)))
    return stats


# 版本 -> 后置钩子（DDL 执行后、schema_version 提交前调用）。钩子签名 (conn, settings)。
_MIGRATION_POST = {5: _post_migrate_v5}
