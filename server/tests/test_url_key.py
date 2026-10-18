# -*- coding: utf-8 -*-
"""url_key 时效签名归一化单测（AC-N1 ~ AC-N11，全部离线）。

覆盖：
- resolve_signed_params / strip_signed_params / url_key 纯函数边界（AC-N1/N2/N3）；
- DDL_V5 迁移：列 23->26、回填零改键、唯一索引、幂等可重入（AC-N5/N6/N7/N8）；
- 碰撞消解：幸存者优先 active、非破坏性保留（collision-orphan）（AC-N4）；
- ingest：签名轮换去重 + 刷新 url/last_seen 且保留 last_verified（AC-N10/N11）；
- verifier 签名判定改走单一事实来源（含 host 作用域）；
- 干跑工具 tools/migrate_v5_dryrun 只读且能报告碰撞组。
"""
import hashlib
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import make_config, write_test_config  # noqa: E402
from app.config import Config                                    # noqa: E402
from app.db import (COLLISION_ORPHAN_STATUS, DDL_V1, DDL_V2,     # noqa: E402
                    DDL_V3, DDL_V4, SCHEMA_VERSION, Database, utcnow)
from app.discovery import SourceDiscovery                        # noqa: E402
from app.parser import ChannelParser                             # noqa: E402
from app.verifier import Verifier, _is_signed_url                # noqa: E402
from tools.migrate_v5_dryrun import dry_run                      # noqa: E402

# 全局名单（与 config/settings.json 中 signed_url_params 一致）
GLOBAL = ['expires', 'expire', 'expires_at', 'token', 'securitykey',
          'sign', 'signature', 'msisdn']
SETTINGS = {
    'signed_url_params': GLOBAL,
    'signed_url_params_by_host': {
        'edgeware.tvb.com': ['sig', 'ts', 'c', 'ext_start_limit', 'int_start_limit'],
    },
}

# HANDOFF §5.2 的真实形态：TVB MPD 源，签名 24h 固定周期轮换（ts = 清单生成 + 86400s）
TVB_A = ('http://edgeware.tvb.com/index.mpd?sig=aaaa1111&ext_start_limit='
         '&int_start_limit=&ts=1700000000&c=')
TVB_B = ('http://edgeware.tvb.com/index.mpd?sig=bbbb2222&ext_start_limit='
         '&int_start_limit=&ts=1700086400&c=')
# 翡翠台最优线路：静态访问 key（非时效签名，必须不剥离）
KEY_URL = 'http://120.196.235.42:9901/tsfile/live/1006_1.m3u8?key=fixture-auth-eec577e289ee'


def make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-urlkey-')
    write_test_config(tmp)
    return tmp


def make_v4(db):
    """把空库手工构造成 v4（执行 V1~V4 DDL，不执行 V5）。"""
    for stmt in DDL_V1 + DDL_V2 + DDL_V3 + DDL_V4:
        db.conn.execute(stmt)
    db.conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','4')")
    db.conn.commit()


def insert_v4(db, url, status='candidate', last_seen=None, url_hash=None):
    now = last_seen or utcnow()
    db.execute(
        "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
        " score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?)",
        (url, url_hash or hashlib.sha1(url.encode()).hexdigest(), 'n', 'CCTV1',
         'test', status, 10, now, now))
    return db.query_one('SELECT id FROM sources WHERE url_hash=?',
                        (url_hash or hashlib.sha1(url.encode()).hexdigest(),))['id']


# --------------------------- 纯函数（AC-N1/N2/N3） ---------------------------
class TestResolveSignedParams(unittest.TestCase):
    def test_global_plus_host_scope(self):
        ps = ChannelParser.resolve_signed_params(TVB_A, SETTINGS)
        for p in ('token', 'signature', 'sig', 'ts', 'c', 'ext_start_limit', 'int_start_limit'):
            self.assertIn(p, ps)
        self.assertEqual(len(ps), len(set(ps)))   # 去重

    def test_host_suffix_match_and_lookalike(self):
        # 后缀匹配：等值 / 子域命中；仿冒 / 前缀干扰域名不命中
        self.assertIn('ts', ChannelParser.resolve_signed_params(TVB_A, SETTINGS))   # 等值
        self.assertIn('ts', ChannelParser.resolve_signed_params(
            'http://a.edgeware.tvb.com/x.mpd?ts=1', SETTINGS))                      # 子域
        self.assertNotIn('ts', ChannelParser.resolve_signed_params(
            'http://edgeware.tvb.com.evil.net/x.mpd?ts=1', SETTINGS))               # 仿冒
        self.assertNotIn('ts', ChannelParser.resolve_signed_params(
            'http://notedgeware.tvb.com/x.mpd?ts=1', SETTINGS))                     # 前缀干扰

    def test_host_with_port_still_matches(self):
        self.assertIn('ts', ChannelParser.resolve_signed_params(
            'http://edgeware.tvb.com:8420/x.mpd?ts=1', SETTINGS))

    def test_missing_or_invalid_settings(self):
        self.assertEqual(ChannelParser.resolve_signed_params(TVB_A, {}), [])
        self.assertEqual(ChannelParser.resolve_signed_params(TVB_A, None), [])
        # 全局名单非法类型 -> 回退空
        self.assertEqual(ChannelParser.resolve_signed_params('http://h/p', {'signed_url_params': 'x'}), [])


class TestStripSignedParams(unittest.TestCase):
    def test_zero_hit_byte_identical(self):
        """★ AC-N1：零命中必须逐字节返回 normalize_url(url)。"""
        urls = [
            'http://a/x.m3u8?foo=1&bar=2',
            'HTTP://A/x.m3u8?Key=1',
            'http://a/x.m3u8?',
            'http://a/x.m3u8',
            KEY_URL,                                   # key= 全局不剥离
        ]
        for u in urls:
            self.assertEqual(ChannelParser.strip_signed_params(u, GLOBAL),
                             ChannelParser.normalize_url(u), u)

    def test_all_signed_params_stripped_no_trailing_question(self):
        self.assertEqual(ChannelParser.strip_signed_params(
            TVB_A, ChannelParser.resolve_signed_params(TVB_A, SETTINGS)),
            'http://edgeware.tvb.com/index.mpd')

    def test_partial_strip_preserves_order_and_encoding(self):
        out = ChannelParser.strip_signed_params(
            'http://h/p?b=2&sign=x&a=1', GLOBAL)
        self.assertEqual(out, 'http://h/p?b=2&a=1')

    def test_duplicate_names_both_dropped(self):
        self.assertEqual(ChannelParser.strip_signed_params(
            'http://h/p?sign=a&sign=b&k=1', GLOBAL), 'http://h/p?k=1')

    def test_percent_encoded_name_not_stripped(self):
        # 不 URL 解码 -> %73ig != sig，不剥离
        self.assertEqual(ChannelParser.strip_signed_params(
            'http://h/p?%73ig=x', ['sig']), 'http://h/p?%73ig=x')

    def test_no_value_param_stripped(self):
        self.assertEqual(ChannelParser.strip_signed_params(
            'http://h/p?sign=&k=1', GLOBAL), 'http://h/p?k=1')

    def test_non_http_scheme_untouched(self):
        for u in ('rtmp://h/p?token=x', 'rtsp://h/p?sign=y', 'udp://h/p?token=z'):
            self.assertEqual(ChannelParser.strip_signed_params(u, GLOBAL),
                             ChannelParser.normalize_url(u), u)

    def test_fragment_handled_by_normalize(self):
        self.assertEqual(ChannelParser.strip_signed_params(
            'http://h/p?sign=x#frag', GLOBAL), 'http://h/p')


class TestUrlKey(unittest.TestCase):
    def test_no_params_equals_url_hash(self):
        """★ AC-N1：无签名参数时 url_key == url_hash。"""
        for u in ('http://a/x.m3u8?foo=1', KEY_URL, 'rtmp://h/p?token=x'):
            self.assertEqual(ChannelParser.url_key(u, GLOBAL),
                             ChannelParser.url_hash(u), u)

    def test_key_and_authid_not_stripped(self):
        """AC-N2：key/authid 静态凭据不剥离。"""
        p = ChannelParser.resolve_signed_params(KEY_URL, SETTINGS)
        self.assertEqual(ChannelParser.url_key(KEY_URL, p), ChannelParser.url_hash(KEY_URL))

    def test_cross_day_signature_same_key(self):
        """AC-N3：TVB 跨日两组签名 -> url_key 相等、url_hash 不等。"""
        p = ChannelParser.resolve_signed_params(TVB_A, SETTINGS)
        self.assertEqual(ChannelParser.url_key(TVB_A, p), ChannelParser.url_key(TVB_B, p))
        self.assertNotEqual(ChannelParser.url_hash(TVB_A), ChannelParser.url_hash(TVB_B))


# --------------------------- 迁移（AC-N1/N4/N5/N6/N7/N8） ---------------------------
class TestV5Migration(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-urlkey-db-')
        self.path = os.path.join(self.tmp, 't.db')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_columns_23_to_27_and_indices(self):
        """AC-N6/N8：v4->(v5..v7) 列数 23->27；url_hash/uq_sources_url 仍在；新唯一索引就位。"""
        db = Database(self.path)
        make_v4(db)
        self.assertEqual(len(db.query('PRAGMA table_info(sources)')), 23)
        insert_v4(db, 'http://a/1.m3u8')
        db.migrate(SETTINGS)
        cols = [r['name'] for r in db.query('PRAGMA table_info(sources)')]
        self.assertEqual(len(cols), 28)          # v5 26 列 + v6 drm_key + v10 base_score
        for c in ('url_key', 'key_source', 'url_observed_at', 'drm_key'):
            self.assertIn(c, cols)
        idx = {r['name'] for r in db.query(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        self.assertIn('uq_sources_url', idx)
        self.assertIn('uq_sources_url_key', idx)
        self.assertIn('idx_sources_url_key', idx)
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))  # v9（含 v7 playback_reports）
        db.close()

    def test_backfill_zero_change_on_plain_urls(self):
        """AC-N1：无签名参数行回填后 url_key <> url_hash 计数为 0。"""
        db = Database(self.path)
        make_v4(db)
        for u in ['http://a/1.m3u8?foo=1', KEY_URL, 'http://a/2.mpd',
                  'rtmp://h/p?token=x', 'http://edgeware.tvb.com/index.mpd']:
            insert_v4(db, u)   # url_hash 按字面真实计算
        db.migrate(SETTINGS)
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) c FROM sources WHERE url_key <> url_hash')['c'], 0)
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) c FROM sources WHERE url_key IS NULL')['c'], 0)
        db.close()

    def test_unique_identity_no_duplicate_keys(self):
        """AC-N7：唯一性（含签名轮换两行 -> 1 行保留身份）。"""
        db = Database(self.path)
        make_v4(db)
        insert_v4(db, TVB_A, url_hash='ha')
        insert_v4(db, TVB_B, url_hash='hb')
        db.migrate(SETTINGS)
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) c FROM (SELECT url_key FROM sources WHERE url_key IS NOT NULL '
            'GROUP BY url_key HAVING COUNT(*) > 1)')['c'], 0)
        db.close()

    def test_collision_resolution_non_destructive_prefers_active(self):
        """AC-N4 + 裁定 4/5：优先保留 active；落败行转 collision-orphan 且不删除。"""
        db = Database(self.path)
        make_v4(db)
        # active 行 last_seen 更旧，candidate 行更新 -> 裁定 4 要求保留 active
        act_id = insert_v4(db, TVB_A, status='active', last_seen='2020-01-01T00:00:00Z',
                           url_hash='ha')
        cand_id = insert_v4(db, TVB_B, status='candidate', last_seen='2030-01-01T00:00:00Z',
                            url_hash='hb')
        # 落败行的子历史（CASCADE 目标）：非破坏性必须保留
        db.execute('INSERT INTO verifications(source_id, ts, result) VALUES (?,?,?)',
                   (cand_id, utcnow(), 'ok'))
        before = db.query_one('SELECT COUNT(*) c FROM sources')['c']
        db.migrate(SETTINGS)
        # 行数不变（不 DELETE）
        self.assertEqual(db.query_one('SELECT COUNT(*) c FROM sources')['c'], before)
        keep = db.query_one('SELECT url_key, status FROM sources WHERE id=?', (act_id,))
        loser = db.query_one('SELECT url_key, status, verdict_reason FROM sources WHERE id=?',
                             (cand_id,))
        self.assertIsNotNone(keep['url_key'])
        self.assertEqual(keep['status'], 'active')
        self.assertIsNone(loser['url_key'])
        self.assertEqual(loser['status'], COLLISION_ORPHAN_STATUS)
        self.assertIn(str(act_id), loser['verdict_reason'] or '')
        # CASCADE 未触发：落败行的验证历史仍在
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) c FROM verifications WHERE source_id=?', (cand_id,))['c'], 1)
        db.close()

    def test_migrate_idempotent_reentrant(self):
        """AC-N5：连跑两次 migrate -> 版本 7、列数不变、url_key 不变、0 异常。"""
        db = Database(self.path)
        make_v4(db)
        insert_v4(db, TVB_A, url_hash='ha')
        insert_v4(db, TVB_B, url_hash='hb')
        db.migrate(SETTINGS)
        cols1 = len(db.query('PRAGMA table_info(sources)'))
        keys1 = {r['id']: r['url_key'] for r in db.query('SELECT id, url_key FROM sources')}
        db.migrate(SETTINGS)   # 第二次
        cols2 = len(db.query('PRAGMA table_info(sources)'))
        keys2 = {r['id']: r['url_key'] for r in db.query('SELECT id, url_key FROM sources')}
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertEqual(cols1, cols2)
        self.assertEqual(keys1, keys2)
        self.assertEqual(db.query_one('SELECT COUNT(*) c FROM sources')['c'], 2)
        db.close()

    def test_ddl_v5_column_guard(self):
        """重入守卫：列已存在时 _apply_ddl_v5 不报 duplicate column。"""
        db = Database(self.path)
        make_v4(db)
        insert_v4(db, 'http://a/1')
        db.migrate(SETTINGS)
        # 再次直接调用（列已存在）不应抛异常
        from app.db import _apply_ddl_v5
        _apply_ddl_v5(db.conn)
        db.conn.commit()
        db.close()


# --------------------------- ingest（AC-N10/N11） ---------------------------
class TestIngestDedupRefresh(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.disc = SourceDiscovery(self.cfg, self.db, None)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def test_ac_n10_dedup_across_signature(self):
        """AC-N10：连续两次 ingest 同一源、签名不同 -> 首次 new=1，二次 new=0/dup=1，行数不增。"""
        n1, d1, _ = self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        self.assertEqual((n1, d1), (1, 0))
        n2, d2, _ = self.disc.ingest([{'url': TVB_B, 'name': '翡翠台'}], 'test')
        self.assertEqual((n2, d2), (0, 1))
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)

    def test_ac_n11_refresh_keeps_last_verified(self):
        """AC-N11：二次 ingest 后 url 刷新为最新签名、last_seen 更新、last_verified 不变。"""
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.db.query_one('SELECT id FROM sources')['id']
        self.db.execute('UPDATE sources SET last_verified=? WHERE id=?',
                        ('2020-01-01T00:00:00Z', sid))
        self.disc.ingest([{'url': TVB_B, 'name': '翡翠台'}], 'test')
        row = self.db.query_one('SELECT url, last_seen, last_verified, url_observed_at,'
                                ' status FROM sources WHERE id=?', (sid,))
        self.assertEqual(row['url'], TVB_B)                    # 最新签名 URL
        self.assertEqual(row['last_verified'], '2020-01-01T00:00:00Z')   # 保留
        self.assertIsNotNone(row['url_observed_at'])
        self.assertEqual(row['status'], 'candidate')

    def test_same_url_only_touches_last_seen(self):
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.db.query_one('SELECT id FROM sources')['id']
        first = self.db.query_one('SELECT last_seen FROM sources WHERE id=?', (sid,))['last_seen']
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)
        self.assertEqual(
            self.db.query_one('SELECT last_seen FROM sources WHERE id=?', (sid,))['last_seen'],
            first)

    def test_new_row_records_url_key_and_key_source(self):
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        row = self.db.query_one('SELECT url, url_hash, url_key, key_source FROM sources')
        self.assertEqual(row['url'], TVB_A)                    # url 始终存完整签名 URL
        self.assertEqual(row['key_source'], 'normalized')      # 剥离过签名
        self.assertNotEqual(row['url_key'], row['url_hash'])
        p = self.disc._signed_params(TVB_A)
        self.assertEqual(row['url_key'], ChannelParser.url_key(TVB_A, p))


# --------------------------- verifier 对齐 ---------------------------
class TestVerifierSignedParams(unittest.TestCase):
    def test_signed_params_single_source_of_truth(self):
        root = make_root()
        try:
            cfg = Config(root)
            db = Database(cfg.db_path)
            db.migrate(cfg.settings)
            v = Verifier(cfg, db, None)
            ps = v._signed_params(TVB_A)
            self.assertIn('sig', ps)
            self.assertIn('ts', ps)
            # 非 TVB host -> 仅全局名单
            self.assertNotIn('ts', v._signed_params('http://other/x.m3u8?ts=1'))
            # 无参调用向后兼容（仅全局）
            self.assertEqual(v._signed_params(), [p.lower() for p in GLOBAL])
            # 单一事实来源：_is_signed_url 用解析结果判定 TVB 为签名 URL
            self.assertTrue(_is_signed_url(TVB_A, v._signed_params(TVB_A)))
            self.assertFalse(_is_signed_url(KEY_URL, v._signed_params(KEY_URL)))
            db.close()
        finally:
            shutil.rmtree(root, ignore_errors=True)


# --------------------------- 干跑工具 ---------------------------
class TestDryRunTool(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-urlkey-dry-')
        self.path = os.path.join(self.tmp, 't.db')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_dry_run_reports_collision_and_is_read_only(self):
        db = Database(self.path)
        make_v4(db)
        insert_v4(db, TVB_A, status='active', url_hash='ha')
        insert_v4(db, TVB_B, url_hash='hb')
        insert_v4(db, 'http://a/plain.m3u8', url_hash='hp')
        db.close()
        before = os.path.getsize(self.path)
        # 工具会加载其项目根配置；本用例只验证迁移报告/只读性，注入合成配置。
        with mock.patch('tools.migrate_v5_dryrun.Config', return_value=make_config()):
            rep = dry_run(self.path, SETTINGS)
        # 只读：文件大小不变、schema_version 仍为 4、数据不变
        self.assertEqual(os.path.getsize(self.path), before)
        chk = Database(self.path)
        self.assertEqual(chk.get_meta('schema_version'), '4')
        self.assertEqual(chk.query_one('SELECT COUNT(*) c FROM sources')['c'], 3)
        chk.close()
        # 报告：1 个碰撞组、待回填 3、非破坏性 0 增减
        self.assertEqual(rep['rows'], 3)
        self.assertEqual(rep['pending_backfill'], 3)
        self.assertEqual(len(rep['collisions']), 1)
        self.assertEqual(rep['collisions'][0]['keep_status'], 'active')
        self.assertEqual(rep['row_delta'], 0)


if __name__ == '__main__':
    unittest.main()
