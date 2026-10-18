# -*- coding: utf-8 -*-
"""health 单测：状态机跃迁 + 线路重排（FakeNet，全部离线）。"""
import os
import shutil
import ssl
import sys
import tempfile
import unittest
from urllib.error import HTTPError, URLError

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import make_config  # noqa: E402
from app.db import Database, utcnow                # noqa: E402
from app.health import HealthMonitor  # noqa: E402
from app.throughput import is_transport_reason  # noqa: E402
from app.net import NetPolicyError, NetResolveError         # noqa: E402


class FakeNet(object):
    """离线假网络。

    routes: dict url -> 行为，行为可为：
      - (status, data, content_type)：像正常 fetch 一样返回；
      - BaseException 实例 / 类：抛出（模拟传输层或未知异常）；
      - 无参 callable：调用后返回其结果（或抛出）。
    alive_urls: 命中即返回合法 #EXTM3U（简写）；未命中且无 route -> 抛 OSError。
    """

    ua = 'test-ua'

    def __init__(self, alive_urls=(), routes=None):
        self.alive_urls = set(alive_urls)
        self.routes = dict(routes or {})

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=512, timeout=None):
        if url in self.routes:
            action = self.routes[url]
            if isinstance(action, BaseException):
                raise action
            if isinstance(action, type) and issubclass(action, BaseException):
                raise action('route error')
            if callable(action):
                return action()
            return action
        if url in self.alive_urls:
            return 200, b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n', 'application/vnd.apple.mpegurl'
        raise OSError('dead')


class ExplodingMonitor(HealthMonitor):
    """probe_alive 对指定 URL 直接抛异常，模拟 future 级未知异常（run 收集阶段跳过）。"""

    def __init__(self, cfg, db, net, boom_url):
        super().__init__(cfg, db, net)
        self.boom_url = boom_url

    def probe_alive(self, url, ua=None):
        if url == self.boom_url:
            raise RuntimeError('boom')
        return super().probe_alive(url, ua)


class TestHealth(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-nas-health-')
        cfg = make_config()
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        cfg.settings['limits']['net_concurrency'] = 4
        # 测试中每轮都复探 dead（生产默认 4 轮一次）
        cfg.settings['health']['dead_probe_every_rounds'] = 1
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _add(self, url, key='CCTV1', status='active', av='video', score=10):
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (url, url, key, key, 'test', status, av, score, now, now))
        return self.db.query_one('SELECT id FROM sources WHERE url_hash=?', (url,))['id']

    def _monitor(self, alive_urls, routes=None):
        return HealthMonitor(self.cfg, self.db, FakeNet(alive_urls, routes))

    def _boom_monitor(self, alive_urls, routes, boom_url):
        return ExplodingMonitor(self.cfg, self.db, FakeNet(alive_urls, routes), boom_url)

    def _status(self, sid):
        return self.db.query_one('SELECT status, ok_streak, fail_streak FROM sources WHERE id=?',
                                 (sid,))

    def _events(self, sid):
        return [r['event'] for r in self.db.query(
            'SELECT event FROM health_events WHERE source_id=? ORDER BY id', (sid,))]

    def _in_lineups(self, sid):
        return bool(self.db.query_one('SELECT 1 AS x FROM lineups WHERE source_id=?', (sid,)))

    def test_full_state_machine(self):
        """active -3连败-> flaky->dead(下线)；dead -2连活-> active(回归 lineups)。"""
        sid = self._add('http://x/a')
        m_dead = self._monitor([])          # 全部探活失败
        m_alive = self._monitor(['http://x/a'])

        # 第 1 轮失败：仍 active
        m_dead.run()
        self.assertEqual(self._status(sid)['status'], 'active')
        # 第 2 轮失败：active -> flaky
        m_dead.run()
        self.assertEqual(self._status(sid)['status'], 'flaky')
        # 第 3 轮失败：flaky -> dead，移出 lineups
        m_dead.run()
        self.assertEqual(self._status(sid)['status'], 'dead')
        self.assertFalse(self._in_lineups(sid))
        self.assertEqual(self._events(sid), ['flaky', 'offline'])
        # 探活历史 3 条
        self.assertEqual(self.db.query_one(
            'SELECT COUNT(*) AS c FROM health_checks WHERE source_id=?', (sid,))['c'], 3)

        # 第 4~5 轮成功：dead -> active（连续 2 次恢复），回归 lineups
        m_alive.run()
        self.assertEqual(self._status(sid)['status'], 'dead')   # 1 次不够
        m_alive.run()
        self.assertEqual(self._status(sid)['status'], 'active')
        self.assertTrue(self._in_lineups(sid))
        self.assertEqual(self._events(sid), ['flaky', 'offline', 'online'])
        self.assertEqual(self._status(sid)['ok_streak'], 2)

    def test_flaky_recovery(self):
        """flaky 源 2 次成功后回 active，期间不下线。"""
        sid = self._add('http://x/b')
        self._monitor([]).run()
        self._monitor([]).run()
        self.assertEqual(self._status(sid)['status'], 'flaky')
        self._monitor(['http://x/b']).run()
        self._monitor(['http://x/b']).run()
        self.assertEqual(self._status(sid)['status'], 'active')

    def test_lineups_rank_by_score(self):
        """多线路按 score DESC 排 rank；audio-only 不入线路。"""
        s1 = self._add('http://x/hi', score=80)
        s2 = self._add('http://x/lo', score=10)
        s3 = self._add('http://x/aud', av='audio-only(CODECS)', score=99)
        self._monitor(['http://x/hi', 'http://x/lo']).run()
        rows = self.db.query(
            'SELECT source_id, rank FROM lineups WHERE channel_key=? ORDER BY rank', ('CCTV1',))
        ids = [r['source_id'] for r in rows]
        self.assertIn(s1, ids)
        self.assertIn(s2, ids)
        self.assertNotIn(s3, ids)                       # 纯音频不占线路位
        self.assertEqual(rows[0]['source_id'], s1)      # score 高的排 rank 0
        self.assertEqual(rows[0]['rank'], 0)

    def test_probe_round_meta_increments(self):
        self._monitor([]).run()
        r1 = int(self.db.get_meta('probe_round'))
        self._monitor([]).run()
        r2 = int(self.db.get_meta('probe_round'))
        self.assertEqual(r2, r1 + 1)

    def test_single_failure_does_not_offline(self):
        """单次失败不改变 active（容灾缓冲）。"""
        sid = self._add('http://x/c')
        self._monitor([]).run()
        self.assertEqual(self._status(sid)['status'], 'active')
        self.assertTrue(self._in_lineups(sid))

    # ---------- 整轮熔断（circuit breaker） ----------
    def test_transport_reason_predicate(self):
        """传输层谓词：仅白名单命中；HTTP/HTML/空响应/未知异常名一律拒绝。"""
        for reason in ('OSError', 'URLError', 'TimeoutError', 'gaierror',
                       'ConnectionResetError', 'timeout',
                       'NetResolveError', 'SSLError', 'SSLEOFError'):
            self.assertTrue(is_transport_reason(reason), reason)
        for reason in ('', 'HTML错误页', 'HTTP 404', 'HTTP 500/空响应',
                       'HTTP 200/空响应', 'ValueError', 'KeyError', None,
                       'NetPolicyError'):
            self.assertFalse(is_transport_reason(reason), reason)

    def test_circuit_open_on_resolve_and_ssl_failures(self):
        """NetResolveError / SSLError / SSLEOFError 全属传输层 -> 熔断冻结状态。"""
        urls = ['http://x/rs%d' % i for i in range(4)]
        sids = [self._add(u) for u in urls]
        routes = {
            urls[0]: NetResolveError('blocked non-public address'),
            urls[1]: NetResolveError('no address resolved'),
            urls[2]: ssl.SSLError('tls handshake'),
            urls[3]: ssl.SSLEOFError('tls eof'),
        }
        res = self._monitor([], routes).run()
        self.assertIs(res['circuit_open'], True)
        self.assertEqual(res['probed'], 4)
        self.assertEqual(res['dead'], 4)
        for s in sids:
            st = self._status(s)
            self.assertEqual(st['status'], 'active')
            self.assertEqual(st['fail_streak'], 0)
            self.assertEqual(self._events(s), [])

    def test_net_policy_error_not_transport_circuit(self):
        """通用 NetPolicyError（URL 语法/重定向/handler）非传输 -> 不熔断，走 on_check。"""
        urls = ['http://x/pe%d' % i for i in range(3)]
        sids = [self._add(u) for u in urls]
        routes = {u: NetPolicyError('unsupported URL scheme') for u in urls}
        res = self._monitor([], routes).run()
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['dead'], 3)
        for s in sids:
            self.assertEqual(self._status(s)['fail_streak'], 1)

    def test_circuit_open_on_transport_failures(self):
        """3 源全传输层失败 -> 熔断：只写 health_checks，冻结状态/事件/线路。"""
        urls = ['http://x/t%d' % i for i in range(3)]
        sids = [self._add(u) for u in urls]
        # 先正常一轮（全活）：建立 lineups，ok_streak=1
        self._monitor(urls).run()
        self.assertTrue(all(self._in_lineups(s) for s in sids))
        self.assertTrue(all(self._status(s)['ok_streak'] == 1 for s in sids))

        routes = {
            urls[0]: OSError('down'),
            urls[1]: URLError('dns'),
            urls[2]: TimeoutError('timeout'),
        }
        res = self._monitor([], routes).run()
        self.assertIs(res['circuit_open'], True)
        self.assertEqual(res['probed'], 3)
        self.assertEqual(res['alive'], 0)
        self.assertEqual(res['dead'], 3)

        for s in sids:
            st = self._status(s)
            self.assertEqual(st['status'], 'active')       # 状态冻结
            self.assertEqual(st['fail_streak'], 0)         # streak 冻结
            self.assertEqual(st['ok_streak'], 1)           # ok_streak 不动
            self.assertTrue(self._in_lineups(s))           # 线路未重排
            self.assertEqual(self._events(s), [])          # 无 health_events
            self.assertEqual(
                self.db.query_one(
                    'SELECT COUNT(*) AS c FROM health_checks WHERE source_id=?',
                    (s,))['c'], 2)                         # 1 正常 + 1 熔断

    def test_circuit_not_open_on_content_or_protocol_failures(self):
        """HTTP 状态 / HTML 错误页 / HTTPError：源端失败不得熔断，走 on_check。"""
        s0 = self._add('http://x/h1')      # 500 空响应
        s1 = self._add('http://x/h2')      # HTML 错误页
        s2 = self._add('http://x/h3')      # 带 .code 的 HTTPError
        routes = {
            'http://x/h1': (500, b'', None),
            'http://x/h2': (200, b'<html>blocked</html>', 'text/html'),
            'http://x/h3': HTTPError('http://x/h3', 404, 'Not Found', {}, None),
        }
        res = self._monitor([], routes).run()
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['dead'], 3)
        for s in (s0, s1, s2):
            st = self._status(s)
            self.assertEqual(st['status'], 'active')
            self.assertEqual(st['fail_streak'], 1)         # on_check 生效（未被冻结）
            self.assertEqual(self._events(s), [])
            self.assertEqual(
                self.db.query_one(
                    'SELECT COUNT(*) AS c FROM health_checks WHERE source_id=?',
                    (s,))['c'], 1)

    def test_mixed_transport_and_content_failure_does_not_open_circuit(self):
        """一条非传输层原因即拒绝熔断：2 传输 + 1 HTML -> 全部走 on_check。"""
        s0 = self._add('http://x/mix0')
        s1 = self._add('http://x/mix1')
        s2 = self._add('http://x/mix2')
        routes = {
            'http://x/mix0': OSError('down'),
            'http://x/mix1': URLError('dns'),
            'http://x/mix2': (200, b'<html>err</html>', 'text/html'),
        }
        res = self._monitor([], routes).run()
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['dead'], 3)
        for s in (s0, s1, s2):
            self.assertEqual(self._status(s)['fail_streak'], 1)

    def test_circuit_not_open_below_three_sources(self):
        """少于 3 个可处理源不得熔断（2 源全传输层失败）。"""
        s0 = self._add('http://x/two0')
        s1 = self._add('http://x/two1')
        res = self._monitor([]).run()
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['dead'], 2)
        for s in (s0, s1):
            self.assertEqual(self._status(s)['fail_streak'], 1)
            self.assertEqual(self._events(s), [])

    def test_future_exception_not_counted_in_circuit_denominator(self):
        """future 未知异常记日志跳过，不进分母：2 传输 + 1 异常 -> 不熔断。"""
        s0 = self._add('http://x/fx0')
        s1 = self._add('http://x/fx1')
        boom = 'http://x/boom'
        s2 = self._add(boom)
        routes = {
            'http://x/fx0': OSError('down'),
            'http://x/fx1': URLError('dns'),
        }
        res = self._boom_monitor([], routes, boom).run()
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['probed'], 3)
        self.assertEqual(res['dead'], 2)                   # 异常源不参与分母
        self.assertEqual(self._status(s0)['fail_streak'], 1)
        self.assertEqual(self._status(s1)['fail_streak'], 1)
        st = self._status(s2)                             # 异常源保持原状
        self.assertEqual(st['status'], 'active')
        self.assertEqual(st['fail_streak'], 0)
        self.assertEqual(
            self.db.query_one(
                'SELECT COUNT(*) AS c FROM health_checks WHERE source_id=?',
                (s2,))['c'], 0)

    def test_circuit_opens_despite_unrelated_future_exception(self):
        """3 条传输层失败 + 1 条 future 异常：异常不阻塞熔断（不在分母也不否决）。"""
        sids = [self._add('http://x/co%d' % i) for i in range(3)]
        boom = 'http://x/co-boom'
        self._add(boom)
        routes = {
            'http://x/co0': OSError('down'),
            'http://x/co1': TimeoutError('t'),
            'http://x/co2': URLError('dns'),
        }
        res = self._boom_monitor([], routes, boom).run()
        self.assertIs(res['circuit_open'], True)
        self.assertEqual(res['probed'], 4)
        self.assertEqual(res['dead'], 3)
        for s in sids:
            self.assertEqual(self._status(s)['status'], 'active')
            self.assertEqual(self._status(s)['fail_streak'], 0)

    def test_normal_rounds_keep_state_machine_and_rebuild(self):
        """未熔断轮保持 on_check 语义与 rebuild_lineups（含 flaky->dead 迁移）。"""
        s0 = self._add('http://x/n0', score=30)
        s1 = self._add('http://x/n1', score=20)
        s2 = self._add('http://x/n2', score=10)
        routes = {'http://x/n2': OSError('down')}
        m = self._monitor(['http://x/n0', 'http://x/n1'], routes)
        for _ in range(3):                                 # 每轮都有源存活 -> 永不熔断
            res = m.run()
            self.assertIs(res['circuit_open'], False)
        self.assertEqual(self._status(s2)['status'], 'dead')
        self.assertFalse(self._in_lineups(s2))
        self.assertEqual(self._events(s2), ['flaky', 'offline'])
        rows = self.db.query(
            'SELECT source_id, rank FROM lineups WHERE channel_key=? ORDER BY rank',
            ('CCTV1',))
        self.assertEqual([r['source_id'] for r in rows], [s0, s1])   # 按 score 重排


if __name__ == '__main__':
    unittest.main()
