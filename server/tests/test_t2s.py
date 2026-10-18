# -*- coding: utf-8 -*-
"""繁体->简体预转换归一（2026-09-10 用户建议）：域内 t2s_map + normalize_channel 两趟。

覆盖：
1. t2s_map 结构自检（域内规模 / 单字 / 无自映射 / 关键映射正确）；
2. 覆盖自检：103 个目标键的【繁体形态】经预转换后可归一回该键（自动反构）；
3. 零回归：既有精确/alias/容差语义在首趟保持不变；
4. 新增覆盖：仅靠预转换才能命中的繁体频道名（首趟必失，第二趟必中）；
5. 边界：预转换不得把应判未匹配的名字误认领。

全部离线可跑：python tests/run_offline.py
"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import make_config  # noqa: E402
from app.config import DEFAULT_CHANNELS  # noqa: E402
from app.parser import ChannelParser              # noqa: E402


def make_parser():
    return ChannelParser(make_config())


def _s2t_inverse(m):
    """由 繁体->简体 表反构 简体->繁体 表（多对一时取首个，回转换恒等）。"""
    inv = {}
    for trad, simp in m.items():
        inv.setdefault(simp, trad)
    return inv


class TestT2sMapStructure(unittest.TestCase):
    """t2s_map 结构：域内规模，单字映射，无自映射。"""

    @classmethod
    def setUpClass(cls):
        cls.cfg = make_config()
        cls.p = ChannelParser(cls.cfg)
        cls.m = cls.cfg.channels.get('t2s_map') or {}

    def test_default_skeleton_present(self):
        # app/config.py 的默认骨架必须存在且为空（缺配置 -> 预转换不生效，安全回退）
        self.assertIn('t2s_map', DEFAULT_CHANNELS)
        self.assertEqual(DEFAULT_CHANNELS['t2s_map'], {})

    def test_is_domain_scoped_table(self):
        self.assertIsInstance(self.m, dict)
        # 域内表（频道名用字），应远小于完整 OpenCC（数千字）
        self.assertGreaterEqual(len(self.m), 60)
        self.assertLessEqual(len(self.m), 400)
        for trad, simp in self.m.items():
            self.assertEqual(len(trad), 1, trad)
            self.assertEqual(len(simp), 1, simp)
            self.assertNotEqual(trad, simp, trad)   # 简繁同形不入表

    def test_key_mappings_correct(self):
        spot = {
            '衛': '卫', '視': '视', '臺': '台', '線': '线', '綫': '线',
            '東': '东', '廣': '广', '聞': '闻', '資': '资', '訊': '讯',
            '鳳': '凤', '無': '无', '綜': '综', '電': '电', '樂': '乐',
            '體': '体', '華': '华', '亞': '亚', '遊': '游', '頻': '频',
        }
        for trad, simp in spot.items():
            self.assertEqual(self.m.get(trad), simp, trad)


class TestT2sHelper(unittest.TestCase):
    """_to_simplified 纯函数行为 + 热重载下的表缓存失效。"""

    def setUp(self):
        self.p = make_parser()

    def test_convert_and_noop(self):
        self.assertEqual(self.p._to_simplified('東方衛視'), '东方卫视')
        # 无繁体字 -> 原样
        self.assertEqual(self.p._to_simplified('湖南卫视'), '湖南卫视')
        self.assertEqual(self.p._to_simplified('CCTV5+'), 'CCTV5+')
        self.assertEqual(self.p._to_simplified(''), '')
        self.assertEqual(self.p._to_simplified(None), None)

    def test_table_cache_follows_hot_reload(self):
        # 首查预转换生效
        self.assertEqual(self.p.normalize_channel('東方衛視'), '东方卫视')
        # 模拟热重载：以新对象清空 t2s_map -> 预转换立即失效（缓存按对象身份失效）
        self.p.cfg.channels['t2s_map'] = {}
        self.assertEqual(self.p.normalize_channel('東方衛視'), '')
        # 换回一个小表 -> 立即生效
        self.p.cfg.channels['t2s_map'] = {'東': '东', '衛': '卫', '視': '视'}
        self.assertEqual(self.p.normalize_channel('東方衛視'), '东方卫视')


class TestCoverageSelfCheck(unittest.TestCase):
    """覆盖自检：每个目标键的繁体形态都能归一回该键；每个目标键本身精确命中。"""

    @classmethod
    def setUpClass(cls):
        cls.cfg = make_config()
        cls.p = ChannelParser(cls.cfg)
        cls.m = cls.cfg.channels.get('t2s_map') or {}
        cls.inv = _s2t_inverse(cls.m)

    def test_every_target_key_traditional_form_normalizes(self):
        table = str.maketrans(self.inv)
        checked = 0
        for key in self.cfg.channels['channels']:
            trad = key.translate(table)
            if trad != key:
                checked += 1
            self.assertEqual(self.p.normalize_channel(trad), key,
                             '%s 的繁体形态 %s 未归一' % (key, trad))
        # 至少覆盖了相当数量的含繁体用字的目标键（防反构表为空导致自检空转）
        self.assertGreaterEqual(checked, 40,
                                '反构繁体形态命中数过少，覆盖自检可能空转')

    def test_every_target_key_exact(self):
        for key in self.cfg.channels['channels']:
            self.assertEqual(self.p.normalize_channel(key), key, key)


class TestZeroRegression(unittest.TestCase):
    """零回归：既有 alias/精确/容差语义在首趟保持不变。"""

    @classmethod
    def setUpClass(cls):
        cls.cfg = make_config()
        cls.p = ChannelParser(cls.cfg)

    def test_all_aliases_resolve_to_their_value(self):
        targets = set(self.cfg.channels['channels'])
        for alias, key in (self.cfg.channels.get('aliases') or {}).items():
            self.assertIn(key, targets, alias)
            self.assertEqual(self.p.normalize_channel(alias), key, alias)

    def test_legacy_semantics_unchanged(self):
        cases = {
            'CCTV-5': 'CCTV5',
            'CCTV５＋': 'CCTV5+',                      # 全角
            '翡翠台': 'TVB翡翠台',                      # alias 精确
            'TVBS新闻台[1280*720]': 'TVBS',            # 清洗 + alias 包含兜底
            '湖南卫视超清': '湖南卫视',                  # weishi 容差 +3
            '湖南卫视超清高清双语': '',                 # 超出容差
            '凤凰中文[1920*1080]': '凤凰中文台',
            '无线新闻 FHD': '无线新闻',
            'TVB翡翠台（备用）': 'TVB翡翠台',
            '广东珠江[1920*1080][1920*1080]': '广东珠江',
            '': '',
            None: '',
        }
        for name, expect in cases.items():
            self.assertEqual(self.p.normalize_channel(name), expect, repr(name))


class TestNewCoverage(unittest.TestCase):
    """新增覆盖：这些繁体名【不在】aliases 中，首趟必失，只有预转换能认出。"""

    @classmethod
    def setUpClass(cls):
        cls.cfg = make_config()
        cls.p = ChannelParser(cls.cfg)
        cls.aliases = cls.cfg.channels.get('aliases') or {}

    CASES = {
        '東方衛視': '东方卫视',
        '湖南衛視': '湖南卫视',
        '江蘇衛視': '江苏卫视',
        '廣東體育': '广东体育',
        '黑龍江衛視': '黑龙江卫视',
        '內蒙古衛視': '内蒙古卫视',
        '重慶衛視': '重庆卫视',
        '陝西衛視': '陕西卫视',
        '兵團衛視': '兵团卫视',
        '廣州綜合': '广州综合',
        '江門綜合': '江门综合',
        '佛山綜合': '佛山综合',
        '汕頭經濟': '汕头经济',
        '廣州競賽': '广州竞赛',
        '廣東新聞': '广东新闻',
        '廣東珠江': '广东珠江',
        '廣州影視': '广州影视',
        '廣東民生': '广东民生',
        '體育休閒': '体育休闲',
        '卡酷少兒': '卡酷少儿',
        '北京文藝': '北京文艺',
        '紀實科教': '纪实科教',
        '中天新聞': '中天新闻',
        '天映頻道': '天映频道',
        '大灣區衛視': '大湾区卫视',
        '雲南衛視': '云南卫视',
        '貴州衛視': '贵州卫视',
        '甘肅衛視': '甘肃卫视',
        '第一財經': '第一财经',
        'Astro 歡喜台': 'Astro 欢喜台',
        'Astro 愛奇藝': 'Astro 爱奇艺',
        # 预转换后再走"简体别名"词条（繁体名本身不是 alias，首趟必失）
        '廣東體育頻道': '广东体育',
        '廣東新聞頻道': '广东新闻',
        '廣州綜合頻道': '广州综合',
        '廣州競賽頻道': '广州竞赛',
        '珠江頻道': '广东珠江',
    }

    def test_traditional_names_now_recognized(self):
        self.assertGreaterEqual(len(self.CASES), 7)
        for name, expect in self.CASES.items():
            # 前置保证：首趟确实失配（否则本用例测不到预转换）
            self.assertNotIn(name, self.aliases, name)
            self.assertEqual(self.p._match(name), '', '%s 首趟不应命中' % name)
            self.assertEqual(self.p.normalize_channel(name), expect, name)


class TestNoFalsePositive(unittest.TestCase):
    """边界：预转换不得把本应判未匹配的名字误认领（域外长尾仍为空）。"""

    def setUp(self):
        self.p = make_parser()

    def test_out_of_domain_still_unmatched(self):
        cases = (
            '亞洲電視',        # 预转换 -> 亚洲电视（非目标键）
            '華視',            # 预转换 -> 华视（非目标键）
            '鳳凰劇集台',      # 非目标键
            'TVB收費台',       # 非目标键
            '山西衛視超清高清双语',  # 非目标键 + 超容差
            '鳳凰衛視資訊台超清高清双语',  # 转换后仍超容差
        )
        for name in cases:
            self.assertEqual(self.p.normalize_channel(name), '', name)


if __name__ == '__main__':
    unittest.main()
