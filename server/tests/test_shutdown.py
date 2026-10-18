# -*- coding: utf-8 -*-
"""停机竞态单测（全部离线、确定性，不触真实 data/iptv.db 与 NAS）。

覆盖：
- `HttpServer.shutdown()` 会等待已进入的 handler 结束：handler 被事件阻塞时
  shutdown 不得返回；释放后 shutdown 才完成（证明在途请求被 drain）；
- `shutdown()` 幂等：重复 / 并发调用只收尾一次且均正常返回；
- 入口收尾顺序：`finalize_resources()` 先停调度、再等 HTTP 在途请求，
  最后才关闭主库（helper 直接可测，不运行真正 main）。
"""
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import main as main_module          # noqa: E402
from app.httpserver import HttpServer        # noqa: E402

# 负向断言宽限：必须大于 serve_forever 默认 poll_interval(0.5s)，让退化行为
# （daemon 线程未被 join）有机会过早返回而被捕获。
DRAIN_GRACE_S = 2.0


class _Cfg(object):
    """HttpServer 所需最小配置面（不读真实 config/）。"""

    def __init__(self, output_dir):
        self.output_dir = output_dir
        self.settings = {'http': {'host': '127.0.0.1', 'port': 0}}

    def http_base(self):
        return 'http://127.0.0.1:0'


class _BlockingStatus(object):
    """`/healthz` 用的假 StatusProvider：进入即置位，等 release 才返回。"""

    def __init__(self, entered, release):
        self.entered = entered
        self.release = release
        self.done = threading.Event()

    def healthz(self):
        self.entered.set()
        self.release.wait(10)
        self.done.set()
        return 200, {'status': 'ok'}


class TestShutdownDrainsInflight(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix='iptv-shutdown-')
        self.cfg = _Cfg(self.root)
        self.entered = threading.Event()
        self.release = threading.Event()
        self.status = _BlockingStatus(self.entered, self.release)
        self.server = HttpServer(self.cfg, status_provider=self.status)
        self.port = self.server.httpd.server_address[1]
        self.serve_thread = threading.Thread(target=self.server.serve_forever,
                                             daemon=True)
        self.serve_thread.start()

    def tearDown(self):
        self.release.set()
        self.server.shutdown()
        self.serve_thread.join(timeout=5)
        shutil.rmtree(self.root, ignore_errors=True)

    def test_shutdown不早于在途handler结束(self):
        """handler 阻塞期间 shutdown 不得返回；释放后请求正常完成。"""
        result = {}

        def _client():
            try:
                with urllib.request.urlopen(
                        'http://127.0.0.1:%d/healthz' % self.port, timeout=10) as r:
                    result['status'] = r.status
                    result['body'] = r.read()
            except Exception as e:  # noqa: BLE001 - 失败信息由断言暴露
                result['error'] = e

        client = threading.Thread(target=_client, daemon=True)
        client.start()
        self.assertTrue(self.entered.wait(5), 'handler 未进入 /healthz')

        called = threading.Event()
        returned = threading.Event()

        def _stop():
            called.set()
            self.server.shutdown()
            returned.set()

        stopper = threading.Thread(target=_stop, daemon=True)
        stopper.start()
        self.assertTrue(called.wait(2), 'shutdown 线程未启动')

        # handler 仍被阻塞：server_close() 必须 join 它，shutdown 不得返回
        self.assertFalse(returned.wait(DRAIN_GRACE_S),
                         'shutdown 在在途 handler 释放前返回（请求线程未被 drain）')

        self.release.set()
        client.join(timeout=10)
        stopper.join(timeout=10)
        self.assertTrue(returned.is_set(), 'shutdown 未完成')
        self.assertTrue(self.status.done.is_set(), 'handler 未正常结束')
        self.assertIsNone(result.get('error'), result.get('error'))
        self.assertEqual(result.get('status'), 200)
        self.assertIn(b'ok', result.get('body') or b'')

    def test_shutdown幂等且可并发(self):
        """无在途请求时并发多次 shutdown 只收尾一次，均正常返回。"""
        threads = [threading.Thread(target=self.server.shutdown, daemon=True)
                   for _ in range(2)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)
        self.assertFalse(any(t.is_alive() for t in threads))
        self.server.shutdown()   # 收尾后再调用仍幂等


class TestFinalizeResourcesOrder(unittest.TestCase):
    def test_收尾顺序_先停HTTP再关主库(self):
        """`finalize_resources` 必须在关闭主库前完成 HTTP（在途请求）收尾。"""
        log = []

        class _Scheduler(object):
            def stop(self):
                log.append('scheduler.stop')

            def join(self, timeout=None):
                log.append('scheduler.join')

        class _Http(object):
            def shutdown(self):
                log.append('http.shutdown')

        class _Db(object):
            def close(self):
                log.append('db.close')

        main_module.finalize_resources(_Scheduler(), _Http(), _Db())

        self.assertEqual(log, ['scheduler.stop', 'http.shutdown',
                               'scheduler.join', 'db.close'])
        self.assertLess(log.index('http.shutdown'), log.index('db.close'))

    def test_HTTP收尾失败时不关闭主库(self):
        """未能证明 handler 已排空时，主库必须保持打开。"""
        log = []

        class _Scheduler(object):
            def stop(self):
                log.append('scheduler.stop')

            def join(self, timeout=None):
                log.append('scheduler.join')

        class _Http(object):
            def shutdown(self):
                log.append('http.shutdown')
                raise RuntimeError('drain failed')

        class _Db(object):
            def close(self):
                log.append('db.close')

        with self.assertRaisesRegex(RuntimeError, 'drain failed'):
            main_module.finalize_resources(_Scheduler(), _Http(), _Db())

        self.assertEqual(log, ['scheduler.stop', 'http.shutdown'])


if __name__ == '__main__':
    unittest.main()
