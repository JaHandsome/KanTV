# -*- coding: utf-8 -*-
"""投递路由叶子模块（无副作用；不 import hub/builder，杜绝横向依赖）。

从 `app/hub.py` **原样迁移** `route_of` 的精确 T3 映射与常量，并新增
**当前主力 media3 客户端可投递路由**这一共享判据：

- `route_of`：完整映射表（含 `flv -> 'flv'`），供契约与向后兼容读取；
- `media3_deliverable_route`：只放行主力客户端原生可播的
  `hls-ts` / `hls-aes128` / `dash-clearkey`，其余（含 FLV、异族 DRM、
  HLS DRM、sample-aes、未知容器）一律 `None`。

`builder._collect_media3_items`（静态 `live.media3.json` 候选全集）与
`hub._line_rows`（按请求 caps 取子集的基础候选集）**必须调用同一函数**，
避免各自再维护 FLV / 异族 DRM 判断，导致两处路由集合漂移。

候选集**取数**（lineups JOIN + sources 兜底、status/av/profile 过滤、route 过滤、
过滤后才截断 max_lines）同样只允许 `collect_deliverable_lines` 一份实现，
builder/hub 各自只做输出投影；`parse_clearkey` / `deliverable_container`
是两端投影共用的容错叶子，也不许多份。
"""
import json

from app.db import DELIVERABLE_PROFILES, infer_container_from_url

# route 枚举与 caps 枚举 v1 为**同一封闭集合**（契约 §3.1 / §5.1 逐字一致）。
# 任何新增必须两端同步（视为破坏性变更，记 HUB_CONTRACT_CHANGELOG）。
CAPS_ENUM = ('hls-ts', 'hls-aes128', 'flv', 'dash-clearkey', 'web-embed')
ROUTE_ENUM = CAPS_ENUM

# 契约 §3.1：v1 明确**不在**集合内 -> 服务端不得投递。
ROUTE_EXCLUDED = ('hls-sample-aes', 'dash-mpegts')

# DASH + ClearKey 的可投递加密取值（契约 §3.1 的 route 表 + T3 映射表）
_DASH_CLEARKEY_ENC = ('clearkey', 'cenc')
# 已知的**非** ClearKey DRM：解不了（客户端无 Widevine/PlayReady 能力）=> fail-closed 不投递。
# （这是对 T3 映射表的加固：仅凭 encryption='cenc' 无法区分 ClearKey 与 Widevine。）
_FOREIGN_DRM = ('widevine', 'playready', 'cenc-unknown')
_PLAINTEXT_ENC = ('',)

# ★ 集合契约：主力 media3 客户端当前可投递的 route 子集。
# 静态 `live.media3.json` = 基于本集合的**候选全集**（不含请求 caps 过滤）；
# Hub 以同一候选集为基础，再按客户端声明 caps 取子集。
MEDIA3_DELIVERABLE_ROUTES = ('hls-ts', 'hls-aes128', 'dash-clearkey')

# lineups JOIN 与 sources 直查两条路径共用的取列清单（(表达式, 别名)）。
# 必须用同一个清单生成 SQL：两条路径字段漂移会让"枢纽能投的 ≠ 构建器能投的"。
# 取列 = builder（media3.json 投影）与 hub（契约 §3 投影）两端所需字段的并集：
# hub 消费 url/url_key/score/verified_at，builder 消费 profile/rank 次序，
# clearkey/container 兜底两端都要；多出的键对另一端无副作用（投影只读取各自字段）。
_LINE_COLS = (('url', 'url'), ('url_key', 'url_key'), ('container', 'container'),
              ('encryption', 'encryption'), ('drm', 'drm'),
              ('keyformat', 'keyformat'), ('kid', 'kid'), ('drm_key', 'drm_key'),
              ('score', 'score'), ('last_verified', 'verified_at'),
              ('profile', 'profile'))


def _fields(prefix=''):
    """生成取列子句；prefix 用于 JOIN 别名（''=直查 sources，'s.'=JOIN 路径）。"""
    return ', '.join('%s%s AS %s' % (prefix, expr, alias) for expr, alias in _LINE_COLS)


def route_of(container, encryption, drm, url=''):
    """线路 -> route 枚举（**纯函数**，契约 §3.1；不可投递返回 None）。

    映射表（T3）+ fail-closed 边界：

    ==============================  =========================  ===============
    container                       encryption / drm            route
    ==============================  =========================  ===============
    hls / ts / m3u8                 明文（enc 空、drm 空）      hls-ts
    hls / ts / m3u8                 enc=aes128、drm 空          hls-aes128
    flv                             明文                        flv
    dash                            enc ∈ {clearkey,cenc}       dash-clearkey
                                    且 drm ∉ {widevine,...}
    其余（含 sample-aes/cbcs、       —                          不投递（fail-closed）
    dash+MPEG-TS、未知容器）
    ==============================  =========================  ===============

    ★ 为什么 `dash` 分支还要看 `drm`：`sources.encryption='cenc'` 同时覆盖
    ClearKey 与 Widevine/PlayReady（license 类型只在 `drm` 列区分）。只看 encryption
    会把 Widevine 源投成 `dash-clearkey` ⇒ 客户端 `drm_error` 黑屏。故显式排除
    已知异族 DRM（fail-closed）。契约 §3.1 的 `hls-sample-aes` / `dash-mpegts`
    不在 v1 内，此处同样落回 None。

    ★ 本函数保留 `flv -> 'flv'` 的完整映射（契约/兼容读取）；**投递侧**请改用
    `media3_deliverable_route`（它会拒掉 flv）。
    """
    c = (container or '').strip().lower() or infer_container_from_url(url)
    enc = (encryption or '').strip().lower()
    d = (drm or '').strip().lower()

    if c in ('hls', 'ts', 'm3u8'):
        if d:
            return None                       # HLS 带任何 DRM -> v1 不可投递
        if enc in _PLAINTEXT_ENC:
            return 'hls-ts'
        if enc == 'aes128':
            return 'hls-aes128'
        return None                           # sample-aes 等（契约 §3.1 明确排除）
    if c == 'flv':
        if d or enc not in _PLAINTEXT_ENC:
            return None
        return 'flv'
    if c == 'dash':
        if enc in _DASH_CLEARKEY_ENC and d not in _FOREIGN_DRM:
            return 'dash-clearkey'
        return None                           # dash+MPEG-TS / 无密钥 DRM -> 不投递
    return None


def media3_deliverable_route(container, encryption, drm, url=''):
    """当前主力 media3 客户端可投递的 route；不可投递返回 None。

    在 `route_of` 之上做**投递策略过滤**（不改写 T3 映射表）：

    - `route_of` 仍把 FLV 映射为 `'flv'`（契约 / 兼容）；
    - 本函数仅放行 `MEDIA3_DELIVERABLE_ROUTES`（`hls-ts` / `hls-aes128` /
      `dash-clearkey`），因此 FLV、HLS DRM、sample-aes、cbcs、异族 DRM
      （Widevine/PlayReady/cenc-unknown）、dash+MPEG-TS、未知容器一律 None。

    builder 与 hub 共用本函数：任何 FLV / 异族 DRM 策略只在这里维护一份。
    """
    route = route_of(container, encryption, drm, url)
    return route if route in MEDIA3_DELIVERABLE_ROUTES else None


# ================= 候选集取数（builder 与 hub 的唯一共享实现） =================

def collect_deliverable_lines(db, channels, max_lines, profiles=DELIVERABLE_PROFILES):
    """{channel_key: [line_row]} —— builder/hub 基础候选集的**唯一**取数实现。

    lineups 优先（health 已重排的线路表）；`channels` 中某频道**过滤后无合格
    lineups** 时回退直查 sources。两处均按同一口径过滤（与
    `health.rebuild_lineups` 写 lineups 的规则一致）：

    - `status='active' AND av LIKE 'video%'` —— lineups 只是重排的**快照**，
      源在探活中被判 dead/flaky 或改判音频后、下一轮 rebuild_lineups 之前的
      窗口期内，不得把死线/音频线投给电视端（P2-3）；
    - 按 `profile ∈ profiles`（默认 DELIVERABLE_PROFILES）过滤；
    - 按共享 `media3_deliverable_route` 过滤（None / FLV / 异族 DRM /
      sample-aes / 未知容器一律不投递）；
    - **过滤后才截断 max_lines**：lineups 路径逐行截断、fallback SQL 刻意不写
      LIMIT（否则高分无效 route 会先占满名额、遮挡后续低分有效源；数据规模
      有限，全量取回后 Python 过滤是最小正确实现）。

    `channels` 只控制 fallback 轮询的键集合；JOIN 路径始终覆盖库内全部频道
    （与重构前 builder/hub 各自实现一致）。row 取列见 `_LINE_COLS`。
    """
    marks = ','.join('?' * len(profiles))
    lines_by_key = {}
    for r in db.query(
            'SELECT l.channel_key AS k, l.rank AS rank, ' + _fields('s.') +
            ' FROM lineups l JOIN sources s ON s.id = l.source_id '
            "WHERE s.status='active' AND s.av LIKE 'video%%' "
            'AND s.profile IN (%s) ORDER BY l.channel_key, l.rank' % marks,
            tuple(profiles)):
        if media3_deliverable_route(r.get('container'), r.get('encryption'),
                                    r.get('drm'), r.get('url')) is None:
            continue
        lines = lines_by_key.setdefault(r['k'], [])
        if len(lines) < max_lines:
            lines.append(r)
    for key in channels:
        if lines_by_key.get(key) or max_lines <= 0:
            continue
        cands = db.query(
            'SELECT ' + _fields('') + " FROM sources WHERE channel_key=? AND "
            "status='active' AND av LIKE 'video%%' AND profile IN (%s) "
            'ORDER BY score DESC, id ASC' % marks,
            (key,) + tuple(profiles))
        picked = []
        for c in cands:
            if media3_deliverable_route(c.get('container'), c.get('encryption'),
                                        c.get('drm'), c.get('url')) is None:
                continue
            picked.append(c)
            if len(picked) >= max_lines:
                break
        if picked:
            lines_by_key[key] = picked
    return lines_by_key


def parse_clearkey(drm_key):
    """`drm_key`（清单内联密钥原文）-> clearkey 对象；坏 JSON/非对象/空 -> None。

    刻意**不改写** base64url 原文（builder 的 media3.json 投影与 hub 的契约 §3
    投影同规则）：密钥保真优先于格式统一。任何解析容错只许在这一份实现里。
    """
    if not drm_key:
        return None
    try:
        parsed = json.loads(drm_key)
    except (ValueError, TypeError):
        return None
    return parsed if isinstance(parsed, dict) else None


def deliverable_container(container, url):
    """container 为 NULL/空时按 URL 后缀兜底推断（两端投影共用，绝不留空 container）。"""
    return container or infer_container_from_url(url)
