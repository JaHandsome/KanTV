# -*- coding: utf-8 -*-
"""库构成与治理缺口分析（只读）。"""
import os
import sqlite3

DB = '/app/data/iptv.db'
print('db file size: %.1f MB' % (os.path.getsize(DB) / 1048576.0))

db = sqlite3.connect(DB)
db.row_factory = sqlite3.Row

print('\n=== 各表行数 ===')
for t in ['sources', 'health_checks', 'health_events', 'verifications', 'repos', 'lineups']:
    try:
        n = db.execute('SELECT COUNT(*) c FROM %s' % t).fetchone()['c']
        print('  %-16s %d' % (t, n))
    except sqlite3.Error as e:
        print('  %-16s ERR %s' % (t, e))

print('\n=== sources 状态 × 是否有 channel_key ===')
for r in db.execute(
        "SELECT status, (channel_key IS NULL OR channel_key='') nokey, COUNT(*) c "
        "FROM sources GROUP BY status, nokey ORDER BY status"):
    print('  %-12s no_key=%-5s %d' % (r['status'], bool(r['nokey']), r['c']))

print('\n=== blacklisted first_seen 月分布 ===')
for r in db.execute(
        "SELECT substr(first_seen,1,7) m, COUNT(*) c FROM sources "
        "WHERE status='blacklisted' GROUP BY m ORDER BY m"):
    print('  %s %d' % (r['m'], r['c']))

print('\n=== 空 key candidate 月分布 ===')
for r in db.execute(
        "SELECT substr(first_seen,1,7) m, COUNT(*) c FROM sources "
        "WHERE status='candidate' AND (channel_key IS NULL OR channel_key='') "
        "GROUP BY m ORDER BY m"):
    print('  %s %d' % (r['m'], r['c']))

print('\n=== 冷却 vs 待验证（有 key 的 candidate）===')
r = db.execute(
    "SELECT SUM(backoff_until IS NOT NULL) cooling, "
    "SUM(backoff_until IS NULL) ready FROM sources "
    "WHERE status='candidate' AND channel_key != ''").fetchone()
print('  cooling=%s ready=%s' % (r['cooling'], r['ready']))

print('\n=== 库里最早/最晚 first_seen ===')
r = db.execute('SELECT MIN(first_seen) a, MAX(first_seen) b FROM sources').fetchone()
print('  %s -> %s' % (r['a'], r['b']))
