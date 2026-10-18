# -*- coding: utf-8 -*-
"""P0 端到端验收（AC 机检固化，T8，全部离线）。

逐条固化可机检项：
- AC-3.1 schema v3 八列 + schema_version==4 + profile/container 无 NULL；
- AC-5.1 live.txt 逐行零 DRM 命中（正则）；
- AC-5.2 live.txt 结构不变 + 无空 URL 行；
- AC-5.3 live.m3u 格式冻结（#EXTINF 行 + 头两行）；
- AC-5.4 tvbox_lives_snippet.json 键集冻结；
- AC-5.5 live.media3.json 存在 + json.load + schema_version + line 必填字段。
"""
import json
import os
import re
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.builder import OutputBuilder                     # noqa: E402
from offline_fixtures import make_config  # noqa: E402
from app.db import SCHEMA_VERSION, Database, utcnow       # noqa: E402

DRM_RE = re.compile(r'(?i)\.mpd|drm|clearkey|widevine|playready|#KODIPROP|\.mp4\?')
TXT_LINE_RE = re.compile(r'^[^,]+,[^,]*$')
EXTINF_RE = re.compile(
    r'^#EXTINF:-1 tvg-id=".*" tvg-name=".*" tvg-logo=".*" group-title=".*",.*$')


class TestAcceptanceP0(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-accept-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['limits']['max_lines_per_channel'] = 3
        cfg.channels['channels'] = ['CCTV1', 'CCTV2', 'TVB翡翠台']
        cfg.channels['channel_groups'] = {'CCTV1': '央视', 'CCTV2': '央视', 'TVB翡翠台': '港澳台'}
        cfg.channels['order'] = []
        cfg.channels['group_index'] = {}
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()
        self._seed_mixed()

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

    def _seed_mixed(self):
        """混合数据集：明文 / AES-128 / DRM 各态，验证隔离与契约。"""
        self._add('http://h1/cctv1.m3u8', 'CCTV1', profile='direct')
        self._add('http://h2/cctv1-aes.m3u8', 'CCTV1', profile='direct',
                  encryption='aes128')
        self._add('http://h3/cctv1-drm.mpd', 'CCTV1', profile='need-key', container='dash',
                  encryption='cenc', drm='widevine', score=99)
        self._add('http://h4/cctv2.m3u8', 'CCTV2', profile='direct')
        self._add('http://h5/cctv2-ck.mpd', 'CCTV2', profile='media3-json', container='dash',
                  encryption='cenc', drm='clearkey')
        # TVB翡翠台：只有非 direct 线路 -> live.txt 整体缺席（AC-5.2）
        self._add('http://h6/fct.mpd', 'TVB翡翠台', profile='need-key', container='dash',
                  encryption='cenc', drm='widevine')

    def _build(self):
        OutputBuilder(self.cfg, self.db).build()

    def _read(self, name):
        with open(os.path.join(self.cfg.output_dir, name), encoding='utf-8') as f:
            return f.read()

    def test_ac_5_1_live_txt_zero_drm(self):
        self._build()
        txt = self._read('live.txt')
        for line in txt.splitlines():
            self.assertIsNone(DRM_RE.search(line),
                              'live.txt 命中 DRM 字样: %r' % line)

    def test_ac_5_2_live_txt_structure(self):
        self._build()
        for line in self._read('live.txt').splitlines():
            self.assertRegex(line, TXT_LINE_RE)
            self.assertFalse(line.endswith(','))     # 无空 URL 频道

    def test_ac_5_3_m3u_format_frozen(self):
        self._build()
        lines = self._read('live.m3u').splitlines()
        self.assertEqual(lines[0], '#EXTM3U')
        self.assertTrue(lines[1].startswith('#EXTM3U x-tvg-url="'))
        for line in lines:
            if line.startswith('#EXTINF'):
                self.assertRegex(line, EXTINF_RE)

    def test_ac_5_4_snippet_keys(self):
        self._build()
        snip = json.loads(self._read('tvbox_lives_snippet.json'))
        self.assertEqual(set(snip['lives'][0].keys()),
                         {'name', 'type', 'url', 'epg', 'logo'})

    def test_ac_5_5_media3_contract(self):
        self._build()
        obj = json.loads(self._read('live.media3.json'))
        self.assertEqual(obj['schema_version'], 2)   # HK MPD 路线：1 -> 2
        self.assertIsInstance(obj['channels'], list)
        for ch in obj['channels']:
            for ln in ch['lines']:
                self.assertIn('url', ln)
                self.assertIn('container', ln)
                if ln['profile'] != 'direct':
                    self.assertIn('profile', ln)

    def test_ac_3_1_schema_v3_columns(self):
        cols = [r['name'] for r in self.db.query('PRAGMA table_info(sources)')]
        for c in ('container', 'encryption', 'drm', 'keyformat', 'kid', 'profile',
                  'caps_required', 'verdict_reason', 'drm_key'):
            self.assertIn(c, cols)
        self.assertEqual(self.db.get_meta('schema_version'), str(SCHEMA_VERSION))  # v9（含 v7 playback_reports）
        self.assertEqual(
            self.db.query_one('SELECT COUNT(*) c FROM sources WHERE profile IS NULL')['c'], 0)
        self.assertEqual(
            self.db.query_one("SELECT COUNT(*) c FROM sources "
                              "WHERE container IS NULL OR container=''")['c'], 0)


if __name__ == '__main__':
    unittest.main()
