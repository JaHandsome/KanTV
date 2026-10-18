# -*- coding: utf-8 -*-
"""P0 修复回归测试（2026-09-07 优化迭代第一批）。

保护五项 P0 修复的行为：
1. 僵尸模式防御：settings.schedule 非法类型时 _period() 回退默认、调度线程不死；
2. verify 成功后立即触发 rebuild_lineups（失败则留给下一轮探活兜底）；
3. ingest 批量化：计数语义 / 同批去重 / 黑名单入库 / last_seen 刷新 / 分块 IN；
4. transaction() 原子回滚 + PRAGMA synchronous=NORMAL 生效；
5. 探活只针对目标频道（channel_key != ''），排除非目标频道与 candidate。

全部离线可跑：python tests/run_offline.py
"""
import json
import os
import shutil
import sys
import tempfile
import time
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config            # noqa: E402
from app.db import Database, utcnow      # noqa: E402
from app.discovery import SourceDiscovery  # noqa: E402
from app.health import HealthMonitor     # noqa: E402
from app.net import NetClient            # noqa: E402
from app.parser import Entry             # noqa: E402
from app.scheduler import Scheduler      # noqa: E402


def make_root(schedule_override=None):
    tmp = tempfile.mkdtemp(prefix='iptv-p0-fixes-')
    write_test_config(tmp)
    if schedule_override is not None:
        p = os.path.join(tmp, 'config', 'settings.json')
        with open(p, 'r', encoding='utf-8') as fh:
            s = json.load(fh)
        s['schedule'] = schedule_override
        with open(p, 'w', encoding='utf-8') as fh:
            json.dump(s, fh)
    return tmp


class FakeComp(object):
    """离线假组件：避免调度线程触网。"""

    def __init__(self, calls=None, name=None, err=None):
        self.calls = calls if calls is not None else []
        self.name = name
        self.err = err

    def run(self):
        if self.err:
            raise self.err
        if self.name:
            self.calls.append(self.name)
        return {'fake': True}

    def build(self):
        return self.run()

    def rebuild_lineups(self):
        self.calls.append('rebuild_lineups')


class TestZombieModeDefense(unittest.TestCase):
    """P0-1：坏配置不得杀死调度线程（QA 曾复现 2 秒内死亡的缺陷）。"""

    def test_schedule_wrong_type_falls_back(self):
        tmp = make_root(schedule_override=['oops'])   # 合法 JSON、非法类型
        try:
            s = Scheduler(tmp)
            self.assertEqual(s._period('probe'), 30 * 60.0)
            self.assertEqual(s._period('verify'), 2 * 3600.0)     # 回退代码默认
            self.assertEqual(s._period('discovery'), 6 * 3600.0)
            self.assertTrue(0 < s._sleep_seconds() <= 30.0)
            s.db.close()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_schedule_bad_value_falls_back(self):
        tmp = make_root(schedule_override={'probe_minutes': 'abc', 'verify_hours': None})
        try:
            s = Scheduler(tmp)
            self.assertEqual(s._period('probe'), 30 * 60.0)
            s.db.close()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_scheduler_thread_survives_bad_config(self):
        tmp = make_root(schedule_override=['oops'])
        try:
            s = Scheduler(tmp)
            # 替换业务组件避免触网（线程存活性验证与业务无关）
            s.health, s.verifier = FakeComp(), FakeComp()
            s.discovery, s.builder = FakeComp(), FakeComp()
            s.start()
            time.sleep(3.0)                       # 原缺陷：2 秒内死亡
            self.assertTrue(s.is_alive(), '调度线程应存活（僵尸模式已修复）')
            s.stop()
            s.join(timeout=5)
            s.db.close()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class TestVerifyTriggersRebuild(unittest.TestCase):
    """P0-3：verify 成功后立即 rebuild_lineups，再重建输出。"""

    def setUp(self):
        self.tmp = make_root()
        self.s = Scheduler(self.tmp)

    def tearDown(self):
        try:
            self.s.db.close()
        except Exception:
            pass
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_rebuild_lineups_after_verify_ok(self):
        calls = []
        self.s.health = FakeComp(calls, 'probe')
        self.s.health.rebuild_lineups = lambda: calls.append('rebuild_lineups')
        self.s.verifier = FakeComp(calls, 'verify')
        self.s.discovery = FakeComp(calls, 'discovery')
        self.s.builder = FakeComp(calls, 'rebuild')
        self.s.last_run = {t: 0.0 for t in self.s.running}
        self.s.tick()
        self.assertIn('rebuild_lineups', calls)
        self.assertLess(calls.index('rebuild_lineups'), calls.index('rebuild'))

    def test_no_rebuild_lineups_when_verify_fails(self):
        calls = []
        self.s.health = FakeComp(calls, 'probe')
        self.s.verifier = FakeComp(err=RuntimeError('verify failed'))
        self.s.discovery = FakeComp(calls, 'discovery')
        self.s.builder = FakeComp(calls, 'rebuild')
        self.s.last_run = {t: 0.0 for t in self.s.running}
        self.s.tick()                              # 异常被吞，不重排
        self.assertNotIn('rebuild_lineups', calls)


class TestIngestBatch(unittest.TestCase):
    """P0-4（discovery 侧）：批量入库语义保持。"""

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.disc = SourceDiscovery(self.cfg, self.db, NetClient(self.cfg))

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    @staticmethod
    def _entry(url, key='CCTV1'):
        return {'url': url, 'name': key, 'channel_key': key, 'status': 'candidate',
                'av': None, 'origin': 'seed', 'score': 10}

    def test_counts_dedup_and_blacklist(self):
        entries = [self._entry('http://a.example/1.m3u8'),
                   self._entry('http://a.example/1.m3u8'),          # 同批重复
                   self._entry('http://39.134.99.99/iptv/CCTV1.m3u8')]  # 黑名单网关
        new, dup, blocked = self.disc.ingest(entries, 'seed')
        self.assertEqual(new, 2)                    # 含黑名单（入库但 status=blacklisted）
        self.assertEqual(blocked, 1)
        rows = self.db.query('SELECT url, status FROM sources ORDER BY id')
        self.assertEqual(len(rows), 2)
        self.assertEqual([r for r in rows if r['status'] == 'blacklisted'][0]['url'],
                         'http://39.134.99.99/iptv/CCTV1.m3u8')

    def test_reingest_refreshes_last_seen(self):
        self.disc.ingest([self._entry('http://a.example/1.m3u8')], 'seed')
        time.sleep(1.1)
        new, dup, _ = self.disc.ingest([self._entry('http://a.example/1.m3u8')], 'seed')
        self.assertEqual((new, dup), (0, 1))
        row = self.db.query_one('SELECT first_seen, last_seen FROM sources')
        self.assertGreater(row['last_seen'], row['first_seen'])

    def test_entry_namedtuple_path(self):
        new, _, _ = self.disc.ingest(
            [Entry('CCTV2', '央视', 'http://b.example/2.m3u8', None)], 'repo:x@main')
        self.assertEqual(new, 1)
        row = self.db.query_one("SELECT channel_key FROM sources WHERE url LIKE '%b.example%'")
        self.assertEqual(row['channel_key'], 'CCTV2')     # 频道归一化生效

    def test_chunked_query_over_500(self):
        big = [self._entry('http://c.example/%d.m3u8' % i) for i in range(1200)]
        new, _, _ = self.disc.ingest(big, 'seed')
        self.assertEqual(new, 1200)
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1200)


class TestTransactionAndPragma(unittest.TestCase):
    """P0-4（db 侧）：原子事务 + synchronous=NORMAL。"""

    def setUp(self):
        self.tmp = make_root()
        self.db = Database(Config(self.tmp).db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_synchronous_normal(self):
        self.assertEqual(self.db.query_one('PRAGMA synchronous')['synchronous'], 1)

    def test_transaction_rollback_atomic(self):
        self.db.execute("INSERT INTO meta(key, value) VALUES ('k1', 'v1')")
        with self.assertRaises(Exception):
            with self.db.transaction() as conn:
                conn.execute("UPDATE meta SET value='v2' WHERE key='k1'")
                conn.execute('INSERT INTO not_exist_table VALUES (1)')
        self.assertEqual(
            self.db.query_one("SELECT value FROM meta WHERE key='k1'")['value'], 'v1')

    def test_transaction_commit(self):
        with self.db.transaction() as conn:
            conn.execute("INSERT INTO meta(key, value) VALUES ('k1', 'v1')")
            conn.execute("INSERT INTO meta(key, value) VALUES ('k2', 'v2')")
        self.assertEqual(self.db.query_one("SELECT COUNT(*) c FROM meta WHERE key LIKE 'k%'")['c'], 2)


class TestProbeChannelFilter(unittest.TestCase):
    """P0-5：探活目标排除非目标频道与 candidate。"""

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        now = utcnow()
        rows = [
            ('http://x/1.m3u8', 'h1', 'CCTV1', 'CCTV1', 'active'),
            ('http://x/2.m3u8', 'h2', '', '', 'active'),        # 非目标频道 active
            ('http://x/3.m3u8', 'h3', 'CCTV2', 'CCTV2', 'flaky'),
            ('http://x/4.m3u8', 'h4', '', '', 'flaky'),         # 非目标频道 flaky
            ('http://x/5.m3u8', 'h5', 'CCTV3', 'CCTV3', 'dead'),
            ('http://x/6.m3u8', 'h6', 'CCTV4', 'CCTV4', 'candidate'),  # 候选不探活
        ]
        self.db.executemany(
            'INSERT INTO sources(url, url_hash, channel_name, channel_key, status,'
            ' first_seen, last_seen) VALUES (?,?,?,?,?,?,?)',
            [(u, h, n, k, s, now, now) for u, h, n, k, s in rows])

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_normal_round_excludes_non_target(self):
        urls = [r['url'] for r in self.db.query(
            "SELECT url FROM sources WHERE status IN ('active','flaky') AND channel_key != ''")]
        self.assertEqual(sorted(urls), ['http://x/1.m3u8', 'http://x/3.m3u8'])

    def test_dead_round_includes_dead_but_excludes_non_target(self):
        urls = [r['url'] for r in self.db.query(
            "SELECT url FROM sources WHERE status IN ('active','flaky','dead') "
            "AND channel_key != ''")]
        self.assertEqual(sorted(urls),
                         ['http://x/1.m3u8', 'http://x/3.m3u8', 'http://x/5.m3u8'])


if __name__ == '__main__':
    unittest.main()
