# -*- coding: utf-8 -*-
"""P0-2 方案 C（url_key 双身份彻底去耦）单测（全部离线）。

覆盖：
- signed_params_fingerprint：稳定性、顺序无关性、名单变更敏感性、非法输入容错；
- schema.rebuild_url_keys：全表重算、撞键消解（复用 v5 裁定 4）、幂等；
- schema.ensure_url_keys_fresh：指纹守卫（不变零操作、变更触发重建并落锚点）；
- discovery.ingest：url_hash 兜底自愈（陈旧 key 行就地刷新，INSERT OR IGNORE 吞并路径消除）；
- 审计分歧钉死：QA 实测（名单变更 -> 重复插入双行）与 ARCH 假设（INSERT 被
  uq_sources_url 静默吞掉 -> 行冻结）两条路径的回归 + 对照用例。

名单语义示例：
- 旧名单只剥全局 'token' -> TVB URL 的 sig/ts/c 留在 key 里（签名轮换即换身份）；
- 新名单追加 by_host fixture-53.example.invalid ['sig','ts','c'] -> 跨日签名同一身份。
"""
import hashlib
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import schema, urlkey                                    # noqa: E402
from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                                     # noqa: E402
from app.db import COLLISION_ORPHAN_STATUS, Database, utcnow      # noqa: E402
from app.discovery import SourceDiscovery                         # noqa: E402

# 旧名单：不感知 TVB host 作用域参数
OLD_LIST = {'signed_url_params': ['token'], 'signed_url_params_by_host': {}}
# 新名单：edgeware.tvb.com 追加 sig/ts/c 等 host 参数（签名剥离 -> 跨日签名同一身份）
NEW_LIST = {'signed_url_params': ['token'],
            'signed_url_params_by_host': {
                'fixture-53.example.invalid': ['sig', 'ts', 'c', 'ext_start_limit', 'int_start_limit']}}

TVB_A = ('http://fixture-53.example.invalid/index.mpd?sig=aaaa1111&ext_start_limit='
         '&int_start_limit=&ts=1700000000&c=')
TVB_B = ('http://fixture-53.example.invalid/index.mpd?sig=bbbb2222&ext_start_limit='
         '&int_start_limit=&ts=1700086400&c=')
# 同一物理源的第三种签名形态（对照用：旧名单下与 TVB_A 身份不同）
TVB_C = ('http://fixture-53.example.invalid/index.mpd?sig=cccc3333&ts=1700172800&c=')


def make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-fp-')
    write_test_config(tmp)
    return tmp


class FingerprintCase(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.disc = SourceDiscovery(self.cfg, self.db, None)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def use_list(self, lst):
        self.cfg.settings['signed_url_params'] = list(lst['signed_url_params'])
        self.cfg.settings['signed_url_params_by_host'] = dict(lst['signed_url_params_by_host'])

    def row(self):
        return self.db.query_one('SELECT * FROM sources')


# --------------------------- 指纹纯函数 ---------------------------
class TestFingerprint(unittest.TestCase):
    def test_same_list_same_fingerprint(self):
        fp1 = urlkey.signed_params_fingerprint(NEW_LIST)
        fp2 = urlkey.signed_params_fingerprint(NEW_LIST)
        self.assertEqual(fp1, fp2)
        self.assertEqual(len(fp1), 12)
        self.assertTrue(all(c in '0123456789abcdef' for c in fp1))

    def test_order_independent(self):
        """顺序无关性（实现定义）：名单书写顺序、JSON 键序不改变指纹。"""
        a = {'signed_url_params': ['token', 'sign', 'expires'],
             'signed_url_params_by_host': {'h1.com': ['a', 'b'], 'h2.com': ['c']}}
        b = {'signed_url_params': ['expires', 'token', 'sign', 'token'],
             'signed_url_params_by_host': {'h2.com': ['c', 'c'], 'h1.com': ['b', 'a']}}
        self.assertEqual(urlkey.signed_params_fingerprint(a),
                         urlkey.signed_params_fingerprint(b))

    def test_case_and_whitespace_normalized(self):
        """大小写 / 空白差异不改变生效名单 -> 指纹不变（与 resolve 语义一致）。"""
        a = {'signed_url_params': ['Token', ' sign ']}
        b = {'signed_url_params': ['token', 'sign']}
        self.assertEqual(urlkey.signed_params_fingerprint(a),
                         urlkey.signed_params_fingerprint(b))
        host_a = {'signed_url_params': [], 'signed_url_params_by_host': {'fixture-53.example.invalid': ['Sig']}}
        host_b = {'signed_url_params': [], 'signed_url_params_by_host': {'fixture-53.example.invalid': ['sig']}}
        self.assertEqual(urlkey.signed_params_fingerprint(host_a),
                         urlkey.signed_params_fingerprint(host_b))

    def test_any_effective_change_moves_fingerprint(self):
        base = urlkey.signed_params_fingerprint(NEW_LIST)
        add_global = dict(NEW_LIST, signed_url_params=['token', 'newparam'])
        self.assertNotEqual(urlkey.signed_params_fingerprint(add_global), base)
        add_host = {'signed_url_params': ['token'],
                    'signed_url_params_by_host': {'fixture-53.example.invalid': ['sig', 'ts', 'c', 'x']}}
        self.assertNotEqual(urlkey.signed_params_fingerprint(add_host), base)
        drop_host = {'signed_url_params': ['token'], 'signed_url_params_by_host': {}}
        self.assertNotEqual(urlkey.signed_params_fingerprint(drop_host), base)

    def test_invalid_settings_stable_and_never_raises(self):
        self.assertEqual(urlkey.signed_params_fingerprint(None),
                         urlkey.signed_params_fingerprint({}))
        self.assertEqual(urlkey.signed_params_fingerprint(
            {'signed_url_params': 'not-a-list', 'signed_url_params_by_host': 42}),
            urlkey.signed_params_fingerprint({}))


# --------------------------- 全表重建 ---------------------------
class TestRebuild(FingerprintCase):
    def test_rebuild_recomputes_stale_keys_and_is_idempotent(self):
        self.use_list(OLD_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.row()['id']
        stale_key = self.row()['url_key']
        self.assertEqual(stale_key, urlkey.url_key(TVB_A, urlkey.resolve_signed_params(TVB_A, OLD_LIST)))

        self.use_list(NEW_LIST)
        stats = schema.ensure_url_keys_fresh(self.db, self.cfg.settings)
        self.assertIsNotNone(stats)
        self.assertEqual(stats['recomputed'], 1)
        row = self.row()
        self.assertEqual(row['id'], sid)               # 仍是同一行（不增删）
        self.assertEqual(row['url'], TVB_A)            # url 不被重建改动
        self.assertEqual(row['url_key'],
                         urlkey.url_key(TVB_A, urlkey.resolve_signed_params(TVB_A, NEW_LIST)))
        self.assertEqual(row['key_source'], 'normalized')
        # 幂等：指纹已落锚点，再次调用零操作
        self.assertIsNone(schema.ensure_url_keys_fresh(self.db, self.cfg.settings))

    def test_rebuild_collision_prefers_active_non_destructive(self):
        """两行陈旧 key 重算到同一新 key -> 裁定 4：active 优先；落败行转孤儿不删除。"""
        self.use_list(OLD_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        self.disc.ingest([{'url': TVB_C, 'name': '翡翠台'}], 'test')
        act_id = self.db.query_one('SELECT id FROM sources WHERE url=?', (TVB_A,))['id']
        cand_id = self.db.query_one('SELECT id FROM sources WHERE url=?', (TVB_C,))['id']
        # active 行 last_seen 更旧也要赢（裁定 4）
        self.db.execute("UPDATE sources SET status='active', last_seen='2020-01-01T00:00:00Z'"
                        ' WHERE id=?', (act_id,))
        self.db.execute("UPDATE sources SET status='candidate', last_seen='2030-01-01T00:00:00Z'"
                        ' WHERE id=?', (cand_id,))
        self.db.execute('INSERT INTO verifications(source_id, ts, result) VALUES (?,?,?)',
                        (cand_id, utcnow(), 'ok'))
        before = self.db.query_one('SELECT COUNT(*) c FROM sources')['c']

        self.use_list(NEW_LIST)
        stats = schema.rebuild_url_keys(self.db.conn, self.cfg.settings)
        self.db.conn.commit()
        self.assertEqual(stats['collisions'], 1)
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], before)
        keep = self.db.query_one('SELECT url_key, status FROM sources WHERE id=?', (act_id,))
        loser = self.db.query_one('SELECT url_key, status, verdict_reason FROM sources WHERE id=?',
                                  (cand_id,))
        self.assertIsNotNone(keep['url_key'])
        self.assertEqual(keep['status'], 'active')
        self.assertIsNone(loser['url_key'])
        self.assertEqual(loser['status'], COLLISION_ORPHAN_STATUS)
        self.assertIn(str(act_id), loser['verdict_reason'])
        # 非破坏性：落败行子历史保留
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) c FROM verifications WHERE source_id=?', (cand_id,))['c'], 1)

    def test_rebuild_skips_existing_orphans(self):
        """历史 collision-orphan 行保持脱离身份空间，不被重建复活。"""
        self.use_list(OLD_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.row()['id']
        self.db.execute("UPDATE sources SET url_key=NULL, status=? WHERE id=?",
                        (COLLISION_ORPHAN_STATUS, sid))
        self.use_list(NEW_LIST)
        schema.rebuild_url_keys(self.db.conn, self.cfg.settings)
        self.db.conn.commit()
        row = self.db.query_one('SELECT url_key, status FROM sources WHERE id=?', (sid,))
        self.assertIsNone(row['url_key'])
        self.assertEqual(row['status'], COLLISION_ORPHAN_STATUS)

    def test_rebuild_unchanged_rows_untouched(self):
        self.use_list(NEW_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.row()['id']
        key_before = self.row()['url_key']
        seen_before = self.row()['last_seen']
        stats = schema.rebuild_url_keys(self.db.conn, NEW_LIST)
        self.db.conn.commit()
        self.assertEqual(stats['recomputed'], 0)
        row = self.db.query_one('SELECT url_key, last_seen FROM sources WHERE id=?', (sid,))
        self.assertEqual(row['url_key'], key_before)
        self.assertEqual(row['last_seen'], seen_before)   # last_seen 不得被重建刷新


# --------------------------- 指纹守卫 ---------------------------
class TestEnsureFresh(FingerprintCase):
    def test_first_call_sets_anchor_then_zero_ops(self):
        # migrate 后锚点不存在：第一次调用必重建（可能 0 行重算，但锚点要落）
        self.use_list(OLD_LIST)
        self.assertIsNotNone(schema.ensure_url_keys_fresh(self.db, self.cfg.settings))
        self.assertEqual(self.db.get_meta(schema.SIGNED_PARAMS_FP_META_KEY),
                         urlkey.signed_params_fingerprint(OLD_LIST))
        self.assertIsNone(schema.ensure_url_keys_fresh(self.db, self.cfg.settings))

    def test_list_change_triggers_rebuild_again(self):
        self.use_list(OLD_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        schema.ensure_url_keys_fresh(self.db, self.cfg.settings)   # 落旧锚点
        self.use_list(NEW_LIST)
        stats = schema.ensure_url_keys_fresh(self.db, self.cfg.settings)
        self.assertIsNotNone(stats)
        self.assertEqual(stats['recomputed'], 1)
        self.assertEqual(self.row()['url_key'],
                         urlkey.url_key(TVB_A, urlkey.resolve_signed_params(TVB_A, NEW_LIST)))


# --------------------------- ingest url_hash 兜底 ---------------------------
class TestIngestHashFallback(FingerprintCase):
    def _insert_stale_row(self, url, stale_key):
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, url_key, key_source, channel_name,"
            " channel_key, origin, status, score, first_seen, last_seen) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (url, urlkey.url_hash(url), stale_key, 'raw', 'n', 'CCTV1', 'test',
             'candidate', 10, '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z'))
        return self.db.query_one('SELECT id FROM sources WHERE url_hash=?',
                                 (urlkey.url_hash(url),))['id']

    def test_stale_key_row_heals_via_hash_fallback(self):
        """陈旧 key 行（url 字面未变）被 url_hash 兜底命中 -> 重算 key 刷新，吞并路径消除。"""
        self.use_list(NEW_LIST)
        sid = self._insert_stale_row(TVB_A, '0' * 40)   # 陈旧/伪造 key
        n, d, _ = self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        self.assertEqual((n, d), (0, 1))                # 视为重复而非新源
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)
        row = self.db.query_one('SELECT id, url_key, key_source, last_seen FROM sources')
        self.assertEqual(row['id'], sid)
        self.assertEqual(row['url_key'],
                         urlkey.url_key(TVB_A, urlkey.resolve_signed_params(TVB_A, NEW_LIST)))
        self.assertEqual(row['key_source'], 'normalized')
        self.assertGreater(row['last_seen'], '2020-01-01T00:00:00Z')   # 冻结解除

    def test_fallback_resurrects_orphan_with_same_url(self):
        """collision-orphan 行（url_key=NULL）被同 url 命中时恢复身份（物理源仍活跃）。"""
        self.use_list(NEW_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.row()['id']
        self.db.execute('UPDATE sources SET url_key=NULL, status=? WHERE id=?',
                        (COLLISION_ORPHAN_STATUS, sid))
        n, d, _ = self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        self.assertEqual((n, d), (0, 1))
        row = self.db.query_one('SELECT url_key, status FROM sources WHERE id=?', (sid,))
        self.assertIsNotNone(row['url_key'])
        self.assertEqual(row['status'], 'candidate')


# --------------------------- 审计分歧钉死（QA vs ARCH） ---------------------------
class TestAuditDisputePinned(FingerprintCase):
    """QA 审计（P1-2）与 ARCH 审计（P0-2）对「名单变更后发生什么」定级分歧的实测钉死。

    QA 实测：签名已轮换时 INSERT 成功 -> 同一物理源变成两行（双身份，重复投递）。
    ARCH 假设：INSERT 被 uq_sources_url 静默吞掉 -> 行冻结（仅当 url 字面不变时成立，
    此时兜底匹配已就地自愈，见 TestIngestHashFallback）。
    """

    def test_rebuild_then_reingest_single_row(self):
        """回归（方案 C 全链路）：旧名单建库 -> 名单变更 -> 启动重建 -> 重新 ingest
        同一物理源（签名轮换后的 url）-> 仍一行、url 已刷新、url_key 已更新。"""
        self.use_list(OLD_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.row()['id']

        self.use_list(NEW_LIST)
        schema.ensure_url_keys_fresh(self.db, self.cfg.settings)   # 启动重建（挂点语义）

        n, d, _ = self.disc.ingest([{'url': TVB_B, 'name': '翡翠台'}], 'test')
        self.assertEqual((n, d), (0, 1))
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)
        row = self.row()
        self.assertEqual(row['id'], sid)
        self.assertEqual(row['url'], TVB_B)    # url 刷新为最新签名
        self.assertEqual(row['url_key'],
                         urlkey.url_key(TVB_A, urlkey.resolve_signed_params(TVB_A, NEW_LIST)))

    def test_control_no_rebuild_yields_duplicate_identity_then_heals(self):
        """对照（不重建、直改名单）：QA 实测后果钉死 —— 同一物理源插入成两行；
        随后跑启动重建 -> 撞键消解回一行 + 孤儿，系统自愈。"""
        self.use_list(OLD_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        self.use_list(NEW_LIST)
        # 旧路径：新名单下 TVB_B 的 url_key/url_hash 都与存量行不同 -> INSERT 成功
        n, d, _ = self.disc.ingest([{'url': TVB_B, 'name': '翡翠台'}], 'test')
        self.assertEqual((n, d), (1, 0))
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 2)
        keys = {r['url_key'] for r in self.db.query('SELECT url_key FROM sources')}
        self.assertEqual(len(keys), 2)   # 双身份（同一物理源两个 url_key）

        # 方案 C 自愈：重建把两行消解回一个身份
        stats = schema.ensure_url_keys_fresh(self.db, self.cfg.settings)
        self.assertIsNotNone(stats)
        self.assertEqual(stats['collisions'], 1)
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 2)
        live = self.db.query_one('SELECT COUNT(*) c FROM sources WHERE url_key IS NOT NULL')['c']
        orphan = self.db.query_one(
            'SELECT COUNT(*) c FROM sources WHERE status=?', (COLLISION_ORPHAN_STATUS,))['c']
        self.assertEqual((live, orphan), (1, 1))

    def test_arch_swallow_path_only_when_url_identical(self):
        """ARCH 假设的适用边界钉死：url 字面不变时旧路径确会被 uq_sources_url 吞并
        （行冻结）；方案 C 的 url_hash 兜底使该路径就地自愈（本测试即其证据）。"""
        self.use_list(OLD_LIST)
        self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        sid = self.row()['id']
        # 手动把 last_seen 拨到过去，排除 utcnow 秒级精度造成的相等
        self.db.execute("UPDATE sources SET last_seen='2020-01-01T00:00:00Z' WHERE id=?", (sid,))
        frozen_last_seen = '2020-01-01T00:00:00Z'
        self.use_list(NEW_LIST)
        # url 未变：旧路径下 INSERT OR IGNORE 被 uq_sources_url 静默吞掉，行冻结。
        # 新代码 url_hash 兜底命中 -> 重算 key + 刷 last_seen。
        n, d, _ = self.disc.ingest([{'url': TVB_A, 'name': '翡翠台'}], 'test')
        self.assertEqual((n, d), (0, 1))
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM sources')['c'], 1)
        row = self.db.query_one('SELECT id, url_key, last_seen FROM sources')
        self.assertEqual(row['id'], sid)
        self.assertGreater(row['last_seen'], frozen_last_seen)   # 未冻结
        self.assertEqual(row['url_key'],
                         urlkey.url_key(TVB_A, urlkey.resolve_signed_params(TVB_A, NEW_LIST)))


if __name__ == '__main__':
    unittest.main()
