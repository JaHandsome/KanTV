# -*- coding: utf-8 -*-
"""回归：record() 非 ok 分支不得清空/篡改清单写入的 DRM 身份（drm/kid）。

自包含（不依赖 QA 的 test_qa2_independent / tools/_qa2_*）：构造"库中已有
drm='clearkey'/kid，探测返回不含 drm/kid 的失败结果"，断言保留；并断言下一轮
_select_batch 拿到的 expect_drm 仍是 'clearkey'（不漂移成 widevine）。
"""
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import KID_HEX, LICENSE  # noqa: E402
from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                                # noqa: E402
from app.db import Database, utcnow                          # noqa: E402
from app.verifier import CLEARKEY_KEYFORMAT, Verifier         # noqa: E402

OTHER_KID_HEX = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
URL = 'http://fixture-40.example.invalid/__cl/slocalr43/__c/ott_J_hevc/__f/index.mpd'


def _copy_config(root):
    write_test_config(root)


class TestRecordDrmProvenance(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-prov-')
        _copy_config(self.tmp)
        self.cfg = Config(self.tmp)
        # 让 record 后该行仍落在 _select_batch 的缓存窗口内（cutoff 置于未来）
        self.cfg.settings['verify_cache_hours'] = -1
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        now = utcnow()
        self.db.execute(
            'INSERT INTO sources(url, url_hash, url_key, channel_name, channel_key, origin,'
            ' status, av, score, first_seen, last_seen, container, encryption, drm, keyformat,'
            ' kid, profile, drm_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
            (URL, 'h1', 'k1', '無線新聞台', '无线新闻', 'urllist:tvb',
             'candidate', None, 10, now, now, 'dash', 'cenc', 'clearkey',
             CLEARKEY_KEYFORMAT, KID_HEX, 'direct', LICENSE))
        self.sid = self.db.query_one('SELECT id FROM sources WHERE url_hash=?', ('h1',))['id']
        self.v = Verifier(self.cfg, self.db, None)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _row(self):
        return self.db.query_one('SELECT * FROM sources WHERE id=?', (self.sid,))

    def test_transient_failure_preserves_manifest_drm_and_kid(self):
        """瞬时失败结果不含 drm/kid -> record 后库中清单身份原值保留。"""
        res = {'alive': False, 'why': 'OSError', 'container': 'dash', 'type': 'DASH'}
        verdict = self.v.record(self._row(), res, 1)
        self.assertEqual(verdict, 'fail')
        after = self._row()
        self.assertEqual(after['drm'], 'clearkey')
        self.assertEqual(after['kid'], KID_HEX)
        self.assertEqual(after['drm_key'], LICENSE)      # drm_key 任何分支都不写
        # 下一轮：_select_batch 取到的行 expect_drm 仍为 clearkey（不漂移成 widevine）
        batch = self.v._select_batch(50)
        hit = [r for r in batch if r['id'] == self.sid]
        self.assertEqual(len(hit), 1, '该源应仍在 _select_batch 中')
        self.assertEqual(hit[0]['drm'], 'clearkey')
        self.assertEqual(hit[0]['kid'], KID_HEX)

    def test_abnormal_kid_mismatch_keeps_manifest_identity(self):
        """kid_mismatch（结果带 MPD 的 kid）不得用 MPD kid 覆盖清单 kid。"""
        res = {'alive': False, 'why': 'kid不一致', 'verdict_reason': 'kid_mismatch',
               'container': 'dash', 'type': 'DASH', 'encryption': 'cenc', 'kid': OTHER_KID_HEX}
        self.v.record(self._row(), res, 1)
        after = self._row()
        self.assertEqual(after['drm'], 'clearkey')       # 保留清单 drm
        self.assertEqual(after['kid'], KID_HEX)          # 不被 MPD 的 kid 篡改
        self.assertEqual(after['drm_key'], LICENSE)

    def test_static_vod_preserves_identity(self):
        """static(点播) 的 abnormal 结果不带 drm -> 清单身份保留。"""
        res = {'alive': False, 'why': '点播(static)', 'verdict_reason': 'vod(static)',
               'container': 'dash', 'type': 'DASH'}
        self.v.record(self._row(), res, 1)
        after = self._row()
        self.assertEqual(after['drm'], 'clearkey')
        self.assertEqual(after['kid'], KID_HEX)

    def test_fail_preserves_container_encryption_keyformat(self):
        """同类面：container/encryption/keyformat 也不被空结果覆盖。"""
        res = {'alive': False, 'why': 'URLError', 'type': 'DASH'}
        self.v.record(self._row(), res, 1)
        after = self._row()
        self.assertEqual(after['container'], 'dash')
        self.assertEqual(after['encryption'], 'cenc')
        self.assertEqual(after['keyformat'], CLEARKEY_KEYFORMAT)

    def test_ok_branch_still_writes_probe_values(self):
        """ok 分支照常写探测值（result 优先）。"""
        res = {'alive': True, 'container': 'dash', 'type': 'DASH', 'encryption': 'cenc',
               'drm': 'clearkey', 'keyformat': CLEARKEY_KEYFORMAT, 'kid': KID_HEX,
               'key_present': True, 'av': 'video'}
        verdict = self.v.record(self._row(), res, 1)
        self.assertEqual(verdict, 'ok')
        after = self._row()
        self.assertEqual(after['drm'], 'clearkey')
        self.assertEqual(after['kid'], KID_HEX)
        self.assertEqual(after['profile'], 'media3-json')
        self.assertEqual(after['drm_key'], LICENSE)


if __name__ == '__main__':
    unittest.main()
