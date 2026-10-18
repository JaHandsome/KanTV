# -*- coding: utf-8 -*-
"""sqlite 封装：WAL + busy_timeout + threading.Lock 单写连接 + meta.schema_version 迁移 + 数据清理。

表结构（ARCHITECTURE §4.1，10 张表）：
  meta            元数据（schema_version 锚点，升级保留数据）
  sources         候选池（discovery 写，verifier/health 更新状态）
  repos           种子仓库清单
  verifications   验证结果（追加不改）
  health_checks   探活原始结果
  health_events   状态机跃迁事件
  throughput_checks 分片吞吐探测原始结果（v10 新增，health 写；ratio=下载耗时/分片时长）
  lineups         频道线路表（health 重排写，builder 只读）
  playback_reports 客户端真机回流（v7 新增，枢纽写；**只记录不自动动作**）
  hub_notices    客户端协议级事件 notices[]（v8 新增，枢纽写；如 schema_unsupported）

约定（ARCHITECTURE §7.1/7.7）：
- 时间戳一律 ISO 8601 UTC 字符串，比较用字符串序；
- 写操作统一经 execute/executemany（内部加 threading.Lock）。

本模块保留 Database 连接/事务门面、cleanup 裁剪、共享叶子常量（投递画像 /
FREEZE_*）与时间工具；迁移 DDL 与在线备份已分别迁至 app.schema / app.dbbackup，
下方以 re-export 保持 from app.db import ... 旧路径可用（纯搬移、零行为变更）。
"""
import contextlib
import datetime
import os
import sqlite3
import threading

from app import schema
from app.config import DEFAULT_SETTINGS
from app.dbbackup import _quick_check_ok, _readonly_uri, _remove_quiet, online_backup
from app.logutil import get_logger
# 兼容 re-export：迁移 DDL / 钩子迁至 app.schema 后，旧 import 路径保持可用
# （测试与 tools 大量 from app.db import DDL_*/_apply_ddl_v5/_post_migrate_v5 等）。
from app.schema import (COLLISION_ORPHAN_STATUS, DDL_V1, DDL_V2, DDL_V3, DDL_V4,
                        DDL_V5, DDL_V6, DDL_V7, DDL_V8, DDL_V9, DDL_V10,
                        SCHEMA_VERSION, _apply_ddl_v5, _apply_ddl_v9,
                        _column_exists, _post_migrate_v5)

log = get_logger('db')

# 投递画像常量（四模块共享的叶子常量，避免横向依赖；任何判断 profile 处禁止内联字符串）。
# 语义分野：前三者=投递通道；后两者=不可投递态。
DELIVERABLE_PROFILES = ('direct', 'm3u-kodiprop', 'media3-json')   # 投递通道
NON_DELIVERABLE_PROFILES = ('need-key', 'unsupported-drm')         # 不可投递态


def delivery_profile(encryption, drm):
    """唯一叶子派生：加密/DRM 事实 -> 投递画像（fail-closed，无副作用）。

    规则（``discovery.derive_profile``、``verifier.classify_verdict`` ok 分支、
    ``verifier.reevaluate_drm_pool`` 全部委托本函数，杜绝多处内联判定漂移）：

    - ``encryption`` 规范化（strip + lower）后不在 ``('', 'aes128')`` -> ``'media3-json'``；
    - ``drm`` 规范化（strip + lower）后非空 -> ``'media3-json'``（``encryption`` 缺失的兜底）；
    - 否则 -> ``'direct'``（客户端原生可播、无需密钥机制）。

    ★ 主判据是 ``encryption``：``sample-aes`` 的 ``drm`` 为空，只看 ``drm`` 会 fail-open。
    本函数是**唯一叶子**：explicit override 属调用方语义（见 discovery.derive_profile），
    不放进这里，避免叶子函数承担覆盖策略。
    """
    enc = (encryption or '').strip().lower()
    if enc not in ('', 'aes128') or (drm or '').strip().lower():
        return 'media3-json'
    return 'direct'


def infer_container_from_url(url):
    """按 URL 路径后缀推断容器类型（唯一实现，迁移回填与 builder 兜底共用）。

    规则：路径部分（`?` 之前）以 `.mpd` 结尾 -> 'dash'；`.flv` -> 'flv'；
    其余（`.m3u8` / `.m3u` / 无扩展名）-> 'hls'。先 strip 再 lower，忽略签名 query。
    DDL_V4 的 SQL 回填是本函数规则的等价 SQL 表达（SQL 无法 import Python），
    tests 中以代表性 URL 断言两者结果一致，防漂移。
    """
    u = (url or '').strip().lower()
    path = u.split('?', 1)[0]
    if path.endswith('.mpd'):
        return 'dash'
    if path.endswith('.flv'):
        return 'flv'
    return 'hls'



# 冻结判定阈值（两端冻结常量）。freeze_seconds 因此是**保守下限**：
# 未达 6 s 的停顿不被计数。
FREEZE_THRESHOLD_SECONDS = 6
# 报表口径阈值：**仅** freeze_long_segments 使用，不参与不变式。
FREEZE_REPORT_THRESHOLD_SECONDS = 45
# 整组上报的五个字段（缺任一 => 整组视为未上报，不做部分采信）。
FREEZE_FIELDS = ('active_seconds', 'freeze_seconds', 'freeze_segments',
                 'freeze_longest_seconds', 'picture_progress_events')
# 可选的报表字段（不参与"整组"判定与不变式）。
FREEZE_OPTIONAL_FIELDS = ('freeze_long_segments',)


def utcnow():
    """ISO 8601 UTC 时间字符串（sqlite 全部时间戳的统一格式）。"""
    return datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def iso_plus_hours(hours):
    """当前时间 + hours 的 ISO 8601 UTC 字符串（24h 验证缓存的截止线）。"""
    t = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=hours)
    return t.strftime('%Y-%m-%dT%H:%M:%SZ')



class Database(object):
    """sqlite 连接管理：WAL、busy_timeout、线程锁串行化写、版本迁移。"""

    def __init__(self, path):
        self.path = path
        d = os.path.dirname(path)
        if d:
            os.makedirs(d, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(path, check_same_thread=False, timeout=10)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute('PRAGMA journal_mode=WAL')
        self.conn.execute('PRAGMA busy_timeout=5000')
        # P0 性能：WAL 下 NORMAL 只在 checkpoint 时 fsync（FULL 是每次 commit），
        # 断电最多丢最后一个事务、不损库——对可重建的探活/验证数据完全可接受，
        # 消除每轮探活/入库的万级 fsync（NAS 机械盘 IO 头号瓶颈）。
        self.conn.execute('PRAGMA synchronous=NORMAL')
        self.conn.execute('PRAGMA foreign_keys=ON')

    # ---------- 结构 ----------
    def migrate(self, settings=None):
        """按 meta.schema_version 逐版本执行 DDL（幂等，可重复执行）。

        settings：可选。v5 回填需要用真实的签名参数名单（全局 + host 作用域）；
        生产调用点（main.py / scheduler.py）传 `cfg.settings`，缺省回退 DEFAULT_SETTINGS
        （保证全局名单在无 cfg 的调用点也生效）。
        """
        if not isinstance(settings, dict):
            settings = DEFAULT_SETTINGS
        with self._lock:
            self.conn.execute(
                'CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
            row = self.conn.execute(
                "SELECT value FROM meta WHERE key='schema_version'").fetchone()
            current = int(row['value']) if row else 0
            if current >= schema.SCHEMA_VERSION:
                return
            ddl_map = {1: schema.DDL_V1, 2: schema.DDL_V2, 3: schema.DDL_V3,
                       4: schema.DDL_V4, 6: schema.DDL_V6, 7: schema.DDL_V7,
                       8: schema.DDL_V8, 10: schema.DDL_V10}
            for version in range(current + 1, schema.SCHEMA_VERSION + 1):
                for stmt in ddl_map.get(version, []):
                    self.conn.execute(stmt)
                if version == 5:
                    schema._apply_ddl_v5(self.conn)   # ADD COLUMN 逐列守卫，防重跑卡死
                if version == 9:
                    schema._apply_ddl_v9(self.conn)   # 同上：ADD COLUMN 逐列守卫
                if version == 10:
                    schema._apply_ddl_v10(self.conn)  # 同上：逐列守卫 + base_score 回填
                post = schema._MIGRATION_POST.get(version)
                if post:
                    post(self.conn, settings)     # 版本后置钩子（v5 回填 + 碰撞消解）
                self.conn.execute(
                    "INSERT OR REPLACE INTO meta(key, value) VALUES ('schema_version', ?)",
                    (str(version),))
                self.conn.commit()
                log.info('schema 迁移到 v%d', version)

    # ---------- 读写 ----------
    def execute(self, sql, params=()):
        """单条写（带锁短事务），返回 cursor。"""
        with self._lock:
            cur = self.conn.execute(sql, params)
            self.conn.commit()
            return cur

    def executemany(self, sql, seq_of_params):
        """批量写（带锁短事务）。"""
        with self._lock:
            cur = self.conn.executemany(sql, seq_of_params)
            self.conn.commit()
            return cur

    @contextlib.contextmanager
    def transaction(self):
        """多语句原子事务：同一把锁内批量提交，任一语句异常整体回滚。

        注意：with 块内必须用 conn.execute（裸连接），
        不能再调 self.execute/self.query（Lock 不可重入会死锁）。
        """
        with self._lock:
            try:
                yield self.conn
                self.conn.commit()
            except Exception:
                self.conn.rollback()
                raise

    def query(self, sql, params=()):
        """只读查询，返回 list[dict]。"""
        with self._lock:
            rows = self.conn.execute(sql, params).fetchall()
        return [dict(r) for r in rows]

    def query_one(self, sql, params=()):
        """只读单行，返回 dict 或 None。"""
        rows = self.query(sql, params)
        return rows[0] if rows else None

    # ---------- 维护 ----------
    def get_meta(self, key, default=None):
        row = self.query_one('SELECT value FROM meta WHERE key=?', (key,))
        return row['value'] if row else default

    def set_meta(self, key, value):
        self.execute('INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)', (key, str(value)))

    def cleanup(self, retention_days=90, dead_retention_days=30,
                checks_retention_days=7, orphan_retention_days=30):
        """清理过期数据：历史表分档裁剪 + dead 源过期删除 + 空 key 孤儿源回收。

        P1-H：dead/blacklisted 源原来单调增长，年级运行后联动探活规模与
        全表扫描恶化；dead 源 last_seen 超过 dead_retention_days 即删除
        （ON DELETE CASCADE 已就位，联动清除验证/探活历史）。

        P1-I（2026-09-10）：探活记录只服务 24h 存活率查询（status._probe_rate_24h），
        却与 verifications 共用 90 天保留期——按 30 分钟/轮、约 1000 源/轮估算，
        90 天将累积约 430 万行（约 300MB），是库体量的主要膨胀源。故将
        health_checks 单列保留期（默认 7 天，保留 7x24h 窗口冗余）。

        v10（2026-09-23）：throughput_checks 与 health_checks 同档（checks_retention_days）
        —— 只服务最近 window 次折算聚合，7 天远超需要；表不存在时（未迁移的旧库）
        静默跳过，不影响其余裁剪。

        P1-J（2026-09-10）：channel_key 为空的源不参与验证（_select_batch 要求
        key 非空）也不参与输出，仅用于 discovery 去重比对；长期堆积会拖慢每轮
        发现。按 orphan_retention_days 回收（保留 30 天，为别名表更新留出窗口——
        历史上曾因繁体别名缺失产生大量此类源，补别名后需回填而非重发现）。

        v7/v8（枢纽）：playback_reports 与 hub_notices 与历史表同档
        （retention_days，>=90 天）—— 回流样本量小（每设备每次播放一条），
        90 天足以支撑"探测判定 vs 真机结果"的人工复核；表不存在时（未迁移的旧库）
        静默跳过，不影响其余裁剪。
        """
        def _ago(days):
            return (datetime.datetime.now(datetime.timezone.utc)
                    - datetime.timedelta(days=int(days))).strftime('%Y-%m-%dT%H:%M:%SZ')

        cutoff = _ago(retention_days)
        try:
            c1 = self.execute('DELETE FROM health_checks WHERE ts < ?',
                              (_ago(checks_retention_days),)).rowcount
            # v10：吞吐探测记录与探活同档（仅服务最近 window 次折算聚合，
            # 短保留即可，同因防库膨胀）
            c1b = 0
            if self._table_exists('throughput_checks'):
                c1b = self.execute('DELETE FROM throughput_checks WHERE ts < ?',
                                   (_ago(checks_retention_days),)).rowcount
            c2 = self.execute('DELETE FROM health_events WHERE ts < ?', (cutoff,)).rowcount
            c3 = self.execute('DELETE FROM verifications WHERE ts < ?', (cutoff,)).rowcount
            c4 = 0
            dead_days = int(dead_retention_days or 0)
            if dead_days > 0:
                c4 = self.execute(
                    "DELETE FROM sources WHERE status='dead' AND last_seen < ?",
                    (_ago(dead_days),)).rowcount
            c5 = 0
            orphan_days = int(orphan_retention_days or 0)
            if orphan_days > 0:
                ocutoff = _ago(orphan_days)
                c5 = self.execute(
                    "DELETE FROM sources WHERE channel_key = '' AND status IN "
                    "('blacklisted','candidate') AND last_seen < ?", (ocutoff,)).rowcount
            # v7/v8：客户端回流记录与协议级事件（旧库无该表 -> 跳过，不打断其余裁剪）
            c6 = 0
            if self._table_exists('playback_reports'):
                c6 = self.execute('DELETE FROM playback_reports WHERE reported_at < ?',
                                  (cutoff,)).rowcount
            c7 = 0
            if self._table_exists('hub_notices'):
                c7 = self.execute('DELETE FROM hub_notices WHERE reported_at < ?',
                                  (cutoff,)).rowcount
            log.info('cleanup: 删除过期数据 health_checks=%d throughput_checks=%d '
                     'health_events=%d '
                     'verifications=%d dead_sources=%d orphan_sources=%d '
                     'playback_reports=%d hub_notices=%d '
                     '(历史 %d 天 / 探活 %d 天 / dead %d 天 / 孤儿源 %d 天)',
                     c1, c1b, c2, c3, c4, c5, c6, c7, retention_days, checks_retention_days,
                     dead_days, orphan_days)
        except sqlite3.Error as e:
            log.error('cleanup 失败: %s', e)

    def _table_exists(self, name):
        """sqlite_master 判表存在（v7 新增表的清理守卫；name 为内部常量，无注入面）。"""
        try:
            return self.query_one(
                "SELECT 1 AS ok FROM sqlite_master WHERE type='table' AND name=?",
                (name,)) is not None
        except sqlite3.Error:
            return False

    def close(self):
        with self._lock:
            try:
                self.conn.close()
            except sqlite3.Error:
                pass
