# -*- coding: utf-8 -*-
"""OutputBuilder：输出构建（P0-5）。

输出格式 100% 移植 iptv_pipeline.build()（架构红线：被电视端验证过的格式不重写）：
- live.txt：`组名,#genre#` 分组头 + `频道名,url1#url2#url3` 多线路 '#' 拼接；
- live.m3u：#EXTM3U + x-tvg-url(EPG 模板 epg.51zmt.top) + tvg-logo(gitee suxuang 台标)
  + group-title，TVB 翡翠/明珠经 epg_names 映射为 tvg-id；
- tvbox_lives_snippet.json：url 指向 http://<snippet_host>:<port>/live.txt。

数据源从 JSON 文件换成 sqlite（lineups 为主，冷启动无 lineups 时回退直查 sources）。
全部文件经 atomic_write（.tmp + os.replace）原子替换，HTTP 端永不读到半截文件。
"""
import json
import os
import time

from app.db import DELIVERABLE_PROFILES, utcnow
from app.delivery import (collect_deliverable_lines, deliverable_container,
                          parse_clearkey)
from app.logutil import get_logger

log = get_logger('builder')

# live.media3.json 契约版本（PRD 附件 A：字段语义不兼容变更时递增）
# v1 -> v2（HK MPD 路线）：首次真正产出 drm='clearkey' + 内联 clearkey 对象，
# key_present 由恒 null 变为有取值；旧客户端拿到 drm='clearkey' 却不知 clearkey 字段
# 会播不了 —— 属语义变更，按附件 A 卫生要求递增。
MEDIA3_SCHEMA_VERSION = 2


class OutputBuilder(object):
    """从 lineups/sources 构建 TVBox 订阅三件套。"""

    def __init__(self, cfg, db):
        self.cfg = cfg
        self.db = db

    # ---------- 数据收集 ----------
    def _collect_items(self, profiles=('direct',)):
        """收集每频道线路 -> 排序后的 [{name, cat, urls}]（P0 R5 投递隔离）。

        lineups 为主数据源；某频道无 lineups（冷启动 health 未跑）时回退直查 sources
        （active + av=video，与 rebuild_lineups 同规则）。两处均按同一口径过滤：
        status='active' AND av LIKE 'video%'（lineups 只是重排快照，源被判 dead/flaky
        或改判音频后、下一轮 rebuild_lineups 之前的窗口期内不得投递，见 delivery.py）；
        NULL 视为不匹配（profile 迁移已回填，回填缺失时由 status._data_invariant 告警；av NULL 正常流程不会以 active 态出现，见 186fa0e reviewer 复核记录）。
        render_txt/render_m3u 调用方默认只传 ('direct',) —— DRM 源物理隔离在 live.txt 外。
        """
        max_lines = int(self.cfg.settings['limits']['max_lines_per_channel'])
        channels = self.cfg.channels.get('channels') or []
        groups = self.cfg.channels.get('channel_groups') or {}
        marks = ','.join('?' * len(profiles))

        urls_by_key = {}
        rows = self.db.query(
            'SELECT l.channel_key AS k, s.url AS url FROM lineups l '
            'JOIN sources s ON s.id = l.source_id '
            "WHERE s.status='active' AND s.av LIKE 'video%%' "
            'AND s.profile IN (%s) ORDER BY l.channel_key, l.rank' % marks,
            tuple(profiles))
        for r in rows:
            urls_by_key.setdefault(r['k'], []).append(r['url'])

        for key in channels:
            if urls_by_key.get(key):
                continue
            # 回退：lineups 为空时直接按线路规则取 sources（同样加 profile 过滤，
            # 否则冷启动会绕过投递隔离，见 FR-5 边界条件）
            cands = self.db.query(
                "SELECT url FROM sources WHERE channel_key=? AND status='active' "
                "AND av LIKE 'video%%' AND profile IN (%s) "
                "ORDER BY score DESC, id ASC LIMIT ?" % marks,
                (key,) + tuple(profiles) + (max_lines,))
            if cands:
                urls_by_key[key] = [c['url'] for c in cands]

        order = {v: i for i, v in enumerate(self.cfg.channels.get('order') or [])}
        gidx = self.cfg.channels.get('group_index') or {}
        items = []
        for key in channels:
            urls = urls_by_key.get(key) or []
            if not urls:
                continue
            items.append({'name': key, 'cat': groups.get(key) or '卫视',
                          'urls': urls[:max_lines]})
        items.sort(key=lambda x: (gidx.get(x['cat'], 9), order.get(x['name'], 99), x['name']))
        return items

    def _collect_media3_items(self, profiles=None):
        """收集**全部可投递**线路（含非 direct）-> live.media3.json 的 channels[]（P0 R5-b）。

        profiles 默认 DELIVERABLE_PROFILES（direct/m3u-kodiprop/media3-json）；
        need-key/unsupported-drm 为不可投递态，不进入本文件（与前三件套"零 DRM 源"一致）。

        候选集取数唯一实现为共享叶子 `delivery.collect_deliverable_lines`
        （与 hub 同源：lineups 优先、sources 兜底、profile 过滤、
        `media3_deliverable_route` 过滤、过滤后才截断 max_lines，见其 docstring）；
        本方法只做 PRD 附件 A line 投影与 (group_index, order, name) 频道排序。
        """
        if profiles is None:
            profiles = DELIVERABLE_PROFILES
        max_lines = int(self.cfg.settings['limits']['max_lines_per_channel'])
        ch = self.cfg.channels
        channels = ch.get('channels') or []
        groups = ch.get('channel_groups') or {}
        epg_names = ch.get('epg_names') or {}
        logo_tpl = ch.get('logo_template', '')
        lines_by_key = collect_deliverable_lines(self.db, channels, max_lines, profiles)

        order = {v: i for i, v in enumerate(ch.get('order') or [])}
        gidx = ch.get('group_index') or {}
        out = []
        for key in channels:
            lines = lines_by_key.get(key) or []
            if not lines:
                continue
            en = epg_names.get(key, key)
            out.append({
                'key': key,
                'name': key,
                'group': groups.get(key) or '卫视',
                'epg_id': en,
                'logo': logo_tpl.format(name=en) if logo_tpl else '',
                'lines': [self._media3_line(r, i) for i, r in enumerate(lines[:max_lines])],
            })
        out.sort(key=lambda x: (gidx.get(x['group'], 9), order.get(x['name'], 99), x['name']))
        return out

    @staticmethod
    def _media3_line(row, rank):
        """单条线路 -> 附件 A 契约 dict（必填 url/container，其余可 null）。

        HK MPD 路线（§6.1）：
        - ``clearkey``：``drm_key`` 列（清单内联密钥 JSON）经 ``json.loads`` 后的**对象**，
          base64url **原样保留**（服务端不改写；客户端直接 ``LocalMediaDrmCallback``）。
          ``drm_key`` 为 NULL / 非 JSON 字符串 -> ``clearkey`` 输出 ``None``（**均不抛异常**）。
        - ``key_present``：``drm=='clearkey'`` 且 ``drm_key`` 非空 -> ``True``（首次有取值）。
        - ``key_uri`` / ``license_uri``：源侧无 license URL，**恒 None**。

        防御（缺陷修复）：container 为 NULL/空时用共享叶子 `delivery.deliverable_container`
        按 URL 后缀兜底，绝不把空 container 写进契约（否则客户端无法区分 HLS/DASH）。
        clearkey 容错同理只此一份（与 hub 契约 §3 投影同源）。
        """
        def g(name):
            return row.get(name) if isinstance(row, dict) else None
        container = deliverable_container(g('container'), g('url'))
        drm = g('drm') or ''
        drm_key = g('drm_key')
        clearkey = parse_clearkey(drm_key)
        return {
            'url': g('url'),
            'container': container,
            'encryption': g('encryption') or '',
            'drm': drm,
            'keyformat': g('keyformat') or '',
            'kid': g('kid') or '',
            'key_present': bool(drm == 'clearkey' and drm_key),
            'key_uri': None,
            'license_uri': None,
            'clearkey': clearkey,
            'profile': g('profile') or 'direct',
            'rank': rank + 1,
        }

    # ---------- 渲染（与 iptv_pipeline.build 逐行兼容） ----------
    def render_txt(self, items):
        """渲染 live.txt（语义收紧：调用方保证 items 已按 profile='direct' 过滤）。

        本函数不加载 DRM 源；某频道只有非 direct 线路时由 _collect_items 整体过滤 ->
        频道"整体缺席"（而不是输出 `频道名,` 空 URL 行；该行会污染播放器解析）。
        """
        ch = self.cfg.channels
        groups = ch.get('groups') or {}
        txt = []
        last = None
        for it in items:
            g = groups.get(it['cat'], it['cat'])
            if g != last:
                txt.append('%s,#genre#' % g)
                last = g
            txt.append('%s,%s' % (it['name'], '#'.join(it['urls'])))
        return '\n'.join(txt) + '\n'

    def render_m3u(self, items):
        ch = self.cfg.channels
        groups = ch.get('groups') or {}
        logo_tpl = ch.get('logo_template', '')
        epg_names = ch.get('epg_names') or {}
        # m3u 面向 Media3/ExoPlayer 系播放器：x-tvg-url 用 XMLTV（e.xml 的 display-name
        # 与 tvg-name 同为频道键风格，可直接匹配）；DIYP 动态 api 仅保留在 TVBox 格式
        # （render_snippet）中，避免 DIYP 系播放器对逗号分隔多 EPG 的兼容风险。
        epg = ch.get('epg_xmltv') or ch.get('epg_template', '')
        m3u = ['#EXTM3U', '#EXTM3U x-tvg-url="%s"' % epg]
        for it in items:
            en = epg_names.get(it['name'], it['name'])
            g = groups.get(it['cat'], it['cat'])
            m3u.append('#EXTINF:-1 tvg-id="%s" tvg-name="%s" tvg-logo="%s" group-title="%s",%s'
                       % (en, en, logo_tpl.format(name=en), g, it['name']))
            m3u.append(it['urls'][0])
        return '\n'.join(m3u) + '\n'

    def render_snippet(self):
        ch = self.cfg.channels
        snippet = {'lives': [{
            'name': 'NAS精选直播',
            'type': 0,
            'url': self.cfg.snippet_url(),
            'epg': ch.get('epg_template', ''),
            'logo': ch.get('logo_template', ''),
        }]}
        return json.dumps(snippet, ensure_ascii=False, indent=1) + '\n'

    def render_media3_json(self, items):
        """渲染 live.media3.json（PRD 附件 A 契约，自研客户端主投递）。

        含 schema_version / generated_at / client_caps_version / channels[]；
        channel 含 key/name/group/epg_id/logo/lines[]；line 必填 url/container，其余可 null。
        与前三件套完全隔离：第三方播放器不解析本文件，本文件也不改变前三件套字节结构。
        """
        caps = self.cfg.settings.get('client_caps')
        version = caps.get('version') if isinstance(caps, dict) else None
        obj = {
            'schema_version': MEDIA3_SCHEMA_VERSION,
            'generated_at': utcnow(),
            'client_caps_version': version or '',
            'channels': items,
        }
        return json.dumps(obj, ensure_ascii=False, indent=1) + '\n'

    # ---------- 原子写 ----------
    @staticmethod
    def atomic_write(path, content):
        """写 *.tmp 后 os.replace 原子替换（ARCHITECTURE §7.6 红线）。

        Windows 平台目标文件被并发读取（如 httpserver/编辑器占用）时
        os.replace 会抛 PermissionError（Linux NAS 无此问题）：
        做 3 次 × 50ms 延迟重试；仍失败则抛出，
        由调度器捕获并在下一轮 rebuild 重建兜底（旧文件始终保持完整）。
        """
        d = os.path.dirname(path)
        if d:
            os.makedirs(d, exist_ok=True)
        tmp = path + '.tmp'
        with open(tmp, 'w', encoding='utf-8', newline='') as f:
            f.write(content)
        last_err = None
        for attempt in range(3):
            try:
                os.replace(tmp, path)
                return
            except PermissionError as e:
                last_err = e
                if attempt < 2:
                    time.sleep(0.05)
        log.error('原子替换失败(重试 3 次): %s (%s)', path, last_err)
        raise last_err

    # ---------- 主流程 ----------
    def build(self):
        """构建 4 个输出文件，返回 {channels, files, built_at}。

        P0 R5：前三件套只消费 profile='direct'（live.txt/live.m3u 零 DRM 源）；
        追加第 4 件套 live.media3.json（全部可投递线路，含元数据，原子写）。
        """
        items = self._collect_items()
        media3_items = self._collect_media3_items()
        out_dir = self.cfg.output_dir
        files = [os.path.join(out_dir, 'live.txt'),
                 os.path.join(out_dir, 'live.m3u'),
                 os.path.join(out_dir, 'tvbox_lives_snippet.json'),
                 os.path.join(out_dir, 'live.media3.json')]
        self.atomic_write(files[0], self.render_txt(items))
        self.atomic_write(files[1], self.render_m3u(items))
        self.atomic_write(files[2], self.render_snippet())
        self.atomic_write(files[3], self.render_media3_json(media3_items))
        result = {'channels': len(items), 'files': files, 'built_at': utcnow()}
        log.info('构建完成: %d 频道 (media3 %d 频道) -> live.txt/live.m3u/'
                 'tvbox_lives_snippet.json/live.media3.json',
                 len(items), len(media3_items))
        return result
