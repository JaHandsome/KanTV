# -*- coding: utf-8 -*-
"""db 在线备份单测：WAL 一致性快照 / quick_check / 原子发布与失败清理（临时目录，离线）。

覆盖：已提交但未 checkpoint 的 WAL 行进入备份、quick_check=ok、
源库文件与源 WAL 未被主动改写、覆盖目标原子成功、失败保留旧目标且无临时残留、
源/目标同真实路径拒绝。
"""
import hashlib
import os
import shutil
import sqlite3
import sys
import tempfile
import threading
import unittest
from unittest import mock

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.db import Database, utcnow, online_backup, _quick_check_ok  # noqa: E402

MARKER = 'wal-only-marker-7f3a9c'


class TestDbBackup(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-nas-backup-')
        self.src = os.path.join(self.tmp, 'src.db')
        self.dst = os.path.join(self.tmp, 'dst.db')
        # 保持源连接常开：WAL 不被关闭时 checkpoint / 清理，确保数据只在 WAL。
        self.src_db = self._make_live_wal_source(self.src)

    def tearDown(self):
        self.src_db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ---------- helpers ----------
    def _make_live_wal_source(self, path):
        db = Database(path)      # __init__ 已 PRAGMA journal_mode=WAL
        db.migrate()
        now = utcnow()
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?)",
            (MARKER, 'h-marker', 'marker', 'MARKER1', '', 'candidate', 10, now, now))
        return db

    def _sha256(self, path):
        with open(path, 'rb') as f:
            return hashlib.sha256(f.read()).hexdigest()

    def _meta(self, path):
        st = os.stat(path)
        return (st.st_size, st.st_mtime, self._sha256(path))

    def _assert_no_temp_leftover(self):
        leftover = [n for n in os.listdir(self.tmp) if '.tmp' in n]
        self.assertEqual(leftover, [], '存在临时残留: %s' % leftover)

    def _count(self, path, sql, params=()):
        conn = sqlite3.connect(path)
        try:
            return conn.execute(sql, params).fetchone()[0]
        finally:
            conn.close()

    def _make_old_target(self):
        old = Database(self.dst)
        old.migrate()
        old.set_meta('sentinel', 'old-target')
        old.close()

    # ---------- tests ----------
    def test_backup_includes_committed_wal_rows(self):
        """WAL 中已提交但未 checkpoint 的行必须出现在备份里。"""
        wal = self.src + '-wal'
        self.assertTrue(os.path.exists(wal), '源 WAL 不存在')
        self.assertGreater(os.path.getsize(wal), 0)
        with open(self.src, 'rb') as f:
            self.assertNotIn(MARKER.encode(), f.read(), '标记行已 checkpoint，测试前提不成立')

        online_backup(self.src, self.dst)

        self.assertEqual(self._count(
            self.dst, 'SELECT COUNT(*) FROM sources WHERE url=?', (MARKER,)), 1)

    def test_backup_target_passes_quick_check(self):
        online_backup(self.src, self.dst)
        self.assertTrue(_quick_check_ok(self.dst))

    def test_source_db_and_wal_not_rewritten(self):
        """只读源连接不得改写源库文件或源 WAL（不主动 checkpoint）。"""
        wal = self.src + '-wal'
        before = (self._meta(self.src), self._meta(wal))

        online_backup(self.src, self.dst)

        self.assertEqual(before, (self._meta(self.src), self._meta(wal)))

    def test_backup_is_consistent_while_writer_transaction_is_open(self):
        """另一连接持有未提交写事务时，快照只包含已提交数据且仍完整。"""
        self.src_db.execute('CREATE TABLE concurrent_marks (value INTEGER NOT NULL)')
        self.src_db.execute('INSERT INTO concurrent_marks(value) VALUES (1)')
        entered = threading.Event()
        release = threading.Event()
        outcome = {}

        def _writer():
            conn = sqlite3.connect(self.src, timeout=5)
            try:
                conn.execute('BEGIN IMMEDIATE')
                conn.execute('INSERT INTO concurrent_marks(value) VALUES (2)')
                entered.set()
                release.wait(10)
                conn.commit()
            except Exception as e:  # noqa: BLE001 - 由主线程断言暴露
                outcome['error'] = e
            finally:
                conn.close()

        writer = threading.Thread(target=_writer, daemon=True)
        writer.start()
        self.assertTrue(entered.wait(5), '写事务未进入')
        try:
            online_backup(self.src, self.dst)
            self.assertEqual(self._count(self.dst, 'SELECT COUNT(*) FROM concurrent_marks'), 1)
            self.assertTrue(_quick_check_ok(self.dst))
        finally:
            release.set()
            writer.join(timeout=10)
        self.assertFalse(writer.is_alive(), '写线程未结束')
        self.assertIsNone(outcome.get('error'), outcome.get('error'))

    def test_overwrite_target_atomic_success(self):
        self._make_old_target()
        self.assertTrue(os.path.exists(self.dst))

        online_backup(self.src, self.dst)

        self.assertEqual(self._count(
            self.dst, 'SELECT COUNT(*) FROM sources WHERE url=?', (MARKER,)), 1)
        self.assertEqual(self._count(
            self.dst, "SELECT COUNT(*) FROM meta WHERE key='sentinel'"), 0)
        self.assertFalse(os.path.exists(self.dst + '-wal'))
        self._assert_no_temp_leftover()

    def test_failure_keeps_old_target_and_cleans_temp(self):
        self._make_old_target()
        before = self._meta(self.dst)

        # mock 目标须为 online_backup 定义所在模块（已迁至 app.dbbackup）。
        with mock.patch('app.dbbackup._quick_check_ok', return_value=False):
            with self.assertRaises(RuntimeError):
                online_backup(self.src, self.dst)

        self.assertEqual(self._meta(self.dst), before)
        self.assertEqual(self._count(
            self.dst, "SELECT COUNT(*) FROM meta WHERE key='sentinel'"), 1)
        self._assert_no_temp_leftover()

    def test_missing_source_cleans_temp_and_keeps_target(self):
        self._make_old_target()
        before = self._meta(self.dst)

        with self.assertRaises(sqlite3.OperationalError):
            online_backup(os.path.join(self.tmp, 'nope.db'), self.dst)

        self.assertEqual(self._meta(self.dst), before)
        self._assert_no_temp_leftover()

    def test_reject_same_source_and_target(self):
        link = os.path.join(self.tmp, 'link.db')
        os.symlink(self.src, link)
        for bad in (self.src, os.path.join(self.tmp, '.', 'src.db'), link):
            with self.assertRaises(ValueError):
                online_backup(self.src, bad)
        self._assert_no_temp_leftover()


if __name__ == '__main__':
    unittest.main()
