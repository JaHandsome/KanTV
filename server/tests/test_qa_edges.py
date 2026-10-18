# -*- coding: utf-8 -*-
"""QA 补充测试 4：解析边界与入库去重（P0-1 验收点③ 重复 URL 入库率=0）。

覆盖：
- classify 关键词边界：深圳卫视->广东（gd '深圳' 先于卫视）、香港卫视->港澳台、
  全角数字 CCTV 变体、澳门台；
- normalize_channel 全角数字、CCTV5+ 变体、卫视超清后缀；
- URL 归一化：query 鉴权参数原样保留（只去 fragment、host 小写）；
- discovery.ingest 去重：同 URL（含大小写/空白差异）第二次只更新 last_seen 不新增。
"""
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import make_config  # noqa: E402
from app.db import Database                 # noqa: E402
from app.discovery import SourceDiscovery   # noqa: E402
from app.parser import ChannelParser, Entry  # noqa: E402


class _NoNet(object):   # 本用例不触网
    ua = 'qa'

    @staticmethod
    def enc(url):
        return url


def make_parser():
    return ChannelParser(make_config())


class TestNormalizeChannelEdges(unittest.TestCase):
    def setUp(self):
        self.p = make_parser()

    def test_fullwidth_digits(self):
        self.assertEqual(self.p.normalize_channel('CCTV５'), 'CCTV5')
        self.assertEqual(self.p.normalize_channel('CCTV５＋'), 'CCTV5+')
        self.assertEqual(self.p.normalize_channel('ＣＣＴＶ５'.translate(
            str.maketrans('ＣＣＴＶ', 'CCTV'))), 'CCTV5')

    def test_guangdong_channels(self):
        self.assertEqual(self.p.normalize_channel('深圳卫视'), '深圳卫视')
        self.assertEqual(self.p.normalize_channel('深圳卫视超清'), '深圳卫视')  # weishi 容差+3
        self.assertEqual(self.p.normalize_channel('香港卫视'), '香港卫视')
        # 2026-09-10 P0：画质/备用后缀在归一化入口统一剥离，
        # 非卫视目标频道（无容差匹配）同样能归并（此前 '广东珠江超清' 会被丢弃）
        self.assertEqual(self.p.normalize_channel('广东珠江超清'), '广东珠江')
        self.assertEqual(self.p.normalize_channel('广东珠江'), '广东珠江')

    def test_unmatched_returns_empty(self):
        self.assertEqual(self.p.normalize_channel('某地方台'), '')
        self.assertEqual(self.p.normalize_channel(None), '')


class TestUrlNormalization(unittest.TestCase):
    def setUp(self):
        self.p = make_parser()

    def test_query_auth_params_preserved(self):
        u = 'http://198.51.100.48:9901/tsfile/live/0018_1.m3u8?key=fixture-auth-eec577e289ee&playlive=1&authid=0'
        self.assertEqual(self.p.normalize_url(u), u)

    def test_normalize_only_touches_host_and_fragment(self):
        self.assertEqual(
            self.p.normalize_url('HTTP://Host.EXAMPLE.com:8080/A/b.m3u8?Token=Xx&y=1#frag'),
            'http://host.example.com:8080/A/b.m3u8?Token=Xx&y=1')

    def test_hash_equal_iff_same_normalized(self):
        a = 'https://Srv1.example/PL.m3u8?k=1 '
        b = 'https://srv1.example/PL.m3u8?k=1#t=0'
        self.assertEqual(self.p.url_hash(a), self.p.url_hash(b))
        self.assertNotEqual(self.p.url_hash(b), self.p.url_hash('https://srv1.example/PL.m3u8?k=2'))

    def test_empty_url(self):
        self.assertEqual(self.p.normalize_url(''), '')
        self.assertEqual(self.p.normalize_url(None), '')


class TestIngestDedup(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-qa-ing-')
        cfg = make_config()
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()
        self.disc = SourceDiscovery(cfg, self.db, _NoNet())

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_duplicate_url_no_reinsert(self):
        """同 URL（含大小写/空白差异）二次入库 -> 新增 0、last_seen 被刷新。"""
        entries1 = [Entry('CCTV1', '央视', 'http://Srv.example/a.m3u8?token=abc', None),
                    Entry('CCTV1', '央视', 'http://other/b.m3u8', None)]
        n1, d1, b1 = self.disc.ingest(entries1, 'qa')
        self.assertEqual((n1, d1, b1), (2, 0, 0))

        # 第二轮：URL 归一化后等价（host 大小写 + 空白 + fragment）
        entries2 = [Entry('CCTV1', '央视', 'http://srv.example/a.m3u8?token=abc#frag ', None),
                    Entry('CCTV1', '央视', 'http://other/b.m3u8', None),
                    Entry('CCTV2', '央视', 'http://new/c.m3u8', None)]
        n2, d2, b2 = self.disc.ingest(entries2, 'qa')
        self.assertEqual((n2, d2, b2), (1, 2, 0))
        self.assertEqual(self.db.query_one('SELECT COUNT(*) AS c FROM sources')['c'], 3)
        # url 原样保留（含 query），未被归一化破坏
        row = self.db.query_one("SELECT url FROM sources WHERE url LIKE '%srv.example%'")
        self.assertIn('token=abc', row['url'])

    def test_blacklist_blocks_at_ingest(self):
        """39.134 网关段入库即拉黑，不进验证队列。"""
        entries = [Entry('CCTV1', '', 'http://39.134.115.163:8080/iptv/1.m3u8', None),
                  Entry('CCTV1', '', 'http://hls-gateway.vpstv.com/x.m3u8', None)]
        n, d, b = self.disc.ingest(entries, 'qa')
        self.assertEqual(b, 2)
        rows = self.db.query("SELECT status FROM sources WHERE url LIKE '%39.134%'"
                             " OR url LIKE '%vpstv%'")
        self.assertEqual([r['status'] for r in rows], ['blacklisted', 'blacklisted'])

    def test_flaky_domain_downweighted(self):
        """t.061899.xyz 降权（score 压到 5 以下）。"""
        entries = [Entry('CCTV1', '', 'http://t.061899.xyz/live.m3u8', None)]
        self.disc.ingest(entries, 'qa')
        row = self.db.query_one("SELECT score FROM sources WHERE url LIKE '%061899%'")
        self.assertLessEqual(row['score'], 5.0)

    def test_invalid_urls_skipped(self):
        entries = [Entry('CCTV1', '', 'not-a-url', None),
                   Entry('CCTV1', '', '', None)]
        n, d, b = self.disc.ingest(entries, 'qa')
        self.assertEqual((n, d, b), (0, 0, 0))


if __name__ == '__main__':
    unittest.main()
