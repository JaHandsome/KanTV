# -*- coding: utf-8 -*-
"""schema v3/v4 迁移 + 状态视图 + 投递画像不变量（P0 R3，全部离线）。

覆盖：
- 迁移：`PRAGMA table_info(sources)` 含 8 新列、`schema_version==4`（AC-3.1）；
  幂等 + 数据保留 + `profile` 回填（`profile IS NULL` 计数为 0）+ `container` 回填
  （`container IS NULL OR ''` 计数为 0；.mpd->dash / .flv->flv / 其余->hls）；
- `_source_counts()` 显式枚举 unsupported/drm（AC-3.3）；
- `_coverage()` 按 DELIVERABLE_PROFILES 过滤（只有 need-key 线路的频道线路数 0，AC-3.5）；
- `_data_invariant()` 统计 active+video+profile IS NULL（AC-3 不变量）与
  active+video+direct 但持有 DRM/非明文加密（DRM 派生漂移不变量）并入 snapshot；
- `cleanup()` 不回收 unsupported（AC-2.3）。
"""
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                              # noqa: E402
from app.db import (DDL_V1, DDL_V2, DELIVERABLE_PROFILES,  # noqa: E402
                    NON_DELIVERABLE_PROFILES, SCHEMA_VERSION, Database,
                    infer_container_from_url, utcnow)
from app.status import STATUS_KEYS, StatusProvider         # noqa: E402

NEW_COLUMNS = ('container', 'encryption', 'drm', 'keyformat', 'kid', 'profile',
               'caps_required', 'verdict_reason')
V5_COLUMNS = ('url_key', 'key_source', 'url_observed_at')   # url_key 归一化（DDL_V5）
V6_COLUMNS = ('drm_key',)                                   # 内联 ClearKey 密钥材料（DDL_V6）


def make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-schema-')
    write_test_config(tmp)
    return tmp


def insert_source(db, url, key='CCTV1', status='candidate', av=None, profile=None,
                  score=10, explicit_profile=False, last_seen=None,
                  encryption=None, drm=None):
    """插入 source；explicit_profile=True 时显式写 profile（可传 NULL 触发不变量）。

    encryption/drm 非 None 时一并写入（构造 DRM 派生漂移的不变量样本）。
    """
    now = utcnow()
    cols = ['url', 'url_hash', 'channel_name', 'channel_key', 'origin', 'status', 'av',
            'score', 'first_seen', 'last_seen']
    vals = [url, url, key, key, 'test', status, av, score, now, last_seen or now]
    if explicit_profile:
        cols.append('profile')
        vals.append(profile)
    if encryption is not None:
        cols.append('encryption')
        vals.append(encryption)
    if drm is not None:
        cols.append('drm')
        vals.append(drm)
    marks = ','.join(['?'] * len(cols))
    db.execute('INSERT INTO sources(%s) VALUES (%s)' % (','.join(cols), marks), vals)
    return db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']


class TestSchemaV3Migration(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-schema-db-')
        self.path = os.path.join(self.tmp, 't.db')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_fresh_migrate_has_all_columns_and_version6(self):
        db = Database(self.path)
        db.migrate()
        cols = [r['name'] for r in db.query('PRAGMA table_info(sources)')]
        for c in NEW_COLUMNS:
            self.assertIn(c, cols)
        for c in V5_COLUMNS:
            self.assertIn(c, cols)
        for c in V6_COLUMNS:
            self.assertIn(c, cols)
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))  # v9（含 v7 枢纽回流表）
        db.close()

    def test_indices_created(self):
        db = Database(self.path)
        db.migrate()
        names = {r['name'] for r in db.query(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        self.assertIn('idx_sources_container', names)
        self.assertIn('idx_sources_status_profile', names)
        db.close()

    def test_v2_to_v4_backfills_profile_and_container(self):
        """v2 库升 v4：8 列落地 + profile 回填 'direct' + 行数不变。"""
        db = Database(self.path)
        # 手工构造 v2 库（只执行 V1+V2，不执行 V3/V4）
        for stmt in DDL_V1 + DDL_V2:
            db.conn.execute(stmt)
        db.conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','2')")
        db.conn.commit()
        now = utcnow()
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen) VALUES ('http://a/1.m3u8','h1','n','CCTV1','',"
            "'active','video(TS:H.264视频)',10,?,?)", (now, now))
        before = db.query_one('SELECT COUNT(*) c FROM sources')['c']

        db.migrate()   # 触发 DDL_V3 ~ DDL_V6

        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertEqual(db.query_one('SELECT COUNT(*) c FROM sources')['c'], before)
        self.assertEqual(
            db.query_one('SELECT COUNT(*) c FROM sources WHERE profile IS NULL')['c'], 0)
        row = db.query_one("SELECT profile, container FROM sources WHERE url_hash='h1'")
        self.assertEqual(row['profile'], 'direct')
        self.assertEqual(row['container'], 'hls')
        db.close()

    def test_container_backfill_by_url_suffix(self):
        """缺陷修复：v2 历史行（container 为 NULL）按 URL 后缀回填，无 NULL/空残留。"""
        urls = [
            'http://a/1.mpd', 'http://a/1.MPD', 'http://a/x.mpd?token=abc',
            'http://a/live.flv', 'http://a/y.flv?x=1',
            'http://a/live.m3u8', 'http://a/stream', 'http://a/no_ext',
        ]
        db = Database(self.path)
        for stmt in DDL_V1 + DDL_V2:
            db.conn.execute(stmt)
        db.conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','2')")
        db.conn.commit()
        now = utcnow()
        for i, u in enumerate(urls):
            db.execute(
                "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
                " av, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)",
                (u, 'h%d' % i, 'n', 'CCTV1', '', 'active', 'video(TS:x)', 10, now, now))

        db.migrate()

        # 无任何 NULL/空 container 残留
        self.assertEqual(db.query_one(
            "SELECT COUNT(*) c FROM sources WHERE container IS NULL OR container=''")['c'], 0)
        # 按后缀正确归类，且与 Python 推断函数一致（防 SQL/代码漂移）
        for u in urls:
            row = db.query_one('SELECT container FROM sources WHERE url=?', (u,))
            self.assertEqual(row['container'], infer_container_from_url(u), u)
        mapping = {r['url']: r['container'] for r in db.query('SELECT url, container FROM sources')}
        self.assertEqual(mapping['http://a/1.mpd'], 'dash')
        self.assertEqual(mapping['http://a/1.MPD'], 'dash')
        self.assertEqual(mapping['http://a/x.mpd?token=abc'], 'dash')
        self.assertEqual(mapping['http://a/live.flv'], 'flv')
        self.assertEqual(mapping['http://a/y.flv?x=1'], 'flv')
        self.assertEqual(mapping['http://a/live.m3u8'], 'hls')
        self.assertEqual(mapping['http://a/stream'], 'hls')
        self.assertEqual(mapping['http://a/no_ext'], 'hls')
        db.close()

    def test_migrate_idempotent(self):
        db = Database(self.path)
        db.migrate()
        insert_source(db, 'http://a/1', status='active', av='video(TS:H.264视频)')
        db.close()
        db2 = Database(self.path)
        db2.migrate()   # 二次迁移：不重复执行、数据保留
        self.assertEqual(db2.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertEqual(db2.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)
        db2.close()

    def test_container_backfill_idempotent(self):
        """连续两次 migrate：container 值不变（幂等）。"""
        db = Database(self.path)
        for stmt in DDL_V1 + DDL_V2:
            db.conn.execute(stmt)
        db.conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','2')")
        db.conn.commit()
        now = utcnow()
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen) VALUES ('http://a/z.mpd','hz','n','CCTV1','',"
            "'active','video(TS:x)',10,?,?)", (now, now))
        db.migrate()
        first = db.query_one("SELECT container FROM sources WHERE url_hash='hz'")['container']
        db.migrate()   # 第二次
        second = db.query_one("SELECT container FROM sources WHERE url_hash='hz'")['container']
        self.assertEqual(first, 'dash')
        self.assertEqual(first, second)
        db.close()

    def test_profile_constants(self):
        self.assertIn('direct', DELIVERABLE_PROFILES)
        self.assertIn('media3-json', DELIVERABLE_PROFILES)
        self.assertNotIn('need-key', DELIVERABLE_PROFILES)
        self.assertIn('need-key', NON_DELIVERABLE_PROFILES)
        self.assertIn('unsupported-drm', NON_DELIVERABLE_PROFILES)


class TestStatusView(unittest.TestCase):
    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.cfg.channels['channels'] = ['CCTV1', 'CCTV2']
        self.db = Database(self.cfg.db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_source_counts_explicit_enumeration(self):
        insert_source(self.db, 'http://x/a', status='unsupported')
        insert_source(self.db, 'http://x/b', status='drm')
        insert_source(self.db, 'http://x/c', status='weird-unknown')   # 归 other
        p = StatusProvider(self.cfg, self.db, None)
        counts = p._source_counts()
        for k in ('unsupported', 'drm', 'blacklisted', 'other'):
            self.assertIn(k, counts)
        self.assertEqual(counts['unsupported'], 1)
        self.assertEqual(counts['drm'], 1)
        self.assertEqual(counts['other'], 1)
        # 键集与 STATUS_KEYS 对齐
        for k in STATUS_KEYS:
            self.assertIn(k, counts)

    def test_api_status_exposes_unsupported_and_drm(self):
        p = StatusProvider(self.cfg, self.db, None)
        snap = p.api_status()
        self.assertIn('unsupported', snap['sources'])
        self.assertIn('drm', snap['sources'])
        self.assertIn('data_invariant', snap)

    def test_url_key_rebuild统计暴露(self):
        """ARCH P1-6：重建统计从 ensure_url_keys_fresh 落到 meta，/api/status 可见。"""
        import json
        from app import schema
        p = StatusProvider(self.cfg, self.db, None)
        self.assertIsNone(p.api_status()['url_key_rebuild'])   # 从未重建
        stats = schema.ensure_url_keys_fresh(self.db, self.cfg.settings)
        self.assertIsNotNone(stats)
        raw = json.loads(self.db.get_meta(schema.URL_KEY_REBUILD_STATS_META))
        for k in ('at', 'recomputed', 'unchanged', 'collisions'):
            self.assertIn(k, raw)
        self.assertEqual(raw['recomputed'], stats['recomputed'])
        seen = p.api_status()['url_key_rebuild']
        self.assertEqual(seen['unchanged'], stats['unchanged'])
        self.assertEqual(seen['collisions'], stats['collisions'])
        # 幂等：指纹不变 -> 零操作，统计锚点保持上一次值
        self.assertIsNone(schema.ensure_url_keys_fresh(self.db, self.cfg.settings))
        self.assertEqual(p.api_status()['url_key_rebuild']['recomputed'],
                         stats['recomputed'])

    def test_coverage_filters_non_deliverable_profile(self):
        """只有 need-key 线路的频道 coverage 线路数为 0（不呈现假绿，AC-3.5）。"""
        insert_source(self.db, 'http://x/nk', key='CCTV1', status='active',
                      av='video(TS:H.264视频)', profile='need-key', explicit_profile=True)
        insert_source(self.db, 'http://x/ok', key='CCTV2', status='active',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True)
        p = StatusProvider(self.cfg, self.db, None)
        cov = p._coverage()
        by = {c['name']: c for c in cov['channels']}
        self.assertEqual(by['CCTV1']['lines'], 0)     # need-key 不算有效线路
        self.assertEqual(by['CCTV2']['lines'], 1)

    def test_data_invariant_flags_null_profile(self):
        insert_source(self.db, 'http://x/null', key='CCTV1', status='active',
                      av='video(TS:H.264视频)', profile=None, explicit_profile=True)
        p = StatusProvider(self.cfg, self.db, None)
        snap = p.snapshot()
        self.assertGreaterEqual(snap['data_invariant']['active_video_profile_null'], 1)
        page = p.render_html()
        self.assertIn('数据不变量告警', page)
        # 分布表行数 >= 6（含 unsupported/drm）
        self.assertGreaterEqual(page.count('<tr><td>'), 6)

    def test_data_invariant_flags_direct_with_drm_facts(self):
        """新增不变量：active+video+direct 但持有 DRM/非明文加密 -> 计数命中并告警。

        规范 direct（明文/aes128/空 drm）不得误报；非 active / 非 video 不计入。
        """
        # 命中：direct + drm 非空
        insert_source(self.db, 'http://x/d1', key='CCTV1', status='active',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True,
                      drm='clearkey')
        # 命中：direct + encryption 非明文（drm 缺失）
        insert_source(self.db, 'http://x/d2', key='CCTV1', status='active',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True,
                      encryption='cenc')
        # 命中：空白/大小写归一后仍命中
        insert_source(self.db, 'http://x/d3', key='CCTV1', status='active',
                      av='video(TS:H.264视频)', profile=' DIRECT ', explicit_profile=True,
                      encryption=' SAMPLE-AES ')
        # 不误报：明文 / aes128 / 空白归一 / 空 drm
        insert_source(self.db, 'http://x/ok1', key='CCTV2', status='active',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True)
        insert_source(self.db, 'http://x/ok2', key='CCTV2', status='active',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True,
                      encryption='aes128', drm='')
        insert_source(self.db, 'http://x/ok3', key='CCTV2', status='active',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True,
                      encryption=' AES128 ', drm='   ')
        # 不计入：非 active / 非 video
        insert_source(self.db, 'http://x/no1', key='CCTV1', status='drm',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True,
                      drm='clearkey')
        insert_source(self.db, 'http://x/no2', key='CCTV1', status='active',
                      av='audio-only', profile='direct', explicit_profile=True, drm='clearkey')
        p = StatusProvider(self.cfg, self.db, None)
        inv = p.snapshot()['data_invariant']
        self.assertEqual(inv['active_video_direct_with_drm'], 3)
        self.assertEqual(inv['active_video_profile_null'], 0)
        self.assertIn('但持有 DRM', p.render_html())

    def test_data_invariant_direct_with_drm_zero_no_warn(self):
        """规范 direct 源：新不变量计数为 0（不误报）。"""
        insert_source(self.db, 'http://x/plain', key='CCTV1', status='active',
                      av='video(TS:H.264视频)', profile='direct', explicit_profile=True,
                      encryption='', drm='')
        p = StatusProvider(self.cfg, self.db, None)
        self.assertEqual(p.snapshot()['data_invariant']['active_video_direct_with_drm'], 0)

    def test_html_has_status_rows(self):
        p = StatusProvider(self.cfg, self.db, None)
        page = p.render_html()
        for token in ('unsupported', 'drm', 'blacklisted', 'other'):
            self.assertIn(token, page)


class TestCleanupKeepsUnsupported(unittest.TestCase):
    def setUp(self):
        self.tmp = make_root()
        self.db = Database(Config(self.tmp).db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_cleanup_does_not_reclaim_unsupported(self):
        """AC-2.3：unsupported 源不被任何删除路径回收（即使 last_seen 陈旧）。"""
        old = '2020-01-01T00:00:00Z'
        insert_source(self.db, 'rtmp://x/old', status='unsupported', last_seen=old)
        insert_source(self.db, 'http://x/dead-old', status='dead', last_seen=old)
        self.db.cleanup(retention_days=90, dead_retention_days=30, orphan_retention_days=30)
        urls = {r['url'] for r in self.db.query('SELECT url FROM sources')}
        self.assertIn('rtmp://x/old', urls)              # unsupported 保留
        self.assertNotIn('http://x/dead-old', urls)      # dead 过期被回收


if __name__ == '__main__':
    unittest.main()
