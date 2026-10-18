# -*- coding: utf-8 -*-
"""离线单测：NAS 探针的分片模板寻址（$Time$/$Number$ 替换）。

回归触发：2026-09-11 真源 probe_dash 收口时，脚本未替换 ``$Time$``，分片 URL 残留
字面量 ``D$Time$.cmfv`` → 恒 404，导致"分片是否需 sig"的对照实验全部作废。
修复：``$Time$`` 用 SegmentTimeline 首个 ``S@t`` 的真实值代入。

离线可跑：python tests/run_offline.py
"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from tools.nas_probe_real import fill_segment_template, expand_timeline  # noqa: E402

import xml.etree.ElementTree as ET   # noqa: E402


class TestExpandTimeline(unittest.TestCase):
    def _tl(self, xml):
        return ET.fromstring(xml)

    def test_basic_repeat_and_inherited_t(self):
        tl = self._tl('<SegmentTimeline>'
                      '<S t="100" d="10" r="2"/>'
                      '<S d="10"/>'
                      '<S t="200" d="5"/>'
                      '</SegmentTimeline>')
        # 100,110,120 (r=2) -> cur=130；第二条承继 t=130,d=10 -> 130；第三条 t=200
        self.assertEqual(expand_timeline(tl, 0), [100, 110, 120, 130, 200])

    def test_duration_fallback_from_template(self):
        tl = self._tl('<SegmentTimeline><S t="50"/></SegmentTimeline>')
        self.assertEqual(expand_timeline(tl, 7), [50])   # @d 缺省 -> template@duration=7

    def test_none_and_empty(self):
        self.assertEqual(expand_timeline(None, 0), [])
        self.assertEqual(expand_timeline(self._tl('<SegmentTimeline/>'), 0), [])


class TestFillSegmentTemplate(unittest.TestCase):
    def test_time_token_uses_first_s_t(self):
        # 真源实测模板形态：$RepresentationID$/D$Time$.cmfv（Period BaseURL '6/' 另算）
        self.assertEqual(
            fill_segment_template('$RepresentationID$/D$Time$.cmfv', 'v5000000', 1, 0),
            'v5000000/D0.cmfv')
        self.assertEqual(
            fill_segment_template('$RepresentationID$/D$Time$.cmfv', 'v5000000', 1, 123456),
            'v5000000/D123456.cmfv')

    def test_number_token(self):
        self.assertEqual(fill_segment_template('seg-$Number$.m4s', 'r', 5, 0), 'seg-5.m4s')
        self.assertEqual(fill_segment_template('$Number$.mp4', 'r', 42, 0), '42.mp4')

    def test_both_tokens(self):
        got = fill_segment_template('$RepresentationID$/$Time$-$Number$.m4s', 'v1', 3, 999)
        self.assertEqual(got, 'v1/999-3.m4s')

    def test_zero_padded_forms(self):
        self.assertEqual(fill_segment_template('$Time%05d$.m4s', 'r', 1, 12345), '12345.m4s')
        self.assertEqual(fill_segment_template('img$Number%03d$.jpg', '', 7, 0), 'img007.jpg')

    def test_init_template_and_empty(self):
        self.assertEqual(fill_segment_template('$RepresentationID$/init.cmfv', 'v9', 1, 0),
                         'v9/init.cmfv')
        self.assertEqual(fill_segment_template(None, 'v9'), '')
        self.assertEqual(fill_segment_template('', 'v9'), '')

    def test_no_leftover_placeholder(self):
        for tmpl in ('$RepresentationID$/D$Time$.cmfv', 'seg-$Number$.m4s',
                     '$Time%05d$-$Number%03d$.m4s'):
            out = fill_segment_template(tmpl, 'v1', 2, 98765)
            self.assertNotIn('$', out, out)


if __name__ == '__main__':
    unittest.main()
