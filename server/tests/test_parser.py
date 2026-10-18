# -*- coding: utf-8 -*-
"""parser 单测：解析/归一化/分类（全部离线，不依赖网络）。"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import make_config  # noqa: E402
from app.parser import ChannelParser  # noqa: E402


def make_parser():
    return ChannelParser(make_config())


class TestParseM3u(unittest.TestCase):
    M3U = ('#EXTM3U\n'
           '#EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV1\n'
           'http://fixture-43.example.invalid/1.m3u8\n'
           '#EXTINF:-1 group-title="港·澳·台" http-user-agent="okhttp/3",翡翠台\n'
           'http://fixture-45.example.invalid/fct.m3u8\n'
           '#EXTINF:-1,无逗号后的名字\n'
           'http://fixture-46.example.invalid/x.m3u8\n')

    def test_basic(self):
        entries = ChannelParser.parse_m3u(self.M3U)
        self.assertEqual(len(entries), 3)
        self.assertEqual(entries[0].name, 'CCTV1')
        self.assertEqual(entries[0].group, '央视')
        self.assertEqual(entries[0].url, 'http://fixture-43.example.invalid/1.m3u8')
        self.assertIsNone(entries[0].ua)
        self.assertEqual(entries[1].group, '港·澳·台')
        self.assertEqual(entries[1].ua, 'okhttp/3')
        self.assertEqual(entries[1].name, '翡翠台')
        self.assertEqual(entries[2].name, '无逗号后的名字')

    def test_empty_and_dirty(self):
        self.assertEqual(ChannelParser.parse_m3u(''), [])
        self.assertEqual(ChannelParser.parse_m3u('#EXTM3U\n#comment'), [])


class TestParseTxt(unittest.TestCase):
    TXT = ('央视,#genre#\n'
           'CCTV1,http://fixture-43.example.invalid/1.m3u8#http://fixture-43.example.invalid/1b.m3u8\n'
           'CCTV2, http://fixture-43.example.invalid/2.m3u8 \n'
           '烂行没有逗号http://x\n'
           '港澳台,#genre#\n'
           '翡翠台,http://fixture-45.example.invalid/fct.m3u8\n')

    def test_basic(self):
        entries = ChannelParser.parse_txt(self.TXT)
        # CCTV1 两条线路 + CCTV2 + 翡翠台
        self.assertEqual(len(entries), 4)
        self.assertEqual(entries[0].name, 'CCTV1')
        self.assertEqual(entries[0].group, '央视')
        self.assertEqual(entries[0].url, 'http://fixture-43.example.invalid/1.m3u8')
        self.assertEqual(entries[1].url, 'http://fixture-43.example.invalid/1b.m3u8')
        self.assertEqual(entries[2].url, 'http://fixture-43.example.invalid/2.m3u8')
        self.assertEqual(entries[3].group, '港澳台')

    def test_skips_garbage(self):
        entries = ChannelParser.parse_txt('##\xf0\x9f\x87\xa8\xf0\x9f\x87\xb3 央视##\r\n乱七八糟\r\n')
        self.assertEqual(entries, [])


class TestNormalizeChannel(unittest.TestCase):
    def setUp(self):
        self.p = make_parser()

    def test_cctv_variants(self):
        self.assertEqual(self.p.normalize_channel('CCTV-5'), 'CCTV5')
        self.assertEqual(self.p.normalize_channel('CCTV5+'), 'CCTV5+')
        self.assertEqual(self.p.normalize_channel('CCTV-5+'), 'CCTV5+')
        self.assertEqual(self.p.normalize_channel('CCTV５＋'), 'CCTV5+')   # 全角
        self.assertEqual(self.p.normalize_channel('CCTV-1综合'), 'CCTV1')
        self.assertEqual(self.p.normalize_channel('CCTV17'), 'CCTV17')

    def test_cctv_out_of_range(self):
        self.assertEqual(self.p.normalize_channel('CCTV18'), '')
        self.assertEqual(self.p.normalize_channel('CCTV0'), '')

    def test_exact_and_alias(self):
        self.assertEqual(self.p.normalize_channel('TVB翡翠台'), 'TVB翡翠台')
        self.assertEqual(self.p.normalize_channel('翡翠台'), 'TVB翡翠台')   # 别名
        self.assertEqual(self.p.normalize_channel('Viutv'), 'Viutv')
        self.assertEqual(self.p.normalize_channel('viutv'), 'Viutv')

    def test_weishi_containment(self):
        self.assertEqual(self.p.normalize_channel('湖南卫视'), '湖南卫视')
        self.assertEqual(self.p.normalize_channel('湖南卫视超清'), '湖南卫视')   # len 容差 +3
        self.assertEqual(self.p.normalize_channel('湖南卫视超清高清双语'), '')  # 超出容差

    def test_unmatched(self):
        self.assertEqual(self.p.normalize_channel('NewTV军事评论'), '')
        self.assertEqual(self.p.normalize_channel(''), '')


class TestQualitySuffixCleaning(unittest.TestCase):
    """画质/备用后缀清洗（2026-09-10 P0）。

    修复背景：`凤凰中文[1920*1080]`、`无线新闻 FHD`、`TVB翡翠台（备用）` 等名称
    超出 alias 兜底的 len+4 容差 → 归一化返回 '' → channel_key 为空 →
    verifier 永不消费 → 源被静默丢弃。用例取自线上库真实出现的名称。
    """

    def setUp(self):
        self.p = make_parser()

    def test_bracket_resolution(self):
        self.assertEqual(self.p.normalize_channel('凤凰中文[1920*1080]'), '凤凰中文台')
        self.assertEqual(self.p.normalize_channel('凤凰资讯[1920*1080]'), '凤凰资讯台')
        self.assertEqual(self.p.normalize_channel('凤凰香港[1920*1080]'), '凤凰香港台')
        self.assertEqual(self.p.normalize_channel('翡翠台[1920*1080]'), 'TVB翡翠台')
        self.assertEqual(self.p.normalize_channel('明珠台[1920*1080]'), 'TVB明珠台')
        self.assertEqual(self.p.normalize_channel('东森电影[1920*1080]'), '东森电影')
        self.assertEqual(self.p.normalize_channel('无线新闻[1920*1080]'), '无线新闻')
        self.assertEqual(self.p.normalize_channel('J2[1920*1080]'), 'J2')

    def test_paren_and_tail(self):
        self.assertEqual(self.p.normalize_channel('凤凰中文 (720p)'), '凤凰中文台')
        self.assertEqual(self.p.normalize_channel('凤凰中文 (576p)'), '凤凰中文台')
        self.assertEqual(self.p.normalize_channel('无线新闻 FHD'), '无线新闻')
        self.assertEqual(self.p.normalize_channel('TVB翡翠台（备用）'), 'TVB翡翠台')
        self.assertEqual(self.p.normalize_channel('TVB明珠台HD'), 'TVB明珠台')
        self.assertEqual(self.p.normalize_channel('第一财经HD'), '第一财经')

    def test_multi_suffix_stacking(self):
        self.assertEqual(self.p.normalize_channel('北京文艺HD[1920*1080][1920*1080]'),
                         '北京文艺')
        self.assertEqual(self.p.normalize_channel('广东珠江[1920*1080][1920*1080]'),
                         '广东珠江')

    def test_alias_fallback_still_works(self):
        # 清洗后交由 alias 包含兜底（'TVBS新闻' in 'TVBS新闻台'）
        self.assertEqual(self.p.normalize_channel('TVBS新闻台[1280*720]'), 'TVBS')

    def test_no_false_positive(self):
        # 语义不同的频道名不受影响（不得因清洗被错误并入目标频道）
        self.assertEqual(self.p.normalize_channel('翡翠剧集台(北美)'), '')
        self.assertEqual(self.p.normalize_channel('明珠剧集台(北美)'), '')
        self.assertEqual(self.p.normalize_channel('浙江·杭州西湖明珠'), '')
        self.assertEqual(self.p.normalize_channel('杭州明珠'), '')
        self.assertEqual(self.p.normalize_channel('翡翠综合台(北美)'), '')

    def test_pure_noise_name_safety(self):
        # 纯噪声名清洗为空时回退原名，不产生误匹配
        self.assertEqual(self.p.normalize_channel('[1080p]'), '')
        self.assertEqual(self.p.normalize_channel('(备用)'), '')

    def test_existing_behaviour_unchanged(self):
        # 既有容差语义保持：无后缀 → 不变；超容差 → 仍不匹配
        self.assertEqual(self.p.normalize_channel('湖南卫视超清'), '湖南卫视')
        self.assertEqual(self.p.normalize_channel('湖南卫视超清高清双语'), '')


class TestUrlNormalize(unittest.TestCase):
    def test_hash_host_case(self):
        h1 = ChannelParser.url_hash('http://fixture-43.example.invalid/x?a=1 ')
        h2 = ChannelParser.url_hash('HTTP://fixture-43.example.invalid/x?a=1')
        self.assertEqual(h1, h2)   # host 大小写归一 + strip

    def test_hash_query_differs(self):
        h1 = ChannelParser.url_hash('http://fixture-43.example.invalid/x?a=1')
        h2 = ChannelParser.url_hash('http://fixture-43.example.invalid/x?a=2')
        self.assertNotEqual(h1, h2)   # query 不归一（鉴权依赖）

    def test_fragment_removed(self):
        h1 = ChannelParser.url_hash('http://fixture-43.example.invalid/x?a=1#frag')
        h2 = ChannelParser.url_hash('http://fixture-43.example.invalid/x?a=1')
        self.assertEqual(h1, h2)

    def test_path_case_preserved(self):
        h1 = ChannelParser.url_hash('http://fixture-43.example.invalid/Live.m3u8')
        h2 = ChannelParser.url_hash('http://fixture-43.example.invalid/live.m3u8')
        self.assertNotEqual(h1, h2)   # path 大小写敏感不归一


class TestHostScore(unittest.TestCase):
    def test_segments(self):
        self.assertEqual(ChannelParser.host_score('39.134.115.163:8080'), 100)  # 电信网关段
        self.assertEqual(ChannelParser.host_score('39.135.138.58:18890'), 100)
        self.assertEqual(ChannelParser.host_score('183.207.255.188'), 80)
        self.assertEqual(ChannelParser.host_score('satellitepull.example.com'), 70)
        self.assertEqual(ChannelParser.host_score('hls-gateway.vpstv.com'), 0)  # 已知死
        self.assertEqual(ChannelParser.host_score('random.host.example'), 10)   # 默认


if __name__ == '__main__':
    unittest.main()
