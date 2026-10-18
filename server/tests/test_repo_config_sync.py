# -*- coding: utf-8 -*-
"""仓库配置启停应更新存量记录，保留发现与抓取历史。"""
import os
import sys
import tempfile
import unittest
from types import SimpleNamespace

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.db import Database
from app.discovery import SourceDiscovery


class TestRepoConfigSync(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='iptv-repo-sync-')
        self.db = Database(os.path.join(self.tmp.name, 'test.db'))
        self.db.migrate()
        self.repo = {'repo': 'example/live', 'branch': 'main',
                     'path': 'live.m3u', 'channel': 'jsdelivr', 'enabled': True}
        self.cfg = SimpleNamespace(repos={'repos': [self.repo]})
        self.discovery = SourceDiscovery(self.cfg, self.db, None, parser=object())

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def test_toggle_updates_existing_record_without_resetting_history(self):
        self.discovery.ensure_repos()
        before = self.db.query_one('SELECT * FROM repos')
        self.db.execute('UPDATE repos SET last_fetch=?, last_ok=? WHERE id=?',
                        ('2026-10-01T00:00:00Z', '2026-09-30T00:00:00Z', before['id']))
        self.repo.update(enabled=False, note='上游已移除文件')
        self.discovery.ensure_repos()
        after = self.db.query_one('SELECT * FROM repos')
        self.assertEqual(after['enabled'], 0)
        self.assertEqual(after['note'], '上游已移除文件')
        self.assertEqual(after['id'], before['id'])
        self.assertEqual(after['last_fetch'], '2026-10-01T00:00:00Z')
        self.assertEqual(after['last_ok'], '2026-09-30T00:00:00Z')
        self.repo['enabled'] = True
        self.discovery.ensure_repos()
        self.discovery.ensure_repos()
        self.assertEqual(self.db.query_one('SELECT enabled FROM repos')['enabled'], 1)
        self.assertEqual(self.db.query_one('SELECT COUNT(*) n FROM repos')['n'], 1)

    def test_unconfigured_mined_record_is_preserved(self):
        self.db.execute('INSERT INTO repos(repo, branch, path, channel, enabled, note) '
                        'VALUES (?,?,?,?,?,?)',
                        ('example/mined', 'main', 'extra.m3u', 'jsdelivr', 0, '保留人工停用'))
        self.discovery.ensure_repos()
        row = self.db.query_one('SELECT * FROM repos WHERE repo=?', ('example/mined',))
        self.assertEqual(row['enabled'], 0)
        self.assertEqual(row['note'], '保留人工停用')


if __name__ == '__main__':
    unittest.main()
