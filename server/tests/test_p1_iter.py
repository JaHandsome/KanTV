# -*- coding: utf-8 -*-
"""P1 迭代回归测试（2026-09 第二批，8 项：A/B/C/D/E/F/G/H）。

覆盖：
- A 日志增强：轮转扩容 20MB x 5（总量 100MB <= P0-6 上限）；
- B 配置容错：reload 解析失败保留上一轮生效配置（各域独立），首次加载才用默认值；
- C 失败候选退避：schema v2 backoff_until 列 + 连续 fail 指数冷却(3/7/30d)
  + HTTP 403/404 长冷却 + 冷却中候选跳过验证；
- D probe_alive HTML 嗅探：<html/<!DOCTYPE/text/html 的 200 判不存活；
- E healthz JSON + 调度心跳：心跳新鲜 200 / 超龄 503；
- F 弱频道优先验证：有效线路 <2 的频道候选排前；
- G 状态页：/api/status 覆盖矩阵 + /status 预渲染 HTML + 任务历史；
- H 小件：dead 源过期清理 + idx_sources_status 索引 + probe_fetch_limit 接通。

全部离线可跑：python tests/run_offline.py
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import __version__                       # noqa: E402
import app.logutil as logutil                     # noqa: E402
from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                     # noqa: E402
from app.db import Database, iso_plus_hours, utcnow  # noqa: E402
from app.health import HealthMonitor              # noqa: E402
from app.scheduler import Scheduler               # noqa: E402
from app.status import StatusProvider             # noqa: E402
from app.verifier import Verifier                 # noqa: E402


def make_root():
    """构造临时项目根（公开元数据与合成 settings，全部离线）。"""
    tmp = tempfile.mkdtemp(prefix='iptv-p1-')
    write_test_config(tmp)
    return tmp


def insert_source(db, url, key='CCTV1', status='candidate', av=None, score=10,
                  first_seen=None, last_seen=None):
    """插入一条 source，返回 id。"""
    now = first_seen or utcnow()
    seen = last_seen or now
    db.execute(
        'INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,'
        ' av, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)',
        (url, url, key, key, 'test', status, av, score, now, seen))
    return db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']


class ProbeNet(object):
    """离线假网络：登记响应；记录每次 fetch 的 (url, limit) 供断言。"""

    ua = 'test-ua'

    def __init__(self, responses=None):
        self.responses = responses or {}
        self.fetched = []
        self.lock = threading.Lock()

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        with self.lock:
            self.fetched.append((url, limit))
        if url not in self.responses:
            raise OSError('no route: %s' % url)
        st, body, ct = self.responses[url]
        return st, body[:limit], ct


class LiveNet(object):
    """离线假网络（探活用）：url -> (status, body, content_type)，未登记抛异常。"""

    ua = 'test-ua'

    def __init__(self, responses):
        self.responses = responses

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=512, timeout=None):
        if url not in self.responses:
            raise OSError('dead')
        st, body, ct = self.responses[url]
        return st, body[:limit], ct


# ---------- A 日志轮转扩容 ----------

class TestLogRotationExpansion(unittest.TestCase):
    """P1-A：轮转扩容到 20MB x 5 = 100MB（P0-6 上限内翻倍）。"""

    def test_rotation_capacity(self):
        self.assertEqual(logutil._MAX_BYTES, 16 * 1024 * 1024)
        self.assertEqual(logutil._BACKUP_COUNT, 5)
        # 当前文件 + 5 个轮转副本 = 96MB 物理上限，守住 P0-6 的 100MB 红线
        self.assertLessEqual(logutil._MAX_BYTES * (logutil._BACKUP_COUNT + 1),
                             100 * 1024 * 1024)
        self.assertGreater(logutil._MAX_BYTES, 10 * 1024 * 1024)   # 确认相对 P0 已扩容

    def test_version_bumped(self):
        # P2 供给扩容升 1.2.0; P3 挖矿形态扩展(主页/镜像/限额)升 1.3.0
        self.assertGreaterEqual([int(x) for x in __version__.split('.')],
                                [1, 2, 0])


# ---------- B 配置容错 ----------

class TestConfigReloadTolerance(unittest.TestCase):
    """P1-B：reload 失败保留旧配置；首次加载失败才用默认值。"""

    def setUp(self):
        self.tmp = make_root()
        self.settings_path = os.path.join(self.tmp, 'config', 'settings.json')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_settings(self, text):
        with open(self.settings_path, 'w', encoding='utf-8') as f:
            f.write(text)

    def test_reload_keeps_old_on_corrupt(self):
        cfg = Config(self.tmp)
        self._write_settings(json.dumps({'http': {'port': 9999}}, ensure_ascii=False))
        cfg.reload()
        self.assertEqual(cfg.settings['http']['port'], 9999)   # 合法变更生效
        self._write_settings('{ broken json !!')
        cfg.reload()                                            # 损坏 -> 保留旧值
        self.assertEqual(cfg.settings['http']['port'], 9999)
        self.assertNotEqual(cfg.settings['http']['port'], 8420)  # 未漂移到默认值

    def test_reload_wrong_type_value_passes_through(self):
        """合法 JSON 但键类型错误：reload 接受（下游 _period 等 P0 防御兜底），
        解析失败才是 B 项"保留旧配置"的触发条件。"""
        cfg = Config(self.tmp)
        self._write_settings(json.dumps({'schedule': ['oops']}))   # 合法 JSON、非法类型
        cfg.reload()
        self.assertIsInstance(cfg.settings['schedule'], list)
        # 调度器对坏类型仍安全（P0 僵尸模式防御未被 B 项削弱）
        from app.scheduler import Scheduler
        s = Scheduler(self.tmp)
        try:
            self.assertEqual(s._period('probe'), 30 * 60.0)
        finally:
            s.db.close()

    def test_reload_updates_when_valid(self):
        cfg = Config(self.tmp)
        self._write_settings(json.dumps({'http': {'snippet_host': '10.0.0.5'}}))
        cfg.reload()
        self.assertEqual(cfg.settings['http']['snippet_host'], '10.0.0.5')

    def test_partial_reload_keeps_broken_domain_only(self):
        cfg = Config(self.tmp)
        channels_path = os.path.join(self.tmp, 'config', 'channels.json')
        with open(channels_path, 'r', encoding='utf-8') as f:
            ch = json.load(f)
        ch['channels'] = ['CCTV1', 'CCTV2']
        with open(channels_path, 'w', encoding='utf-8') as f:
            json.dump(ch, f, ensure_ascii=False)
        self._write_settings('{ broken !!')
        cfg.reload()
        # settings 损坏保留旧值，channels 正常更新（各域独立容错）
        self.assertEqual(cfg.settings['http']['port'], 8420)    # 端口未漂移
        self.assertEqual(cfg.channels['channels'], ['CCTV1', 'CCTV2'])

    def test_first_load_corrupt_uses_defaults(self):
        self._write_settings('{ broken !!')
        cfg = Config(self.tmp)
        self.assertEqual(cfg.settings['http']['port'], 8420)   # 默认值兜底
        self.assertEqual(cfg.settings['http']['host'], '0.0.0.0')


# ---------- C 失败候选退避 ----------

class TestFailBackoff(unittest.TestCase):
    """P1-C：指数退避 + 403/404 长冷却 + 冷却候选跳过。"""

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.v = Verifier(self.cfg, self.db, ProbeNet())

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _row(self, url):
        """取 source 行；不存在则插入（同 URL 多轮复用，不触发 UNIQUE 冲突）。"""
        if self.db.query_one('SELECT 1 AS x FROM sources WHERE url=?', (url,)) is None:
            insert_source(self.db, url)
        return self.db.query_one('SELECT * FROM sources WHERE url=?', (url,))

    def test_schema_v2_backoff_column_and_index(self):
        cols = [r['name'] for r in self.db.query('PRAGMA table_info(sources)')]
        self.assertIn('backoff_until', cols)
        idx = self.db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_sources_status'")
        self.assertEqual(len(idx), 1)

    def test_exponential_backoff_on_consecutive_fail(self):
        fail = {'alive': False, 'why': 'TimeoutError'}
        url = 'http://x/a.m3u8'
        for expected_days in (None, 3, 7, 30, 30):
            row = self._row(url)
            self.v.record(row, fail, 100)
            cur = self.db.query_one('SELECT fail_streak, backoff_until FROM sources WHERE url=?',
                                    (url,))
            if expected_days is None:
                self.assertIsNone(cur['backoff_until'])
            else:
                lo = iso_plus_hours(expected_days * 24 - 1)
                hi = iso_plus_hours(expected_days * 24 + 1)
                self.assertTrue(lo < cur['backoff_until'] < hi,
                                'streak=%s 期望 %s 天' % (cur['fail_streak'], expected_days))

    def test_http_403_404_long_cooldown(self):
        for code in (403, 404):
            url = 'http://x/%d.m3u8' % code
            row = self._row(url)
            self.v.record(row, {'alive': False, 'why': 'HTTPError', 'code': code}, 100)
            cur = self.db.query_one('SELECT backoff_until FROM sources WHERE url=?', (url,))
            self.assertTrue(iso_plus_hours(30 * 24 - 1) < cur['backoff_until']
                            < iso_plus_hours(30 * 24 + 1))

    def test_ok_clears_backoff(self):
        url = 'http://x/b.m3u8'
        sid = insert_source(self.db, url)
        self.db.execute('UPDATE sources SET fail_streak=3, backoff_until=? WHERE id=?',
                        (iso_plus_hours(72), sid))
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)',
                            'note': 'video', 'type': 'HLS'}, 100)
        cur = self.db.query_one('SELECT status, backoff_until, fail_streak FROM sources WHERE id=?',
                                (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertIsNone(cur['backoff_until'])
        self.assertEqual(cur['fail_streak'], 0)

    def test_run_skips_cooldown_candidates(self):
        hot = 'http://x/hot.m3u8'      # 冷却中
        cold = 'http://x/cold.m3u8'    # 正常候选
        insert_source(self.db, hot)
        self.db.execute("UPDATE sources SET backoff_until=? WHERE url=?",
                        (iso_plus_hours(72), hot))
        insert_source(self.db, cold)
        net = ProbeNet()
        self.v.net = net
        self.v.run()
        fetched = [u for u, _ in net.fetched]
        self.assertIn(cold, fetched)
        self.assertNotIn(hot, fetched)


# ---------- F 弱频道优先验证 ----------

class TestWeakChannelPriority(unittest.TestCase):
    """P1-F：有效线路 <2 的频道候选验证排序靠前。"""

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.v = Verifier(self.cfg, self.db, ProbeNet())

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_weak_channel_first(self):
        # CCTV1：2 条 active video（强），候选 score 100；CCTV2：0 线路（弱），score 10
        insert_source(self.db, 'http://x/s1', key='CCTV1', status='active', av='video(TS)', score=50)
        insert_source(self.db, 'http://x/s2', key='CCTV1', status='active', av='video(TS)', score=40)
        insert_source(self.db, 'http://x/strong.m3u8', key='CCTV1', score=100)
        insert_source(self.db, 'http://x/weak.m3u8', key='CCTV2', score=10)
        self.cfg.settings['limits']['verify_batch'] = 1
        batch = self.v._select_batch(1)
        self.assertEqual(len(batch), 1)
        self.assertEqual(batch[0]['url'], 'http://x/weak.m3u8')

    def test_strong_channel_order_by_score_when_both_weak_or_both_strong(self):
        insert_source(self.db, 'http://x/a.m3u8', key='CCTV1', score=30)
        insert_source(self.db, 'http://x/b.m3u8', key='CCTV2', score=90)
        batch = self.v._select_batch(10)
        self.assertEqual([r['url'] for r in batch],
                         ['http://x/b.m3u8', 'http://x/a.m3u8'])   # 同级 score DESC


# ---------- D probe_alive HTML 嗅探 ----------

class TestProbeAliveHtmlSniff(unittest.TestCase):
    """P1-D：HTML 错误页/拦截页的 200 不再判存活。"""

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.m = HealthMonitor(self.cfg, self.db, LiveNet({}))

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_html_page_rejected(self):
        net = LiveNet({'http://x/a': (200, b'<html><body>404</body></html>', 'text/html')})
        self.m.net = net
        alive, _lat, reason = self.m.probe_alive('http://x/a')
        self.assertFalse(alive)
        self.assertEqual(reason, 'HTML错误页')

    def test_doctype_page_rejected(self):
        net = LiveNet({'http://x/b': (200, b'<!DOCTYPE html><html>cf</html>', 'text/html')})
        self.m.net = net
        alive, _lat, _r = self.m.probe_alive('http://x/b')
        self.assertFalse(alive)

    def test_text_html_content_type_rejected(self):
        net = LiveNet({'http://x/c': (200, b'error page body', 'text/html; charset=utf-8')})
        self.m.net = net
        alive, _lat, _r = self.m.probe_alive('http://x/c')
        self.assertFalse(alive)

    def test_media_stream_still_alive(self):
        net = LiveNet({'http://x/d': (200, b'\x00\x01\x02binarytsdata' * 20, 'video/mp2t')})
        self.m.net = net
        alive, _lat, _r = self.m.probe_alive('http://x/d')
        self.assertTrue(alive)

    def test_m3u8_header_still_alive(self):
        net = LiveNet({'http://x/e': (200, b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n',
                                      'application/vnd.apple.mpegurl')})
        self.m.net = net
        alive, _lat, _r = self.m.probe_alive('http://x/e')
        self.assertTrue(alive)


# ---------- E healthz JSON + 调度心跳 ----------

class TestHealthzHeartbeat(unittest.TestCase):
    """P1-E：healthz 暴露调度心跳，超龄 503（healthcheck 升级判定依据）。"""

    def setUp(self):
        self.tmp = make_root()
        self.s = Scheduler(self.tmp)

    def tearDown(self):
        try:
            self.s.db.close()
        except Exception:
            pass
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_healthz_ok_with_fresh_heartbeat(self):
        self.s.last_tick_time = time.time()
        p = StatusProvider(self.s.cfg, self.s.db, self.s)
        code, data = p.healthz()
        self.assertEqual(code, 200)
        self.assertEqual(data['status'], 'ok')
        self.assertTrue(data['scheduler_alive'])
        self.assertIn('probe_alive_rate', data)
        self.assertIn('active_sources', data)
        self.assertIn('last_tick_age_s', data)

    def test_healthz_503_when_heartbeat_stale(self):
        self.s.last_tick_time = time.time() - 999999    # 远超 5 x probe 周期
        p = StatusProvider(self.s.cfg, self.s.db, self.s)
        code, data = p.healthz()
        self.assertEqual(code, 503)
        self.assertEqual(data['status'], 'degraded')
        self.assertFalse(data['scheduler_alive'])

    def test_healthz_without_scheduler_not_false_alarm(self):
        p = StatusProvider(self.s.cfg, self.s.db, None)
        code, data = p.healthz()
        self.assertEqual(code, 200)
        self.assertIsNone(data['scheduler_alive'])


# ---------- G 状态页 ----------

class TestStatusPage(unittest.TestCase):
    """P1-G：/api/status 覆盖矩阵 + /status 预渲染 HTML + 任务历史。"""

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.cfg.channels['channels'] = ['CCTV1', 'CCTV2', 'CCTV3']
        self.cfg.channels['channel_groups'] = {'CCTV1': '央视'}

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _seed(self):
        insert_source(self.db, 'http://x/s1', key='CCTV1', status='active', av='video(TS)', score=50)
        insert_source(self.db, 'http://x/s2', key='CCTV1', status='active', av='video(TS)', score=40)
        insert_source(self.db, 'http://x/s3', key='CCTV2', status='active', av='video(TS)', score=30)
        insert_source(self.db, 'http://x/s4', key='CCTV3', status='candidate')

    def test_api_status_coverage_matrix(self):
        self._seed()
        p = StatusProvider(self.cfg, self.db, None)
        api = p.api_status()
        cov = api['coverage']
        self.assertEqual(cov['total'], 3)
        self.assertEqual(cov['ok'], 1)          # CCTV1 双线路
        self.assertEqual(cov['partial'], 1)     # CCTV2 单线路
        self.assertEqual(cov['missing'], 1)     # CCTV3 缺失
        by_name = {c['name']: c for c in cov['channels']}
        self.assertEqual(by_name['CCTV1']['lines'], 2)
        self.assertEqual(by_name['CCTV1']['color'], 'ok')
        self.assertEqual(by_name['CCTV2']['color'], 'warn')
        self.assertEqual(by_name['CCTV3']['color'], 'bad')
        self.assertEqual(by_name['CCTV1']['group'], '央视')
        self.assertEqual(api['sources']['active'], 3)
        self.assertEqual(api['sources']['candidate'], 1)
        self.assertEqual(api['version'], __version__)

    def test_api_status_recent_tasks_from_scheduler_history(self):
        self._seed()
        s = Scheduler(self.tmp)
        try:
            s.history.append({'task': 'probe', 'ts': utcnow(), 'ok': True, 'detail': '完成'})
            p = StatusProvider(s.cfg, s.db, s)
            api = p.api_status()
            self.assertEqual(api['recent_tasks'][0]['task'], 'probe')
        finally:
            s.db.close()

    def test_render_html_contains_channels(self):
        self._seed()
        p = StatusProvider(self.cfg, self.db, None)
        page = p.render_html()
        for token in ('CCTV1', 'CCTV2', 'CCTV3', '<table', 'iptv-nas', __version__):
            self.assertIn(token, page)

    def test_scheduler_records_task_history(self):
        self._seed()
        s = Scheduler(self.tmp)
        try:
            # 替换业务组件避免触网（与 P0 测试同手法）
            class FakeComp(object):
                def run(self):
                    return {}

                def build(self):
                    return {}

                def rebuild_lineups(self):
                    pass
            s.health, s.verifier = FakeComp(), FakeComp()
            s.discovery, s.builder = FakeComp(), FakeComp()
            s.last_run = {t: 0.0 for t in s.running}
            s.tick()
            tasks = [h['task'] for h in s.history]
            for t in ('probe', 'verify', 'discovery', 'rebuild'):
                self.assertIn(t, tasks)
            self.assertTrue(all(h['ok'] for h in s.history))
            self.assertIsNotNone(s.last_tick_time)   # P1-E 心跳被刷新
        finally:
            s.db.close()


# ---------- H 小件：dead 源过期清理 ----------

class TestDeadSourceCleanup(unittest.TestCase):
    """P1-H：dead 且 last_seen 超期删除（CASCADE 联动历史表）；其他状态保留。"""

    def setUp(self):
        self.tmp = make_root()
        self.db = Database(Config(self.tmp).db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_cleanup_removes_expired_dead_sources(self):
        old = '2020-01-01T00:00:00Z'
        now = utcnow()
        dead_old = insert_source(self.db, 'http://x/dead-old', status='dead',
                                 first_seen=old, last_seen=old)
        dead_recent = insert_source(self.db, 'http://x/dead-new', status='dead')
        active_old = insert_source(self.db, 'http://x/active-old', status='active',
                                   first_seen=old, last_seen=old)
        self.db.execute('INSERT INTO health_checks(source_id, ts, alive) VALUES (?,?,0)',
                        (dead_old, old))
        self.db.cleanup(retention_days=90, dead_retention_days=30)
        urls = {r['url'] for r in self.db.query('SELECT url FROM sources')}
        self.assertNotIn('http://x/dead-old', urls)      # 过期 dead 已删
        self.assertIn('http://x/dead-new', urls)          # 未到期 dead 保留
        self.assertIn('http://x/active-old', urls)        # active 不受 dead 清理影响
        # CASCADE：过期 dead 的探活历史一并删除
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM health_checks WHERE source_id=?', (dead_old,))['c'], 0)
        self.assertGreater(self.db.query_one(
            'SELECT COUNT(*) AS c FROM sources')['c'], 0)

    def test_cleanup_zero_dead_retention_disables(self):
        old = '2020-01-01T00:00:00Z'
        insert_source(self.db, 'http://x/dead-old', status='dead',
                      first_seen=old, last_seen=old)
        self.db.cleanup(retention_days=90, dead_retention_days=0)
        self.assertEqual(self.db.query_one('SELECT COUNT(*) AS c FROM sources')['c'], 1)


# ---------- H 小件：probe_fetch_limit 接通 ----------

class TestProbeFetchLimitWiring(unittest.TestCase):
    """P1-H：verifier 探测 fetch limit 从 settings.network.probe_fetch_limit 读取。"""

    def setUp(self):
        self.tmp = make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.cfg.settings['network']['probe_fetch_limit'] = 12345

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_playlist_and_segment_use_configured_limit(self):
        m3u = b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n'
        net = ProbeNet({'http://x/live.m3u8': (200, m3u, 'application/vnd.apple.mpegurl'),
                        'http://x/seg.ts': (200, b'\x00' * 4000, 'video/mp2t')})
        insert_source(self.db, 'http://x/live.m3u8')
        Verifier(self.cfg, self.db, net).run()
        fetched = dict(net.fetched)
        self.assertEqual(fetched.get('http://x/live.m3u8'), 12345)
        self.assertEqual(fetched.get('http://x/seg.ts'), 12345)

    def test_sniff_segment_respects_limit_param(self):
        from app.verifier import sniff_segment
        net = ProbeNet({'http://x/s.ts': (200, b'\x00' * 4000, 'video/mp2t')})
        sniff_segment(net, 'http://x/s.ts', None, limit=777)
        self.assertEqual(net.fetched[0][1], 777)


if __name__ == '__main__':
    unittest.main()
