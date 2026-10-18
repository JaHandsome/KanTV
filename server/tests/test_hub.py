# -*- coding: utf-8 -*-
"""枢纽（Hub）单测：HUB CONTRACT v1 的服务端一半（全部离线）。

覆盖：
- `route_of` 每个分支 + fail-closed 分支（含"cenc 不等于 ClearKey"的加固）；
- `parse_caps`：子集 / 未知 caps 忽略 / 缺省 fail-closed；
- `build_subscription`：hub_schema=1、groups、aliases（含 t2s 展开）、
  caps 过滤（子集时不投递未声明 route）、全被过滤 -> unsupported_for_client、
  missing / partial / ok 三态、routes 按 score 降序、dash-clearkey 带
  clearkey + kid + url_key；
- ETag 稳定 + If-None-Match -> 304；
- feedback 落库：正常 / 未知 channel_id / 未知 url_key / 缺字段 / 超长 body；
- feedback 端点鉴权：无 token 401 / 错误 token 401 / 正确 202；
- feedback_summary：冲突两类（疑似假阳性 / 疑似假阴性）+ 只读；
- DDL_V7：playback_reports 建表 + 索引 + 幂等 + cleanup 保留期；
- HTTP 路由端到端：三路由 + 老端点行为不变。

数据库/项目根均为临时副本，绝不触碰真实 data/iptv.db 与 NAS。
"""
import hashlib
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import KID_HEX as TVB_KID, LICENSE as TVB_KEY_JSON  # noqa: E402
from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                                    # noqa: E402
from app.db import (DDL_V1, DDL_V2, DDL_V3, DDL_V4, DDL_V6,      # noqa: E402
                    DDL_V7, DDL_V8, SCHEMA_VERSION, Database, utcnow)
from app.hub import (CAPS_ENUM, HUB_SCHEMA, MAX_FEEDBACK_BYTES,  # noqa: E402
                     MAX_FEEDBACK_NOTICES, MAX_FEEDBACK_RESULTS, NOTICE_CODES,
                     Hub, parse_caps, route_of)
from app.httpserver import HttpServer                            # noqa: E402
from app.parser import ChannelParser                             # noqa: E402

TOKEN = 'unit-test-token-0123456789abcdef'
# 合成 ClearKey 样本；kid 与测试 MPD/许可证向量保持一致
TVB_MPD = 'http://fixture-40.example.invalid/__cl/slocalr43/__c/ott_C_hevc/__op/cenc_m/__f/index.mpd?sig=fixture-auth-99bc00de9d88&ext_start_limit=1789181296&int_start_limit=1789181296&ts=1789181296&c=850464580'
HLS_URL = 'http://198.51.100.41:9901/tsfile/live/1006_1.m3u8?key=fixture-auth-eec577e289ee'
FLV_URL = 'http://198.51.100.42/qctv.fengshows.cn/live/0701pcc72.flv'


def make_root(hub_enabled=True):
    """临时项目根：公开元数据与合成 settings，并把 hub 段写成测试用值。"""
    tmp = tempfile.mkdtemp(prefix='iptv-hub-')
    write_test_config(tmp)
    p = os.path.join(tmp, 'config', 'settings.json')
    with open(p, encoding='utf-8') as fh:
        d = json.load(fh)
    d['hub'] = {'enabled': bool(hub_enabled), 'feedback_token': TOKEN}
    with open(p, 'w', encoding='utf-8') as fh:
        json.dump(d, fh, ensure_ascii=False, indent=2)
    os.makedirs(os.path.join(tmp, 'data', 'output'), exist_ok=True)
    return tmp


def add_source(db, url, channel_key, **kw):
    """插一条源（默认 active + video + 可投递），返回 id。url_key 按权威实现计算。"""
    settings = kw.pop('settings', {'signed_url_params': [], 'signed_url_params_by_host': {}})
    uk = ChannelParser.url_key(url, ChannelParser.resolve_signed_params(url, settings))
    now = utcnow()
    row = {
        'url': url, 'url_hash': ChannelParser.url_hash(url), 'channel_name': channel_key,
        'channel_key': channel_key, 'origin': 'test', 'status': 'active',
        'av': 'video', 'score': 10.0, 'first_seen': now, 'last_seen': now,
        'last_verified': now, 'profile': 'direct', 'url_key': uk,
        'container': 'hls', 'encryption': '', 'drm': '', 'keyformat': '',
        'kid': '', 'drm_key': None,
    }
    row.update(kw)
    cols = ','.join(row)
    marks = ','.join('?' * len(row))
    db.execute('INSERT INTO sources(%s) VALUES(%s)' % (cols, marks), tuple(row.values()))
    return db.query_one('SELECT id FROM sources WHERE url_hash=?', (row['url_hash'],))['id']


# ============================ 纯函数 ============================
class TestRouteOf(unittest.TestCase):
    def test_每个分支(self):
        self.assertEqual(route_of('hls', '', ''), 'hls-ts')
        self.assertEqual(route_of('ts', '', ''), 'hls-ts')
        self.assertEqual(route_of('m3u8', '', ''), 'hls-ts')
        self.assertEqual(route_of(None, '', '', HLS_URL), 'hls-ts')      # 容器缺失按后缀推断
        self.assertEqual(route_of('hls', 'aes128', ''), 'hls-aes128')
        self.assertEqual(route_of('flv', '', ''), 'flv')
        self.assertEqual(route_of('dash', 'cenc', 'clearkey'), 'dash-clearkey')
        self.assertEqual(route_of('dash', 'clearkey', ''), 'dash-clearkey')
        self.assertEqual(route_of('dash', 'CENC', 'CLEARKEY'), 'dash-clearkey')  # 大小写

    def test_fail_closed_分支(self):
        # 契约 §3.1 明确不在 v1 内
        self.assertIsNone(route_of('hls', 'sample-aes', ''))
        self.assertIsNone(route_of('dash', '', ''))
        self.assertIsNone(route_of('dash', 'cenc', 'widevine'))
        self.assertIsNone(route_of('dash', 'cenc', 'playready'))
        self.assertIsNone(route_of('dash', 'cenc', 'cenc-unknown'))
        self.assertIsNone(route_of('dash', 'cbcs', 'clearkey'))
        self.assertIsNone(route_of('hls', 'cenc', 'clearkey'))
        self.assertIsNone(route_of('hls', '', 'clearkey'))
        self.assertIsNone(route_of('flv', 'aes128', ''))
        self.assertIsNone(route_of('flv', '', 'clearkey'))
        self.assertIsNone(route_of('unknown', '', ''))

    def test_route枚举封闭(self):
        self.assertEqual(set(CAPS_ENUM), {'hls-ts', 'hls-aes128', 'flv',
                                          'dash-clearkey', 'web-embed'})


class TestParseCaps(unittest.TestCase):
    def test_子集与空白(self):
        self.assertEqual(parse_caps('hls-ts, flv'), {'hls-ts', 'flv'})
        self.assertEqual(parse_caps('HLS-TS'), {'hls-ts'})
        self.assertEqual(parse_caps('hls-ts,hls-ts'), {'hls-ts'})

    def test_未知caps被忽略(self):
        self.assertEqual(parse_caps('hls-ts,hls-sample-aes,dash-mpegts,foo'),
                         {'hls-ts'})
        self.assertEqual(parse_caps('完全未知'), set())

    def test_缺省为空集合(self):
        for v in (None, '', '   ', []):
            self.assertEqual(parse_caps(v), set())


# ============================ 投递 ============================
class TestSubscription(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)
        self.settings = self.cfg.settings

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _seed(self):
        add_source(self.db, HLS_URL, '无线新闻', container='hls', encryption='',
                   profile='direct', settings=self.settings)
        add_source(self.db, 'http://a/2.m3u8', '无线新闻', container='hls',
                   encryption='aes128', profile='direct', score=5.0,
                   settings=self.settings)
        add_source(self.db, TVB_MPD, '无线新闻', container='dash', encryption='cenc',
                   drm='clearkey', kid=TVB_KID, drm_key=TVB_KEY_JSON,
                   profile='media3-json', score=92.0, settings=self.settings)
        add_source(self.db, FLV_URL, 'Viutv', container='flv', encryption='',
                   profile='direct', settings=self.settings)
        # 不可投递态：不得出现
        add_source(self.db, 'http://a/needkey.m3u8', 'Viutv', container='hls',
                   encryption='sample-aes', profile='need-key', settings=self.settings)

    def _chan(self, payload, cid):
        got = [c for c in payload['channels'] if c['channel_id'] == cid]
        return got[0] if got else None

    def test_hub_schema与骨架(self):
        p = self.hub.build_subscription(caps='hls-ts')
        self.assertEqual(p['hub_schema'], HUB_SCHEMA)
        self.assertEqual(p['hub_schema'], 2)
        self.assertIn('seq', p)
        self.assertIsInstance(p['seq'], int)
        self.assertTrue(p['generated_at'])
        self.assertEqual(p['server']['caps_supported'], list(CAPS_ENUM))
        self.assertTrue(p['groups'])
        gids = {g['id'] for g in p['groups']}
        self.assertIn('港澳台', gids)
        self.assertIn('央视', gids)
        for g in p['groups']:
            self.assertIn('title', g)
            self.assertIn('order', g)

    def test_频道字段完整(self):
        self._seed()
        p = self.hub.build_subscription(caps='dash-clearkey,hls-ts')
        c = self._chan(p, '无线新闻')
        self.assertIsNotNone(c)
        for k in ('channel_id', 'display', 'group_id', 'logo', 'epg_id',
                  'coverage', 'routes'):
            self.assertIn(k, c)
        self.assertEqual(c['group_id'], '港澳台')
        self.assertNotIn('fallback', c)      # 未配置 web_routes -> 省略该键

    def test_missing与unsupported与ok三态(self):
        p = self.hub.build_subscription(caps='hls-ts')
        self.assertEqual(self._chan(p, '第一财经')['coverage'], 'missing')
        self.assertEqual(self._chan(p, '第一财经')['routes'], [])
        self.assertIsNotNone(self._chan(p, '无线新闻'))       # 无源 -> missing 也返回频道

    def test_caps子集过滤(self):
        self._seed()
        only_ts = self.hub.build_subscription(caps='hls-ts')
        routes = {r['route'] for c in only_ts['channels'] for r in c['routes']}
        self.assertEqual(routes, {'hls-ts'})                  # 只出现声明的 route
        c = self._chan(only_ts, '无线新闻')
        self.assertEqual({r['route'] for r in c['routes']}, {'hls-ts'})

    def test_全部被过滤为unsupported(self):
        self._seed()
        p = self.hub.build_subscription(caps='dash-clearkey')
        c = self._chan(p, '无线新闻')
        self.assertNotEqual(c['coverage'], 'missing')          # 库里有线路
        self.assertEqual(c['coverage'], 'partial')             # 只有 1 条 dash 线路
        p2 = self.hub.build_subscription(caps='web-embed')
        c2 = self._chan(p2, '无线新闻')
        self.assertEqual(c2['coverage'], 'unsupported_for_client')
        self.assertEqual(c2['routes'], [])

    def test_缺省caps_fail_closed(self):
        self._seed()
        p = self.hub.build_subscription()                      # 未声明 caps
        c = self._chan(p, '无线新闻')
        self.assertEqual(c['coverage'], 'unsupported_for_client')
        self.assertTrue(all(not x['routes'] for x in p['channels']))

    def test_未知caps被忽略不报错(self):
        self._seed()
        p = self.hub.build_subscription(caps='hls-ts,bogus-cap')
        routes = {r['route'] for c in p['channels'] for r in c['routes']}
        self.assertEqual(routes, {'hls-ts'})

    def test_routes按score降序且含dash字段(self):
        self._seed()
        p = self.hub.build_subscription(caps='dash-clearkey,hls-ts,hls-aes128')
        c = self._chan(p, '无线新闻')
        scores = [r['score'] for r in c['routes']]
        self.assertEqual(scores, sorted(scores, reverse=True))
        dash = [r for r in c['routes'] if r['route'] == 'dash-clearkey']
        self.assertEqual(len(dash), 1)
        d = dash[0]
        self.assertEqual(d['url_key'], ChannelParser.url_key(
            TVB_MPD, ChannelParser.resolve_signed_params(TVB_MPD, self.settings)))
        self.assertEqual(len(d['url_key']), 40)
        self.assertEqual(d['kid'], TVB_KID)
        self.assertEqual(d['clearkey']['keys'][0]['k'], '_-7dzLuqmYh3ZlVEMyIRAA')
        self.assertEqual(d['container'], 'dash')
        self.assertEqual(d['encryption'], 'cenc')

    def test_不可投递态不入投递(self):
        self._seed()
        p = self.hub.build_subscription(caps=','.join(CAPS_ENUM))
        urls = {r['url'] for c in p['channels'] for r in c['routes']}
        self.assertNotIn('http://a/needkey.m3u8', urls)

    def test_coverage_ok需要两条以上(self):
        self._seed()
        p = self.hub.build_subscription(caps='hls-ts,hls-aes128')
        self.assertEqual(self._chan(p, '无线新闻')['coverage'], 'ok')
        p2 = self.hub.build_subscription(caps='hls-aes128')
        self.assertEqual(self._chan(p2, '无线新闻')['coverage'], 'partial')

    def test_aliases含t2s展开(self):
        p = self.hub.build_subscription(caps='hls-ts')
        table = {a['name']: a['channel_id'] for a in p['aliases']}
        self.assertEqual(table['無線新聞台'], '无线新闻')      # 直接别名
        self.assertEqual(table['無綫新聞台'], '无线新闻')      # 直接别名（异体字）
        self.assertEqual(table['无线新闻'], '无线新闻')        # channel_id 自身
        self.assertEqual(table['翡翠台'], 'TVB翡翠台')          # 直接别名
        self.assertEqual(table['三立台湾台'], '三立台湾')      # t2s 展开生成的条目
        self.assertIn('公视', table)                           # 简体目标键
        self.assertIn('公視', table)                           # 繁体别名
        names = [a['name'] for a in p['aliases']]
        self.assertEqual(names, sorted(names))                 # 排序 -> 响应字节稳定

    def test_unknown_channel_id_记为none(self):
        self.assertIsNone(self.hub.resolve_channel_id('不存在的台'))
        self.assertEqual(self.hub.resolve_channel_id('無線新聞台'), '无线新闻')

    def test_seq单调并可用meta置底(self):
        seq1, gen1 = self.hub._seq_and_generated_at()
        self.assertIsInstance(seq1, int)
        self.db.set_meta('hub_seq', seq1 + 1000)
        seq2, _ = self.hub._seq_and_generated_at()
        self.assertEqual(seq2, seq1 + 1000)                    # meta 作为下限
        # 同一进程同一时刻两次调用必须一致（ETag 稳定的前提）
        self.assertEqual(self.hub._seq_and_generated_at()[1],
                         self.hub._seq_and_generated_at()[1])

    def test_etag稳定与304(self):
        self._seed()
        c1, h1, b1 = self.hub.subscription_response(caps='hls-ts')
        c2, h2, b2 = self.hub.subscription_response(caps='hls-ts')
        self.assertEqual((c1, c2), (200, 200))
        self.assertEqual(h1['ETag'], h2['ETag'])
        self.assertEqual(b1, b2)
        self.assertIn('X-Hub-Seq', h1)
        c3, _h3, b3 = self.hub.subscription_response(caps='hls-ts',
                                                    if_none_match=h1['ETag'])
        self.assertEqual(c3, 304)
        self.assertEqual(b3, b'')
        # 弱校验符 / 逗号列表 / 引号容忍
        self.assertTrue(self.hub.etag_matches('W/' + h1['ETag'], h1['ETag']))
        self.assertTrue(self.hub.etag_matches('"x", ' + h1['ETag'], h1['ETag']))
        self.assertTrue(self.hub.etag_matches('*', h1['ETag']))
        self.assertFalse(self.hub.etag_matches('"other"', h1['ETag']))
        # caps 变化 -> ETag 变化（内容确实不同）
        _c, h4, _b = self.hub.subscription_response(caps='flv')
        self.assertNotEqual(h1['ETag'], h4['ETag'])

    def test_响应是合法json且为紧凑编码(self):
        body = self.hub.encode(self.hub.build_subscription(caps='hls-ts'))
        obj = json.loads(body.decode('utf-8'))
        self.assertEqual(obj['hub_schema'], 2)
        self.assertNotIn(b': ', body)                          # 紧凑分隔符

    def test_hub禁用时_enabled为假(self):
        root = make_root(hub_enabled=False)
        try:
            cfg = Config(root)
            db = Database(cfg.db_path)
            db.migrate(cfg.settings)
            self.assertFalse(Hub(cfg, db).enabled())
            db.close()
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_缺hub段默认关闭(self):
        root = make_root()
        try:
            p = os.path.join(root, 'config', 'settings.json')
            with open(p, encoding='utf-8') as fh:
                d = json.load(fh)
            del d['hub']
            with open(p, 'w', encoding='utf-8') as fh:
                json.dump(d, fh, ensure_ascii=False)
            cfg = Config(root)
            db = Database(cfg.db_path)
            db.migrate(cfg.settings)
            h = Hub(cfg, db)
            self.assertFalse(h.enabled())                      # fail-closed
            self.assertEqual(h.token(), '')
            db.close()
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ============================ 投递路由过滤（与 Builder 同源）============================
class TestHubDeliveryRouteFilter(unittest.TestCase):
    """Hub 基础候选必须与 Builder 一样拒掉 FLV / 异族 DRM / HLS DRM / sample-aes。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)
        self.settings = self.cfg.settings

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _row(self, **kw):
        row = {'url': HLS_URL, 'url_key': None, 'container': 'hls', 'encryption': '',
               'drm': '', 'kid': '', 'drm_key': None, 'score': 10.0, 'verified_at': None}
        row.update(kw)
        return row

    def test_route_entry拒绝flv接受hls_ts(self):
        self.assertEqual(self.hub._route_entry(self._row())['route'], 'hls-ts')
        flv = self._row(url=FLV_URL, container='flv')
        self.assertIsNone(self.hub._route_entry(flv))          # 不再保留 FLV 策略分支
        wn = self._row(url=TVB_MPD + '&x=wn', container='dash',
                       encryption='cenc', drm='widevine')
        self.assertIsNone(self.hub._route_entry(wn))

    def test_fallback高分无效route不遮挡低分有效源(self):
        """3 条高分无效 FLV + 1 条低分有效 HLS：过滤必须先于 max_lines 截断。"""
        for i in range(3):
            add_source(self.db, 'http://a/viutv-bad%d.flv' % i, '无线新闻',
                       container='flv', encryption='', profile='direct',
                       score=99.0 - i, settings=self.settings)
        add_source(self.db, HLS_URL, '无线新闻', container='hls', encryption='',
                   profile='direct', score=5.0, settings=self.settings)
        rows = self.hub._line_rows().get('无线新闻') or []
        self.assertEqual([r['url'] for r in rows], [HLS_URL])
        p = self.hub.build_subscription(caps='hls-ts')
        urls = {r['url'] for c in p['channels'] for r in c['routes']}
        self.assertIn(HLS_URL, urls)
        self.assertFalse(any(u.endswith('.flv') for u in urls))


# ============================ 回流 ============================
class TestFeedback(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)
        self.sid = add_source(self.db, HLS_URL, 'TVB翡翠台', settings=self.cfg.settings)
        self.url_key = self.db.query_one('SELECT url_key FROM sources WHERE id=?',
                                         (self.sid,))['url_key']

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _payload(self, **over):
        r = {'channel_id': 'TVB翡翠台', 'url_key': self.url_key, 'route': 'hls-ts',
             'outcome': 'ok', 'engine': 'media3', 'first_frame_ms': 1820,
             'stalls': 0, 'play_seconds': 300, 'fail_reason': None,
             'observed_at': '2026-09-11T19:00:00+08:00'}
        r.update(over)
        p = {'hub_schema': 1, 'client': 'kantv', 'app': '0.1.8',
             'device': {'model': 'TCL Q10M Pro', 'android_api': 30, 'abi': 'arm64-v8a'},
             'session': 'sess-1', 'reported_at': '2026-09-11T19:05:00+08:00',
             'results': [r]}
        return p

    def test_正常落库(self):
        out = self.hub.record_feedback(self._payload())
        self.assertEqual(out['accepted'], 1)
        self.assertEqual(out['unknown_channel'], 0)
        self.assertEqual(out['unknown_url_key'], 0)
        row = self.db.query_one('SELECT * FROM playback_reports')
        self.assertEqual(row['channel_id'], 'TVB翡翠台')
        self.assertEqual(row['url_key'], self.url_key)
        self.assertEqual(row['outcome'], 'ok')
        self.assertEqual(row['engine'], 'media3')
        self.assertEqual(row['first_frame_ms'], 1820)
        self.assertEqual(row['device_model'], 'TCL Q10M Pro')
        self.assertEqual(row['android_api'], 30)
        self.assertTrue(row['reported_at'])
        self.assertIn('media3', row['raw_json'])

    def test_未知channel_id仍落库不报错(self):
        out = self.hub.record_feedback(self._payload(channel_id='不存在的台'))
        self.assertEqual(out['accepted'], 1)
        self.assertEqual(out['unknown_channel'], 1)
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM playback_reports')['c'], 1)

    def test_未知url_key仍落库不报错(self):
        out = self.hub.record_feedback(self._payload(url_key='0' * 40))
        self.assertEqual(out['accepted'], 1)
        self.assertEqual(out['unknown_url_key'], 1)
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM playback_reports')['c'], 1)

    def test_显示名与繁体名可归因(self):
        out = self.hub.record_feedback(self._payload(channel_id='無線新聞台'))
        # 無線新聞台 -> 无线新闻（合法 channel_id，只是本例库里没源）
        self.assertEqual(out['accepted'], 1)
        self.assertEqual(out['unknown_channel'], 0)

    def test_缺字段被容忍(self):
        p = self._payload()
        p['results'] = [{'url_key': self.url_key}, {'outcome': 'fail'}, 'not-a-dict']
        out = self.hub.record_feedback(p)
        self.assertEqual(out['accepted'], 2)       # 两条 dict 条目落库
        self.assertEqual(out['rejected'], 1)       # 非 dict 条目只计数
        self.assertEqual(out['unknown_channel'], 2)
        rows = self.db.query('SELECT channel_id, outcome FROM playback_reports')
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[1]['outcome'], 'fail')
        self.assertEqual(rows[0]['outcome'], 'unknown')   # outcome 缺省

    def test_缺results键视为空数组(self):
        out = self.hub.record_feedback({'client': 'kantv'})
        self.assertEqual(out['accepted'], 0)

    def test_结构非法抛valueerror(self):
        for bad in ({'results': 'x'}, {'results': {'a': 1}}):
            with self.assertRaises(ValueError):
                self.hub.record_feedback(bad)

    def test_results超限被截断(self):
        p = self._payload()
        p['results'] = [{'outcome': 'ok'}] * 6000
        out = self.hub.record_feedback(p)
        self.assertEqual(out['accepted'], 5000)
        self.assertEqual(out['truncated'], 1000)
        self.assertEqual(out['results_truncated'], 1000)   # QA P2-2 拆分计数
        self.assertEqual(out['notices_truncated'], 0)

    def test_超长body_413(self):
        big = b'x' * (MAX_FEEDBACK_BYTES + 1)
        code, _h, body = self.hub.feedback_response('Bearer ' + TOKEN, big)
        self.assertEqual(code, 413)
        self.assertIn(b'payload too large', body)

    def test_坏json_400(self):
        code, _h, _b = self.hub.feedback_response('Bearer ' + TOKEN, b'{not json')
        self.assertEqual(code, 400)

    def test_鉴权(self):
        good = json.dumps(self._payload()).encode('utf-8')
        for hdr in (None, '', 'Bearer', 'Bearer ', 'Bearer wrong-token',
                    'Basic ' + TOKEN, 'Bearer ' + TOKEN + 'x'):
            code, _h, _b = self.hub.feedback_response(hdr, good)
            self.assertEqual(code, 401, hdr)
        code, _h, body = self.hub.feedback_response('Bearer ' + TOKEN, good)
        self.assertEqual(code, 202)
        self.assertEqual(json.loads(body.decode('utf-8'))['accepted'], 1)
        # 大小写不敏感的 scheme
        code, _h, _b = self.hub.feedback_response('bearer ' + TOKEN, good)
        self.assertEqual(code, 202)

    def test_未配置token时一律401(self):
        self.cfg.settings['hub']['feedback_token'] = ''
        code, _h, _b = self.hub.feedback_response('Bearer ' + TOKEN,
                                                 b'{"results":[]}')
        self.assertEqual(code, 401)


# ============================ 聚合 ============================
class TestFeedbackSummary(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)
        self.ok_id = add_source(self.db, HLS_URL, 'TVB翡翠台',
                                settings=self.cfg.settings)
        self.ok_key = self.db.query_one('SELECT url_key FROM sources WHERE id=?',
                                        (self.ok_id,))['url_key']
        # 探测 fail 的源（dead）
        self.fail_id = add_source(self.db, 'http://a/dead.m3u8', 'TVB翡翠台',
                                  status='dead', av=None, settings=self.cfg.settings)
        self.fail_key = self.db.query_one('SELECT url_key FROM sources WHERE id=?',
                                          (self.fail_id,))['url_key']

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _report(self, url_key, outcome, channel='TVB翡翠台'):
        self.hub.record_feedback({'client': 'kantv', 'results': [
            {'channel_id': channel, 'url_key': url_key, 'outcome': outcome,
             'route': 'hls-ts'}]})

    def test_假阳性冲突(self):
        self._report(self.ok_key, 'fail')          # 探测 ok 但真机 fail
        s = self.hub.feedback_summary()
        reasons = [c['reason'] for c in s['conflicts']]
        self.assertIn('probe_ok_device_fail', reasons)
        self.assertEqual(s['totals']['conflicts'], 1)
        self.assertEqual(s['hub_schema'], 2)
        self.assertIn('不作为删除', s['note'])

    def test_假阴性冲突(self):
        self._report(self.fail_key, 'ok')          # 探测 fail 但真机 ok
        s = self.hub.feedback_summary()
        self.assertIn('probe_fail_device_ok',
                      [c['reason'] for c in s['conflicts']])

    def test_无冲突时清单为空(self):
        self._report(self.ok_key, 'ok')
        s = self.hub.feedback_summary()
        self.assertEqual(s['conflicts'], [])
        ch = s['channels'][0]
        self.assertEqual(ch['channel_id'], 'TVB翡翠台')
        self.assertEqual(ch['routes'][0]['device']['ok_rate'], 1.0)
        self.assertIsNone(ch['routes'][0]['conflict'])

    def test_未知url_key计入unknown而非conflict(self):
        self._report('f' * 40, 'fail')
        s = self.hub.feedback_summary()
        self.assertEqual(s['conflicts'], [])
        self.assertEqual(s['totals']['url_keys'], 1)

    def test_未知channel_id单独列出(self):
        self._report(self.ok_key, 'ok', channel='野台')
        s = self.hub.feedback_summary()
        self.assertIn('野台', s['unknown_channel_ids'])
        self.assertEqual(s['channels'], [])

    def test_只读_不产生任何写入(self):
        self._report(self.ok_key, 'ok')
        before = self.db.query_one('SELECT COUNT(*) AS c FROM playback_reports')['c']
        self.hub.feedback_summary()
        self.hub.build_subscription(caps='hls-ts')
        after = self.db.query_one('SELECT COUNT(*) AS c FROM playback_reports')['c']
        self.assertEqual(before, after)


# ============================ DDL_V7 ============================
class TestMigrationV7(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-v7-')
        self.path = os.path.join(self.tmp, 't.db')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_建表与索引与版本(self):
        db = Database(self.path)
        db.migrate({})
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertGreaterEqual(SCHEMA_VERSION, 8)      # v7 建表这一事实继续成立
        cols = [r['name'] for r in db.query('PRAGMA table_info(playback_reports)')]
        # v7 的 19 列 + v9 追加的 7 列（ADD COLUMN 一律追加在末尾，故顺序固定）
        self.assertEqual(cols, [
            'id', 'reported_at', 'client', 'app', 'device_model', 'android_api',
            'session', 'channel_id', 'url_key', 'route', 'outcome', 'engine',
            'first_frame_ms', 'stalls', 'stall_seconds', 'play_seconds',
            'fail_reason', 'observed_at', 'raw_json',
            'active_seconds', 'freeze_seconds', 'freeze_segments',
            'freeze_longest_seconds', 'picture_progress_events',
            'freeze_long_segments', 'freeze_contract_ok'])
        idx = {r['name'] for r in db.query(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        self.assertIn('idx_pr_channel_url_key', idx)
        self.assertIn('idx_pr_reported_at', idx)
        # sources 结构不受 v7 影响；v10 追加 base_score（吞吐评分基线），现为 28 列
        self.assertEqual(len(db.query('PRAGMA table_info(sources)')), 28)
        idx = {r['name'] for r in db.query(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        self.assertIn('idx_sources_url_key', idx)
        db.close()

    def test_幂等可重入(self):
        db = Database(self.path)
        db.migrate({})
        db.set_meta('hub_seq', 1)          # 触发一次写入
        db.migrate({})
        db.migrate({})
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM playback_reports')['c'], 0)
        db.close()

    def test_v6到v7升级只加表不动数据(self):
        db = Database(self.path)
        # 手工造一个 v6 库（V1..V4 + V5 列 + V6）
        for stmt in DDL_V1 + DDL_V2 + DDL_V3 + DDL_V4 + DDL_V6:
            db.conn.execute(stmt)
        from app.db import _apply_ddl_v5
        _apply_ddl_v5(db.conn)
        db.conn.execute("INSERT OR REPLACE INTO meta(key,value) "
                        "VALUES ('schema_version','6')")
        db.conn.commit()
        db.execute("INSERT INTO sources(url, url_hash, channel_name, channel_key,"
                   " origin, status, score, first_seen, last_seen)"
                   " VALUES ('http://a/1','h1','n','CCTV1','','active',10,?,?)",
                   (utcnow(), utcnow()))
        before = db.query_one('SELECT COUNT(*) AS c FROM sources')['c']
        db.migrate({})
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))   # 7 -> 8
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM sources')['c'], before)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM playback_reports')['c'], 0)
        db.close()

    def test_cleanup按保留期裁剪回流表(self):
        import datetime
        db = Database(self.path)
        db.migrate({})
        old = (datetime.datetime.now(datetime.timezone.utc)
               - datetime.timedelta(days=200)).strftime('%Y-%m-%dT%H:%M:%SZ')
        db.execute('INSERT INTO playback_reports(reported_at, outcome) VALUES (?,?)',
                   (old, 'ok'))
        db.execute('INSERT INTO playback_reports(reported_at, outcome) VALUES (?,?)',
                   (utcnow(), 'ok'))
        db.cleanup(retention_days=90)
        rows = db.query('SELECT reported_at FROM playback_reports')
        self.assertEqual(len(rows), 1)     # 200 天前被裁掉，当天的保留
        db.close()

    def test_cleanup在无回流表时也不报错(self):
        db = Database(self.path)
        # 只建 meta + sources，不迁移到 v7
        for stmt in DDL_V1:
            db.conn.execute(stmt)
        db.conn.commit()
        db.cleanup(retention_days=90)      # 不得抛异常
        db.close()


# ============================ HTTP 端到端 ============================
class TestHttpEndToEnd(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.cfg.settings['http']['host'] = '127.0.0.1'
        self.cfg.settings['http']['port'] = 0            # 由内核分配空闲端口
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)
        self.sid = add_source(self.db, HLS_URL, 'TVB翡翠台', settings=self.cfg.settings)
        self.server = HttpServer(self.cfg, None, self.hub)
        self.port = self.server.httpd.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        # 老产物：live.txt 必须仍可 200
        with open(os.path.join(self.cfg.output_dir, 'live.txt'), 'w',
                  encoding='utf-8') as f:
            f.write('🌊港澳台,#genre#\nTVB翡翠台,' + HLS_URL + '\n')

    def tearDown(self):
        self.server.shutdown()
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _url(self, path):
        return 'http://127.0.0.1:%d%s' % (self.port, path)

    def _get(self, path, headers=None):
        req = urllib.request.Request(self._url(path), headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def _post(self, path, body, headers=None):
        req = urllib.request.Request(self._url(path), data=body, method='POST',
                                     headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_订阅端点(self):
        code, hdr, body = self._get(
            '/api/hub/v1/subscription?client=kantv&schema=1&caps=hls-ts')
        self.assertEqual(code, 200)
        self.assertIn('ETag', hdr)
        self.assertIn('X-Hub-Seq', hdr)
        obj = json.loads(body.decode('utf-8'))
        self.assertEqual(obj['hub_schema'], 2)
        c = [x for x in obj['channels'] if x['channel_id'] == 'TVB翡翠台'][0]
        self.assertEqual(c['routes'][0]['route'], 'hls-ts')

    def test_不声明caps也返回合法json(self):
        code, _hdr, body = self._get('/api/hub/v1/subscription?client=kantv&schema=1')
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(body.decode('utf-8'))['hub_schema'], 2)

    def test_304分支(self):
        _c, hdr, _b = self._get('/api/hub/v1/subscription?client=kantv&schema=1&caps=hls-ts')
        code, hdr2, body = self._get('/api/hub/v1/subscription?client=kantv&schema=1&caps=hls-ts',
                                     {'If-None-Match': hdr['ETag']})
        self.assertEqual(code, 304)
        self.assertEqual(body, b'')
        self.assertEqual(hdr2['ETag'], hdr['ETag'])

    def test_回流鉴权与落库(self):
        url_key = self.db.query_one('SELECT url_key FROM sources WHERE id=?',
                                    (self.sid,))['url_key']
        payload = json.dumps({'hub_schema': 1, 'client': 'kantv', 'results': [
            {'channel_id': 'TVB翡翠台', 'url_key': url_key, 'route': 'hls-ts',
             'outcome': 'ok', 'engine': 'media3'}]}).encode('utf-8')
        code, _body = self._post('/api/hub/v1/feedback', payload,
                                 {'Content-Type': 'application/json'})
        self.assertEqual(code, 401)
        code, body = self._post('/api/hub/v1/feedback', payload,
                                {'Content-Type': 'application/json',
                                 'Authorization': 'Bearer ' + TOKEN})
        self.assertEqual(code, 202)
        self.assertEqual(json.loads(body.decode('utf-8'))['accepted'], 1)
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM playback_reports')['c'], 1)

    def test_聚合端点(self):
        code, _hdr, body = self._get('/api/hub/v1/feedback/summary')
        self.assertEqual(code, 200)
        obj = json.loads(body.decode('utf-8'))
        self.assertIn('conflicts', obj)
        self.assertIn('totals', obj)

    def test_HEAD可取到ETag且无body(self):
        """验收 #5 用 `curl -I` 读 ETag：HEAD 必须返回响应头且不带 body。"""
        req = urllib.request.Request(
            self._url('/api/hub/v1/subscription?client=kantv&schema=1&caps=hls-ts'),
            method='HEAD')
        with urllib.request.urlopen(req, timeout=10) as r:
            self.assertEqual(r.status, 200)
            etag = r.headers.get('ETag')
            self.assertEqual(r.read(), b'')
        self.assertTrue(etag and etag.startswith('"'))
        # HEAD + If-None-Match -> 304
        req2 = urllib.request.Request(
            self._url('/api/hub/v1/subscription?client=kantv&schema=1&caps=hls-ts'),
            headers={'If-None-Match': etag})
        try:
            with urllib.request.urlopen(req2, timeout=10) as r:
                self.assertEqual(r.status, 304)
        except urllib.error.HTTPError as e:      # urllib 会把 304 当错误抛出
            self.assertEqual(e.code, 304)
        # 老产物 HEAD 也可用（只补能力，不改 GET 响应体）
        req3 = urllib.request.Request(self._url('/live.txt'), method='HEAD')
        with urllib.request.urlopen(req3, timeout=10) as r:
            self.assertEqual(r.status, 200)
            self.assertTrue(int(r.headers.get('Content-Length')) > 0)
            self.assertEqual(r.read(), b'')

    def test_老端点行为不变(self):
        code, hdr, body = self._get('/live.txt')
        self.assertEqual(code, 200)
        self.assertIn('#genre#', body.decode('utf-8'))
        code, _h, _b = self._get('/api/status')
        self.assertEqual(code, 404)          # status_provider=None 时的既有行为
        code, _h, _b = self._get('/nope')
        self.assertEqual(code, 404)
        code, _b = self._post('/nope', b'{}')
        self.assertEqual(code, 404)          # 非回流的 POST 不引入新写面

    def test_hub禁用时三端点404(self):
        self.cfg.settings['hub']['enabled'] = False
        self.assertEqual(self._get('/api/hub/v1/subscription')[0], 404)
        self.assertEqual(self._get('/api/hub/v1/feedback/summary')[0], 404)
        self.assertEqual(self._post('/api/hub/v1/feedback', b'{}')[0], 404)

    def test_超长body被拒(self):
        self.cfg.settings['hub']['enabled'] = True
        code, _body = self._post(
            '/api/hub/v1/feedback', b'x' * (MAX_FEEDBACK_BYTES + 10),
            {'Authorization': 'Bearer ' + TOKEN})
        self.assertEqual(code, 413)


# ============================ v1.1 修复轮：F2 / F3 / F4 / F6 ============================
class TestF2UrlKeyNonEmpty(unittest.TestCase):
    """F2：`routes[].url_key` 必填非空（v1.1 §3 / §2.5）。

    安全网：只要本项成立，客户端就**永远不进入本地兜底计算**分支 ——
    那条分支正是 v1 两端 url_key 分歧的来源。
    """

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _row(self, **kw):
        row = {'url': HLS_URL, 'url_key': None, 'container': 'hls', 'encryption': '',
               'drm': '', 'kid': '', 'drm_key': None, 'score': 10.0, 'verified_at': None}
        row.update(kw)
        return row

    def test_缺url_key现场补算(self):
        row = self._row()
        entry = self.hub._route_entry(row)
        expect = ChannelParser.url_key(
            HLS_URL, ChannelParser.resolve_signed_params(HLS_URL, self.cfg.settings))
        self.assertEqual(entry['url_key'], expect)
        self.assertEqual(len(entry['url_key']), 40)

    def test_长度非法的url_key也补算(self):
        for bad in ('', 'abc', 'x' * 39, 'y' * 41):
            entry = self.hub._route_entry(self._row(url_key=bad))
            self.assertEqual(entry['url_key'], ChannelParser.url_key(
                HLS_URL, ChannelParser.resolve_signed_params(HLS_URL, self.cfg.settings)),
                bad)

    def test_已有合法url_key不改写(self):
        uk = 'a' * 40
        self.assertEqual(self.hub._route_entry(self._row(url_key=uk))['url_key'], uk)

    def test_大写url_key被归一为小写(self):
        self.assertEqual(self.hub._route_entry(self._row(url_key='A' * 40))['url_key'],
                         'a' * 40)

    def test_url为空则不投递(self):
        for bad in ('', '   ', None):
            self.assertIsNone(self.hub._route_entry(self._row(url=bad)))

    def test_投递体里url_key恒非空(self):
        """端到端：把 url_key 列置 NULL 的行落到库里，投递体必须仍给非空 url_key。"""
        sid = add_source(self.db, HLS_URL, 'TVB翡翠台', settings=self.cfg.settings)
        add_source(self.db, 'http://a/2.m3u8', 'TVB翡翠台', settings=self.cfg.settings)
        self.db.execute('UPDATE sources SET url_key=NULL')      # 模拟碰撞落败行的 NULL
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM sources WHERE url_key IS NULL')['c'], 2)
        p = self.hub.build_subscription(caps='hls-ts'),
        p = p[0]
        ch = [c for c in p['channels'] if c['channel_id'] == 'TVB翡翠台'][0]
        self.assertEqual(ch['coverage'], 'ok')
        for r in ch['routes']:
            self.assertTrue(r['url_key'], r)
            self.assertEqual(len(r['url_key']), 40)
        # 且补算值 = 权威实现直算（与 sources.id 无关）
        keys = {r['url_key'] for r in ch['routes']}
        expect = {ChannelParser.url_key(u, ChannelParser.resolve_signed_params(
            u, self.cfg.settings)) for u in (HLS_URL, 'http://a/2.m3u8')}
        self.assertEqual(keys, expect)
        self.assertIsNotNone(sid)

    def test_url为空的行不出现在投递体(self):
        add_source(self.db, HLS_URL, 'Viutv', settings=self.cfg.settings)
        self.db.execute("UPDATE sources SET url='' WHERE channel_key='Viutv'")
        p = self.hub.build_subscription(caps='hls-ts')
        ch = [c for c in p['channels'] if c['channel_id'] == 'Viutv'][0]
        urls = {r['url'] for c in p['channels'] for r in c['routes']}
        self.assertNotIn('', urls)
        self.assertEqual(ch['coverage'], 'unsupported_for_client')


class TestF3SignedParams(unittest.TestCase):
    """F3：投递 `signed_params`（v1.1 §3 加法字段，供客户端算非枢纽来源的 url_key）。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def test_与settings同源(self):
        sp = self.hub.build_subscription(caps='hls-ts')['signed_params']
        self.assertEqual(set(sp), {'global', 'by_host', 'fingerprint'})
        self.assertEqual(sp['global'],
                         self.cfg.settings['signed_url_params'])
        self.assertEqual(sp['by_host'],
                         {'edgeware.tvb.com': ['sig', 'ts', 'c', 'ext_start_limit',
                                               'int_start_limit']})
        self.assertEqual(len(sp['global']), 8)

    def test_fingerprint与守卫锚点同算法同源(self):
        """P0-2 方案 C：投递指纹必须与 schema 指纹守卫的 meta 锚点同值（同源可执行证据）。"""
        from app import urlkey
        fp = urlkey.signed_params_fingerprint(self.cfg.settings)
        self.assertEqual(self.hub.signed_params()['fingerprint'], fp)
        self.assertEqual(self.hub.build_subscription(caps='hls-ts')['signed_params']['fingerprint'], fp)
        # 12 位小写 hex
        self.assertTrue(all(c in '0123456789abcdef' for c in fp))
        self.assertEqual(len(fp), 12)
        # 名单变更即指纹变更
        changed = dict(self.cfg.settings)
        changed['signed_url_params'] = list(self.cfg.settings['signed_url_params']) + ['newparam']
        self.assertNotEqual(urlkey.signed_params_fingerprint(changed), fp)

    def test_全部小写且去重(self):
        self.cfg.settings['signed_url_params'] = ['Token', 'token', ' SIG ', '']
        self.cfg.settings['signed_url_params_by_host'] = {'EDGEWARE.TVB.COM': ['Sig', 'sig'],
                                                          '': ['x'], 'k2': []}
        sp = self.hub.signed_params()
        self.assertEqual(sp['global'], ['token', 'sig'])
        self.assertEqual(sp['by_host'], {'edgeware.tvb.com': ['sig']})

    def test_缺配置时为空骨架(self):
        self.cfg.settings['signed_url_params'] = None
        self.cfg.settings['signed_url_params_by_host'] = 'not-a-dict'
        sp = self.hub.signed_params()
        self.assertEqual(sp['global'], [])
        self.assertEqual(sp['by_host'], {})
        # 关键：不得因缺配置而省略这些键（形状固定；fingerprint 对空名单仍有稳定值）
        bsp = self.hub.build_subscription(caps='hls-ts')['signed_params']
        self.assertEqual(set(bsp), {'global', 'by_host', 'fingerprint'})
        self.assertEqual(len(sp['fingerprint']), 12)

    def test_与resolve_signed_params一致(self):
        """同一 URL：投递名单算出的 url_key 必须等于权威实现（同源的可执行证据）。"""
        add_source(self.db, TVB_MPD, '无线新闻', container='dash', encryption='cenc',
                   drm='clearkey', kid=TVB_KID, drm_key=TVB_KEY_JSON,
                   profile='media3-json', settings=self.cfg.settings)
        p = self.hub.build_subscription(caps='dash-clearkey')
        route = [c for c in p['channels'] if c['channel_id'] == '无线新闻'][0]['routes'][0]
        self.assertEqual(route['url_key'], ChannelParser.url_key(
            TVB_MPD, ChannelParser.resolve_signed_params(TVB_MPD, self.cfg.settings)))


class TestF4VectorsEndpoint(unittest.TestCase):
    """F4：`GET /api/hub/v1/urlkey-vectors`（v1.1 §2.5）。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)
        os.makedirs(os.path.join(self.root, 'docs'), exist_ok=True)
        self.path = os.path.join(self.root, 'docs', 'urlkey_vectors.json')
        # 端点只负责交付向量；契约算法另由 test_url_key 覆盖。绝不读取现场向量。
        urls = ['https://vectors.example.invalid/%d.m3u8' % i for i in range(40)]
        vectors = [{'url': url, 'url_key': hashlib.sha1(url.encode()).hexdigest()}
                   for url in urls]
        with open(self.path, 'w', encoding='utf-8') as handle:
            json.dump(vectors, handle)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def test_返回向量数组(self):
        code, headers, body = self.hub.vectors_response()
        self.assertEqual(code, 200)
        self.assertIn('ETag', headers)
        self.assertEqual(headers['X-Hub-Vectors'], str(len(json.loads(body.decode('utf-8')))))
        data = json.loads(body.decode('utf-8'))
        self.assertIsInstance(data, list)
        self.assertGreaterEqual(len(data), 40)                 # v1.1 §2.5 要求 >=40
        for item in data:
            self.assertEqual(set(item), {'url', 'url_key'})
            self.assertRegex(item['url_key'], r'^[0-9a-f]{40}$')
            self.assertIsInstance(item['url'], str)

    def test_etag与304(self):
        _c, h1, b1 = self.hub.vectors_response()
        code, h2, b2 = self.hub.vectors_response(if_none_match=h1['ETag'])
        self.assertEqual(code, 304)
        self.assertEqual(b2, b'')
        self.assertEqual(h1['ETag'], h2['ETag'])
        _c, h3, _b = self.hub.vectors_response()
        self.assertEqual(h3['ETag'], h1['ETag'])

    def test_文件缺失404(self):
        os.remove(self.path)
        code, _h, _b = self.hub.vectors_response()
        self.assertEqual(code, 404)

    def test_文件损坏500(self):
        for bad in (b'not-json', b'{}', b'[]', b'"x"'):
            with open(self.path, 'wb') as f:
                f.write(bad)
            code, _h, _b = self.hub.vectors_response()
            self.assertEqual(code, 500, bad)

    def test_元素非对象500(self):
        """QA P2-1：合法 JSON 数组但元素非 dict（如 [1,2]）不得 200 原样发出。"""
        for bad in (b'[1,2]', b'["x"]', b'[{"url":"u","url_key":"k"}, 1]'):
            with open(self.path, 'wb') as f:
                f.write(bad)
            code, _h, body = self.hub.vectors_response()
            self.assertEqual(code, 500, bad)
            self.assertEqual(body, b'{"error":"vectors file malformed"}')

    def test_不参与seq(self):
        """向量端点不参与 seq/订阅载荷（v1.1 §2.5：只读、独立）。"""
        seq_before, _ = self.hub._seq_and_generated_at()
        self.hub.vectors_response()
        self.assertEqual(self.hub._seq_and_generated_at()[0], seq_before)
        self.assertNotIn('url_key_vectors', self.hub.build_subscription(caps='hls-ts'))


class TestF6Notices(unittest.TestCase):
    """F6：回流 `notices[]`（v1.1 §4/§5.2）。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _payload(self, notices):
        p = {'hub_schema': 1, 'client': 'kantv', 'app': '0.1.8',
             'session': 'sess-n', 'results': []}
        if notices is not None:
            p['notices'] = notices
        return p

    def test_已知code落库(self):
        out = self.hub.record_feedback(self._payload([
            {'code': 'schema_unsupported', 'detail': 'client supports 1, server sent 2'}]))
        self.assertEqual(out['notices'], 1)
        self.assertEqual(out['unknown_notice_code'], 0)
        row = self.db.query_one('SELECT * FROM hub_notices')
        self.assertEqual(row['code'], 'schema_unsupported')
        self.assertEqual(row['client'], 'kantv')
        self.assertEqual(row['session'], 'sess-n')
        self.assertIn('server sent 2', row['detail'])
        self.assertIn('schema_unsupported', row['raw_json'])

    def test_未知code接受并计数不报错(self):
        out = self.hub.record_feedback(self._payload([
            {'code': 'brand_new_code', 'detail': 'x'},
            {'code': 'caps_unknown'},
            {'code': ''}]))
        self.assertEqual(out['notices'], 3)
        self.assertEqual(out['unknown_notice_code'], 1)      # brand_new_code
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM hub_notices')['c'], 3)
        # 空 code 归 'other'（_s('') -> None -> 'other'）
        self.assertEqual(self.db.query_one(
            "SELECT COUNT(*) AS c FROM hub_notices WHERE code='other'")['c'], 1)

    def test_非dict条目只计数(self):
        out = self.hub.record_feedback(self._payload(['x', {'code': 'other'}]))
        self.assertEqual(out['notices'], 1)
        self.assertEqual(out['notice_rejected'], 1)

    def test_notices非数组抛valueerror(self):
        with self.assertRaises(ValueError):
            self.hub.record_feedback(self._payload('not-a-list'))

    def test_缺notices视为空(self):
        out = self.hub.record_feedback(self._payload(None))
        self.assertEqual(out['notices'], 0)
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM hub_notices')['c'], 0)

    def test_超限截断(self):
        out = self.hub.record_feedback(self._payload(
            [{'code': 'other'}] * (MAX_FEEDBACK_NOTICES + 5)))
        self.assertEqual(out['notices'], MAX_FEEDBACK_NOTICES)
        self.assertEqual(out['truncated'], 5)
        self.assertEqual(out['results_truncated'], 0)      # QA P2-2 拆分计数
        self.assertEqual(out['notices_truncated'], 5)

    def test_截断计数拆分(self):
        """QA P2-2：results 与 notices 同时超限 -> truncated 为和，两个拆分字段各自正确。"""
        p = self._payload([{'code': 'other'}] * (MAX_FEEDBACK_NOTICES + 50))
        p['results'] = [{'outcome': 'ok'}] * (MAX_FEEDBACK_RESULTS + 100)
        out = self.hub.record_feedback(p)
        self.assertEqual(out['truncated'], 150)            # 旧字段语义不变（之和）
        self.assertEqual(out['results_truncated'], 100)
        self.assertEqual(out['notices_truncated'], 50)

    def test_状态码202(self):
        body = json.dumps(self._payload([{'code': 'schema_unsupported', 'detail': 't'}])
                          ).encode('utf-8')
        code, _h, out = self.hub.feedback_response('Bearer ' + TOKEN, body)
        self.assertEqual(code, 202)
        obj = json.loads(out.decode('utf-8'))
        self.assertEqual(obj['notices'], 1)
        self.assertIn('accepted', obj)                        # 既有字段照常

    def test_hub_schema只计数不校验(self):
        """ARCH P2-7：hub_schema 只累加 meta 计数；不校验、不拒绝（§4 红线）。"""
        from app.hub import HUB_SCHEMA_COUNTS_META
        self.assertEqual(self.hub.record_feedback(self._payload(None))['accepted'], 0)
        p = self._payload(None)
        p['hub_schema'] = 1
        self.hub.record_feedback(p)
        p2 = self._payload(None)
        p2['hub_schema'] = 1
        p2['results'] = [{'outcome': 'ok'}]
        self.hub.record_feedback(p2)
        p3 = self._payload(None)
        p3['hub_schema'] = 999          # 未知/更高 schema 也照收，只计数
        self.hub.record_feedback(p3)
        p4 = self._payload(None)
        p4['hub_schema'] = {'x': 1}     # 非标量 -> 'unset'
        self.hub.record_feedback(p4)
        counts = json.loads(self.db.get_meta(HUB_SCHEMA_COUNTS_META))
        # _payload 默认自带 hub_schema=1（3 次），999 一次，dict 非标量 -> unset 一次
        self.assertEqual(counts, {'1': 3, '999': 1, 'unset': 1})

    def test_结果与notices可同行(self):
        sid = add_source(self.db, HLS_URL, 'TVB翡翠台', settings=self.cfg.settings)
        uk = self.db.query_one('SELECT url_key FROM sources WHERE id=?', (sid,))['url_key']
        p = self._payload([{'code': 'caps_unknown'}])
        p['results'] = [{'channel_id': 'TVB翡翠台', 'url_key': uk, 'outcome': 'ok'}]
        out = self.hub.record_feedback(p)
        self.assertEqual((out['accepted'], out['notices']), (1, 1))
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM playback_reports')['c'], 1)
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM hub_notices')['c'], 1)

    def test_汇总里可见notices(self):
        self.hub.record_feedback(self._payload(
            [{'code': 'schema_unsupported', 'detail': 'd1'}, {'code': 'weird'}]))
        s = self.hub.feedback_summary()
        self.assertIn('notices', s)
        codes = {x['code']: x['count'] for x in s['notices']['by_code']}
        self.assertEqual(codes, {'schema_unsupported': 1})
        self.assertEqual([x['code'] for x in s['notices']['unknown_code']], ['weird'])
        self.assertEqual(s['notices']['total'], 2)
        self.assertEqual(NOTICE_CODES, ('schema_unsupported', 'caps_unknown', 'other'))


class TestMigrationV8(unittest.TestCase):
    """DDL_V8：`hub_notices` 表 + 索引 + 幂等 + 保留期。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-v8-')
        self.path = os.path.join(self.tmp, 't.db')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_建表与索引(self):
        db = Database(self.path)
        db.migrate({})
        self.assertGreaterEqual(SCHEMA_VERSION, 8)      # v8 建表这一事实继续成立
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        cols = [r['name'] for r in db.query('PRAGMA table_info(hub_notices)')]
        self.assertEqual(cols, ['id', 'reported_at', 'client', 'app', 'session',
                                'code', 'detail', 'raw_json'])
        idx = {r['name'] for r in db.query(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        self.assertIn('idx_notices_reported_at', idx)
        self.assertIn('idx_notices_code', idx)
        # sources 结构不受 v8/v9 影响；v10 追加 base_score，现为 28 列
        self.assertEqual(len(db.query('PRAGMA table_info(sources)')), 28)
        # v7 的表仍在（19 列 + v9 的 7 列 = 26）
        self.assertEqual(len(db.query('PRAGMA table_info(playback_reports)')), 26)
        db.close()

    def test_v7到v8只加表不动数据(self):
        db = Database(self.path)
        for stmt in DDL_V1 + DDL_V2 + DDL_V3 + DDL_V4 + DDL_V6:
            db.conn.execute(stmt)
        from app.db import _apply_ddl_v5
        _apply_ddl_v5(db.conn)
        for stmt in DDL_V7:
            db.conn.execute(stmt)
        db.conn.execute("INSERT OR REPLACE INTO meta(key,value) "
                        "VALUES ('schema_version','7')")
        db.conn.commit()
        db.execute("INSERT INTO sources(url, url_hash, channel_name, channel_key,"
                   " origin, status, score, first_seen, last_seen)"
                   " VALUES ('http://a/1','h1','n','CCTV1','','active',10,?,?)",
                   (utcnow(), utcnow()))
        db.execute("INSERT INTO playback_reports(reported_at, outcome) VALUES (?,?)",
                   (utcnow(), 'ok'))
        db.migrate({})
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM sources')['c'], 1)
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) AS c FROM playback_reports')['c'], 1)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM hub_notices')['c'], 0)
        db.close()

    def test_幂等可重入(self):
        db = Database(self.path)
        db.migrate({})
        db.migrate({})
        db.migrate({})
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        db.close()

    def test_cleanup裁剪notices与reports(self):
        import datetime
        db = Database(self.path)
        db.migrate({})
        old = (datetime.datetime.now(datetime.timezone.utc)
               - datetime.timedelta(days=200)).strftime('%Y-%m-%dT%H:%M:%SZ')
        for ts in (old, utcnow()):
            db.execute('INSERT INTO hub_notices(reported_at, code) VALUES (?,?)',
                       (ts, 'other'))
            db.execute('INSERT INTO playback_reports(reported_at, outcome) VALUES (?,?)',
                       (ts, 'ok'))
        db.cleanup(retention_days=90)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM hub_notices')['c'], 1)
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) AS c FROM playback_reports')['c'], 1)
        db.close()

    def test_旧库无notices表时cleanup不报错(self):
        db = Database(self.path)
        for stmt in DDL_V1:
            db.conn.execute(stmt)
        db.conn.commit()
        db.cleanup(retention_days=90)
        db.close()


if __name__ == '__main__':
    unittest.main()
