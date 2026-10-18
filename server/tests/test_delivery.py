# -*- coding: utf-8 -*-
"""投递隔离 + 第四件套（P0 R5，全部离线）。

覆盖：
- live.txt 只消费 profile='direct'（含 lineups 主路径与冷启动回退路径）；
- 某频道只有非 direct 线路 -> 该频道在 live.txt 整体缺席（无空 URL 行）；
- AC-5.7：active 且 profile='need-key' 的 .mpd URL 不出现在 live.txt；
- live.media3.json 契约（AC-5.5）：schema_version、line 必填 url/container、非 direct 带 profile；
- AC-5.8：GET /live.media3.json 返回 200 + application/json 且可 json.load。
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.builder import OutputBuilder                        # noqa: E402
from offline_fixtures import make_config  # noqa: E402
from app.db import Database, utcnow                          # noqa: E402
from app.delivery import (MEDIA3_DELIVERABLE_ROUTES,         # noqa: E402
                          collect_deliverable_lines,
                          deliverable_container,
                          media3_deliverable_route,
                          parse_clearkey)
from app.hub import Hub, route_of                            # noqa: E402
from app.httpserver import _BoundedHTTPServer, _make_handler  # noqa: E402
from app.verifier import Verifier                            # noqa: E402


class TestDeliveryIsolation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-delivery-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['limits']['max_lines_per_channel'] = 3
        cfg.channels['channels'] = ['CCTV1', 'CCTV2']
        cfg.channels['channel_groups'] = {}
        cfg.channels['order'] = []
        cfg.channels['group_index'] = {}
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _add(self, url, key, profile='direct', status='active', av='video(TS:H.264视频)',
             container='hls', encryption='', drm='', score=10):
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen, profile, container, encryption, drm)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (url, url, key, key, 'test', status, av, score, now, now,
             profile, container, encryption, drm))
        return self.db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']

    def _lineup(self, key, sid, rank):
        self.db.execute(
            'INSERT INTO lineups(channel_key, source_id, rank, updated_at) VALUES (?,?,?,?)',
            (key, sid, rank, utcnow()))

    def _txt(self):
        with open(os.path.join(self.cfg.output_dir, 'live.txt'), encoding='utf-8') as f:
            return f.read()

    def _m3u(self):
        with open(os.path.join(self.cfg.output_dir, 'live.m3u'), encoding='utf-8') as f:
            return f.read()

    def test_fallback_path_filters_need_key(self):
        """冷启动回退路径（无 lineups）也必须隔离：need-key 源不进 live.txt。"""
        self._add('http://x/cctv1-direct.m3u8', 'CCTV1', profile='direct')
        self._add('http://x/cctv1-drm.mpd', 'CCTV1', profile='need-key',
                  container='dash', encryption='cenc', drm='widevine', score=99)
        OutputBuilder(self.cfg, self.db).build()
        txt = self._txt()
        self.assertIn('http://x/cctv1-direct.m3u8', txt)
        self.assertNotIn('http://x/cctv1-drm.mpd', txt)

    def test_lineups_path_filters_need_key(self):
        """lineups 主路径同样按 profile 过滤。"""
        direct = self._add('http://x/d.m3u8', 'CCTV1', profile='direct')
        drm = self._add('http://x/n.mpd', 'CCTV1', profile='need-key',
                        container='dash', encryption='cenc', drm='widevine')
        self._lineup('CCTV1', drm, 0)      # rank 0 -> 若不过滤会排最前
        self._lineup('CCTV1', direct, 1)
        OutputBuilder(self.cfg, self.db).build()
        txt = self._txt()
        self.assertIn('http://x/d.m3u8', txt)
        self.assertNotIn('http://x/n.mpd', txt)

    def test_channel_with_only_non_direct_is_absent(self):
        """只有 need-key 线路的频道 -> 整体缺席（无 `频道名,` 空 URL 行）。"""
        self._add('http://x/only.mpd', 'CCTV1', profile='need-key',
                  container='dash', encryption='cenc', drm='widevine')
        self._add('http://x/ok.m3u8', 'CCTV2', profile='direct')
        OutputBuilder(self.cfg, self.db).build()
        lines = self._txt().splitlines()
        self.assertFalse(any(l.startswith('CCTV1,') for l in lines))
        self.assertTrue(any(l.startswith('CCTV2,') for l in lines))
        self.assertFalse(any(l.endswith(',') for l in lines))   # 无空 URL 行

    def test_cold_start_after_clearing_lineups(self):
        """清空 lineups 后重建：隔离在回退路径同样生效（AC-5.6）。"""
        direct = self._add('http://x/d2.m3u8', 'CCTV2', profile='direct')
        drm = self._add('http://x/n2.mpd', 'CCTV2', profile='need-key',
                        container='dash', encryption='cenc', drm='widevine')
        self._lineup('CCTV2', direct, 0)
        self._lineup('CCTV2', drm, 1)
        self.db.execute('DELETE FROM lineups')
        OutputBuilder(self.cfg, self.db).build()
        txt = self._txt()
        self.assertIn('http://x/d2.m3u8', txt)
        self.assertNotIn('http://x/n2.mpd', txt)

    def test_media3_json_contract(self):
        """AC-5.5：schema_version + line 必填 url/container + 非 direct 带 profile。"""
        self._add('http://x/d3.m3u8', 'CCTV1', profile='direct')
        self._add('http://x/ck.mpd', 'CCTV1', profile='media3-json', container='dash',
                  encryption='cenc', drm='clearkey')
        OutputBuilder(self.cfg, self.db).build()
        path = os.path.join(self.cfg.output_dir, 'live.media3.json')
        self.assertTrue(os.path.isfile(path))
        with open(path, encoding='utf-8') as f:
            obj = json.load(f)
        self.assertEqual(obj['schema_version'], 2)   # HK MPD 路线：1 -> 2
        self.assertIn('generated_at', obj)
        by_key = {c['key']: c for c in obj['channels']}
        self.assertIn('CCTV1', by_key)
        lines = by_key['CCTV1']['lines']
        self.assertTrue(lines)
        for ln in lines:
            self.assertIn('url', ln)
            self.assertIn('container', ln)
        non_direct = [ln for ln in lines if ln['url'].endswith('.mpd')]
        self.assertEqual(len(non_direct), 1)
        self.assertEqual(non_direct[0]['profile'], 'media3-json')
        self.assertEqual(non_direct[0]['container'], 'dash')
        # 归属性（AC-5.7）：该 .mpd 不得出现在 live.txt
        self.assertNotIn('http://x/ck.mpd', self._txt())

    def test_stored_drm_derived_profile_never_in_live_txt_or_m3u(self):
        """缺陷回归：存量 cenc+clearkey+drm_key 源经 record() 派生后不得进前两件套。

        探针结果只报视频存活、不带任何 DRM 事实；修复前 profile 会漂移成 direct，
        使裸 MPD URL 进 live.txt/live.m3u（黑屏）。修复后基于有效事实派生 media3-json。
        """
        url = 'http://x/tvb/index.mpd'
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen, profile, container, encryption, drm, drm_key)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (url, url, 'CCTV1', 'CCTV1', 'test', 'candidate', None, 10, now, now,
             'direct', 'dash', 'cenc', 'clearkey',
             '{"keys":[{"kty":"oct","k":"AAAA"}]}'))
        sid = self.db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']
        self.cfg.settings['client_caps'] = {'version': 'test', 'caps': {'dash_clearkey': True}}
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        Verifier(self.cfg, self.db, object()).record(
            row, {'alive': True, 'av': 'video(TS:H.264视频)'}, 50)
        cur = self.db.query_one('SELECT status, profile FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'media3-json')

        OutputBuilder(self.cfg, self.db).build()
        self.assertNotIn(url, self._txt())
        self.assertNotIn(url, self._m3u())
        with open(os.path.join(self.cfg.output_dir, 'live.media3.json'), encoding='utf-8') as f:
            obj = json.load(f)
        delivered = [ln['url'] for c in obj['channels'] for ln in c['lines']]
        self.assertIn(url, delivered)      # 第四件套仍投递（隔离而非丢弃）

    def test_media3_json_container_inferred_when_null(self):
        """缺陷防御：container 为 NULL 的线路，media3.json 输出必须非空（按 URL 推断）。"""
        self._add('http://x/null.m3u8', 'CCTV1', profile='direct', container=None)
        self._add('http://x/dash.mpd', 'CCTV1', profile='media3-json', container=None,
                  encryption='cenc', drm='clearkey')
        OutputBuilder(self.cfg, self.db).build()
        path = os.path.join(self.cfg.output_dir, 'live.media3.json')
        with open(path, encoding='utf-8') as f:
            obj = json.load(f)
        by_key = {c['key']: c for c in obj['channels']}
        by_url = {ln['url']: ln for ln in by_key['CCTV1']['lines']}
        for ln in by_url.values():
            self.assertTrue(ln['container'], 'container 不得为空: %r' % ln)
        self.assertEqual(by_url['http://x/null.m3u8']['container'], 'hls')
        self.assertEqual(by_url['http://x/dash.mpd']['container'], 'dash')

    def test_media3_route_served(self):
        """AC-5.8：GET /live.media3.json 返回 200 + JSON，可 json.load。"""
        self._add('http://x/d4.m3u8', 'CCTV1', profile='direct')
        OutputBuilder(self.cfg, self.db).build()
        handler = _make_handler(self.cfg.output_dir, None)
        srv = _BoundedHTTPServer(('127.0.0.1', 0), handler)
        port = srv.server_address[1]
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            url = 'http://127.0.0.1:%d/live.media3.json' % port
            with urllib.request.urlopen(url, timeout=5) as r:
                self.assertEqual(r.status, 200)
                self.assertIn('application/json', r.headers.get('Content-Type', ''))
                obj = json.loads(r.read().decode('utf-8'))
            self.assertIn('schema_version', obj)
        finally:
            srv.shutdown()
            srv.server_close()


class TestSharedDeliveryRoute(unittest.TestCase):
    """共享路由叶子：`route_of` 向后兼容 + 主力 media3 可投递子集判据。"""

    def test_hub_route_of_backward_compatible(self):
        """`from app.hub import route_of` 兼容；迁移不得改变 T3 映射（含 flv->flv）。"""
        self.assertEqual(route_of('flv', '', ''), 'flv')
        self.assertEqual(route_of('hls', '', ''), 'hls-ts')
        self.assertEqual(route_of('dash', 'cenc', 'widevine'), None)

    def test_接受主力media3可投递路由(self):
        self.assertEqual(media3_deliverable_route('hls', '', ''), 'hls-ts')
        self.assertEqual(media3_deliverable_route('ts', '', ''), 'hls-ts')
        self.assertEqual(media3_deliverable_route('m3u8', '', ''), 'hls-ts')
        self.assertEqual(media3_deliverable_route(None, '', '', 'http://x/a.m3u8'),
                         'hls-ts')                                   # 容器缺失按后缀推断
        self.assertEqual(media3_deliverable_route('hls', 'aes128', ''), 'hls-aes128')
        self.assertEqual(media3_deliverable_route('dash', 'cenc', 'clearkey'),
                         'dash-clearkey')
        self.assertEqual(media3_deliverable_route('dash', 'clearkey', ''),
                         'dash-clearkey')
        self.assertEqual(set(MEDIA3_DELIVERABLE_ROUTES),
                         {'hls-ts', 'hls-aes128', 'dash-clearkey'})

    def test_拒绝flv与异族DRM与HLS_DRM(self):
        for c, e, d in (('flv', '', ''),                            # 主力客户端已禁用 FLV
                        ('dash', 'cenc', 'widevine'),
                        ('dash', 'cenc', 'playready'),
                        ('dash', 'cenc', 'cenc-unknown'),
                        ('hls', 'cenc', 'clearkey'),               # HLS 带任何 DRM
                        ('hls', '', 'clearkey'),
                        ('hls', 'sample-aes', ''),                 # sample-aes
                        ('dash', 'cbcs', 'clearkey'),
                        ('dash', '', ''),
                        ('unknown', '', '')):
            self.assertIsNone(media3_deliverable_route(c, e, d), (c, e, d))
        # FLV 是唯一 "route_of 放行、投递侧不放行" 的 route：策略点只在共享函数一处
        self.assertEqual(route_of('flv', '', ''), 'flv')
        self.assertIsNone(media3_deliverable_route('flv', '', ''))


class TestMedia3CandidateSetContract(unittest.TestCase):
    """集合契约：静态 live.media3.json 候选全集 == Hub caps 全开基础候选；caps 为子集。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-delivery-set-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['limits']['max_lines_per_channel'] = 3
        cfg.channels['channels'] = ['CCTV1', 'CCTV2', 'CCTV3']
        cfg.channels['channel_groups'] = {}
        cfg.channels['order'] = []
        cfg.channels['group_index'] = {}
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _add(self, url, key, profile='direct', status='active', av='video(TS:H.264视频)',
             container='hls', encryption='', drm='', score=10):
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen, profile, container, encryption, drm)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (url, url, key, key, 'test', status, av, score, now, now,
             profile, container, encryption, drm))
        return self.db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']

    def _lineup(self, key, sid, rank):
        self.db.execute(
            'INSERT INTO lineups(channel_key, source_id, rank, updated_at) VALUES (?,?,?,?)',
            (key, sid, rank, utcnow()))

    def _seed(self):
        # CCTV1：lineups 主路径，含高分无效 route（FLV / Widevine / sample-aes）；
        # 另有一条不在 lineups 的有效源 -> 过滤后无合格 lineups 必须回退 sources 抓它
        self._lineup('CCTV1', self._add(
            'http://x/c1-invalid.flv', 'CCTV1', profile='direct',
            container='flv', score=99), 0)
        self._lineup('CCTV1', self._add(
            'http://x/c1-wn.mpd', 'CCTV1', profile='media3-json', container='dash',
            encryption='cenc', drm='widevine', score=98), 1)
        self._lineup('CCTV1', self._add(
            'http://x/c1-sample.m3u8', 'CCTV1', profile='media3-json',
            encryption='sample-aes', score=97), 2)
        self._add('http://x/c1-fallback.m3u8', 'CCTV1', profile='direct', score=10)
        # CCTV2：无 lineups -> fallback，高分无效 route 不得遮挡低分有效源
        for i, score in enumerate((99, 98, 97)):
            self._add('http://x/c2-bad%d.flv' % i, 'CCTV2', profile='direct',
                      container='flv', score=score)
        self._add('http://x/c2-ok.m3u8', 'CCTV2', profile='direct', score=10)
        # CCTV3：仅无效 route -> 整体缺席
        self._add('http://x/c3-only.flv', 'CCTV3', profile='direct',
                  container='flv', score=50)

    def _builder_urls(self):
        OutputBuilder(self.cfg, self.db).build()
        path = os.path.join(self.cfg.output_dir, 'live.media3.json')
        with open(path, encoding='utf-8') as f:
            obj = json.load(f)
        return {ln['url'] for c in obj['channels'] for ln in c['lines']}

    def test_builder与hub基础候选同源且caps为子集(self):
        self._seed()
        builder_urls = self._builder_urls()
        hub = Hub(self.cfg, self.db)

        base_urls = {r['url'] for rows in hub._line_rows().values() for r in rows}
        self.assertEqual(base_urls, builder_urls)
        self.assertEqual(builder_urls, {'http://x/c1-fallback.m3u8',
                                        'http://x/c2-ok.m3u8'})

        full = hub.build_subscription(caps=','.join(MEDIA3_DELIVERABLE_ROUTES))
        full_urls = {r['url'] for c in full['channels'] for r in c['routes']}
        self.assertEqual(full_urls, builder_urls)                   # caps 全开 = 全集

        only_ts = hub.build_subscription(caps='hls-ts')
        ts_urls = {r['url'] for c in only_ts['channels'] for r in c['routes']}
        self.assertTrue(ts_urls <= builder_urls)                    # caps 响应是子集
        self.assertEqual(ts_urls, builder_urls)                     # 本样本全为 hls-ts

        # caps 不全时确实取子集：声明 web-embed（无任何候选 route 命中）
        none_urls = {r['url'] for c in hub.build_subscription(
            caps='web-embed')['channels'] for r in c['routes']}
        self.assertEqual(none_urls, set())

    def test_lineups过滤后无合格线路才回退sources(self):
        self._seed()
        urls = self._builder_urls()
        # CCTV1：lineups 全为无效 route -> 回退并抓到不在 lineups 的有效源
        self.assertIn('http://x/c1-fallback.m3u8', urls)
        # CCTV2：无 lineups -> fallback 跳过 3 条高分无效 FLV，取低分有效源
        self.assertIn('http://x/c2-ok.m3u8', urls)
        # CCTV3：仅无效 route -> 整体缺席
        self.assertNotIn('http://x/c3-only.flv', urls)


class TestCollectDeliverableLinesSharedLeaf(unittest.TestCase):
    """共享叶子 `collect_deliverable_lines` / `parse_clearkey` / `deliverable_container`
    的直接单测（重构 2-1：builder/hub 候选集取数单份实现）——守门集合契约测试之外，
    锁住截断边界、取列并集与 clearkey/container 容错这三处最容易在合并时漂移的行为。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-delivery-leaf-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['limits']['max_lines_per_channel'] = 2
        cfg.channels['channels'] = ['CCTV1']
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _add(self, url, key, profile='direct', status='active',
             av='video(TS:H.264视频)', container='hls', encryption='', drm='',
             score=10, drm_key=None):
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen, profile, container, encryption, drm,"
            " drm_key, last_verified)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (url, url, key, key, 'test', status, av, score, now, now,
             profile, container, encryption, drm, drm_key, now))
        return self.db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']

    def _lineup(self, key, sid, rank):
        self.db.execute(
            'INSERT INTO lineups(channel_key, source_id, rank, updated_at) VALUES (?,?,?,?)',
            (key, sid, rank, utcnow()))

    def test_lineups路径过滤后才截断且保rank次序(self):
        # 3 条有效 + 1 条无效（FLV）混排：无效不占名额，截断取 rank 前 2
        self._lineup('CCTV1', self._add('http://x/a.m3u8', 'CCTV1', score=1), 0)
        self._lineup('CCTV1', self._add('http://x/bad.flv', 'CCTV1',
                                        container='flv', score=99), 1)
        self._lineup('CCTV1', self._add('http://x/b.m3u8', 'CCTV1', score=2), 2)
        self._lineup('CCTV1', self._add('http://x/c.m3u8', 'CCTV1', score=3), 3)
        out = collect_deliverable_lines(self.db, ['CCTV1'], 2)
        self.assertEqual([r['url'] for r in out['CCTV1']],
                         ['http://x/a.m3u8', 'http://x/b.m3u8'])

    def test_fallback行带齐两端投影字段并集(self):
        # 无 lineups -> 回退 sources；hub 投影需要 url_key/score/verified_at，
        # builder 投影需要 profile，行里一个都不能缺（取列并集契约）
        sid = self._add('http://x/fb.m3u8', 'CCTV1', score=7)
        out = collect_deliverable_lines(self.db, ['CCTV1'], 2)
        row = out['CCTV1'][0]
        for col in ('url', 'url_key', 'container', 'encryption', 'drm', 'keyformat',
                    'kid', 'drm_key', 'score', 'verified_at', 'profile'):
            self.assertIn(col, row)
        self.assertEqual(row['url_key'],
                         self.db.query_one('SELECT url_key FROM sources WHERE id=?',
                                           (sid,))['url_key'])
        self.assertEqual(row['profile'], 'direct')
        self.assertIsNotNone(row['verified_at'])

    def test_过滤后无合格lineups才回退sources(self):
        self._lineup('CCTV1', self._add('http://x/dead.flv', 'CCTV1',
                                        container='flv', score=99), 0)
        self._add('http://x/fb.m3u8', 'CCTV1', score=10)
        out = collect_deliverable_lines(self.db, ['CCTV1'], 2)
        self.assertEqual([r['url'] for r in out['CCTV1']], ['http://x/fb.m3u8'])

    def test_lineups路径过滤dead与音频源与rebuild_lineups同口径(self):
        """P2-3：lineups JOIN 路径补上 status='active' AND av LIKE 'video%'，
        与 rebuild_lineups 写表口径一致 —— 投递窗口期不再含死线/音频线。"""
        self._lineup('CCTV1', self._add('http://x/dead.m3u8', 'CCTV1',
                                        status='dead', score=99), 0)
        self._lineup('CCTV1', self._add('http://x/flaky.m3u8', 'CCTV1',
                                        status='flaky', score=98), 1)
        self._lineup('CCTV1', self._add('http://x/audio.m3u8', 'CCTV1',
                                        av='audio(AAC音频)', score=97), 2)
        self._lineup('CCTV1', self._add('http://x/ok.m3u8', 'CCTV1', score=10), 3)
        out = collect_deliverable_lines(self.db, ['CCTV1'], 2)
        self.assertEqual([r['url'] for r in out['CCTV1']], ['http://x/ok.m3u8'])

    def test_lineups全被status_av过滤时回退sources抓活线(self):
        """窗口期退化场景：lineups 里全是 dead/音频源 -> 过滤后无合格 lineups
        必须回退 sources（同口径谓词），把仍 active+video 的源投出去。"""
        self._lineup('CCTV1', self._add('http://x/dead.m3u8', 'CCTV1',
                                        status='dead', score=99), 0)
        self._lineup('CCTV1', self._add('http://x/audio.m3u8', 'CCTV1',
                                        av='audio(AAC音频)', score=98), 1)
        self._add('http://x/live.m3u8', 'CCTV1', score=10)
        out = collect_deliverable_lines(self.db, ['CCTV1'], 2)
        self.assertEqual([r['url'] for r in out['CCTV1']], ['http://x/live.m3u8'])

    def test_fallback路径status_av过滤行为不变(self):
        """回归锁：回退直查 sources 的 status/av 谓词不因 JOIN 路径修复而改变。"""
        self._add('http://x/dead.m3u8', 'CCTV1', status='dead', score=99)
        self._add('http://x/audio.m3u8', 'CCTV1', av='audio(AAC音频)', score=98)
        self._add('http://x/ok.m3u8', 'CCTV1', score=10)
        out = collect_deliverable_lines(self.db, ['CCTV1'], 2)
        self.assertEqual([r['url'] for r in out['CCTV1']], ['http://x/ok.m3u8'])


class TestProjectionLeafs(unittest.TestCase):
    """`parse_clearkey` / `deliverable_container`：builder 与 hub 投影共用的容错叶子。"""

    def test_parse_clearkey保留原文且容错(self):
        obj = {'keys': [{'kty': 'oct', 'k': 'base64url_原文==',
                         'kid': 'kid_原文'}]}
        raw = json.dumps(obj)
        self.assertEqual(parse_clearkey(raw), obj)      # 对象原样返回，不改写
        self.assertIsNone(parse_clearkey('not-json'))   # 坏 JSON
        self.assertIsNone(parse_clearkey('[1,2]'))      # JSON 但不是对象
        self.assertIsNone(parse_clearkey('"str"'))      # JSON 字符串
        self.assertIsNone(parse_clearkey(''))           # 空串
        self.assertIsNone(parse_clearkey(None))         # NULL 列

    def test_deliverable_container空值按URL后缀兜底(self):
        self.assertEqual(deliverable_container(None, 'http://x/a.mpd'), 'dash')
        self.assertEqual(deliverable_container('', 'http://x/a.flv?sig=1'), 'flv')
        self.assertEqual(deliverable_container(None, 'http://x/a.m3u8'), 'hls')
        self.assertEqual(deliverable_container('hls', 'http://x/a.mpd'), 'hls')  # 非空不动


if __name__ == '__main__':
    unittest.main()
