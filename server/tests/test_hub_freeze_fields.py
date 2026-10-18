# -*- coding: utf-8 -*-
"""v9「画面冻结」字段组单测（全部离线，临时目录，不触碰真实 data/iptv.db 与 NAS）。

对照 `docs/HUB_CONTRACT_CHANGELOG.md` 2026-09-18「两端冻结」条目：

- DDL_V9：7 列加到位 + 迁移幂等（含"逐列守卫"防半迁移卡死）；
- 落库：整组齐 / 零冻结 / 缺项 / 各种不变式违反 / 完全未上报 —— 五种形态各自的可观测行为；
- **NULL ≠ 0**：未上报冻结字段的记录不得进冻结率计算（这是本表最要紧的语义）；
- summary：冻结率聚合、契约违反计数、历史行不被污染；
- 宽松原则：任何脏冻结字段都**只计数、绝不 4xx**（沿用 notices 的既有纪律）。
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                                    # noqa: E402
from app.db import (DDL_V9, FREEZE_FIELDS, FREEZE_REPORT_THRESHOLD_SECONDS,  # noqa: E402
                    FREEZE_THRESHOLD_SECONDS, SCHEMA_VERSION, Database,
                    _apply_ddl_v9, _column_exists, utcnow)
from app.hub import Hub                                          # noqa: E402
from app.parser import ChannelParser                             # noqa: E402

TOKEN = 'unit-test-token-0123456789abcdef'
HLS_URL = 'http://120.196.235.42:9901/tsfile/live/1006_1.m3u8?key=fixture-auth-eec577e289ee'
CH = 'TVB翡翠台'

# 一端冻结的合规样例：300 s 活跃里冻结 10 s（1 段，最长 10 s），另有一段 ≥45 s 的报表口径
GOOD = {'active_seconds': 300.0, 'freeze_seconds': 10.0, 'freeze_segments': 1,
        'freeze_longest_seconds': 10.0, 'picture_progress_events': 120,
        'freeze_long_segments': 0}


def make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-frz-')
    write_test_config(tmp)
    p = os.path.join(tmp, 'config', 'settings.json')
    with open(p, encoding='utf-8') as fh:
        d = json.load(fh)
    d['hub'] = {'enabled': True, 'feedback_token': TOKEN}
    with open(p, 'w', encoding='utf-8') as fh:
        json.dump(d, fh, ensure_ascii=False, indent=2)
    os.makedirs(os.path.join(tmp, 'data', 'output'), exist_ok=True)
    return tmp


def add_source(db, url, channel_key):
    settings = {'signed_url_params': [], 'signed_url_params_by_host': {}}
    uk = ChannelParser.url_key(url, ChannelParser.resolve_signed_params(url, settings))
    now = utcnow()
    row = {'url': url, 'url_hash': ChannelParser.url_hash(url),
           'channel_name': channel_key, 'channel_key': channel_key, 'origin': 'test',
           'status': 'active', 'av': 'video', 'score': 10.0, 'first_seen': now,
           'last_seen': now, 'last_verified': now, 'profile': 'direct', 'url_key': uk,
           'container': 'hls', 'encryption': '', 'drm': '', 'keyformat': '',
           'kid': '', 'drm_key': None}
    cols = ','.join(row)
    db.execute('INSERT INTO sources(%s) VALUES(%s)' % (cols, ','.join('?' * len(row))),
               tuple(row.values()))
    return uk


class Base(unittest.TestCase):
    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        self.hub = Hub(self.cfg, self.db)
        self.url_key = add_source(self.db, HLS_URL, CH)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def payload(self, item_extra=None, drop=None):
        r = {'channel_id': CH, 'url_key': self.url_key, 'route': 'hls-ts',
             'outcome': 'ok', 'engine': 'media3', 'first_frame_ms': 1820,
             'stalls': 0, 'stall_seconds': 0.0, 'play_seconds': 600.0,
             'fail_reason': None, 'observed_at': '2026-09-18T20:56:24+08:00'}
        if item_extra:
            r.update(item_extra)
        for k in (drop or ()):
            r.pop(k, None)
        return {'hub_schema': 1, 'client': 'kantv', 'app': '0.2.21', 'session': 's-1',
                'reported_at': '2026-09-18T21:05:14+08:00', 'results': [r]}

    def row(self):
        return self.db.query_one('SELECT * FROM playback_reports')


class TestDdlV9(Base):
    def test_七列都加到位(self):
        want = {'active_seconds', 'freeze_seconds', 'freeze_segments',
                'freeze_longest_seconds', 'picture_progress_events',
                'freeze_long_segments', 'freeze_contract_ok'}
        have = {r[1] for r in self.db.conn.execute(
            'PRAGMA table_info(playback_reports)')}
        self.assertTrue(want.issubset(have), '缺列: %s' % (want - have))
        self.assertEqual(len(DDL_V9), 7)

    def test_版本号已推进到9(self):
        # v9 建表这一事实继续成立；版本随后续迭代（v10 吞吐表）继续递增
        self.assertGreaterEqual(SCHEMA_VERSION, 9)
        self.assertEqual(self.db.query_one(
            "SELECT value FROM meta WHERE key='schema_version'")['value'], str(SCHEMA_VERSION))

    def test_迁移幂等_重复调用不报错(self):
        # 第一次 migrate 已完成；再跑一次应直接返回（current >= SCHEMA_VERSION）
        self.db.migrate(self.cfg.settings)
        # 再直接跑逐列守卫：列已存在 -> 一条 ALTER 都不执行，不抛异常
        with self.db.transaction() as conn:
            _apply_ddl_v9(conn)
            _apply_ddl_v9(conn)
        self.assertTrue(_column_exists(self.db.conn, 'playback_reports', 'freeze_contract_ok'))

    def test_旧行不受影响_新列为NULL(self):
        # 模拟迁移前写入的历史行（不带任何冻结字段）
        self.hub.record_feedback(self.payload())
        row = self.row()
        for c in FREEZE_FIELDS + ('freeze_long_segments', 'freeze_contract_ok'):
            self.assertIsNone(row[c], '%s 应为 NULL' % c)


class TestIngest(Base):
    def test_合规整组落库(self):
        out = self.hub.record_feedback(self.payload(dict(GOOD)))
        self.assertEqual(out['accepted'], 1)
        self.assertEqual(out['freeze_ok'], 1)
        self.assertEqual(out['freeze_violation'], 0)
        self.assertEqual(out['freeze_partial'], 0)
        row = self.row()
        self.assertEqual(row['active_seconds'], 300.0)
        self.assertEqual(row['freeze_seconds'], 10.0)
        self.assertEqual(row['freeze_segments'], 1)
        self.assertEqual(row['freeze_longest_seconds'], 10.0)
        self.assertEqual(row['picture_progress_events'], 120)
        self.assertEqual(row['freeze_long_segments'], 0)
        self.assertEqual(row['freeze_contract_ok'], 1)

    def test_零冻结与未上报必须区分(self):
        # 上报了、且确实 0 冻结 -> contract_ok=1
        self.hub.record_feedback(self.payload(
            {'active_seconds': 600.0, 'freeze_seconds': 0.0, 'freeze_segments': 0,
             'freeze_longest_seconds': 0.0, 'picture_progress_events': 240}))
        self.assertEqual(self.row()['freeze_contract_ok'], 1)
        self.assertEqual(self.row()['freeze_seconds'], 0.0)
        # 完全未上报 -> 全 NULL、contract_ok 为 NULL（**不是 0**）
        self.hub.record_feedback(self.payload())
        rows = self.db.query('SELECT freeze_seconds, freeze_contract_ok '
                             'FROM playback_reports ORDER BY id')
        self.assertIsNone(rows[1]['freeze_seconds'])
        self.assertIsNone(rows[1]['freeze_contract_ok'])

    def test_缺项整组按未上报处理且只计数(self):
        out = self.hub.record_feedback(self.payload({'freeze_seconds': 10.0}))
        self.assertEqual(out['accepted'], 1)          # 绝不 4xx / 绝不丢弃
        self.assertEqual(out['freeze_partial'], 1)
        self.assertEqual(out['freeze_ok'], 0)
        row = self.row()
        self.assertIsNone(row['freeze_seconds'])      # 不做部分采信
        self.assertIsNone(row['freeze_contract_ok'])

    def test_未上报不计数(self):
        out = self.hub.record_feedback(self.payload())
        self.assertEqual((out['freeze_ok'], out['freeze_violation'],
                          out['freeze_partial']), (0, 0, 0))

    def test_五种不变式违反只计数不报错(self):
        bad_cases = {
            '超分母': dict(GOOD, freeze_seconds=400.0, freeze_longest_seconds=10.0),
            '最长超累计': dict(GOOD, freeze_seconds=5.0, freeze_longest_seconds=9.0),
            '段数与最长不同步': dict(GOOD, freeze_segments=0),
            '段数>0但最长<阈值': dict(GOOD, freeze_longest_seconds=3.0),
            '存在负值': dict(GOOD, freeze_seconds=-1.0, freeze_longest_seconds=0.0,
                         freeze_segments=0),
        }
        for name, item in bad_cases.items():
            out = self.hub.record_feedback(self.payload(item))
            self.assertEqual(out['accepted'], 1, name)
            self.assertEqual(out['freeze_violation'], 1, name)
            self.assertEqual(out['freeze_ok'], 0, name)

    def test_非数值字段算违反而非缺项(self):
        out = self.hub.record_feedback(self.payload(dict(GOOD, active_seconds='abc')))
        self.assertEqual(out['freeze_violation'], 1)
        self.assertEqual(out['freeze_partial'], 0)
        self.assertEqual(self.row()['freeze_contract_ok'], 0)

    def test_报表字段可单独出现且不触发缺项(self):
        out = self.hub.record_feedback(self.payload({'freeze_long_segments': 13}))
        self.assertEqual(out['freeze_partial'], 0)
        self.assertEqual(self.row()['freeze_long_segments'], 13)
        self.assertIsNone(self.row()['freeze_contract_ok'])

    def test_报表字段负值被丢弃(self):
        self.hub.record_feedback(self.payload(dict(GOOD, freeze_long_segments=-3)))
        self.assertIsNone(self.row()['freeze_long_segments'])
        self.assertEqual(self.row()['freeze_contract_ok'], 1)   # 不影响主字段判定

    def test_报表字段的_1哨兵等价于键缺席(self):
        """对端 2026-09-18 实现：`freeze_long_segments` 取不到时发 -1。

        契约允许"键缺席"与"-1 哨兵"两种表示，**二者等价、都落 NULL**，
        且**都必须能与"确实没有 ≥45 s 段"（=0）区分开**。
        """
        # ① 键缺席（GOOD 里本身带该键，此处显式去掉）
        absent = {k: v for k, v in GOOD.items() if k != 'freeze_long_segments'}
        self.hub.record_feedback(self.payload(absent))
        # ② -1 哨兵
        self.hub.record_feedback(self.payload(dict(GOOD, freeze_long_segments=-1)))
        # ③ 真的没有长冻结
        self.hub.record_feedback(self.payload(dict(GOOD, freeze_long_segments=0)))
        rows = self.db.query('SELECT freeze_long_segments, freeze_contract_ok '
                             'FROM playback_reports ORDER BY id')
        self.assertIsNone(rows[0]['freeze_long_segments'])     # 缺席 -> NULL
        self.assertIsNone(rows[1]['freeze_long_segments'])     # -1   -> NULL（不是 -1，也不是违反）
        self.assertEqual(rows[2]['freeze_long_segments'], 0)   # 0    -> 0（与"取不到"严格区分）
        for r in rows:
            self.assertEqual(r['freeze_contract_ok'], 1)       # 三者都不影响成组判定

    def test_未知字段仍被忽略且原文留存(self):
        out = self.hub.record_feedback(self.payload(dict(GOOD, freeze_something_new=7)))
        self.assertEqual(out['accepted'], 1)
        self.assertIn('freeze_something_new', self.row()['raw_json'])

    def test_整组齐但阈值边界恰好等于6秒算合规(self):
        self.hub.record_feedback(self.payload(
            dict(GOOD, freeze_seconds=FREEZE_THRESHOLD_SECONDS,
                 freeze_longest_seconds=FREEZE_THRESHOLD_SECONDS)))
        self.assertEqual(self.row()['freeze_contract_ok'], 1)


class TestSummary(Base):
    def test_冻结率按已上报记录计算(self):
        self.hub.record_feedback(self.payload(dict(GOOD)))            # 300 / 10
        self.hub.record_feedback(self.payload(
            {'active_seconds': 100.0, 'freeze_seconds': 40.0, 'freeze_segments': 2,
             'freeze_longest_seconds': 25.0, 'picture_progress_events': 40,
             'freeze_long_segments': 1}))
        self.hub.record_feedback(self.payload())                      # 未上报：不得稀释
        s = self.hub.feedback_summary()
        route = s['channels'][0]['routes'][0]
        frz = route['freeze']
        self.assertEqual(frz['reported'], 2)
        self.assertEqual(frz['contract_ok'], 2)
        self.assertEqual(frz['active_seconds'], 400.0)
        self.assertEqual(frz['freeze_seconds'], 50.0)
        self.assertEqual(frz['freeze_ratio'], 0.125)
        self.assertEqual(frz['segments'], 3)
        self.assertEqual(frz['longest_seconds'], 25.0)
        self.assertEqual(frz['long_segments'], 1)
        self.assertEqual(frz['threshold_seconds'], FREEZE_THRESHOLD_SECONDS)
        self.assertEqual(frz['long_threshold_seconds'], FREEZE_REPORT_THRESHOLD_SECONDS)
        self.assertEqual(route['device']['reports'], 3)               # 未上报那条仍在播放统计里
        self.assertEqual(s['totals']['freeze_reported'], 2)
        self.assertEqual(s['totals']['freeze_contract_violation'], 0)

    def test_违反不变式的记录仍计入冻结秒但不计入合规(self):
        self.hub.record_feedback(self.payload(dict(GOOD, freeze_seconds=400.0)))
        s = self.hub.feedback_summary()
        frz = s['channels'][0]['routes'][0]['freeze']
        self.assertEqual(frz['reported'], 1)
        self.assertEqual(frz['contract_ok'], 0)
        self.assertEqual(frz['contract_violation'], 1)
        self.assertEqual(s['totals']['freeze_contract_violation'], 1)

    def test_从未收到冻结字段时聚合为中性值(self):
        self.hub.record_feedback(self.payload())
        frz = self.hub.feedback_summary()['channels'][0]['routes'][0]['freeze']
        self.assertEqual(frz['reported'], 0)
        self.assertEqual(frz['active_seconds'], 0.0)
        self.assertIsNone(frz['freeze_ratio'])       # 分母为 0 -> None，而非 0.0

    def test_旧库无冻结数据时summary不炸(self):
        s = self.hub.feedback_summary()
        self.assertEqual(s['totals']['freeze_reported'], 0)
        self.assertEqual(s['totals']['freeze_seconds'], 0.0)


if __name__ == '__main__':
    unittest.main()
