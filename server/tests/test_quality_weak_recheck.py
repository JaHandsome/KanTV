# -*- coding: utf-8 -*-
"""方案A/C 回归测试（2026-09-25）。

- 方案A：质量弱频道（首发阵容吞吐干净线 <2）获得候选验证预算，三层排序
  数量弱 > 质量弱 > 其他；开关 verify_quality_weak_enabled=False 退回两层。
- 方案C：陈旧拉黑（已不命中现行死源规则）低频复核复出；命中现行规则 /
  空 channel_key / 验证缓存未到期的不复核；fail 保持 blacklisted 并记退避；
  复核 ok 经 record() 转 active 复出。

全部离线可跑：python tests/run_offline.py
"""
import os
import shutil
import sys
import tempfile
import threading
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                     # noqa: E402
from app.db import Database, iso_plus_hours, utcnow  # noqa: E402
from app.verifier import Verifier                 # noqa: E402


def make_root():
    """构造临时项目根（公开元数据与合成 settings，全部离线）。"""
    tmp = tempfile.mkdtemp(prefix='iptv-qw-')
    write_test_config(tmp)
    return tmp


def insert_source(db, url, key='CCTV1', status='candidate', av=None, score=10,
                  profile=None, last_verified=None):
    now = utcnow()
    db.execute(
        'INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,'
        ' av, score, first_seen, last_seen, last_verified, profile)'
        ' VALUES (?,?,?,?,?,?,?,?,?,?,?,?)',
        (url, url, key, key, 'test', status, av, score, now, now, last_verified, profile))
    return db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']


def insert_throughput(db, sid, ratio, ok=1):
    db.execute(
        'INSERT INTO throughput_checks(source_id, ts, seg_duration_ms, download_ms,'
        ' ratio, ok, reason) VALUES (?,?,?,?,?,?,?)',
        (sid, utcnow(), 5000, 1000, ratio, ok, ''))


def make_deliverable_line(db, url, key, score, ratio=None):
    """一条 active+video+可投递 profile 的有效线；ratio 给定时落一条吞吐记录。"""
    sid = insert_source(db, url, key=key, status='active', av='video(TS)',
                        score=score, profile='direct')
    if ratio is not None:
        insert_throughput(db, sid, ratio)
    return sid


class _Base(unittest.TestCase):

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.v = Verifier(self.cfg, self.db, net=None)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)


class TestQualityWeakPriority(_Base):
    """方案A：质量弱频道候选验证排序（三层）。"""

    def _setup_quality_weak_vs_healthy(self):
        # 频道A：3 条有效线但 2 条吞吐脏（干净=1 < 2）-> 质量弱；候选 score 10
        make_deliverable_line(self.db, 'http://x/a1.m3u8', 'CHAN_A', 90, ratio=0.2)
        make_deliverable_line(self.db, 'http://x/a2.m3u8', 'CHAN_A', 80, ratio=1.5)
        make_deliverable_line(self.db, 'http://x/a3.m3u8', 'CHAN_A', 70, ratio=2.0)
        insert_source(self.db, 'http://x/a-cand.m3u8', key='CHAN_A', score=10)
        # 频道B：3 条干净线 -> 健康；候选 score 100
        make_deliverable_line(self.db, 'http://x/b1.m3u8', 'CHAN_B', 90, ratio=0.2)
        make_deliverable_line(self.db, 'http://x/b2.m3u8', 'CHAN_B', 80, ratio=0.3)
        make_deliverable_line(self.db, 'http://x/b3.m3u8', 'CHAN_B', 70, ratio=0.1)
        insert_source(self.db, 'http://x/b-cand.m3u8', key='CHAN_B', score=100)

    def test_quality_weak_beats_healthy(self):
        self._setup_quality_weak_vs_healthy()
        batch = self.v._select_batch(1)
        self.assertEqual(len(batch), 1)
        self.assertEqual(batch[0]['url'], 'http://x/a-cand.m3u8')

    def test_count_weak_still_beats_quality_weak(self):
        self._setup_quality_weak_vs_healthy()
        # 频道C：0 有效线（数量弱），候选 score 仅 5
        insert_source(self.db, 'http://x/c-cand.m3u8', key='CHAN_C', score=5)
        batch = self.v._select_batch(2)
        self.assertEqual([r['url'] for r in batch],
                         ['http://x/c-cand.m3u8', 'http://x/a-cand.m3u8'])

    def test_no_throughput_data_is_not_quality_weak(self):
        # 频道D：3 条有效线、无任何吞吐数据 -> clean=3 -> 健康
        for i in range(3):
            make_deliverable_line(self.db, 'http://x/d%d.m3u8' % i, 'CHAN_D', 50)
        counts = self.v._clean_line_counts({'CHAN_D'})
        self.assertEqual(counts['CHAN_D'], 3)

    def test_toggle_off_falls_back_to_two_tiers(self):
        self._setup_quality_weak_vs_healthy()
        self.cfg.settings['verify_quality_weak_enabled'] = False
        batch = self.v._select_batch(1)
        self.assertEqual(batch[0]['url'], 'http://x/b-cand.m3u8')   # 仅按 score

    def test_failed_probe_counts_dirty(self):
        # ok=0 的吞吐检查按 2.0 罚分计入窗口 -> 该线为脏
        make_deliverable_line(self.db, 'http://x/e1.m3u8', 'CHAN_E', 90, ratio=0.2)
        sid2 = make_deliverable_line(self.db, 'http://x/e2.m3u8', 'CHAN_E', 80)
        sid3 = make_deliverable_line(self.db, 'http://x/e3.m3u8', 'CHAN_E', 70)
        insert_throughput(self.db, sid2, None, ok=0)
        insert_throughput(self.db, sid3, None, ok=0)
        insert_source(self.db, 'http://x/e-cand.m3u8', key='CHAN_E', score=1)
        counts = self.v._clean_line_counts({'CHAN_E'})
        self.assertEqual(counts['CHAN_E'], 1)     # 只有 e1 干净 -> 质量弱


class TestBlacklistRecheck(_Base):
    """方案C：陈旧拉黑复核。"""

    STALE = 'http://203.0.113.9/live/jade.m3u8'        # 不命中任何现行规则
    STALE2 = 'http://203.0.113.10/live/jade2.m3u8'
    RULED = 'http://live.264788.xyz/x.m3u8'            # 命中 rules.json 现行域名

    def test_stale_selected_ruled_and_orphan_skipped(self):
        old = iso_plus_hours(-24 * 30)
        insert_source(self.db, self.STALE, key='TVB翡翠台', status='blacklisted',
                      score=70, last_verified=old)
        insert_source(self.db, self.RULED, key='TVB翡翠台', status='blacklisted',
                      score=60, last_verified=old)
        insert_source(self.db, 'http://203.0.113.11/o.m3u8', key='',
                      status='blacklisted', score=50, last_verified=old)
        picked = self.v._select_blacklist_recheck(5)
        self.assertEqual([r['url'] for r in picked], [self.STALE])

    def test_quota_and_order(self):
        old = iso_plus_hours(-24 * 30)
        insert_source(self.db, self.STALE, key='K1', status='blacklisted',
                      score=10, last_verified=old)
        insert_source(self.db, self.STALE2, key='K2', status='blacklisted',
                      score=70, last_verified=old)
        insert_source(self.db, 'http://203.0.113.12/z.m3u8', key='K3',
                      status='blacklisted', score=50, last_verified=old)
        picked = self.v._select_blacklist_recheck(2)
        self.assertEqual([r['url'] for r in picked], [self.STALE2,
                                                      'http://203.0.113.12/z.m3u8'])

    def test_fresh_last_verified_skipped(self):
        insert_source(self.db, self.STALE, key='K1', status='blacklisted',
                      score=70, last_verified=utcnow())   # 7 天缓存未到期
        self.assertEqual(self.v._select_blacklist_recheck(5), [])

    def test_fail_keeps_blacklisted_with_backoff(self):
        insert_source(self.db, self.STALE, key='K1', status='blacklisted',
                      score=70, last_verified=iso_plus_hours(-24 * 30))
        row = self.db.query_one('SELECT * FROM sources WHERE url=?', (self.STALE,))
        verdict = self.v.record(row, {'alive': False, 'why': 'timeout'}, 1000)
        self.assertEqual(verdict, 'fail')
        cur = self.db.query_one(
            'SELECT status, fail_streak, last_verified FROM sources WHERE url=?',
            (self.STALE,))
        self.assertEqual(cur['status'], 'blacklisted')    # 不因为复核改变身份
        self.assertEqual(cur['fail_streak'], 1)
        self.assertIsNotNone(cur['last_verified'])        # 计入 7 天复核缓存

    def test_ok_revives_to_active(self):
        insert_source(self.db, self.STALE, key='K1', status='blacklisted',
                      score=70, last_verified=iso_plus_hours(-24 * 30))
        row = self.db.query_one('SELECT * FROM sources WHERE url=?', (self.STALE,))
        verdict = self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)',
                                      'container': 'hls', 'encryption': '',
                                      'drm': '', 'key_present': False}, 50)
        self.assertEqual(verdict, 'ok')
        cur = self.db.query_one('SELECT status, profile FROM sources WHERE url=?',
                                (self.STALE,))
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'direct')


class ProbeNet(object):
    """离线假网络：未登记的 URL 抛异常（=> 验证 fail）。"""

    ua = 'test-ua'

    def __init__(self):
        self.fetched = []
        self.lock = threading.Lock()

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        with self.lock:
            self.fetched.append((url, limit))
        raise OSError('no route: %s' % url)


class TestRecheckIntegration(_Base):
    """方案C 与 run() 的集成：复核配额被追加进批次并真正探测。"""

    def test_run_appends_recheck_and_probes(self):
        old = iso_plus_hours(-24 * 30)
        insert_source(self.db, TestBlacklistRecheck.STALE, key='K1',
                      status='blacklisted', score=70, last_verified=old)
        net = ProbeNet()
        self.v.net = net
        stats = self.v.run()
        self.assertEqual(stats['batch'], 1)               # 无候选，只有复核 1 条
        self.assertIn((TestBlacklistRecheck.STALE, 393216),
                      [(u, l) for u, l in net.fetched])
        cur = self.db.query_one('SELECT status FROM sources WHERE url=?',
                                (TestBlacklistRecheck.STALE,))
        self.assertEqual(cur['status'], 'blacklisted')    # fail 后保持

    def test_run_skips_recheck_when_disabled(self):
        old = iso_plus_hours(-24 * 30)
        insert_source(self.db, TestBlacklistRecheck.STALE, key='K1',
                      status='blacklisted', score=70, last_verified=old)
        self.cfg.settings['blacklist_recheck_enabled'] = False
        net = ProbeNet()
        self.v.net = net
        stats = self.v.run()
        self.assertEqual(stats['batch'], 0)
        self.assertEqual(net.fetched, [])


if __name__ == '__main__':
    unittest.main()
