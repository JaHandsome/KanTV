# -*- coding: utf-8 -*-
"""db 单测：建表/迁移幂等/去重/清理（临时目录，离线）。"""
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.db import SCHEMA_VERSION, Database, utcnow  # noqa: E402

EXPECTED_TABLES = {'meta', 'sources', 'repos', 'verifications',
                   'health_checks', 'health_events', 'lineups'}


class TestDb(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-nas-test-')
        self.path = os.path.join(self.tmp, 'test.db')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _db(self):
        db = Database(self.path)
        db.migrate()
        return db

    def test_migrate_creates_all_tables(self):
        db = self._db()
        rows = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        tables = {r['name'] for r in rows} - {'sqlite_sequence'}
        self.assertTrue(EXPECTED_TABLES.issubset(tables))
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))   # v9（含 v5/v6/v7/v9）
        db.close()

    def test_migrate_idempotent_keeps_data(self):
        """二次启动不报错、数据保留（升级保留数据机制）。"""
        db = self._db()
        now = utcnow()
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " score, first_seen, last_seen) VALUES ('http://a/1','h1','n','CCTV1','','candidate',10,?,?)",
            (now, now))
        db.close()
        # 模拟重启：再次 migrate
        db2 = Database(self.path)
        db2.migrate()
        row = db2.query_one("SELECT * FROM sources WHERE url_hash='h1'")
        self.assertIsNotNone(row)
        self.assertEqual(row['channel_key'], 'CCTV1')
        self.assertEqual(db2.get_meta('schema_version'), str(SCHEMA_VERSION))  # 迁移到当前版本
        db2.close()

    def test_url_hash_unique_and_ignore(self):
        db = self._db()
        now = utcnow()
        sql = ("INSERT OR IGNORE INTO sources(url, url_hash, channel_name, channel_key, origin,"
               " status, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,10,?,?)")
        db.execute(sql, ('http://a/1', 'h1', 'n', 'CCTV1', '', 'candidate', now, now))
        db.execute(sql, ('http://a/1', 'h1', 'n', 'CCTV1', '', 'candidate', now, now))  # 重复
        cnt = db.query_one('SELECT COUNT(*) AS c FROM sources')['c']
        self.assertEqual(cnt, 1)
        db.close()

    def test_cleanup_old_rows(self):
        db = self._db()
        now = utcnow()
        old = '2020-01-01T00:00:00Z'
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " score, first_seen, last_seen) VALUES ('http://a/1','h1','n','CCTV1','','active',10,?,?)",
            (now, now))
        sid = db.query_one("SELECT id FROM sources WHERE url_hash='h1'")['id']
        db.execute('INSERT INTO health_checks(source_id, ts, alive) VALUES (?,?,1)', (sid, old))
        db.execute('INSERT INTO health_events(source_id, ts, event) VALUES (?,?,?)',
                   (sid, old, 'offline'))
        db.execute('INSERT INTO verifications(source_id, ts, result) VALUES (?,?,?)',
                   (sid, old, 'fail'))
        db.cleanup(retention_days=90)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM health_checks')['c'], 0)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM health_events')['c'], 0)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM verifications')['c'], 0)
        # sources 本体不受清理影响
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM sources')['c'], 1)
        db.close()

    def test_cleanup_checks_retention_independent(self):
        """P1-I：health_checks 独立保留期（7 天），不再与 90 天历史保留期共用。"""
        import datetime

        def ago(days):
            return (datetime.datetime.now(datetime.timezone.utc)
                    - datetime.timedelta(days=days)).strftime('%Y-%m-%dT%H:%M:%SZ')

        db = self._db()
        now = utcnow()
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " score, first_seen, last_seen) VALUES ('http://a/1','h1','n','CCTV1','','active',10,?,?)",
            (now, now))
        sid = db.query_one("SELECT id FROM sources WHERE url_hash='h1'")['id']
        db.execute('INSERT INTO health_checks(source_id, ts, alive) VALUES (?,?,1)',
                   (sid, ago(3)))
        db.execute('INSERT INTO health_checks(source_id, ts, alive) VALUES (?,?,1)',
                   (sid, ago(10)))
        db.execute('INSERT INTO verifications(source_id, ts, result) VALUES (?,?,?)',
                   (sid, ago(10), 'ok'))
        db.cleanup(retention_days=90, checks_retention_days=7)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM health_checks')['c'], 1)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM verifications')['c'], 1)
        db.close()

    def test_cleanup_orphan_sources(self):
        """P1-J：channel_key 为空的孤儿源按 orphan_retention_days 回收。"""
        import datetime

        def ago(days):
            return (datetime.datetime.now(datetime.timezone.utc)
                    - datetime.timedelta(days=days)).strftime('%Y-%m-%dT%H:%M:%SZ')

        db = self._db()
        rows = [
            ('h1', '', 'candidate', ago(40)),     # 孤儿 + 陈旧 -> 删
            ('h2', '', 'blacklisted', ago(40)),   # 孤儿 + 陈旧 -> 删
            ('h3', '', 'candidate', ago(5)),      # 孤儿 + 近期 -> 保留
            ('h4', 'CCTV1', 'active', ago(40)),   # 非孤儿 -> 保留
        ]
        for h, key, st, ls in rows:
            db.execute(
                "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
                " score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?)",
                ('http://a/' + h, h, 'n', key, '', st, 10, ls, ls))
        db.cleanup(retention_days=90, dead_retention_days=0, orphan_retention_days=30)
        remain = {r['url_hash'] for r in db.query('SELECT url_hash FROM sources')}
        self.assertEqual(remain, {'h3', 'h4'})
        db.close()

    def test_cleanup_hub_tables_retention(self):
        """ARCH P2-6：playback_reports / hub_notices 的 90 天裁剪（cleanup 的 c6/c7 分支）
        此前从未实跑过，本测试钉死：超期行删除、未超期行保留。"""
        import datetime

        def ago(days):
            return (datetime.datetime.now(datetime.timezone.utc)
                    - datetime.timedelta(days=days)).strftime('%Y-%m-%dT%H:%M:%SZ')

        db = self._db()
        db.execute('INSERT INTO playback_reports(reported_at, client) VALUES (?,?)',
                   (ago(100), 'kantv'))
        db.execute('INSERT INTO playback_reports(reported_at, client) VALUES (?,?)',
                   (ago(10), 'kantv'))
        db.execute('INSERT INTO hub_notices(reported_at, code) VALUES (?,?)',
                   (ago(100), 'schema_unsupported'))
        db.execute('INSERT INTO hub_notices(reported_at, code) VALUES (?,?)',
                   (ago(10), 'schema_unsupported'))
        db.cleanup(retention_days=90)
        self.assertEqual(
            db.query_one('SELECT COUNT(*) AS c FROM playback_reports')['c'], 1)
        self.assertEqual(
            db.query_one('SELECT COUNT(*) AS c FROM hub_notices')['c'], 1)
        db.close()

    def test_lineups_pk(self):
        db = self._db()
        now = utcnow()
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " score, first_seen, last_seen) VALUES ('http://a/1','h1','n','CCTV1','','active',10,?,?)",
            (now, now))
        sid = db.query_one("SELECT id FROM sources WHERE url_hash='h1'")['id']
        db.execute('INSERT INTO lineups(channel_key, source_id, rank, updated_at) VALUES (?,?,0,?)',
                   ('CCTV1', sid, now))
        with self.assertRaises(Exception):
            db.execute('INSERT INTO lineups(channel_key, source_id, rank, updated_at) VALUES (?,?,1,?)',
                       ('CCTV1', sid, now))  # PK 冲突
        db.close()


if __name__ == '__main__':
    unittest.main()
