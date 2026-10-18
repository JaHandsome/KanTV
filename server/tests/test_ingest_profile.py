# -*- coding: utf-8 -*-
"""ingest() 的 profile 派生（fail-closed）单测。

背景（安全字段上的 fail-open 默认值）：
    sources.profile 的列默认值是 'direct'（DDL_V3 的 ADD COLUMN profile TEXT DEFAULT 'direct'），
    含义是"可进 TVBox 轨"（render_txt/render_m3u 会渲染进 live.txt/live.m3u）。若任何"绕过
    record() 直接把 DRM 源置为 status='active'"的入库路径让 profile 停在列默认 'direct'，
    一个没有密钥参数的裸 MPD URL 就会进 TVBox 订阅 -> 黑屏。本测试锁定结构上的 fail-closed：
    ingest() 必须按 DRM/加密事实**派生** profile，绝不依赖列默认值。

全部离线（零网络）；SourceDiscovery.ingest 不触网。
"""
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
from app.db import Database, delivery_profile                          # noqa: E402
from app.discovery import SourceDiscovery, derive_profile             # noqa: E402
from app.parser import Entry                                          # noqa: E402


# --------------------------- 唯一叶子函数 delivery_profile ---------------------------
class TestDeliveryProfileLeaf(unittest.TestCase):
    """db.delivery_profile：唯一叶子派生（表驱动，含空白/大小写归一）。"""

    CASES = [
        (None, None, 'direct'),
        ('', '', 'direct'),
        ('aes128', '', 'direct'),
        (' AES128 ', '   ', 'direct'),
        ('aes128', None, 'direct'),
        ('cenc', 'clearkey', 'media3-json'),
        ('cbcs', 'widevine', 'media3-json'),
        ('sample-aes', '', 'media3-json'),
        ('  SAMPLE-AES  ', None, 'media3-json'),
        ('', 'clearkey', 'media3-json'),
        (None, ' WideVine ', 'media3-json'),
        ('cenc', '', 'media3-json'),
    ]

    def test_table_driven(self):
        for enc, drm, want in self.CASES:
            self.assertEqual(delivery_profile(enc, drm), want, (enc, drm))

    def test_derive_profile_delegates_without_explicit(self):
        """旧契约不变：非 explicit 时 derive_profile 结果 == 唯一叶子结果。"""
        for enc, drm, want in self.CASES:
            self.assertEqual(derive_profile(enc, drm), want, (enc, drm))
            self.assertEqual(derive_profile(enc, drm), delivery_profile(enc, drm))


def _copy_config(root):
    write_test_config(root)


# --------------------------- 纯函数 derive_profile ---------------------------
class TestDeriveProfile(unittest.TestCase):
    def test_plaintext_and_aes128_are_direct(self):
        self.assertEqual(derive_profile('', ''), 'direct')
        self.assertEqual(derive_profile(None, None), 'direct')
        self.assertEqual(derive_profile('aes128', ''), 'direct')
        self.assertEqual(derive_profile('', None), 'direct')

    def test_drm_facts_are_media3_json(self):
        self.assertEqual(derive_profile('cenc', 'clearkey'), 'media3-json')
        self.assertEqual(derive_profile('cbcs', 'clearkey'), 'media3-json')
        self.assertEqual(derive_profile('cenc', 'widevine'), 'media3-json')
        self.assertEqual(derive_profile('cenc', 'playready'), 'media3-json')

    def test_sample_aes_is_media3_json_even_with_empty_drm(self):
        """★ 只看 drm 会漏判：sample-aes 的 encryption 已置但 drm='' —— 仍须 media3-json。"""
        self.assertEqual(derive_profile('sample-aes', ''), 'media3-json')
        self.assertEqual(derive_profile('sample-aes', None), 'media3-json')

    def test_drm_only_defensive_branch(self):
        """enc 缺失但 drm 非空（异常组合）-> 仍 fail-closed 落 media3-json。"""
        self.assertEqual(derive_profile('', 'clearkey'), 'media3-json')
        self.assertEqual(derive_profile(None, 'widevine'), 'media3-json')

    def test_explicit_overrides(self):
        self.assertEqual(derive_profile('cenc', 'clearkey', explicit='m3u-kodiprop'),
                         'm3u-kodiprop')
        self.assertEqual(derive_profile('', '', explicit='media3-json'), 'media3-json')

    def test_normalizes_whitespace_and_case(self):
        """规范化：strip + lower 后判定（委托唯一叶子，与 delivery_profile 同语义）。"""
        self.assertEqual(derive_profile('  CENC  ', ' ClearKey '), 'media3-json')
        self.assertEqual(derive_profile('  AES128 ', '   '), 'direct')
        self.assertEqual(derive_profile('  ', '  '), 'direct')


# --------------------------- ingest() 落库 ---------------------------
class TestIngestProfile(unittest.TestCase):
    DRM_URL = 'http://example.com/live/hk/index.mpd'
    PLAIN_URL = 'http://example.com/live/plain/index.m3u8'
    EXPLICIT_URL = 'http://example.com/live/exp/index.m3u8'

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-profile-')
        _copy_config(self.tmp)
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        # ingest() 不触网；net 仅占位。
        self.disc = SourceDiscovery(self.cfg, self.db, object())

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _profile_of(self, url):
        row = self.db.query_one('SELECT profile FROM sources WHERE url=?', (url,))
        return None if row is None else row['profile']

    def test_drm_entry_directly_active_yields_media3_json(self):
        """★ 核心断言：DRM entry（未带 profile）即使直接 status='active'，也须落 media3-json。"""
        entries = [{
            'url': self.DRM_URL, 'name': '翡翠台', 'status': 'active',
            'container': 'dash', 'encryption': 'cenc', 'drm': 'clearkey',
            'keyformat': 'urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e',
            'kid': '56603b65fa1d7383b6ef0e73b9ae69fa', 'drm_key': '{"kty":"oct"}',
        }]
        self.disc.ingest(entries, 'urllist:x')
        self.assertEqual(self._profile_of(self.DRM_URL), 'media3-json')

    def test_plain_entry_yields_direct(self):
        entries = [{'url': self.PLAIN_URL, 'name': 'CCTV1', 'status': 'active'}]
        self.disc.ingest(entries, 'seed')
        self.assertEqual(self._profile_of(self.PLAIN_URL), 'direct')

    def test_entry_namedtuple_yields_direct(self):
        """Entry namedtuple 分支（仓库解析）：无 DRM 字段 -> 'direct'。"""
        self.disc.ingest([Entry(name='CCTV1', group='', url=self.PLAIN_URL, ua=None)], 'repo:x')
        self.assertEqual(self._profile_of(self.PLAIN_URL), 'direct')

    def test_explicit_profile_respected(self):
        entries = [{'url': self.EXPLICIT_URL, 'name': '某某台', 'status': 'candidate',
                    'profile': 'm3u-kodiprop'}]
        self.disc.ingest(entries, 'urllist:x')
        self.assertEqual(self._profile_of(self.EXPLICIT_URL), 'm3u-kodiprop')

    def test_regression_no_direct_row_with_drm(self):
        """回归护栏：不存在 profile='direct' 且 drm 非空的行（防未来 fail-open 回归）。"""
        self.disc.ingest([
            {'url': self.DRM_URL, 'name': '翡翠台', 'status': 'active',
             'container': 'dash', 'encryption': 'cenc', 'drm': 'clearkey'},
            {'url': self.PLAIN_URL, 'name': 'CCTV1', 'status': 'active'},
        ], 'urllist:x')
        bad = self.db.query_one(
            "SELECT COUNT(*) AS c FROM sources WHERE profile='direct' AND drm IS NOT NULL"
            " AND drm<>''")
        self.assertEqual(bad['c'], 0)
        # 同样不得存在 direct 且 encryption 非明文/AES-128（含 sample-aes 无 drm 的 fail-open）
        bad_enc = self.db.query_one(
            "SELECT COUNT(*) AS c FROM sources WHERE trim(lower(profile))='direct' "
            "AND encryption IS NOT NULL AND trim(lower(encryption)) NOT IN ('', 'aes128')")
        self.assertEqual(bad_enc['c'], 0)

    def test_signature_rotation_refreshes_profile(self):
        """签名轮换分支：DRM 源 url 轮换时 profile 同源刷新（不残留 direct）。

        构造：先用**显式** profile='direct' 入库一条 DRM 源（模拟历史 fail-open 产物）；
        再以同 url_key（仅 token 轮换）且不带显式 profile 的新一轮清单入库 -> 走 to_refresh，
        应把 profile 修正为 'media3-json'。
        """
        self.disc.ingest([{'url': self.DRM_URL + '?token=old', 'name': '翡翠台',
                           'status': 'active', 'container': 'dash',
                           'encryption': 'cenc', 'drm': 'clearkey',
                           'profile': 'direct'}], 'urllist:x')
        self.assertEqual(self._profile_of(self.DRM_URL + '?token=old'), 'direct')
        # 同 url_key（token 属全局签名参数，被剥离）但字面 URL 不同 -> to_refresh
        self.disc.ingest([{'url': self.DRM_URL + '?token=new', 'name': '翡翠台',
                           'status': 'active', 'container': 'dash',
                           'encryption': 'cenc', 'drm': 'clearkey'}], 'urllist:x')
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)
        self.assertEqual(self._profile_of(self.DRM_URL + '?token=new'), 'media3-json')


if __name__ == '__main__':
    unittest.main()
