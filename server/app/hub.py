# -*- coding: utf-8 -*-
"""Hub：枢纽服务层（HUB CONTRACT v1.1 的**服务端一半**）。

契约是唯一权威文本：`docs/HUB_CONTRACT_V1.md`（冻结，逐字复制自对端共用文本）。
本模块只做契约规定的三件事，**不侵入四模块**（discovery/verifier/health/builder）：

  ① 投递   build_subscription()  -> GET  /api/hub/v1/subscription
  ② 回流   record_feedback()     -> POST /api/hub/v1/feedback      （Bearer 鉴权）
           feedback_summary()    -> GET  /api/hub/v1/feedback/summary
  ③ 协商   caps / hub_schema（见 CAPS_ENUM / HUB_SCHEMA）

设计纪律（与既有代码一致，勿"优化"掉）：
- **只读优先**：投递与聚合全部只读；唯一的写路径是 `record_feedback()`。
- **fail-closed**：投递路由（`app.delivery`）与 caps 过滤遇不确定一律"不投递"，
  与 `discovery.derive_profile` 同一纪律；绝不用"可能是明文"的乐观默认。
- **回流只记录**（契约 §4.1）：`playback_reports` 不参与任何自动删除/降级/拉黑。
- **集合契约（精确）**：静态 `live.media3.json` = 当前服务端认定的主力 media3
  **可投递候选全集**（lineups 优先、sources 兜底、`profile ∈ DELIVERABLE_PROFILES`，
  再经共享 `delivery.media3_deliverable_route` 过滤，**不含请求 caps 过滤**）；
  Hub 的 `_line_rows` 基于**同一候选集**，再按客户端声明 caps 取子集。
  ⇒ `caps` 全开时 Hub 基础候选（URL 集合）与 Builder 相同；FLV / 异族 DRM 等
  永不进入。避免再声称"无条件完全相等"。

本模块的"已知契约偏差"清单见 `docs/HUB_CONTRACT_CHANGELOG.md`（D1–D9）。
"""
import datetime
import hashlib
import hmac
import json
import os

from app import __version__, urlkey
from app.db import (DELIVERABLE_PROFILES, FREEZE_FIELDS,  # noqa: F401
                    FREEZE_REPORT_THRESHOLD_SECONDS, FREEZE_THRESHOLD_SECONDS,
                    utcnow)
# 投递路由叶子：route_of 与 route 常量从 `app.delivery` **原样迁移**后在此 import，
# 继续暴露 `from app.hub import route_of` / `CAPS_ENUM` 等既有入口（向后兼容）。
# 候选集取数（collect_deliverable_lines）与投影容错叶子（parse_clearkey /
# deliverable_container）同在 `app.delivery`：builder 与 hub 只允许这一份实现。
# 其中 route_of / ROUTE_ENUM / ROUTE_EXCLUDED 是兼容性 re-export（本模块内不直接引用）。
from app.delivery import (CAPS_ENUM, ROUTE_ENUM, ROUTE_EXCLUDED,  # noqa: F401
                          collect_deliverable_lines, deliverable_container,
                          media3_deliverable_route, parse_clearkey, route_of)
from app.logutil import get_logger
from app.parser import ChannelParser

log = get_logger('hub')

# ---------- 契约常量（HUB_CONTRACT_V1 §3.1 / §5.1）----------
# hub_schema=2 对应 v1.1 语义（CHANGELOG 2026-09-26 条目；schema 1 = v1 语义）
HUB_SCHEMA = 2

# 回流请求体上限（防单请求打满内存；超限 413，不解析）
MAX_FEEDBACK_BYTES = 1024 * 1024
MAX_FEEDBACK_RESULTS = 5000
# 超限但未超过本值时，服务端仍把请求体**读掉丢弃**（不保留）再回 413。
# 必要性：连接上残留未读数据时，服务端关闭连接会被内核换成 RST，
# 客户端会**丢掉这个 413 响应**（表现为 connection reset），比 413 本身更糟。
MAX_DRAIN_BYTES = 8 * 1024 * 1024
# ARCH P2-7：回流请求 hub_schema 的只计数 meta 键（JSON dict，键 = str(hub_schema)，
# 未携带/非标量计入 'unset'）。只累加、不校验、不拒绝——旧客户端不带也照收。
HUB_SCHEMA_COUNTS_META = 'feedback_hub_schema_counts'
# notices（§4 的协议级事件）单请求上限
MAX_FEEDBACK_NOTICES = 100
# notices[].code 封闭集合（v1.1 §4）。未知 code **必须接受并计数**，不得 4xx。
NOTICE_CODES = ('schema_unsupported', 'caps_unknown', 'other')

# ---- F5：`routes[].drm_uuid` 本轮**不投递**（已核实，不是猜）----
# v1.1 §3：`drm_uuid` 仅在"服务端**已核实**该 MPD 的 ContentProtection@schemeIdUri"时提供，
# 否则省略、客户端回落 Media3 的 `C.CLEARKEY_UUID`。
# 2026-09-11 实测 4 条真实 TVB MPD（`tools/probe_drm_uuid.py`，NAS 上跑，HTTP 200 跟随 302）：
#   每条都只声明三类 schemeIdUri ——
#     urn:mpeg:dash:mp4protection:2011 (value=cenc) / urn:uuid:edef8ba9-…(Widevine)
#     / urn:uuid:9a04f079-…(PlayReady, value=MSPR 2.0)
#   **没有任何一条声明 ClearKey 的 schemeIdUri**，因此无法"从 MPD 原样透传"该值
#   ⇒ 按契约省略该字段（把 Widevine/PlayReady 的 uuid 当 drm_uuid 发出去会直接误导客户端）。
#   ⚠️ 注意 `sources.keyformat` 里的 `urn:uuid:e2719d58-…` 是**我们自己的** KODIPROP 映射常量
#   （`discovery.CLEARKEY_KEYFORMAT`），不是 MPD 声明值 —— 因此不得当作"MPD 实际 schemeIdUri"投递。
DRM_UUID_UNVERIFIED = True

_TZ8 = datetime.timezone(datetime.timedelta(hours=8))

# 投递时读取的构建产物（seq 的 mtime 来源，契约 §6「seq 未变时可返回 304」）
_SEQ_ARTIFACTS = ('live.media3.json', 'live.m3u', 'live.txt')

# lineups JOIN 与 sources 直查的取列清单与候选集取数已收敛到
# `app.delivery._LINE_COLS` / `collect_deliverable_lines`（builder/hub 单份实现）。


def parse_caps(raw):
    """`caps` 查询参数 -> 已声明能力集合。

    - 逗号分隔，可重复、可含空白；大小写不敏感（契约写的是小写枚举）。
    - **未知 caps 必须忽略**（契约 §5.1 向前兼容），不得报错。
    - 缺省 / 空 -> 空集合（⇒ 任何 route 都不被覆盖 ⇒ `unsupported_for_client`）。
      ★ 这是**刻意 fail-closed**：客户端未声明能力时投递等于"猜"，而猜错的表现是
      设备端黑屏（最难远程诊断的故障）。契约 §5.1 未定义缺省语义，本实现取保守侧，
      已记入 HUB_CONTRACT_CHANGELOG（D7）待两端冻结。
    """
    out = set()
    for part in str(raw or '').split(','):
        p = part.strip().lower()
        if p and p in CAPS_ENUM:
            out.add(p)
        elif p:
            log.info('hub: 忽略未知 caps %r（契约 §5.1 向前兼容）', p)
    return out


class Hub(object):
    """枢纽服务层：读 SQLite 组装投递；接收并落库回流。"""

    def __init__(self, cfg, db):
        self.cfg = cfg
        self.db = db
        self._t2s_cache = None

    # ================= ③ 能力与身份协商 =================
    def section(self):
        """settings.json 的 `hub` 段（热重载：每请求现读，不缓存）。"""
        sec = self.cfg.settings.get('hub')
        return sec if isinstance(sec, dict) else {}

    def enabled(self):
        return bool(self.section().get('enabled'))

    def token(self):
        """回流专用 hub_token（契约 §4.2）。空串表示未配置 -> 一律 401。"""
        return str(self.section().get('feedback_token') or '')

    # ---------- 频道身份（契约 §1）----------
    def _t2s(self, text):
        """繁体->简体字符级预转换（与 parser 同一张 `t2s_map`，按对象身份缓存）。"""
        mapping = self.cfg.channels.get('t2s_map') or {}
        cache = self._t2s_cache
        if cache is not None and cache[0] is mapping:
            return text.translate(cache[1])
        table = str.maketrans(mapping) if mapping else {}
        self._t2s_cache = (mapping, table)
        return text.translate(table) if table else text

    def channel_ids(self):
        """服务端权威 channel_id 集合（= channels.json 的 channels 目标键，简体）。"""
        return [str(k) for k in (self.cfg.channels.get('channels') or [])]

    def alias_table(self):
        """别名表 name -> channel_id（契约 §1.1 的 1/2/3 步**预展开**）。

        契约 §1.1 的匹配链是「别名精确 -> display/channel_id 精确 -> t2s 后重复」。
        服务端把第 3 步的结果一并下发，客户端即可退化为**一次字典查表**（这正是
        「客户端不得本地维护第二份」的落地方式）。展开顺序保证语义不变：
        真实别名优先于自动生成的 t2s 变体。
        """
        with self.cfg.lock:
            aliases = self.cfg.channels.get('aliases') or {}
            ids = set(self.channel_ids())
            table = {}
            for name, key in aliases.items():             # 1) 别名精确（优先级最高）
                if name and key in ids:
                    table[str(name)] = str(key)
            for key in ids:                               # 2) channel_id 自身精确
                table.setdefault(key, key)
            for name, key in list(table.items()):         # 3) t2s 变体（不覆盖已有键）
                conv = self._t2s(name)
                if conv and conv != name and conv not in table:
                    table[conv] = key
            return table

    def aliases(self):
        """契约 §3 响应体的 `aliases` 数组（按 name 排序，保证响应字节稳定）。"""
        table = self.alias_table()
        return [{'name': n, 'channel_id': table[n]} for n in sorted(table)]

    def resolve_channel_id(self, name):
        """任意频道名 -> channel_id；无法归因返回 None（契约 §1 第 4 步）。"""
        if not name:
            return None
        table = self.alias_table()
        n = str(name).strip()
        if n in table:
            return table[n]
        return None

    # ================= ① 投递 =================
    def _line_rows(self):
        """{channel_key: [line_row]} —— 与 `builder._collect_media3_items` 同源候选集。

        取数唯一实现为共享叶子 `delivery.collect_deliverable_lines`
        （lineups 优先、sources 兜底、profile 过滤、`media3_deliverable_route`
        过滤、过滤后才截断 max_lines，见其 docstring）；本方法只做参数装配。
        """
        max_lines = int(self.cfg.settings['limits']['max_lines_per_channel'])
        return collect_deliverable_lines(self.db, self.channel_ids(), max_lines)

    def _route_entry(self, row):
        """line row -> 契约 §3 的 route dict；不可投递/无 URL 返回 None。

        ★ 投递路由统一走共享叶子 `media3_deliverable_route`（不再保留单独的
        FLV 策略分支）：它已拒掉 FLV / 异族 DRM / sample-aes / 未知容器。
        `_line_rows` 已按同一函数过滤，此处复用同一判据（可重复调用）。

        ★ v1.1 §3 / §2.5：**`url_key` 必填且非空**。`sources.url_key` 可能为 NULL
        （url_key 碰撞消解的落败行刻意置 NULL，见 `db.COLLISION_ORPHAN_STATUS`），
        故此处**现场补算**（与服务端权威实现同一函数，绝不另写一套算法）。
        客户端因此永远不会进入"本地兜底计算 url_key"的分支 —— 那条分支才是
        两端 url_key 分歧的来源（v1 的实际教训）。
        行内 `url` 为空 -> **不投递**（无法播放，也无从补算），并记 log。
        """
        url = (row.get('url') or '').strip()
        if not url:
            log.warning('hub: 线路 url 为空，不投递 (channel=%s)', row.get('k'))
            return None
        route = media3_deliverable_route(row.get('container'), row.get('encryption'),
                                         row.get('drm'), url)
        if route is None:
            return None
        url_key = (row.get('url_key') or '').strip().lower()
        if len(url_key) != 40:
            # 缺列值 / 长度异常 -> 现场补算（v1.1 §3 的强保证）
            url_key = ChannelParser.url_key(
                url, ChannelParser.resolve_signed_params(url, self.cfg.settings))
            log.info('hub: url_key 缺失或非法，已现场补算 channel=%s url_key=%s url=%s',
                     row.get('k'), url_key, url[:80])
        entry = {
            'route': route,
            'url': url,
            'url_key': url_key,
            'container': deliverable_container(row.get('container'), url),
            'encryption': row.get('encryption') or '',
            'kid': row.get('kid') or '',
            # 服务端库无画质元数据 -> 恒 null（v1.1 §3 已明文标注；不编造）
            'quality': None,
            'score': row.get('score'),
            'verified_at': row.get('verified_at'),
        }
        if route == 'dash-clearkey':
            entry['clearkey'] = parse_clearkey(row.get('drm_key'))
            # 不投递 `drm_uuid`：见模块顶部 DRM_UUID_UNVERIFIED 的实测证据
        return entry

    def signed_params(self):
        """契约 §2.2 的 `signed_params`（两级名单）—— 由 settings 同源导出（不另建副本）。

        值一律**小写**（§2.2「合并结果去重后的小写参数名集合」），层级与键名固定。
        `fingerprint`（v1.1 加法字段，P0-2 方案 C）：生效名单的稳定指纹（sha1 前 12 位，
        与 schema.ensure_url_keys_fresh 的 meta 锚点同算法同源）——名单变更即指纹变更，
        客户端据此可知服务端 url_key 语义已切换（陈旧 key 全表重算后投递）。
        """
        st = self.cfg.settings
        out = []
        raw_global = st.get('signed_url_params')
        if isinstance(raw_global, (list, tuple)):
            for p in raw_global:
                lp = str(p).strip().lower()
                if lp and lp not in out:
                    out.append(lp)
        by_host = {}
        raw_host = st.get('signed_url_params_by_host')
        if isinstance(raw_host, dict):
            for key, vals in raw_host.items():
                k = str(key or '').strip().lower()
                if not k or not isinstance(vals, (list, tuple)):
                    continue
                acc = []
                for p in vals:
                    lp = str(p).strip().lower()
                    if lp and lp not in acc:
                        acc.append(lp)
                if acc:
                    by_host[k] = acc
        return {'global': out, 'by_host': by_host,
                'fingerprint': urlkey.signed_params_fingerprint(st)}

    def _seq_and_generated_at(self):
        """(seq, generated_at)：由构建产物 mtime 派生的**单调不减**整数（契约 §6）。

        - mtime 只会前进 ⇒ seq 单调；`meta.hub_seq` 作为**下限**兜底
          （供人工置值/产物被清空时不回退）。
        - `generated_at` **与 seq 同源**（同一时刻的 +08:00 ISO 8601）而不是"当前时间"：
          否则响应体每次请求都变 ⇒ ETag 永不命中 ⇒ 304 分支形同虚设（契约 §6 要求
          "seq 未变时可返回 304"）。
        - 无任何产物（冷启动）-> seq=0 / epoch，保持确定性。
        """
        seq = 0
        for name in _SEQ_ARTIFACTS:
            try:
                seq = max(seq, int(os.path.getmtime(
                    os.path.join(self.cfg.output_dir, name))))
            except OSError:
                continue
        try:
            floor = int(self.db.get_meta('hub_seq') or 0)
        except (TypeError, ValueError):
            floor = 0
        seq = max(seq, floor)
        return seq, datetime.datetime.fromtimestamp(seq, _TZ8).isoformat()

    def _groups(self):
        """契约 §3 的 `groups`（id 用 config 的分组键，服务端权威；见 CHANGELOG D5）。"""
        groups = self.cfg.channels.get('groups') or {}
        gidx = self.cfg.channels.get('group_index') or {}
        used = set()
        for key in self.channel_ids():
            used.add((self.cfg.channels.get('channel_groups') or {}).get(key) or '卫视')
        return [{'id': g, 'title': groups.get(g, g), 'order': gidx.get(g, 99)}
                for g in sorted(used, key=lambda g: (gidx.get(g, 99), g))]

    def build_subscription(self, caps=None, client='', schema=None, app='', device=''):
        """组装 hub v1 订阅响应体（契约 §3）。**只读**。

        caps：已声明的客户端能力（str 或 set）。未声明的 route 一律不投递；
        全部被过滤掉 -> `coverage='unsupported_for_client'`。
        schema：客户端声明的 hub_schema。服务端承诺同 schema 内只做加法，
        故对"客户端声明更高 schema"只记日志、仍返回自身 v1 载荷（契约 §5.2 的
        拒绝应用动作在客户端侧）。
        """
        with self.cfg.lock:
            caps_set = caps if isinstance(caps, (set, frozenset)) else parse_caps(caps)
            try:
                req_schema = int(schema) if schema is not None else None
            except (TypeError, ValueError):
                req_schema = None
            if req_schema is not None and req_schema != HUB_SCHEMA:
                log.info('hub: 客户端声明 hub_schema=%s（服务端=%d）client=%s app=%s',
                         req_schema, HUB_SCHEMA, client, app)

            ch = self.cfg.channels
            groups_map = ch.get('channel_groups') or {}
            epg_names = ch.get('epg_names') or {}
            logo_tpl = ch.get('logo_template', '')
            lines_by_key = self._line_rows()

            channels = []
            for key in self.channel_ids():
                rows = lines_by_key.get(key) or []
                entries = []
                for row in rows:
                    e = self._route_entry(row)
                    if e is not None:
                        entries.append(e)
                # 契约 §3：routes 按 score 降序（score 相同则保持 lineups 的 rank 次序）
                entries.sort(key=lambda e: -(e['score'] if e['score'] is not None else 0))
                routes = [e for e in entries if e['route'] in caps_set]
                if not rows:
                    coverage = 'missing'
                elif routes:
                    coverage = 'ok' if len(routes) >= 2 else 'partial'
                else:
                    coverage = 'unsupported_for_client'
                en = epg_names.get(key, key)
                channels.append({
                    'channel_id': key,
                    'display': en,
                    'group_id': groups_map.get(key) or '卫视',
                    'logo': logo_tpl.format(name=en) if logo_tpl else '',
                    'epg_id': en,
                    'coverage': coverage,
                    'routes': routes,
                    # `fallback`（契约 §3）：服务端仅在已知官网地址时下发。
                    # 本次不做 web_routes 配置化（P1），故**一律省略**该键
                    # （契约 §3「否则省略」；见 HUB_CONTRACT_CHANGELOG D6）。
                })
            _seq, generated_at = self._seq_and_generated_at()
            return {
                'hub_schema': HUB_SCHEMA,
                'seq': _seq,
                'generated_at': generated_at,
                'server': {'app': __version__, 'caps_supported': list(CAPS_ENUM)},
                'groups': self._groups(),
                # v1.1 §3 加法字段：供客户端实现**非枢纽来源**（如第三方 M3U）的本地 url_key。
                # 枢纽链路上客户端仍必须使用服务端下发的 routes[].url_key（§2.5）。
                'signed_params': self.signed_params(),
                'channels': channels,
                'aliases': self.aliases(),
            }

    # ---------- 响应封装（供 httpserver 直接吐字节）----------
    @staticmethod
    def encode(payload):
        """响应体字节（紧凑分隔符 + 键序确定 => ETag 稳定）。"""
        return json.dumps(payload, ensure_ascii=False,
                          separators=(',', ':')).encode('utf-8')

    @staticmethod
    def etag_of(body):
        return '"%s"' % hashlib.sha1(body).hexdigest()

    @staticmethod
    def etag_matches(header_value, etag):
        """If-None-Match 判定：容忍 `W/` 弱前缀、引号、逗号列表与 `*`。"""
        if not header_value:
            return False
        want = etag.strip().strip('"')
        for part in str(header_value).split(','):
            p = part.strip()
            if p == '*':
                return True
            if p.startswith('W/'):
                p = p[2:]
            if p.strip().strip('"') == want:
                return True
        return False

    def subscription_response(self, caps=None, client='', schema=None, app='',
                              device='', if_none_match=None):
        """-> (http_code, headers, body_bytes)。304 时 body 为空（契约 §6）。"""
        payload = self.build_subscription(caps=caps, client=client, schema=schema,
                                          app=app, device=device)
        body = self.encode(payload)
        etag = self.etag_of(body)
        headers = {
            'Content-Type': 'application/json; charset=utf-8',
            'ETag': etag,
            'X-Hub-Seq': str(payload['seq']),
        }
        if self.etag_matches(if_none_match, etag):
            return 304, headers, b''
        return 200, headers, body

    # ================= ② 回流 =================
    def _known_url_keys(self):
        """服务端已持有（含历史）的全部 url_key —— 用于统计 `unknown_url_key`。

        刻意取**全表**而非"当前投递集合"：客户端可能在上一轮拿到过某线路，
        隔一轮源变 dead 也仍是"服务端给过的键"，不该被判未知。
        """
        rows = self.db.query('SELECT url_key FROM sources WHERE url_key IS NOT NULL')
        return {r['url_key'] for r in rows if r['url_key']}

    @staticmethod
    def _as_int(v):
        try:
            return int(v)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _as_float(v):
        try:
            return float(v)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _freeze_contract(item):
        """解析并校验回流「画面冻结」字段组（两端冻结规格，见 HUB_CONTRACT_CHANGELOG 2026-09-18）。

        规格要点（与客户端冻结的原文一致）：
          - 五个字段 `FREEZE_FIELDS` **整组给齐或整组不给**；缺任一 => 整组视为未上报
            （`reported=False, partial=True`），**不做部分采信**；
          - 不变式：`0 ≤ freeze_seconds ≤ active_seconds`、
            `0 ≤ freeze_longest_seconds ≤ freeze_seconds`、
            `freeze_segments == 0 ⟺ freeze_longest_seconds == 0`、
            `freeze_segments ≥ 1 ⟹ freeze_longest_seconds ≥ FREEZE_THRESHOLD_SECONDS`；
          - 违反不变式**只计数并落 `freeze_contract_ok=0`，绝不 4xx**（客户端是电视上的
            单体程序，一条脏数据不该让整批上报作废 —— 与 notices 的宽松原则同源）；
          - `freeze_long_segments`（≥45 s 报表口径）可选、独立，不参与整组判定与不变式。

        返回 dict：
          reported        本次附着是否整组上报了冻结字段
          partial         有字段但没给齐（按未上报处理，另计数）
          ok              True/False（仅在 reported=True 时有意义），None 表示未上报
          reason          不变式不通过的原因（可读，用于日志与 summary）
          values          六列取值（未上报时全 None）
        """
        raw = {n: item.get(n) for n in FREEZE_FIELDS}
        present = [n for n in FREEZE_FIELDS if raw[n] is not None]
        # `freeze_long_segments` 是**独立可选**字段（不属于上面那组五个），不参与整组判定，
        # 也不参与任何不变式。契约允许两种"本次取不到"的表示，**二者等价、都落 NULL**：
        #   ① 键缺席（推荐、规范形式）；② 客户端以 **-1** 作哨兵值（对端 2026-09-18 的实现选择）。
        # ⚠️ 这里把负值一律归为 NULL 是**有意为之**，不是顺手容错：对端用 -1 表达"45 s 口径
        #   取不到"，若把它当成不变式违反或原样落库，都会污染"确实没有 ≥45 s 段"（=0）的语义。
        #   改动此处前请先改契约（见 HUB_CONTRACT_CHANGELOG 2026-09-18 条目第三轮 E）。
        long_segments = Hub._as_int(item.get('freeze_long_segments'))
        if long_segments is not None and long_segments < 0:
            long_segments = None
        blank = {'active_seconds': None, 'freeze_seconds': None,
                 'freeze_segments': None, 'freeze_longest_seconds': None,
                 'picture_progress_events': None, 'freeze_long_segments': long_segments}
        if not present:
            return {'reported': False, 'partial': False, 'ok': None, 'reason': '',
                    'values': blank}
        if len(present) < len(FREEZE_FIELDS):
            missing = [n for n in FREEZE_FIELDS if raw[n] is None]
            return {'reported': False, 'partial': True, 'ok': None,
                    'reason': '缺项: %s' % ','.join(missing), 'values': blank}

        active = Hub._as_float(raw['active_seconds'])
        freeze = Hub._as_float(raw['freeze_seconds'])
        segments = Hub._as_int(raw['freeze_segments'])
        longest = Hub._as_float(raw['freeze_longest_seconds'])
        events = Hub._as_int(raw['picture_progress_events'])
        values = {'active_seconds': active, 'freeze_seconds': freeze,
                  'freeze_segments': segments, 'freeze_longest_seconds': longest,
                  'picture_progress_events': events,
                  'freeze_long_segments': long_segments}

        bad = []
        nums = (active, freeze, segments, longest, events)
        if any(v is None for v in nums):
            bad.append('存在非数值字段')
        else:
            eps = 1e-6
            if min(nums) < 0:
                bad.append('存在负值')
            if freeze > active + eps:
                bad.append('freeze_seconds > active_seconds')
            if longest > freeze + eps:
                bad.append('freeze_longest_seconds > freeze_seconds')
            if (segments == 0) != (longest == 0):
                bad.append('freeze_segments 与 freeze_longest_seconds 不同步')
            if segments >= 1 and longest < FREEZE_THRESHOLD_SECONDS - eps:
                bad.append('freeze_segments>0 但 longest < %ds' % FREEZE_THRESHOLD_SECONDS)
        return {'reported': True, 'partial': False, 'ok': not bad,
                'reason': '；'.join(bad), 'values': values}

    def record_feedback(self, payload, now=None):
        """接收回流并落库（契约 §4）。返回计数 dict；**任何未知 id / code 都不报错**。

        结构不合法（非对象 / `results` 非数组）由调用方转 400；单条结果不合法
        （非对象）计入 `rejected` 但不影响其余条目 —— 客户端是家里电视上的单体
        程序，一条脏数据不该让它整批上报作废。

        v1.1 §4 新增 `notices[]`（协议级事件，如 `schema_unsupported`）：
        与播放结果**分开落表** `hub_notices`（见 DDL_V8 的选择说明），
        未知 `code` 只计数不 4xx（§4 明文要求）。
        """
        if not isinstance(payload, dict):
            raise ValueError('payload 必须是 JSON 对象')
        results = payload.get('results')
        if results is None:
            results = []
        if not isinstance(results, list):
            raise ValueError('results 必须是数组')
        truncated = 0
        results_truncated = 0
        notices_truncated = 0
        if len(results) > MAX_FEEDBACK_RESULTS:
            results_truncated = len(results) - MAX_FEEDBACK_RESULTS
            truncated = results_truncated
            results = results[:MAX_FEEDBACK_RESULTS]
            log.warning('hub: feedback results 超限，截断 %d 条', truncated)

        device = payload.get('device') if isinstance(payload.get('device'), dict) else {}
        client = payload.get('client')
        app = payload.get('app')
        session = payload.get('session')
        reported_at = now or utcnow()
        known_keys = self._known_url_keys()
        with self.cfg.lock:
            known_ids = set(self.channel_ids())
            aliases = self.alias_table()

        rows = []
        accepted = unknown_channel = unknown_url_key = rejected = 0
        # v9：冻结字段组的落库质量计数（违反不变式/缺项都只计数，绝不 4xx）
        freeze_ok = freeze_bad = freeze_partial = 0
        for item in results:
            if not isinstance(item, dict):
                rejected += 1
                continue
            raw_cid = item.get('channel_id')
            cid = None
            if raw_cid is not None and str(raw_cid).strip():
                # 客户端若误用显示名/繁体名，按契约 §1.1 归一后再判未知
                name = str(raw_cid).strip()
                cid = aliases.get(name) or name
            # 契约 §1：无法归因的频道仍可播放、可上报，只是 channel_id 记为 null
            stored_cid = cid or None
            if cid is None or cid not in known_ids:
                unknown_channel += 1
            uk = item.get('url_key')
            uk = str(uk).strip().lower() if uk is not None and str(uk).strip() else None
            if uk is None or uk not in known_keys:
                unknown_url_key += 1
            frz = self._freeze_contract(item)
            fv = frz['values']
            if frz['reported']:
                if frz['ok']:
                    freeze_ok += 1
                else:
                    freeze_bad += 1
                    log.warning('hub: 冻结字段违反不变式 url_key=%s 原因=%s client=%s',
                                uk, frz['reason'], _s(client))
            elif frz['partial']:
                freeze_partial += 1
                log.warning('hub: 冻结字段缺项，整组按未上报处理 url_key=%s 原因=%s client=%s',
                            uk, frz['reason'], _s(client))
            rows.append((
                reported_at,
                _s(client), _s(app),
                _s(device.get('model')), self._as_int(device.get('android_api')),
                _s(session),
                stored_cid, uk,
                _s(item.get('route')), _s(item.get('outcome')) or 'unknown',
                _s(item.get('engine')), self._as_int(item.get('first_frame_ms')),
                self._as_int(item.get('stalls')), self._as_float(item.get('stall_seconds')),
                self._as_float(item.get('play_seconds')),
                _s(item.get('fail_reason')), _s(item.get('observed_at')),
                # v9：画面冻结字段组（未上报全为 NULL —— 与"上报了 0"严格区分）
                fv['active_seconds'], fv['freeze_seconds'], fv['freeze_segments'],
                fv['freeze_longest_seconds'], fv['picture_progress_events'],
                fv['freeze_long_segments'],
                # 服务端派生：None=未上报 / 1=整组且合规 / 0=整组但违反不变式
                (None if not frz['reported'] else (1 if frz['ok'] else 0)),
                _s(json.dumps(item, ensure_ascii=False)[:4000]),
            ))
            accepted += 1

        # ---- v1.1 §4：notices[]（协议级事件）----
        notices = payload.get('notices')
        if notices is None:
            notices = []
        if not isinstance(notices, list):
            raise ValueError('notices 必须是数组')
        if len(notices) > MAX_FEEDBACK_NOTICES:
            notices_truncated = len(notices) - MAX_FEEDBACK_NOTICES
            truncated += notices_truncated
            notices = notices[:MAX_FEEDBACK_NOTICES]
        notice_rows = []
        notice_accepted = unknown_notice_code = notice_rejected = 0
        for n in notices:
            if not isinstance(n, dict):
                notice_rejected += 1
                continue
            code = _s(n.get('code')) or 'other'
            if code not in NOTICE_CODES:
                unknown_notice_code += 1        # §4：未知 code 必须接受并计数，不得 4xx
            notice_rows.append((
                reported_at, _s(client), _s(app), _s(session),
                code, _s(n.get('detail')),
                _s(json.dumps(n, ensure_ascii=False)[:4000]),
            ))
            notice_accepted += 1

        if rows or notice_rows:
            with self.db.transaction() as conn:
                if rows:
                    conn.executemany(
                        'INSERT INTO playback_reports(reported_at, client, app,'
                        ' device_model, android_api, session, channel_id, url_key, route,'
                        ' outcome, engine, first_frame_ms, stalls, stall_seconds,'
                        ' play_seconds, fail_reason, observed_at,'
                        ' active_seconds, freeze_seconds, freeze_segments,'
                        ' freeze_longest_seconds, picture_progress_events,'
                        ' freeze_long_segments, freeze_contract_ok, raw_json) '
                        'VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', rows)
                if notice_rows:
                    conn.executemany(
                        'INSERT INTO hub_notices(reported_at, client, app, session,'
                        ' code, detail, raw_json) VALUES(?,?,?,?,?,?,?)', notice_rows)
        log.info('hub: 回流落库 accepted=%d unknown_channel=%d unknown_url_key=%d '
                 'rejected=%d notices=%d unknown_notice_code=%d notice_rejected=%d '
                 'truncated=%d freeze_ok=%d freeze_violation=%d freeze_partial=%d client=%s',
                 accepted, unknown_channel, unknown_url_key, rejected,
                 notice_accepted, unknown_notice_code, notice_rejected,
                 truncated, freeze_ok, freeze_bad, freeze_partial, client)
        # ARCH P2-7：请求里的 hub_schema 只计数（meta 累加，跨请求可诊断
        # "多少回流来自旧 schema 客户端"）；**不校验、不拒绝**，旧客户端兼容红线。
        self._count_feedback_hub_schema(payload)
        return {'accepted': accepted, 'unknown_channel': unknown_channel,
                'unknown_url_key': unknown_url_key, 'rejected': rejected,
                'notices': notice_accepted,
                'unknown_notice_code': unknown_notice_code,
                'notice_rejected': notice_rejected,
                # v1.1 加法字段（QA P2-2）：results / notices 的截断数分别计数；
                # `truncated` 保持旧语义（两者之和），旧客户端兼容。
                'truncated': truncated,
                'results_truncated': results_truncated,
                'notices_truncated': notices_truncated,
                # v9 加法字段：冻结字段组的落库质量（客户端可据此自检，旧客户端忽略即可）
                'freeze_ok': freeze_ok,
                'freeze_violation': freeze_bad,
                'freeze_partial': freeze_partial}

    def _count_feedback_hub_schema(self, payload):
        """ARCH P2-7：回流 payload 的 hub_schema 只计数（meta JSON 累加）。

        键 = str(hub_schema)（契约 §4 请求体为整数）；未携带或非 int/str 值计入
        'unset'，用于区分"旧客户端没传"与"声明了旧 schema"。计数失败只记日志、
        绝不影响回流主路径（回流只记录，§4.1）。
        """
        raw = payload.get('hub_schema') if isinstance(payload, dict) else None
        key = str(raw) if isinstance(raw, (int, str)) else 'unset'
        try:
            counts = json.loads(self.db.get_meta(HUB_SCHEMA_COUNTS_META) or '{}')
            if not isinstance(counts, dict):
                counts = {}
            counts[key] = int(counts.get(key) or 0) + 1
            self.db.set_meta(HUB_SCHEMA_COUNTS_META, json.dumps(counts, sort_keys=True))
        except Exception as e:  # noqa: BLE001 - 计数是观测性加法，不得让回流失败
            log.warning('hub: hub_schema 计数失败（忽略，不影响回流）: %s', e)

    def feedback_authorized(self, auth_header):
        """读体前与服务层独立防御共用；每次读取当前热加载 token。"""
        token = self.token()
        return bool(token) and _bearer_ok(auth_header, token)

    def feedback_response(self, auth_header, body):
        """POST 响应：(code, headers, body_bytes)。鉴权失败 401，成功 202（契约 §4）。"""
        if not self.feedback_authorized(auth_header):
            return (401, {'Content-Type': 'application/json; charset=utf-8'},
                    b'{"error":"unauthorized"}')
        if len(body or b'') > MAX_FEEDBACK_BYTES:
            return (413, {'Content-Type': 'application/json; charset=utf-8'},
                    json.dumps({'error': 'payload too large',
                                'limit': MAX_FEEDBACK_BYTES}).encode('utf-8'))
        try:
            payload = json.loads((body or b'').decode('utf-8'))
        except (ValueError, UnicodeDecodeError) as e:
            log.warning('hub: feedback 解析失败: %s', e)
            return (400, {'Content-Type': 'application/json; charset=utf-8'},
                    b'{"error":"invalid json"}')
        try:
            counts = self.record_feedback(payload)
        except ValueError as e:
            return (400, {'Content-Type': 'application/json; charset=utf-8'},
                    json.dumps({'error': str(e)}, ensure_ascii=False).encode('utf-8'))
        return (202, {'Content-Type': 'application/json; charset=utf-8'},
                json.dumps(counts, ensure_ascii=False).encode('utf-8'))

    # ---------- 聚合（只读，契约 §4.1）----------
    def feedback_summary(self):
        """「探测判定 vs 真机结果」只读聚合 + **冲突清单**（契约 §4.1）。

        冲突两类（供人工复核，**不触发任何自动动作**）：
        - `probe_ok_device_fail`  ：服务端判可用、真机全失败 -> 疑似**假阳性**
        - `probe_fail_device_ok`  ：服务端判不可用/未知、真机成功 -> 疑似**假阴性**
        """
        deliverables = set(DELIVERABLE_PROFILES)
        probe = {}
        for r in self.db.query(
                'SELECT channel_key, url_key, status, av, profile, last_verified '
                'FROM sources WHERE url_key IS NOT NULL'):
            probe[r['url_key']] = r
        agg = self.db.query(
            "SELECT channel_id, url_key, COUNT(*) AS n, "
            "SUM(CASE WHEN outcome='ok' THEN 1 ELSE 0 END) AS ok, "
            "SUM(CASE WHEN outcome='partial' THEN 1 ELSE 0 END) AS partial, "
            "SUM(CASE WHEN outcome='fail' THEN 1 ELSE 0 END) AS fail, "
            "MIN(reported_at) AS first_at, MAX(reported_at) AS last_at, "
            "MAX(app) AS app, "
            # v9：冻结字段组（NULL ≠ 0：未上报的记录一律不进这组，故用 IS NOT NULL 显式计数）
            "SUM(CASE WHEN freeze_contract_ok IS NOT NULL THEN 1 ELSE 0 END) AS frz_reported, "
            "SUM(CASE WHEN freeze_contract_ok = 1 THEN 1 ELSE 0 END) AS frz_ok, "
            "SUM(CASE WHEN freeze_contract_ok = 0 THEN 1 ELSE 0 END) AS frz_bad, "
            "SUM(COALESCE(active_seconds, 0)) AS frz_active, "
            "SUM(COALESCE(freeze_seconds, 0)) AS frz_seconds, "
            "SUM(COALESCE(freeze_segments, 0)) AS frz_segments, "
            "MAX(freeze_longest_seconds) AS frz_longest, "
            "SUM(COALESCE(freeze_long_segments, 0)) AS frz_long_segments "
            'FROM playback_reports GROUP BY channel_id, url_key ORDER BY channel_id')

        ch = self.cfg.channels
        ids = {str(key) for key in ch.get('channels') or []}
        epg_names = ch.get('epg_names') or {}
        by_channel = {}
        unknown_channels = {}
        totals = {'reports': 0, 'ok': 0, 'partial': 0, 'fail': 0,
                  'conflicts': 0, 'channels': 0, 'url_keys': 0,
                  # v9：冻结字段组的全局口径（同样区分"未上报"与"零冻结"）
                  'freeze_reported': 0, 'freeze_contract_violation': 0,
                  'freeze_seconds': 0.0, 'freeze_active_seconds': 0.0}
        for r in agg:
            cid = r['channel_id']
            if cid not in ids:
                key = cid if cid else '<null>'
                unknown_channels[key] = unknown_channels.get(key, 0) + int(r['n'] or 0)
                continue
            uk = r['url_key']
            src = probe.get(uk)
            probe_ok = bool(src and src.get('status') == 'active'
                            and str(src.get('av') or '').startswith('video')
                            and src.get('profile') in deliverables)
            n = int(r['n'] or 0)
            n_ok = int(r['ok'] or 0)
            n_fail = int(r['fail'] or 0)
            # v9：冻结率 = freeze_seconds / active_seconds（仅在**上报了**的记录内计算；
            # 未上报的记录不进分子也不进分母 —— 否则"没数据"会被读成"没有冻结"）。
            frz_active = float(r['frz_active'] or 0.0)
            frz_seconds = float(r['frz_seconds'] or 0.0)
            freeze = {
                'reported': int(r['frz_reported'] or 0),
                'contract_ok': int(r['frz_ok'] or 0),
                'contract_violation': int(r['frz_bad'] or 0),
                'active_seconds': round(frz_active, 1),
                'freeze_seconds': round(frz_seconds, 1),
                'freeze_ratio': round(frz_seconds / frz_active, 4) if frz_active > 0 else None,
                'segments': int(r['frz_segments'] or 0),
                'longest_seconds': r['frz_longest'],
                'long_segments': int(r['frz_long_segments'] or 0),
                'threshold_seconds': FREEZE_THRESHOLD_SECONDS,
                'long_threshold_seconds': FREEZE_REPORT_THRESHOLD_SECONDS,
            }
            conflict = None
            if probe_ok and n and n_ok == 0:
                conflict = 'probe_ok_device_fail'
            elif (not probe_ok) and n_ok > 0:
                conflict = 'probe_fail_device_ok'
            item = {
                'url_key': uk,
                'probe': 'ok' if probe_ok else ('fail' if src else 'unknown'),
                'probe_status': src.get('status') if src else None,
                'probe_profile': src.get('profile') if src else None,
                'probe_verified_at': src.get('last_verified') if src else None,
                'device': {'reports': n, 'ok': n_ok,
                           'partial': int(r['partial'] or 0), 'fail': n_fail,
                           'ok_rate': round(float(n_ok) / n, 4) if n else 0.0,
                           'first_at': r['first_at'], 'last_at': r['last_at'],
                           'app': r['app']},
                'freeze': freeze,
                'conflict': conflict,
            }
            bucket = by_channel.setdefault(cid, {'channel_id': cid,
                                                 'display': epg_names.get(cid, cid),
                                                 'routes': []})
            bucket['routes'].append(item)
            totals['reports'] += n
            totals['ok'] += n_ok
            totals['partial'] += int(r['partial'] or 0)
            totals['fail'] += n_fail
            totals['url_keys'] += 1
            totals['freeze_reported'] += freeze['reported']
            totals['freeze_contract_violation'] += freeze['contract_violation']
            totals['freeze_seconds'] = round(totals['freeze_seconds'] + frz_seconds, 1)
            totals['freeze_active_seconds'] = round(
                totals['freeze_active_seconds'] + frz_active, 1)
            if conflict:
                totals['conflicts'] += 1

        conflicts = []
        channels = []
        for cid in sorted(by_channel):
            bucket = by_channel[cid]
            bucket['routes'].sort(key=lambda x: -x['device']['reports'])
            bucket['conflicts'] = [x['url_key'] for x in bucket['routes']
                                   if x['conflict']]
            channels.append(bucket)
            for x in bucket['routes']:
                if x['conflict']:
                    conflicts.append({'channel_id': cid, 'reason': x['conflict'],
                                      'url_key': x['url_key'],
                                      'probe': x['probe'],
                                      'device': x['device']})
        totals['channels'] = len(channels)
        _seq, generated_at = self._seq_and_generated_at()
        return {
            'hub_schema': HUB_SCHEMA,
            'generated_at': generated_at,
            'note': ('v1：回流仅用于记录与只读聚合，不作为删除/降级/拉黑任何源的依据'
                     '（HUB_CONTRACT_V1 §4.1）'),
            'totals': totals,
            'conflicts': conflicts,
            'unknown_channel_ids': unknown_channels,
            # v1.1 §4：协议级事件（notices）也要看得见 —— `schema_unsupported` 意味着
            # 客户端正在拒绝应用订阅，属需要人工介入的信号，不能只躺在日志里。
            'notices': self.notice_summary(),
            'channels': channels,
        }

    def notice_summary(self):
        """按 `code` 聚合协议级事件（只读；契约 §4 / §5.2）。"""
        rows = self.db.query(
            'SELECT code, COUNT(*) AS n, MIN(reported_at) AS first_at, '
            'MAX(reported_at) AS last_at, MAX(client) AS client, MAX(app) AS app, '
            'MAX(detail) AS detail '
            'FROM hub_notices GROUP BY code ORDER BY n DESC, code')
        by_code = []
        unknown = []
        for r in rows:
            item = {'code': r['code'], 'count': int(r['n'] or 0),
                    'first_at': r['first_at'], 'last_at': r['last_at'],
                    'client': r['client'], 'app': r['app'],
                    'sample_detail': r['detail']}
            if r['code'] in NOTICE_CODES:
                by_code.append(item)
            else:
                unknown.append(item)
        return {'by_code': by_code, 'unknown_code': unknown,
                'total': sum(x['count'] for x in by_code + unknown)}

    # ---------- url_key 测试向量（契约 §2.5，只读，不参与 seq）----------
    def vectors_path(self):
        return os.path.join(self.cfg.root, 'docs', 'urlkey_vectors.json')

    def vectors_response(self, if_none_match=None):
        """`GET /api/hub/v1/urlkey-vectors` -> (code, headers, body)。

        内容 = `docs/urlkey_vectors.json`（由 `tools/export_urlkey_vectors.py` 用
        服务端权威实现生成）。**只读、无鉴权、不参与 seq**（契约 §2.5 / §3）。
        文件缺失或损坏 -> 404/500 并记日志（绝不返回半截数据：客户端要拿它当
        "禁止上线"的门禁，不能给它一个看起来合法但缺项的数组）。
        """
        path = self.vectors_path()
        try:
            with open(path, 'rb') as f:
                raw = f.read()
        except OSError as e:
            log.error('hub: 向量文件不可读 %s (%s)', path, e)
            return (404, {'Content-Type': 'application/json; charset=utf-8'},
                    b'{"error":"vectors not available"}')
        try:
            parsed = json.loads(raw.decode('utf-8'))
        except (ValueError, UnicodeDecodeError) as e:
            log.error('hub: 向量文件损坏 %s (%s)', path, e)
            return (500, {'Content-Type': 'application/json; charset=utf-8'},
                    b'{"error":"vectors file corrupted"}')
        if not isinstance(parsed, list) or not parsed:
            log.error('hub: 向量文件格式非法（应为非空数组）%s', path)
            return (500, {'Content-Type': 'application/json; charset=utf-8'},
                    b'{"error":"vectors file malformed"}')
        if not all(isinstance(e, dict) for e in parsed):
            log.error('hub: 向量文件格式非法（元素应为对象）%s', path)
            return (500, {'Content-Type': 'application/json; charset=utf-8'},
                    b'{"error":"vectors file malformed"}')
        body = self.encode(parsed)
        etag = self.etag_of(body)
        headers = {'Content-Type': 'application/json; charset=utf-8',
                   'ETag': etag, 'X-Hub-Vectors': str(len(parsed))}
        if self.etag_matches(if_none_match, etag):
            return 304, headers, b''
        return 200, headers, body

    def summary_response(self):
        body = self.encode(self.feedback_summary())
        return (200, {'Content-Type': 'application/json; charset=utf-8'}, body)


def _s(v):
    """任意值 -> 去空白字符串或 None（落库前的宽松归一，不抛异常）。"""
    if v is None:
        return None
    s = str(v).strip()
    return s or None


def _bearer_ok(auth_header, token):
    """`Authorization: Bearer <token>` 常量时间比对。"""
    if not auth_header:
        return False
    parts = str(auth_header).strip().split(None, 1)
    if len(parts) != 2 or parts[0].lower() != 'bearer':
        return False
    return hmac.compare_digest(parts[1].strip().encode('utf-8'),
                               str(token).encode('utf-8'))
