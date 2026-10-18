# -*- coding: utf-8 -*-
"""T4 单测：live.media3.json 内联 clearkey 投递 + 投递隔离（全部离线，临时库构造）。

依据 HK_MPD_ROUTE_DESIGN §6：schema 1->2；line 增 clearkey/key_present；
前三件套（live.txt/live.m3u）隔离不得被破坏。
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.builder import MEDIA3_SCHEMA_VERSION, OutputBuilder     # noqa: E402
from offline_fixtures import make_config  # noqa: E402
from app.db import Database, utcnow                              # noqa: E402

LICENSE_J = ('{"keys": [{"kty": "oct", "k": "22Q4E0SDl+0TWap5BmJG3Q", '
             '"kid": "VmA7Zfodc4O27w5zua5p+g"}], "type": "temporary"}')
BAD_JSON = '{oops not json'
WN_MPD = 'http://fixture-40.example.invalid/__cl/slocalr43/__c/ott_C_hevc/__f/index.mpd?sig=a'
WN_DIRECT = 'http://x/wn-direct.m3u8'
TVBP_MPD = 'http://fixture-40.example.invalid/__cl/slocalr87/__c/ott_B_hevc/__f/index.mpd?sig=b'
NULLKEY_MPD = 'http://x/nullkey.mpd'
BADKEY_MPD = 'http://x/badkey.mpd'


class TestMedia3Clearkey(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-media3-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['limits']['max_lines_per_channel'] = 3
        cfg.channels['channels'] = ['无线新闻', 'TVB Plus', 'CCTV1']
        cfg.channels['channel_groups'] = {}
        cfg.channels['order'] = []
        cfg.channels['group_index'] = {}
        cfg.channels['epg_names'] = {}
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()
        # 无线新闻：一条 direct + 一条 clearkey(合法 JSON) —— 用于隔离验证
        self._lineup('无线新闻', self._add(WN_DIRECT, '无线新闻', profile='direct'), 0)
        self._lineup('无线新闻', self._add(
            WN_MPD, '无线新闻', profile='media3-json', container='dash',
            encryption='cenc', drm='clearkey',
            keyformat='urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e',
            kid='e2367e20a72375639adadea499c55ce6', drm_key=LICENSE_J), 1)
        # TVB Plus：仅 media3-json —— 用于"整体缺席"
        self._lineup('TVB Plus', self._add(
            TVBP_MPD, 'TVB Plus', profile='media3-json', container='dash',
            encryption='cenc', drm='clearkey', kid='56603b65fa1d7383b6ef0e73b9ae69fa',
            drm_key=LICENSE_J), 0)
        # CCTV1：drm_key=NULL 与 drm_key=坏 JSON —— 用于健壮性
        self._lineup('CCTV1', self._add(
            NULLKEY_MPD, 'CCTV1', profile='media3-json', container='dash',
            encryption='cenc', drm='clearkey', drm_key=None), 0)
        self._lineup('CCTV1', self._add(
            BADKEY_MPD, 'CCTV1', profile='media3-json', container='dash',
            encryption='cenc', drm='clearkey', drm_key=BAD_JSON), 1)
        self.result = OutputBuilder(self.cfg, self.db).build()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _add(self, url, key, profile='direct', status='active', av='video(TS:H.264视频)',
             container='hls', encryption='', drm='', keyformat=None, kid=None,
             drm_key=None, score=10):
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen, profile, container, encryption, drm,"
            " keyformat, kid, drm_key)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (url, url, key, key, 'test', status, av, score, now, now,
             profile, container, encryption, drm, keyformat, kid, drm_key))
        return self.db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']

    def _lineup(self, key, sid, rank):
        self.db.execute(
            'INSERT INTO lineups(channel_key, source_id, rank, updated_at) VALUES (?,?,?,?)',
            (key, sid, rank, utcnow()))

    def _read(self, name):
        with open(os.path.join(self.cfg.output_dir, name), encoding='utf-8') as f:
            return f.read()

    def _media3(self):
        return json.loads(self._read('live.media3.json'))

    def _line_by_url(self, url):
        for ch in self._media3()['channels']:
            for ln in ch['lines']:
                if ln['url'] == url:
                    return ln
        return None

    # 1
    def test_schema_version_is_2(self):
        obj = self._media3()
        self.assertEqual(obj['schema_version'], 2)
        self.assertEqual(MEDIA3_SCHEMA_VERSION, 2)

    # 2 + 3
    def test_clearkey_object_bytes_preserved(self):
        ln = self._line_by_url(WN_MPD)
        self.assertIsNotNone(ln)
        self.assertEqual(ln['drm'], 'clearkey')
        expected = json.loads(LICENSE_J)
        self.assertEqual(ln['clearkey'], expected)
        # base64url 逐字节相同（未被改写）
        self.assertEqual(ln['clearkey']['keys'][0]['k'], '22Q4E0SDl+0TWap5BmJG3Q')
        self.assertEqual(ln['clearkey']['keys'][0]['kid'], 'VmA7Zfodc4O27w5zua5p+g')
        self.assertEqual(ln['clearkey']['type'], 'temporary')
        # 3
        self.assertTrue(ln['key_present'])
        self.assertIsNone(ln['key_uri'])
        self.assertIsNone(ln['license_uri'])

    # 4
    def test_null_drm_key_yields_none(self):
        ln = self._line_by_url(NULLKEY_MPD)
        self.assertIsNotNone(ln)
        self.assertIsNone(ln['clearkey'])
        self.assertFalse(ln['key_present'])

    # 5
    def test_bad_json_drm_key_yields_none_no_raise(self):
        ln = self._line_by_url(BADKEY_MPD)
        self.assertIsNotNone(ln)
        self.assertIsNone(ln['clearkey'])

    # 6
    def test_isolation_drm_url_absent_from_txt_and_m3u(self):
        txt = self._read('live.txt')
        m3u = self._read('live.m3u')
        self.assertIn(WN_DIRECT, txt)                 # direct 正常出现
        self.assertNotIn(WN_MPD, txt)                 # DRM 线路零次出现
        self.assertNotIn(WN_MPD, m3u)
        self.assertNotIn(TVBP_MPD, txt)
        self.assertNotIn(TVBP_MPD, m3u)

    # 7
    def test_channel_only_media3_absent_from_txt_present_in_media3(self):
        lines = self._read('live.txt').splitlines()
        self.assertFalse(any(l.startswith('TVB Plus') for l in lines))   # 频道名不出现
        self.assertFalse(any(l.endswith(',') for l in lines))            # 无空 URL 行
        keys = {c['key'] for c in self._media3()['channels']}
        self.assertIn('TVB Plus', keys)                                  # media3 内含该频道


class TestMedia3RouteExclusion(unittest.TestCase):
    """路由集合漂移回归：Builder 两路均须在 max_lines 截断前过滤无效 route。

    已确认缺陷：旧 `_collect_media3_items` 只按 DELIVERABLE_PROFILES 过滤，会把
    route_of=None 的 HLS DRM / Widevine / sample-aes / 未知容器及已禁用的 FLV
    写进 live.media3.json。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-media3-route-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['limits']['max_lines_per_channel'] = 3
        cfg.channels['channels'] = ['A', 'B', 'C']
        cfg.channels['channel_groups'] = {}
        cfg.channels['order'] = []
        cfg.channels['group_index'] = {}
        cfg.channels['epg_names'] = {}
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()
        # A：lineups 主路径，高分无效 route 排在有效 route 之前
        self._lineup('A', self._add('http://x/a.flv', 'A', profile='direct',
                                    container='flv', score=99), 0)
        self._lineup('A', self._add('http://x/a-wn.mpd', 'A', profile='media3-json',
                                    container='dash', encryption='cenc',
                                    drm='widevine', score=98), 1)
        self._lineup('A', self._add('http://x/a-sample.m3u8', 'A',
                                    profile='media3-json', encryption='sample-aes',
                                    score=97), 2)
        self._lineup('A', self._add('http://x/a-ok.m3u8', 'A', profile='direct',
                                    score=10), 3)
        # B：无 lineups -> fallback，3 条高分无效 FLV > max_lines，不得遮挡低分有效源
        for i in range(3):
            self._add('http://x/b-bad%d.flv' % i, 'B', profile='direct',
                      container='flv', score=99 - i)
        self._add('http://x/b-ok.m3u8', 'B', profile='direct', score=10)
        # C：lineups 中仅有无效 route，且无其它源 -> 整体缺席
        self._lineup('C', self._add('http://x/c-only.flv', 'C', profile='direct',
                                    container='flv', score=50), 0)
        self.result = OutputBuilder(self.cfg, self.db).build()

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

    def _read(self, name):
        with open(os.path.join(self.cfg.output_dir, name), encoding='utf-8') as f:
            return f.read()

    def _media3_urls(self):
        obj = json.loads(self._read('live.media3.json'))
        return {ln['url'] for c in obj['channels'] for ln in c['lines']}

    def test_lineups路径排除无效route(self):
        urls = self._media3_urls()
        self.assertIn('http://x/a-ok.m3u8', urls)
        for bad in ('http://x/a.flv', 'http://x/a-wn.mpd', 'http://x/a-sample.m3u8'):
            self.assertNotIn(bad, urls)

    def test_fallback高分无效route不遮挡低分有效源(self):
        urls = self._media3_urls()
        self.assertIn('http://x/b-ok.m3u8', urls)
        self.assertFalse(any(u.endswith('.flv') for u in urls))

    def test_仅无效route的频道整体缺席media3(self):
        obj = json.loads(self._read('live.media3.json'))
        self.assertNotIn('C', {c['key'] for c in obj['channels']})
        self.assertNotIn('http://x/c-only.flv', self._media3_urls())

    def test_direct前三件套收集行为不变(self):
        """工具路由过滤只作用于 live.media3.json；live.txt/live.m3u 的 direct 收集不动。"""
        txt = self._read('live.txt')
        m3u = self._read('live.m3u')
        # A 的 direct 明文 FLV 仍是前三件套内容（旧行为保留：不因 media3 路由过滤移除）
        self.assertIn('http://x/a.flv', txt)
        self.assertIn('http://x/a.flv', m3u)
        # 非 direct 的 DRM 源仍被前三件套物理隔离（与改动前一致）
        self.assertNotIn('http://x/a-wn.mpd', txt)
        self.assertNotIn('http://x/a-sample.m3u8', m3u)


if __name__ == '__main__':
    unittest.main()
