# -*- coding: utf-8 -*-
"""HK MPD 路线（T1+T2）单测：parse_kodiprop / UrlListProvider / DDL_V6 / ingest 透传。

全部离线（零网络）：NetClient 以计数桩替换。
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

from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                                          # noqa: E402
from app.db import (DDL_V1, DDL_V2, DDL_V3, DDL_V4, SCHEMA_VERSION,   # noqa: E402
                    Database, _apply_ddl_v5, _post_migrate_v5, utcnow)
from app.discovery import (CLEARKEY_KEYFORMAT, DASH_CLEARKEY_CAPS,     # noqa: E402
                           SourceDiscovery, UrlListProvider)
from app.parser import ChannelParser                                   # noqa: E402

SETTINGS = {
    'signed_url_params': ['expires', 'token', 'sign'],
    'signed_url_params_by_host': {
        'edgeware.tvb.com': ['sig', 'ts', 'c', 'ext_start_limit', 'int_start_limit'],
    },
}

GOOD_KID_B64 = 'VmA7Zfodc4O27w5zua5p+g'
GOOD_KID_HEX = '56603b65fa1d7383b6ef0e73b9ae69fa'
KID2_B64 = 'AAECAwQFBgcICQoLDA0ODw'
KID2_HEX = '000102030405060708090a0b0c0d0e0f'
LICENSE_J = ('{"keys": [{"kty": "oct", "k": "22Q4E0SDl+0TWap5BmJG3Q", '
             '"kid": "%s"}], "type": "temporary"}' % GOOD_KID_B64)
LICENSE_J2 = ('{"keys": [{"kty": "oct", "k": "ERERERERERERERERERER", '
              '"kid": "%s"}], "type": "temporary"}' % KID2_B64)

URL_J_1 = ('http://edgeware-live.edgeware.tvb.com/__cl/slocalr43/__c/ott_J_hevc/'
           '__op/cenc_m/__f/index.mpd?sig=aaa&ts=100&c=2')
URL_J_2 = ('http://edgeware-live.edgeware.tvb.com/__cl/slocalr43/__c/ott_J_hevc/'
           '__op/cenc_m/__f/index.mpd?sig=bbb&ts=200&c=2')
URL_C = ('http://edgeware-live.edgeware.tvb.com/__cl/slocalr43/__c/ott_C_hevc/'
         '__op/cenc_m/__f/index.mpd?sig=ccc&ts=100&c=2')

_BAD_LIC = '#KODIPROP:inputstream.adaptive.license_key={bad json'


def _m3u(url, name, license_line):
    """构造一条 m3u 块：EXTINF -> KODIPROP×3 -> URL（URL 在 KODIPROP 之后）。"""
    return ('#EXTM3U\n#EXTINF:-1,%s\n'
            '#KODIPROP:inputstream.adaptive.manifest_type=mpd\n'
            '#KODIPROP:inputstream.adaptive.license_type=clearkey\n'
            '%s\n%s\n' % (name, license_line, url))


FAKE_M3U = (_m3u(URL_J_1, '翡翠台',
                 '#KODIPROP:inputstream.adaptive.license_key=' + LICENSE_J)
            + _m3u(URL_C, '無線新聞台', _BAD_LIC))


class _StubNet(object):
    """计数桩：记录所有网络调用；返回预置正文。用于断言 enabled=false 时零网络。"""

    def __init__(self, body=b''):
        self.calls = []
        self.body = body

    def fetch(self, url, ua=None, limit=None, timeout=None):
        self.calls.append(('fetch', url))
        return 200, self.body, 'text/plain'

    def fetch_retry(self, url, ua=None, limit=None, timeout=None, retries=None, backoff=None):
        self.calls.append(('fetch_retry', url))
        return self.body


def _copy_config(root):
    write_test_config(root)


# --------------------------- parse_kodiprop / kid_hex ---------------------------
class TestParseKodiprop(unittest.TestCase):
    def test_binds_kodiprop_to_following_url(self):
        """KODIPROP 在 URL 前：正确绑定到其下方 URL；raw license_key 原样保留。"""
        p = ChannelParser.parse_kodiprop(FAKE_M3U)
        self.assertIn(URL_J_1, p)
        self.assertEqual(p[URL_J_1]['manifest_type'], 'mpd')
        self.assertEqual(p[URL_J_1]['license_type'], 'clearkey')
        self.assertEqual(p[URL_J_1]['license_key'], LICENSE_J)     # 原始字符串原样
        self.assertEqual(p[URL_J_1]['license_key_obj']['keys'][0]['kid'], GOOD_KID_B64)

    def test_no_cross_entry_contamination(self):
        """多条目不串味：坏 JSON 条目自身的 obj 为 None，且不污染上一条。"""
        p = ChannelParser.parse_kodiprop(FAKE_M3U)
        self.assertIn(URL_C, p)
        self.assertEqual(p[URL_C]['manifest_type'], 'mpd')
        self.assertIsNone(p[URL_C]['license_key_obj'])
        # 上一条完好
        self.assertIsNotNone(p[URL_J_1]['license_key_obj'])

    def test_bad_json_no_raise(self):
        text = ('#EXTM3U\n#EXTINF:-1,x\n'
                '#KODIPROP:inputstream.adaptive.license_key={oops\nhttp://h/x.mpd\n')
        p = ChannelParser.parse_kodiprop(text)
        self.assertIn('http://h/x.mpd', p)
        self.assertIsNone(p['http://h/x.mpd']['license_key_obj'])

    def test_url_without_kodiprop_absent(self):
        p = ChannelParser.parse_kodiprop('#EXTM3U\n#EXTINF:-1,x\nhttp://h/plain.m3u8\n')
        self.assertEqual(p, {})

    def test_kid_hex_variants(self):
        self.assertEqual(ChannelParser.kid_hex(GOOD_KID_B64), GOOD_KID_HEX)
        self.assertEqual(ChannelParser.kid_hex(KID2_B64), KID2_HEX)
        self.assertEqual(ChannelParser.kid_hex('c1ce80ff-bae2-0972-c279-64491e9fc2c6'),
                         'c1ce80ffbae20972c27964491e9fc2c6')
        self.assertEqual(ChannelParser.kid_hex('c1ce80ffbae20972c27964491e9fc2c6'),
                         'c1ce80ffbae20972c27964491e9fc2c6')
        self.assertEqual(ChannelParser.kid_hex(''), '')
        self.assertEqual(ChannelParser.kid_hex('not-a-kid'), '')


# --------------------------- UrlListProvider ---------------------------
class TestUrlListProvider(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-urllist-')
        _copy_config(self.tmp)
        self.cfg = Config(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _lists(self, lst):
        self.cfg.url_lists['lists'] = lst

    def test_disabled_zero_network(self):
        """★ enabled=false -> collect() 零网络调用。"""
        net = _StubNet(FAKE_M3U.encode())
        self._lists([{'id': 'x', 'url': 'http://list/x.m3u', 'enabled': False}])
        self.assertEqual(UrlListProvider(self.cfg, net).collect(), [])
        self.assertEqual(net.calls, [])

    def test_enabled_maps_drm_fields(self):
        net = _StubNet(FAKE_M3U.encode())
        self._lists([{'id': 'x', 'url': 'http://list/x.m3u', 'enabled': True}])
        out = UrlListProvider(self.cfg, net).collect()
        self.assertEqual(len(out), 1)
        origin, entries = out[0]
        self.assertEqual(origin, 'urllist:x')
        by_url = {e['url']: e for e in entries}
        good = by_url[URL_J_1]
        self.assertEqual(good['container'], 'dash')
        self.assertEqual(good['drm'], 'clearkey')
        self.assertEqual(good['encryption'], 'cenc')
        self.assertEqual(good['keyformat'], CLEARKEY_KEYFORMAT)
        self.assertEqual(good['kid'], GOOD_KID_HEX)
        self.assertEqual(good['drm_key'], LICENSE_J)
        self.assertEqual(json.loads(good['caps_required']), DASH_CLEARKEY_CAPS)
        bad = by_url[URL_C]
        self.assertEqual(bad['container'], 'dash')          # container 非 DRM 信息，保留
        for k in ('drm', 'kid', 'drm_key', 'caps_required'):
            self.assertNotIn(k, bad)                         # 坏 JSON -> 不带 DRM 字段


# --------------------------- ingest 透传 + 签名轮换刷新 ---------------------------
class TestIngestDrmPassthrough(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-urllist-db-')
        _copy_config(self.tmp)
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.net = _StubNet(FAKE_M3U.encode())
        self.disc = SourceDiscovery(self.cfg, self.db, self.net)
        self.cfg.url_lists['lists'] = [{'id': 'x', 'url': 'http://list/x.m3u',
                                        'enabled': True}]

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _collect_ingest(self, m3u):
        self.net.body = m3u.encode()
        res = (0, 0, 0)
        for origin, entries in UrlListProvider(self.cfg, self.net).collect():
            res = self.disc.ingest(entries, origin)
        return res

    def test_good_and_bad_rows(self):
        self._collect_ingest(FAKE_M3U)
        good = self.db.query_one('SELECT * FROM sources WHERE url=?', (URL_J_1,))
        self.assertIsNotNone(good)
        self.assertEqual(good['container'], 'dash')
        self.assertEqual(good['drm'], 'clearkey')
        self.assertEqual(good['encryption'], 'cenc')
        self.assertEqual(good['keyformat'], CLEARKEY_KEYFORMAT)
        self.assertEqual(good['kid'], GOOD_KID_HEX)
        self.assertEqual(good['drm_key'], LICENSE_J)
        self.assertEqual(json.loads(good['caps_required']), DASH_CLEARKEY_CAPS)
        bad = self.db.query_one('SELECT * FROM sources WHERE url=?', (URL_C,))
        self.assertIsNotNone(bad)
        self.assertEqual(bad['container'], 'dash')
        self.assertIsNone(bad['drm'])
        self.assertIsNone(bad['kid'])
        self.assertIsNone(bad['drm_key'])

    def test_signature_rotation_refreshes_url_kid_drm_key(self):
        """AC（§4.3）：仅签名不同 -> 不新增行，url / drm_key / kid 同源刷新。"""
        self._collect_ingest(FAKE_M3U)
        before = self.db.query_one('SELECT COUNT(*) c FROM sources')['c']
        m3u2 = (_m3u(URL_J_2, '翡翠台',
                     '#KODIPROP:inputstream.adaptive.license_key=' + LICENSE_J2)
                + _m3u(URL_C, '無線新聞台', _BAD_LIC))
        n, d, _b = self._collect_ingest(m3u2)
        self.assertEqual(n, 0)
        self.assertEqual(d, 2)
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], before)
        row = self.db.query_one('SELECT url, drm_key, kid FROM sources WHERE channel_name=?',
                                ('翡翠台',))
        self.assertEqual(row['url'], URL_J_2)         # 签名刷新
        self.assertEqual(row['drm_key'], LICENSE_J2)  # 密钥同源刷新
        self.assertEqual(row['kid'], KID2_HEX)        # kid 同步


# --------------------------- DDL_V6 ---------------------------
def _make_v5(db, settings):
    for stmt in DDL_V1 + DDL_V2 + DDL_V3 + DDL_V4:
        db.conn.execute(stmt)
    _apply_ddl_v5(db.conn)
    _post_migrate_v5(db.conn, settings)
    db.conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','5')")
    db.conn.commit()


class TestDdlV6(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-v6-')
        self.path = os.path.join(self.tmp, 't.db')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_v5_to_v6_adds_drm_key_null_and_idempotent(self):
        db = Database(self.path)
        _make_v5(db, SETTINGS)
        self.assertEqual(len(db.query('PRAGMA table_info(sources)')), 26)
        now = utcnow()
        db.execute('INSERT INTO sources(url, url_hash, url_key, channel_name, channel_key,'
                   ' origin, status, score, first_seen, last_seen)'
                   " VALUES ('http://a/1.m3u8','h1','k1','n','CCTV1','t','active',10,?,?)",
                   (now, now))
        db.migrate(SETTINGS)                       # v5 -> v6（migrate 直跑到当前版本，v10 会再追加 base_score）
        cols = [r['name'] for r in db.query('PRAGMA table_info(sources)')]
        self.assertEqual(len(cols), 28)
        self.assertIn('drm_key', cols)
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))  # v9（含 v7/v8/v9）
        self.assertEqual(db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)   # 行数不变
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) c FROM sources WHERE drm_key IS NULL')['c'], 1)        # 全 NULL
        db.migrate(SETTINGS)                       # 幂等
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertEqual(len(db.query('PRAGMA table_info(sources)')), 28)
        db.close()


if __name__ == '__main__':
    unittest.main()
