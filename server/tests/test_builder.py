# -*- coding: utf-8 -*-
"""builder 单测：输出格式与 iptv_pipeline.build() 逐行兼容（固定 sqlite 样例数据）。"""
import json
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.builder import OutputBuilder  # noqa: E402
from offline_fixtures import make_config  # noqa: E402
from app.db import Database, utcnow    # noqa: E402


class TestBuilder(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-nas-build-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['http']['snippet_host'] = '192.168.1.10'
        cfg.settings['http']['port'] = 8420
        cfg.settings['limits']['max_lines_per_channel'] = 3
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _add_source(self, url, h, key, status='active', av='video', score=10):
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (url, h, key, key, 'test', status, av, score, now, now))
        return self.db.query_one('SELECT id FROM sources WHERE url_hash=?', (h,))['id']

    def _add_lineup(self, key, sid, rank):
        self.db.execute(
            'INSERT INTO lineups(channel_key, source_id, rank, updated_at) VALUES (?,?,?,?)',
            (key, sid, rank, utcnow()))

    def test_output_format_compatible(self):
        """2 频道 3 线路 -> live.txt/live.m3u 与旧脚本格式逐行一致。"""
        # CCTV1: 2 条线路（rank 顺序即 '#' 拼接顺序）
        s1 = self._add_source('http://h80/cctv1', 'h1', 'CCTV1', score=80)
        s2 = self._add_source('http://h70/cctv1', 'h2', 'CCTV1', score=70)
        self._add_lineup('CCTV1', s2, 0)
        self._add_lineup('CCTV1', s1, 1)
        # TVB翡翠台: 1 条线路（EPG 名映射为 翡翠台）
        s3 = self._add_source('http://x/fct', 'h3', 'TVB翡翠台', score=70)
        self._add_lineup('TVB翡翠台', s3, 0)

        result = OutputBuilder(self.cfg, self.db).build()
        self.assertEqual(result['channels'], 2)

        with open(os.path.join(self.cfg.output_dir, 'live.txt'), encoding='utf-8') as f:
            txt = f.read()
        expected_txt = (
            '📺央视CCTV,#genre#\n'
            'CCTV1,http://h70/cctv1#http://h80/cctv1\n'
            '🌊港澳台,#genre#\n'
            'TVB翡翠台,http://x/fct\n')
        self.assertEqual(txt, expected_txt)

        with open(os.path.join(self.cfg.output_dir, 'live.m3u'), encoding='utf-8') as f:
            m3u = f.read()
        expected_m3u = (
            '#EXTM3U\n'
            '#EXTM3U x-tvg-url="http://epg.51zmt.top:8000/e.xml"\n'
            '#EXTINF:-1 tvg-id="CCTV1" tvg-name="CCTV1" '
            'tvg-logo="https://gitee.com/suxuang/logo/raw/master/mylogo/CCTV1.png" '
            'group-title="📺央视CCTV",CCTV1\n'
            'http://h70/cctv1\n'
            '#EXTINF:-1 tvg-id="翡翠台" tvg-name="翡翠台" '
            'tvg-logo="https://gitee.com/suxuang/logo/raw/master/mylogo/翡翠台.png" '
            'group-title="🌊港澳台",TVB翡翠台\n'
            'http://x/fct\n')
        self.assertEqual(m3u, expected_m3u)

        with open(os.path.join(self.cfg.output_dir, 'tvbox_lives_snippet.json'),
                  encoding='utf-8') as f:
            snippet = json.load(f)
        self.assertEqual(snippet['lives'][0]['url'], 'http://192.168.1.10:8420/live.txt')
        self.assertEqual(snippet['lives'][0]['name'], 'NAS精选直播')
        self.assertEqual(snippet['lives'][0]['type'], 0)

    def test_group_order_and_fallback(self):
        """分组顺序 央视->卫视->广东->港澳台；无 lineups 时回退直查 sources；dead 不入输出。"""
        self._add_source('http://x/cctv1', 'a1', 'CCTV1', score=10)
        self._add_source('http://x/hnw', 'a2', '湖南卫视', score=10)      # 无 lineups -> 回退
        self._add_source('http://x/gdzj', 'a3', '广东珠江', score=10)     # 无 lineups -> 回退
        self._add_source('http://x/fct', 'a4', 'TVB翡翠台', score=10)     # 无 lineups -> 回退
        self._add_source('http://x/dead', 'a5', 'CCTV1', status='dead')   # dead 排除
        self._add_source('http://x/aud', 'a6', 'CCTV1', av='audio-only(TS:MP3)')  # 纯音频排除

        OutputBuilder(self.cfg, self.db).build()
        with open(os.path.join(self.cfg.output_dir, 'live.txt'), encoding='utf-8') as f:
            lines = f.read().splitlines()
        groups = [l.split(',#genre#')[0] for l in lines if l.endswith(',#genre#')]
        self.assertEqual(groups, ['📺央视CCTV', '📶省级卫视', '☘️广东频道', '🌊港澳台'])
        chans = [l.split(',')[0] for l in lines if not l.endswith(',#genre#')]
        self.assertEqual(chans, ['CCTV1', '湖南卫视', '广东珠江', 'TVB翡翠台'])
        self.assertNotIn('http://x/dead', '\n'.join(lines))

    def test_lineups过滤dead与音频源live_txt不含(self):
        """lineups JOIN 路径补 status/av 过滤（P2-3）：lineups 混入 dead/音频源时
        live.txt/live.m3u 输出不含它们（投递窗口期同口径修复，见 0c70114）。"""
        sd = self._add_source('http://x/dead', 'f1', 'CCTV1', status='dead', score=99)
        sa = self._add_source('http://x/aud', 'f2', 'CCTV1', av='audio(AAC音频)', score=98)
        sok = self._add_source('http://x/ok', 'f3', 'CCTV1', score=10)
        self._add_lineup('CCTV1', sd, 0)
        self._add_lineup('CCTV1', sa, 1)
        self._add_lineup('CCTV1', sok, 2)

        OutputBuilder(self.cfg, self.db).build()
        with open(os.path.join(self.cfg.output_dir, 'live.txt'), encoding='utf-8') as f:
            txt = f.read()
        self.assertEqual(txt, '📺央视CCTV,#genre#\nCCTV1,http://x/ok\n')
        with open(os.path.join(self.cfg.output_dir, 'live.m3u'), encoding='utf-8') as f:
            m3u = f.read()
        self.assertNotIn('http://x/dead', m3u)
        self.assertNotIn('http://x/aud', m3u)
        self.assertIn('http://x/ok', m3u)

    def test_lineups全被status_av过滤时回退sources抓活线(self):
        """窗口期退化场景：lineups 里全是 dead/音频源 -> 过滤后无合格 lineups
        必须回退 sources（同口径谓词），把仍 active+video 的源写进 live.txt。"""
        sd = self._add_source('http://x/dead', 'g1', 'CCTV1', status='dead', score=99)
        sa = self._add_source('http://x/aud', 'g2', 'CCTV1', av='audio(AAC音频)', score=98)
        self._add_lineup('CCTV1', sd, 0)
        self._add_lineup('CCTV1', sa, 1)
        self._add_source('http://x/live', 'g3', 'CCTV1', score=10)

        OutputBuilder(self.cfg, self.db).build()
        with open(os.path.join(self.cfg.output_dir, 'live.txt'), encoding='utf-8') as f:
            txt = f.read()
        self.assertEqual(txt, '📺央视CCTV,#genre#\nCCTV1,http://x/live\n')

    def test_fallback路径status_av过滤行为不变(self):
        """回归锁：回退直查 sources 的 status/av 谓词不因 JOIN 路径修复而改变。"""
        self._add_source('http://x/dead', 'h1', 'CCTV1', status='dead', score=99)
        self._add_source('http://x/aud', 'h2', 'CCTV1', av='audio(AAC音频)', score=98)
        self._add_source('http://x/ok', 'h3', 'CCTV1', score=10)

        OutputBuilder(self.cfg, self.db).build()
        with open(os.path.join(self.cfg.output_dir, 'live.txt'), encoding='utf-8') as f:
            txt = f.read()
        self.assertNotIn('http://x/dead', txt)
        self.assertNotIn('http://x/aud', txt)
        self.assertIn('http://x/ok', txt)

    def test_atomic_write(self):
        """写 tmp 阶段不产生残留 .tmp，替换后内容完整。"""
        path = os.path.join(self.tmp, 'out', 'live.txt')
        OutputBuilder.atomic_write(path, 'A' * 10)
        OutputBuilder.atomic_write(path, 'B' * 20)
        with open(path, encoding='utf-8') as f:
            self.assertEqual(f.read(), 'B' * 20)
        self.assertEqual([f for f in os.listdir(os.path.dirname(path)) if f.endswith('.tmp')], [])

    def test_atomic_write_retries_permission_error(self):
        """P2-1 回归：Windows 并发读占用时 PermissionError 延迟重试 3 次。"""
        from unittest import mock
        path = os.path.join(self.tmp, 'out2', 'live.txt')
        real_replace = os.replace
        calls = []

        def flaky_replace(src, dst):
            calls.append(1)
            if len(calls) < 3:
                raise PermissionError(13, 'Permission denied')   # 模拟读占用
            real_replace(src, dst)

        with mock.patch('app.builder.os.replace', side_effect=flaky_replace):
            OutputBuilder.atomic_write(path, 'RETRY_OK')
        with open(path, encoding='utf-8') as f:
            self.assertEqual(f.read(), 'RETRY_OK')
        self.assertEqual(len(calls), 3)   # 前两次被占用, 第 3 次成功

        # 持续被占用: 重试 3 次后抛 PermissionError（调度器下轮重建兜底）
        with mock.patch('app.builder.os.replace', side_effect=PermissionError(13, 'busy')):
            with self.assertRaises(PermissionError):
                OutputBuilder.atomic_write(path, 'SHOULD_FAIL')

        # 非 PermissionError 的 OSError（如 disk full）不重试, 立即抛出
        with mock.patch('app.builder.os.replace', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                OutputBuilder.atomic_write(path, 'SHOULD_FAIL2')

    def test_max_lines(self):
        """max_lines_per_channel 限制每频道线路数。"""
        for i in range(5):
            sid = self._add_source('http://x/cctv1-%d' % i, 'm%d' % i, 'CCTV1', score=100 - i)
            self._add_lineup('CCTV1', sid, i)
        OutputBuilder(self.cfg, self.db).build()
        with open(os.path.join(self.cfg.output_dir, 'live.txt'), encoding='utf-8') as f:
            line = [l for l in f.read().splitlines() if l.startswith('CCTV1,')][0]
        self.assertEqual(len(line.split(',', 1)[1].split('#')), 3)


if __name__ == '__main__':
    unittest.main()
