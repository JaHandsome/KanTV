# -*- coding: utf-8 -*-
"""T3 单测：probe_dash / probe() .mpd 路由 / probe_m3u8 的 302 final_url base 修复。

全部离线：用内联 MPD 文本 + 计数/记录型假网络，零网络依赖。
"""
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

import app.verifier as V                                       # noqa: E402
from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                                  # noqa: E402
from app.db import Database, utcnow                            # noqa: E402
from app.verifier import (CLEARKEY_KEYFORMAT, _fetch_ex,        # noqa: E402
                          classify_verdict, probe, probe_dash,
                          probe_m3u8, Verifier)

GOOD_KID_UUID = 'c1ce80ff-bae2-0972-c279-64491e9fc2c6'
GOOD_KID_HEX = 'c1ce80ffbae20972c27964491e9fc2c6'
BAD_KID_HEX = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
LICENSE_J = ('{"keys": [{"kty": "oct", "k": "22Q4E0SDl+0TWap5BmJG3Q", '
             '"kid": "VmA7Zfodc4O27w5zua5p+g"}], "type": "temporary"}')

# 典型 TVB MPD：dynamic + mp4protection(default_KID) + Widevine + PlayReady（无 ClearKey 节点）
MPD_DYN = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"\n'
    '     type="dynamic" minimumUpdatePeriod="PT2S">\n'
    '  <Period>\n'
    '    <AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true">\n'
    '      <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"\n'
    '          cenc:default_KID="%s"/>\n'
    '      <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>\n'
    '      <ContentProtection schemeIdUri="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"/>\n'
    '      <Representation id="video=5000000" codecs="hev1.1.6.L150.90" bandwidth="5000000">\n'
    '        <SegmentTemplate timescale="90000" initialization="$RepresentationID$/init.cmfv"\n'
    '            media="$RepresentationID$/seg-$Number$.cmfv"/>\n'
    '      </Representation>\n'
    '    </AdaptationSet>\n'
    '    <AdaptationSet contentType="audio" mimeType="audio/mp4">\n'
    '      <Representation id="audio=128000" codecs="mp4a.40.2" bandwidth="128000"/>\n'
    '    </AdaptationSet>\n'
    '  </Period>\n'
    '</MPD>' % GOOD_KID_UUID)

# static（点播）——不上线
MPD_STATIC = ('<?xml version="1.0"?>\n'
              '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">\n'
              '  <Period><AdaptationSet contentType="video" mimeType="video/mp4">\n'
              '    <Representation id="v"><SegmentTemplate initialization="$RepresentationID$/i"/>\n'
              '    </Representation></AdaptationSet></Period>\n'
              '</MPD>')

# 仅 mp4protection、无任何 DRM 具体节点（expect_drm=None 时应判 cenc-unknown，不得判 clearkey）
MPD_ONLY_PROT = ('<?xml version="1.0"?>\n'
                 '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"\n'
                 '     type="dynamic">\n'
                 '  <Period><AdaptationSet contentType="video" mimeType="video/mp4">\n'
                 '    <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" '
                 'value="cenc" cenc:default_KID="%s"/>\n'
                 '    <Representation id="v"><SegmentTemplate '
                 'initialization="$RepresentationID$/i"/></Representation>\n'
                 '  </AdaptationSet></Period>\n'
                 '</MPD>' % GOOD_KID_UUID)

# Period 级 BaseURL（实测 TVB 为 '6/' / '8/'）：init/分片寻址必须拼接
MPD_DYN_BASEURL = MPD_DYN.replace('  <Period>\n', '  <Period>\n    <BaseURL>6/</BaseURL>\n')

MPD_NO_KID = ('<?xml version="1.0"?>\n'
              '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">\n'
              '  <Period><AdaptationSet contentType="video" mimeType="video/mp4">\n'
              '    <Representation id="v"><SegmentTemplate initialization="$RepresentationID$/i"/>'
              '</Representation></AdaptationSet></Period>\n'
              '</MPD>')


def fmp4_video():
    """含 hvcC 的 fMP4 init 段样本（mp4_judge -> video:H.265）。"""
    return (b'\x00\x00\x00\x20ftypisom' + b'\x00' * 8 + b'hvcC\x01\x02\x03')


class DashNet(object):
    """记录 + 可重定向的假网络：fetch_ex 暴露 final_map 中的最终 URL。"""

    ua = 'test-ua'

    def __init__(self, responses, final_map=None):
        self.responses = responses
        self.final_map = final_map or {}
        self.requested = []
        self.limits = []

    @staticmethod
    def enc(url):
        return url

    def fetch_ex(self, url, ua=None, limit=None, timeout=None):
        self.requested.append(url)
        self.limits.append(limit)
        if url not in self.responses:
            raise KeyError(url)
        st, body, ct = self.responses[url]
        lim = 393216 if limit is None else limit
        return st, body[:lim], ct, self.final_map.get(url, url)

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        st, body, ct, _ = self.fetch_ex(url, ua, limit, timeout)
        return st, body, ct


def _dash_net(mpd=MPD_DYN):
    init = 'http://x/video=5000000/init.cmfv'
    return DashNet({'http://x/v.mpd': (200, mpd.encode(), 'application/dash+xml'),
                    init: (200, fmp4_video(), 'video/mp4')})


# --------------------------- probe_dash 纯判定 ---------------------------
class TestProbeDash(unittest.TestCase):
    def test_static_is_abnormal(self):
        r = probe_dash(_dash_net(MPD_STATIC), 'http://x/v.mpd', None)
        self.assertFalse(r['alive'])
        v, _p, _reason, _caps = classify_verdict(r, {'version': '1', 'caps': {}})
        self.assertEqual(v, 'abnormal')

    def test_non_mpd(self):
        net = DashNet({'http://x/v.mpd': (200, b'<html>no</html>', 'text/html')})
        r = probe_dash(net, 'http://x/v.mpd', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], '非MPD')

    def test_clearkey_manifest_first_not_downgraded(self):
        """★ expect_drm='clearkey' + 非零 default_KID：即便 MPD 含 Widevine/PlayReady 也不降级。"""
        r = probe_dash(_dash_net(), 'http://x/v.mpd', None,
                       expect_drm='clearkey', expect_kid=GOOD_KID_HEX)
        self.assertTrue(r['alive'])
        self.assertEqual(r['drm'], 'clearkey')
        self.assertEqual(r['encryption'], 'cenc')
        self.assertEqual(r['keyformat'], CLEARKEY_KEYFORMAT)
        self.assertEqual(r['kid'], GOOD_KID_HEX)
        self.assertTrue(r['key_present'])
        self.assertEqual(r['av'], 'video')
        v, profile, _reason, caps = classify_verdict(
            r, {'version': '1', 'caps': {'dash': True, 'dash_clearkey': True}})
        self.assertEqual(v, 'ok')
        self.assertEqual(profile, 'media3-json')
        self.assertEqual(caps, {'dash_clearkey': True})

    def test_kid_mismatch_abnormal(self):
        r = probe_dash(_dash_net(), 'http://x/v.mpd', None,
                       expect_drm='clearkey', expect_kid=BAD_KID_HEX)
        self.assertFalse(r['alive'])
        self.assertEqual(r['verdict_reason'], 'kid_mismatch')
        v, _p, _reason, _caps = classify_verdict(r, {'version': '1', 'caps': {}})
        self.assertEqual(v, 'abnormal')

    def test_missing_default_kid_abnormal(self):
        r = probe_dash(_dash_net(MPD_NO_KID), 'http://x/v.mpd', None,
                       expect_drm='clearkey')
        self.assertFalse(r['alive'])
        self.assertEqual(r['verdict_reason'], 'no_kid')
        self.assertEqual(r['drm'], 'cenc-unknown')

    def test_expect_none_only_mp4protection_is_cenc_unknown(self):
        """未走清单：只有 mp4protection -> cenc-unknown，不得判 clearkey。"""
        r = probe_dash(_dash_net(MPD_ONLY_PROT), 'http://x/v.mpd', None)
        self.assertTrue(r['alive'])
        self.assertEqual(r['drm'], 'cenc-unknown')
        self.assertNotEqual(r['drm'], 'clearkey')
        self.assertEqual(r['kid'], GOOD_KID_HEX)

    def test_init_uses_final_url_as_base(self):
        """init 段 URL 以 MPD 的 final_url 为 base 拼接。"""
        final = 'http://x/session/abc/index.mpd'
        net = DashNet({'http://x/req.mpd': (200, MPD_DYN.encode(), 'application/dash+xml'),
                       'http://x/session/abc/video=5000000/init.cmfv':
                           (200, fmp4_video(), 'video/mp4')},
                      final_map={'http://x/req.mpd': final})
        r = probe_dash(net, 'http://x/req.mpd', None, expect_drm='clearkey')
        self.assertIn('http://x/session/abc/video=5000000/init.cmfv', net.requested)
        self.assertNotIn('http://x/video=5000000/init.cmfv', net.requested)
        self.assertEqual(r['av'], 'video')

    def test_period_baseurl_joined_into_init_url(self):
        """★ Period 级 BaseURL（'6/'）必须拼进 init URL（否则真实 TVB 源 404 -> av 未判定）。"""
        net = DashNet({'http://x/req.mpd': (200, MPD_DYN_BASEURL.encode(),
                                            'application/dash+xml'),
                       'http://x/6/video=5000000/init.cmfv': (200, fmp4_video(), 'video/mp4')})
        r = probe_dash(net, 'http://x/req.mpd', None, expect_drm='clearkey')
        self.assertIn('http://x/6/video=5000000/init.cmfv', net.requested)
        self.assertNotIn('http://x/video=5000000/init.cmfv', net.requested)
        self.assertEqual(r['av'], 'video')
        self.assertEqual(r['drm'], 'clearkey')

    def test_mpd_fetch_limit_raised_above_512k(self):
        """MPD 抓取下限 4MB（TVB MPD 实测 ~975KB，512KB 会截断）。"""
        net = _dash_net()
        probe_dash(net, 'http://x/v.mpd', None, limit=393216)
        self.assertGreaterEqual(net.limits[0], 4 * 1024 * 1024)


# --------------------------- _fetch_ex 退化为 fetch ---------------------------
class _FetchOnlyNet(object):
    ua = 'ua'

    def __init__(self, body):
        self.body = body
        self.calls = []

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        self.calls.append(url)
        return 200, self.body[:limit], 'text/plain'


class TestFetchExFallback(unittest.TestCase):
    def test_fallback_uses_request_url_as_final(self):
        net = _FetchOnlyNet(b'abc')
        st, body, ct, final = _fetch_ex(net, 'http://x/a', 'ua', 10)
        self.assertEqual((st, body, ct, final), (200, b'abc', 'text/plain', 'http://x/a'))
        self.assertEqual(net.calls, ['http://x/a'])


# --------------------------- probe_m3u8 302 base 修复 ---------------------------
class TestProbeM3u8RedirectBase(unittest.TestCase):
    def test_segment_base_uses_final_url(self):
        """请求 URL 与最终 URL 不同：分片 URL 必须基于 final_url 拼接（否则 403）。"""
        media = (b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n')
        net = DashNet({
            'http://x/req.m3u8': (200, media, 'application/vnd.apple.mpegurl'),
            'http://x/session/abc/seg1.ts': (200, fmp4_video() * 200, 'video/mp2t')},
            final_map={'http://x/req.m3u8': 'http://x/session/abc/index.m3u8'})
        r = probe_m3u8(net, 'http://x/req.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertIn('http://x/session/abc/seg1.ts', net.requested)
        self.assertNotIn('http://x/seg1.ts', net.requested)

    def test_nested_master_relative_child_and_segment_use_final_url(self):
        """嵌套层 302：child 以 master 的 final_url 为 base，segment 以 child 的 final_url 为 base。"""
        top = (b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8\n')
        media = b'#EXTM3U\n#EXTINF:6,\nseg.ts\n'
        net = DashNet({
            'http://x/req.m3u8': (200, top, 'application/vnd.apple.mpegurl'),
            'http://x/a/child.m3u8': (200, media, 'application/vnd.apple.mpegurl'),
            'http://x/b/seg.ts': (200, fmp4_video() * 200, 'video/mp2t')},
            final_map={'http://x/req.m3u8': 'http://x/a/index.m3u8',
                       'http://x/a/child.m3u8': 'http://x/b/media.m3u8'})
        r = probe_m3u8(net, 'http://x/req.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertIn('http://x/a/child.m3u8', net.requested)
        self.assertIn('http://x/b/seg.ts', net.requested)
        self.assertNotIn('http://x/child.m3u8', net.requested)
        self.assertNotIn('http://x/a/seg.ts', net.requested)

    def test_nested_redirect_back_to_seen_url_is_cycle(self):
        """子请求 302 回已访问的顶层 final_url，必须在解析下一层前拒绝。"""
        top = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8\n'
        net = DashNet({
            'http://x/req.m3u8': (200, top, 'application/vnd.apple.mpegurl'),
            'http://x/a/child.m3u8': (200, top, 'application/vnd.apple.mpegurl')},
            final_map={'http://x/req.m3u8': 'http://x/a/index.m3u8',
                       'http://x/a/child.m3u8': 'http://x/a/index.m3u8'})
        r = probe_m3u8(net, 'http://x/req.m3u8', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], 'master循环')
        self.assertEqual(net.requested.count('http://x/a/child.m3u8'), 1)


# --------------------------- probe() 路由 ---------------------------
class TestProbeRouting(unittest.TestCase):
    def test_mpd_routes_to_probe_dash_with_expect(self):
        called = {}
        orig = V.probe_dash

        def stub(net, url, ua, limit=524288, expect_drm=None, expect_kid=None):
            called.update(url=url, drm=expect_drm, kid=expect_kid)
            return {'alive': True, 'container': 'dash', 'type': 'DASH'}

        V.probe_dash = stub
        try:
            r = probe(DashNet({}), 'http://x/a.mpd?sig=1', None,
                      expect_drm='clearkey', expect_kid=GOOD_KID_HEX)
        finally:
            V.probe_dash = orig
        self.assertEqual(called.get('url'), 'http://x/a.mpd?sig=1')
        self.assertEqual(called.get('drm'), 'clearkey')
        self.assertEqual(called.get('kid'), GOOD_KID_HEX)
        self.assertEqual(r['type'], 'DASH')
        self.assertEqual(r['container'], 'dash')


# --------------------------- record() 不清空清单 DRM ---------------------------
def _make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-t3-')
    write_test_config(tmp)
    return tmp


class TestRecordKeepsManifestDrm(unittest.TestCase):
    """record() 不得用 MPD 声明覆盖清单 drm，也不得把 drm_key 写成 NULL（§5.3/§7.2）。"""

    def setUp(self):
        self.tmp = _make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.v = Verifier(self.cfg, self.db, DashNet({}))
        now = utcnow()
        self.db.execute(
            'INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status, av, '
            'score, first_seen, last_seen, container, encryption, drm, keyformat, kid, drm_key) '
            'VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
            ('http://x/v.mpd?sig=1', 'h', '無線新聞台', '无线新闻', 'urllist:tvb',
             'candidate', None, 10, now, now, 'dash', 'cenc', 'clearkey',
             CLEARKEY_KEYFORMAT, GOOD_KID_HEX, LICENSE_J))
        self.sid = self.db.query_one(
            'SELECT * FROM sources WHERE url_hash=?', ('h',))['id']

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_drm_key_and_drm_preserved(self):
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (self.sid,))
        result = {'alive': True, 'container': 'dash', 'type': 'DASH', 'encryption': 'cenc',
                  'drm': 'clearkey', 'keyformat': CLEARKEY_KEYFORMAT, 'kid': GOOD_KID_HEX,
                  'key_present': True, 'av': 'video', 'note': 'MPD:dynamic'}
        verdict = self.v.record(row, result, 5)
        self.assertEqual(verdict, 'ok')
        after = self.db.query_one('SELECT * FROM sources WHERE id=?', (self.sid,))
        self.assertEqual(after['drm'], 'clearkey')          # 未被宽泛声明覆盖
        self.assertEqual(after['drm_key'], LICENSE_J)        # drm_key 未被清空
        self.assertEqual(after['kid'], GOOD_KID_HEX)
        self.assertEqual(after['status'], 'active')
        self.assertEqual(after['profile'], 'media3-json')


if __name__ == '__main__':
    unittest.main()
