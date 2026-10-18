# -*- coding: utf-8 -*-
"""StatusProvider：healthz / api/status / 状态页的唯一数据出口（P1-E/G）。

- healthz()      -> (http_code, dict)：调度心跳 + 数据新鲜度；
                    心跳超龄（> 5 x probe 周期）返回 503，Docker healthcheck
                    据此重启容器，堵死"僵尸模式"检测盲区；
- api_status()   -> dict：频道覆盖矩阵 + 源状态分布 + 最近流水线结果（JSON）；
- render_html()  -> str：服务端预渲染的 /status 页面（纯 HTML，无 JS 依赖）。

数据全部来自现成查询（sources/health_checks/lineups + 调度器内存状态），
无任何写操作，请求粒度聚合（查询均为索引命中，开销可忽略）。
"""
import html
import json
import time

from app import __version__
from app.db import DELIVERABLE_PROFILES, iso_plus_hours, utcnow
from app.logutil import get_logger
from app.schema import URL_KEY_REBUILD_STATS_META

log = get_logger('status')

# 源状态取值域（P0 R3：原 4 类增加 unsupported / drm；v5 增加 collision-orphan。
# collision-orphan 是 url_key 碰撞消解的落败行——它代表"发生过疑似误合并"，
# 必须独立可见而非归入 other，否则需要人工介入的信号会被掩盖）
STATUS_KEYS = ('active', 'flaky', 'dead', 'candidate', 'blacklisted', 'unsupported',
               'drm', 'collision-orphan')

# 频道覆盖色标：>=2 条有效线路 ok（绿）/ 1 条 warn（黄）/ 0 条 bad（红）
_COLOR_OK, _COLOR_WARN, _COLOR_BAD = 'ok', 'warn', 'bad'
_COLOR_CSS = {'ok': '#2e7d32', 'warn': '#f9a825', 'bad': '#c62828'}

_PAGE_TMPL = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>iptv-nas 状态页</title>
<style>
  body {{ font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
         margin: 24px; background: #f5f6f8; color: #222; }}
  h1 {{ font-size: 20px; margin: 0 0 4px; }}
  .sub {{ color: #777; font-size: 12px; margin-bottom: 16px; }}
  .cards {{ display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 20px; }}
  .card {{ background: #fff; border-radius: 8px; padding: 12px 18px;
          box-shadow: 0 1px 3px rgba(0,0,0,.08); min-width: 150px; }}
  .card .k {{ color: #888; font-size: 12px; }}
  .card .v {{ font-size: 20px; font-weight: 600; margin-top: 4px; }}
  table {{ border-collapse: collapse; background: #fff; width: 100%;
          max-width: 860px; box-shadow: 0 1px 3px rgba(0,0,0,.08);
          margin-bottom: 20px; }}
  th, td {{ border-bottom: 1px solid #eee; padding: 6px 10px;
           font-size: 13px; text-align: left; }}
  th {{ background: #fafafa; color: #555; }}
  .num {{ text-align: right; }}
  .dot {{ display: inline-block; width: 10px; height: 10px; border-radius: 50%;
         margin-right: 8px; vertical-align: middle; }}
  h2 {{ font-size: 15px; margin: 18px 0 8px; }}
</style>
</head>
<body>
<h1>iptv-nas 状态页</h1>
<div class="sub">版本 {version} ｜ 生成时间 {generated_at} ｜ 无人值守家庭 IPTV 聚合服务</div>
{invariant_html}

<div class="cards">
  <div class="card"><div class="k">调度心跳</div><div class="v">{scheduler_alive}</div></div>
  <div class="card"><div class="k">最近 tick</div><div class="v">{tick}</div></div>
  <div class="card"><div class="k">最近构建</div><div class="v">{build}</div></div>
  <div class="card"><div class="k">24h 探活存活率</div><div class="v">{rate}</div></div>
  <div class="card"><div class="k">active 源</div><div class="v">{active}</div></div>
  <div class="card"><div class="k">频道覆盖</div><div class="v">{ok_n}/{total} 双线路</div></div>
</div>

<h2>频道覆盖矩阵（绿 ≥2 线路 ｜ 黄 1 线路 ｜ 红 缺失）</h2>
<table>
<tr><th>频道</th><th>分组</th><th class="num">有效线路</th></tr>
{channel_rows}
</table>

<h2>源状态分布</h2>
<table>
<tr><th>状态</th><th class="num">数量</th></tr>
<tr><td>active</td><td class="num">{n_active}</td></tr>
<tr><td>candidate</td><td class="num">{n_candidate}</td></tr>
<tr><td>flaky</td><td class="num">{n_flaky}</td></tr>
<tr><td>dead</td><td class="num">{n_dead}</td></tr>
<tr><td>blacklisted</td><td class="num">{n_blacklisted}</td></tr>
<tr><td>unsupported</td><td class="num">{n_unsupported}</td></tr>
<tr><td>drm</td><td class="num">{n_drm}</td></tr>
<tr><td>other</td><td class="num">{n_other}</td></tr>
</table>

<h2>最近流水线任务</h2>
<table>
<tr><th>时间(UTC)</th><th>任务</th><th>结果</th><th>详情</th></tr>
{task_rows}
</table>
</body>
</html>
"""


class StatusProvider(object):
    """聚合 cfg / db / scheduler 数据生成健康与状态视图（只读）。"""

    def __init__(self, cfg, db, scheduler=None):
        """scheduler 可为 None（离线测试/独立装配），此时心跳项报未知但不误报故障。"""
        self.cfg = cfg
        self.db = db
        self.scheduler = scheduler

    # ---------- 调度心跳 ----------
    def _tick_age(self):
        """距上次调度 tick 的秒数；从未运行返回 None。"""
        if self.scheduler is None:
            return None
        last = getattr(self.scheduler, 'last_tick_time', None)
        if not last:
            return None
        return max(0.0, time.time() - last)

    def _max_tick_age(self):
        """心跳判活上限：5 x probe 周期（僵尸模式检测阈值，与 analysis #9 一致）。"""
        if self.scheduler is not None:
            try:
                return float(self.scheduler._period('probe')) * 5.0
            except Exception:  # noqa: BLE001 - 周期计算异常回退配置/默认值
                pass
        try:
            minutes = float(self.cfg.settings['schedule']['probe_minutes'])
        except (KeyError, TypeError, ValueError):
            minutes = 60.0
        return minutes * 60.0 * 5.0

    def _scheduler_view(self):
        age = self._tick_age()
        alive = None if age is None else (age <= self._max_tick_age())
        build_age = None
        if self.scheduler is not None:
            last_build = (getattr(self.scheduler, 'last_run', {}) or {}).get('rebuild', 0.0)
            if last_build:
                build_age = max(0.0, time.time() - last_build)
        return {'alive': alive, 'last_tick_age_s': age, 'last_build_age_s': build_age}

    # ---------- 数据聚合 ----------
    def _probe_rate_24h(self):
        """近 24h 探活存活率（无数据返回 None）。"""
        row = self.db.query_one(
            'SELECT COUNT(*) AS c, COALESCE(SUM(alive), 0) AS a '
            'FROM health_checks WHERE ts >= ?', (iso_plus_hours(-24),))
        if row and row['c']:
            return round(float(row['a']) / float(row['c']), 3)
        return None

    def _source_counts(self):
        """显式枚举已知状态 + 'other'（P0 R3：未知状态归 other，杜绝静默丢弃）。

        原实现只认 4 个键，新状态 unsupported/drm 会被静默丢弃 -> "独立可见"的
        验收无法达成（FR-3 行为 5）。
        """
        counts = {k: 0 for k in STATUS_KEYS}
        counts['other'] = 0
        for r in self.db.query('SELECT status, COUNT(*) AS c FROM sources GROUP BY status'):
            st = r['status']
            if st in counts:
                counts[st] = int(r['c'])
            else:
                counts['other'] += int(r['c'])
        return counts

    def _data_invariant(self):
        """数据不变量告警（P0 R3 + DRM 派生漂移修复）。

        统计两个稳定命名字段：

        - ``active_video_profile_null``：`active + av=video% + profile IS NULL` 的行数。
          迁移回填缺失时这些行会被 render_txt 的 profile 过滤整体排除，使频道从 live.txt
          静默消失——把该情况变成可观测事件（>0 记一次 ERROR）。
        - ``active_video_direct_with_drm``：`active + av=video%` 且 ``profile`` 规范化为
          'direct'，但 ``drm`` 非空或 ``encryption`` 规范化后不在 ``('', 'aes128')`` 的行数。
          这是 `db.delivery_profile` 的 fail-closed 反命题：命中即说明有 DRM 源泄漏进
          live.txt/live.m3u（>0 记一次 ERROR）。SQL 的 NULL/空白/大小写归一与
          ``delivery_profile`` 语义一致（NULL 视为空；strip + lower 后比较）。
        """
        n_null = 0
        row = self.db.query_one(
            "SELECT COUNT(*) AS c FROM sources "
            "WHERE status='active' AND av LIKE 'video%' AND profile IS NULL")
        if row:
            n_null = int(row['c'])
        if n_null > 0 and not getattr(self, '_invariant_warned', False):
            log.error('数据不变量告警: %d 个 active+video 源 profile IS NULL（将从 live.txt '
                      '静默消失；检查 schema v3 迁移回填是否生效）', n_null)
            self._invariant_warned = True

        n_direct = 0
        row = self.db.query_one(
            "SELECT COUNT(*) AS c FROM sources "
            "WHERE status='active' AND av LIKE 'video%' "
            "AND profile IS NOT NULL AND trim(lower(profile))='direct' "
            "AND ((drm IS NOT NULL AND trim(lower(drm)) <> '') "
            "     OR (encryption IS NOT NULL "
            "         AND trim(lower(encryption)) NOT IN ('', 'aes128')))")
        if row:
            n_direct = int(row['c'])
        if n_direct > 0 and not getattr(self, '_invariant_direct_warned', False):
            log.error('数据不变量告警: %d 个 active+video 源 profile=direct 但持有 DRM/'
                      '非明文加密事实（会泄漏进 live.txt/live.m3u；检查 record() 是否'
                      '按 db.delivery_profile 派生 profile）', n_direct)
            self._invariant_direct_warned = True

        return {'active_video_profile_null': n_null,
                'active_video_direct_with_drm': n_direct}

    def _coverage(self):
        """每频道有效线路数（active 且 av=video 且 profile ∈ DELIVERABLE_PROFILES）+ 色标。

        P0 R3：加 profile 过滤（引用常量集，不内联字符串）——否则 need-key/
        unsupported-drm 源被算作有效线路，频道呈现"有线路但播不了"的假绿。
        """
        marks = ','.join('?' * len(DELIVERABLE_PROFILES))
        rows = self.db.query(
            "SELECT channel_key AS k, COUNT(*) AS c FROM sources "
            "WHERE status='active' AND av LIKE 'video%%' AND channel_key != '' "
            "AND profile IN (%s) GROUP BY channel_key" % marks, DELIVERABLE_PROFILES)
        lines = {r['k']: int(r['c']) for r in rows}
        ch = self.cfg.channels
        groups = ch.get('channel_groups') or {}
        channels = []
        for key in ch.get('channels') or []:
            n = lines.get(key, 0)
            color = _COLOR_OK if n >= 2 else (_COLOR_WARN if n == 1 else _COLOR_BAD)
            channels.append({'name': key, 'group': groups.get(key) or '卫视',
                             'lines': n, 'color': color})
        ok_n = sum(1 for c in channels if c['color'] == _COLOR_OK)
        warn_n = sum(1 for c in channels if c['color'] == _COLOR_WARN)
        return {'total': len(channels), 'ok': ok_n, 'partial': warn_n,
                'missing': len(channels) - ok_n - warn_n, 'channels': channels}

    def _url_key_rebuild(self):
        """ARCH P1-6：最近一次 url_key 全表重建统计（meta 锚点，只读）。

        指纹变化触发重建时由 schema.ensure_url_keys_fresh 在同一事务落盘；
        从未重建 / 值损坏 -> None（状态页显示"无记录"，不误报）。
        """
        raw = self.db.get_meta(URL_KEY_REBUILD_STATS_META)
        if not raw:
            return None
        try:
            stats = json.loads(raw)
        except ValueError:
            return None
        return stats if isinstance(stats, dict) else None

    def snapshot(self):
        """聚合完整状态快照（json 可序列化）。"""
        return {
            'version': __version__,
            'generated_at': utcnow(),
            'scheduler': self._scheduler_view(),
            'probe_alive_rate_24h': self._probe_rate_24h(),
            'sources': self._source_counts(),
            'data_invariant': self._data_invariant(),
            # ARCH P1-6：补算率可观测（最近一次重建的 recomputed/unchanged/collisions）
            'url_key_rebuild': self._url_key_rebuild(),
            'coverage': self._coverage(),
            'recent_tasks': list(getattr(self.scheduler, 'history', [])[-20:])
                            if self.scheduler is not None else [],
        }

    # ---------- 出口 ----------
    def healthz(self):
        """返回 (http_code, dict)。

        503 仅在心跳明确超龄（alive is False）时返回——Docker healthcheck
        收到非 2xx 即判 unhealthy 触发重启；从未运行/无调度器不误报。
        """
        s = self._scheduler_view()
        degraded = (s['alive'] is False)
        data = {
            'status': 'degraded' if degraded else 'ok',
            'version': __version__,
            'scheduler_alive': s['alive'],
            'last_tick_age_s': s['last_tick_age_s'],
            'last_build_age_s': s['last_build_age_s'],
            'probe_alive_rate': self._probe_rate_24h(),
            'active_sources': self._source_counts()['active'],
        }
        return (503 if degraded else 200), data

    def api_status(self):
        """GET /api/status 的 JSON 数据。"""
        return self.snapshot()

    def render_html(self):
        """GET /status 的服务端预渲染 HTML（无 JS）。"""
        esc = html.escape
        snap = self.snapshot()
        cov, src, sched = snap['coverage'], snap['sources'], snap['scheduler']
        tick = sched['last_tick_age_s']
        tick_s = '从未运行' if tick is None else '%.0f 秒前' % tick
        build = sched['last_build_age_s']
        build_s = '从未构建' if build is None else '%.0f 秒前' % build
        alive = sched['alive']
        alive_s = {True: '正常', False: '失联(疑似僵尸模式)',
                   None: '未知'}[alive]
        alive_style = '' if alive is True else ' style="color:#c62828"'
        rate = snap['probe_alive_rate_24h']
        rate_s = '--' if rate is None else '%.1f%%' % (rate * 100)
        channel_rows = '\n'.join(
            '<tr><td><span class="dot" style="background:%s"></span>%s</td>'
            '<td>%s</td><td class="num">%d</td></tr>'
            % (_COLOR_CSS[c['color']], esc(c['name']), esc(c['group']), c['lines'])
            for c in cov['channels'])
        task_rows = '\n'.join(
            '<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>'
            % (esc(t.get('ts', '')), esc(t.get('task', '')),
               '成功' if t.get('ok') else '失败', esc(t.get('detail', '')))
            for t in reversed(snap['recent_tasks']))
        if not task_rows:
            task_rows = '<tr><td colspan="4">暂无记录</td></tr>'
        inv = snap.get('data_invariant') or {}
        n_null = int(inv.get('active_video_profile_null', 0))
        n_direct = int(inv.get('active_video_direct_with_drm', 0))
        cards = []
        if n_null > 0:
            cards.append(
                '<div class="card" style="background:#fdecea;border-left:4px solid #c62828">'
                '<div class="k">数据不变量告警</div><div class="v" style="color:#c62828">'
                '%d 个 active+video 源 profile IS NULL</div>'
                '<div class="sub" style="margin:6px 0 0">这些频道将从 live.txt 静默消失，'
                '请检查 schema v3 迁移回填</div></div>' % n_null)
        if n_direct > 0:
            cards.append(
                '<div class="card" style="background:#fdecea;border-left:4px solid #c62828">'
                '<div class="k">数据不变量告警</div><div class="v" style="color:#c62828">'
                '%d 个 active+video 源 direct 但持有 DRM/非明文加密</div>'
                '<div class="sub" style="margin:6px 0 0">这些 DRM 源会泄漏进 live.txt/live.m3u，'
                '请检查 record() 是否按 db.delivery_profile 派生 profile</div></div>' % n_direct)
        invariant_html = ''.join(cards)
        return _PAGE_TMPL.format(
            version=esc(snap['version']), generated_at=esc(snap['generated_at']),
            scheduler_alive='<span%s>%s</span>' % (alive_style, alive_s),
            tick=tick_s, build=build_s, rate=rate_s,
            active=src['active'], total=cov['total'], ok_n=cov['ok'],
            n_active=src['active'], n_candidate=src['candidate'],
            n_flaky=src['flaky'], n_dead=src['dead'],
            n_blacklisted=src.get('blacklisted', 0), n_unsupported=src.get('unsupported', 0),
            n_drm=src.get('drm', 0), n_other=src.get('other', 0),
            invariant_html=invariant_html,
            channel_rows=channel_rows, task_rows=task_rows)
