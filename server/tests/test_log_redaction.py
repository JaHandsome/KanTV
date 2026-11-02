# -*- coding: utf-8 -*-
"""日志脱敏：签名 query 与 userinfo 不得进入日志文本或策略异常。"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.logutil import (  # noqa: E402
    redact_access_message, redact_exc, redact_request_target, redact_text, redact_url,
)
from app.net import NetPolicyError, _validate_url  # noqa: E402

SECRET = 's3cret-token'


class TestRedactUrl(unittest.TestCase):
    def test_drops_query_userinfo_and_fragment(self):
        raw = 'http://user:%s@cdn.example/live/a.m3u8?token=%s&expires=1#frag' % (SECRET, SECRET)
        safe = redact_url(raw)
        self.assertEqual(safe, 'http://cdn.example/live/a.m3u8')
        self.assertNotIn(SECRET, safe)
        self.assertNotIn('user:', safe)
        self.assertNotIn('?', safe)

    def test_keeps_port_and_ipv6_host(self):
        self.assertEqual(
            redact_url('https://edge.example:8443/p?sign=%s' % SECRET),
            'https://edge.example:8443/p')
        safe = redact_url('http://[2001:db8::10]/a?token=%s' % SECRET)
        self.assertEqual(safe, 'http://[2001:db8::10]/a')
        self.assertNotIn(SECRET, safe)

    def test_multicast_query_is_dropped(self):
        self.assertEqual(
            redact_url('udp://239.1.1.1:1234?token=%s' % SECRET),
            'udp://239.1.1.1:1234')

    def test_unparseable_or_injected_text_is_placeholder(self):
        self.assertEqual(redact_url(''), '<redacted-url>')
        self.assertEqual(redact_url('not a url'), '<redacted-url>')
        self.assertEqual(redact_url('http://host/a\n?token=%s' % SECRET), '<redacted-url>')
        self.assertNotIn(SECRET, redact_url(None))

    def test_text_replacement_covers_embedded_urls(self):
        text = 'fetch failed http://cdn.example/a.m3u8?securitykey=%s after retry' % SECRET
        safe = redact_text(text)
        self.assertIn('http://cdn.example/a.m3u8', safe)
        self.assertNotIn(SECRET, safe)
        self.assertNotIn('securitykey', safe)

    def test_policy_errors_do_not_echo_secrets(self):
        samples = (
            'http://user:%s@example.com/live?token=%s' % (SECRET, SECRET),
            'http://example.com:99999/a?token=%s' % SECRET,
            'http://example.com:abc/a?token=%s' % SECRET,
        )
        for url in samples:
            with self.assertRaises(NetPolicyError) as ctx:
                _validate_url(url)
            self.assertNotIn(SECRET, str(ctx.exception))
            self.assertNotIn('user:', str(ctx.exception))

    def test_request_target_and_access_line_drop_query(self):
        target = '/live.m3u?token=%s&expires=1' % SECRET
        self.assertEqual(redact_request_target(target), '/live.m3u')
        self.assertEqual(
            redact_request_target('http://user:%s@cdn.example/a.m3u8?token=%s' % (SECRET, SECRET)),
            'http://cdn.example/a.m3u8')
        self.assertEqual(redact_request_target('/a?token=%s\nX' % SECRET), '<redacted-url>')
        safe = redact_access_message('"GET %s HTTP/1.1" 200 -' % target)
        self.assertEqual(safe, '"GET /live.m3u HTTP/1.1" 200 -')
        self.assertNotIn(SECRET, safe)

    def test_access_line_control_character_drops_forged_tail(self):
        """请求行中间的控制字符会截断 \\S 匹配，后面的伪造行和 query 不能留下。"""
        for control in ('\r', '\n', '\x00', '\x0b', '\x7f', '\x85', '\xa0'):
            raw = (
                '"GET /foo%s10.0.0.2 - "GET /admin?token=%s HTTP/1.1" 200 -" HTTP/1.1" 200 -'
                % (control, SECRET)
            )
            safe = redact_access_message(raw)
            self.assertNotIn(control, safe)
            self.assertNotIn(SECRET, safe)
            self.assertNotIn('admin', safe)
            self.assertNotIn('\r', safe)
            self.assertNotIn('\n', safe)
            self.assertIn('/foo', safe)

    def test_syntax_error_line_drops_query_and_userinfo(self):
        """语法错误走 %r，单引号里的 query 和 userinfo 也要丢掉。"""
        query = (
            "code 400, message Bad request syntax ('GET /live.txt?token=%s oops HTTP/1.1')"
            % SECRET
        )
        safe_query = redact_access_message(query)
        self.assertIn('/live.txt', safe_query)
        self.assertNotIn(SECRET, safe_query)
        self.assertNotIn('token=', safe_query)
        self.assertNotIn('?', safe_query)

        userinfo = (
            "code 400, message Bad request syntax "
            "('GET http://user:%s@cdn.example/a extra HTTP/1.1')" % SECRET
        )
        safe_user = redact_access_message(userinfo)
        self.assertIn('http://cdn.example/a', safe_user)
        self.assertNotIn(SECRET, safe_user)
        self.assertNotIn('user:', safe_user)

    def test_live_access_log_does_not_keep_query_token(self):
        import logging
        import tempfile
        import threading
        import urllib.error
        import urllib.request
        from app.httpserver import _BoundedHTTPServer, _make_handler

        records = []

        class Capture(logging.Handler):
            def emit(self, record):
                records.append(record.getMessage())

        logger = logging.getLogger('http')
        capture = Capture()
        logger.addHandler(capture)
        logger.setLevel(logging.INFO)
        try:
            with tempfile.TemporaryDirectory() as output:
                server = _BoundedHTTPServer(('127.0.0.1', 0), _make_handler(output, None))
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    url = 'http://127.0.0.1:%d/live.txt?token=%s&expires=9' % (
                        server.server_address[1], SECRET)
                    try:
                        urllib.request.urlopen(url, timeout=5)
                    except urllib.error.HTTPError:
                        pass
                finally:
                    server.shutdown()
                    server.server_close()
        finally:
            logger.removeHandler(capture)
        self.assertTrue(records)
        joined = '\n'.join(records)
        self.assertIn('/live.txt', joined)
        self.assertNotIn(SECRET, joined)
        self.assertNotIn('token=', joined)

    def test_live_access_log_drops_forged_tail_and_syntax_error_secret(self):
        import logging
        import socket
        import tempfile
        import threading
        from app.httpserver import _BoundedHTTPServer, _make_handler

        records = []

        class Capture(logging.Handler):
            def emit(self, record):
                records.append(record.getMessage())

        logger = logging.getLogger('http')
        capture = Capture()
        logger.addHandler(capture)
        logger.setLevel(logging.INFO)
        payloads = (
            b'GET /foo\rGET /admin?token=' + SECRET.encode('ascii')
            + b' HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n',
            b'GET /live.txt?token=' + SECRET.encode('ascii')
            + b' oops HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n',
            b'GET http://user:' + SECRET.encode('ascii')
            + b'@cdn.example/a extra HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n',
        )
        try:
            with tempfile.TemporaryDirectory() as output:
                server = _BoundedHTTPServer(('127.0.0.1', 0), _make_handler(output, None))
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    for payload in payloads:
                        sock = socket.create_connection(server.server_address, timeout=5)
                        try:
                            sock.sendall(payload)
                            sock.settimeout(2)
                            try:
                                while sock.recv(4096):
                                    pass
                            except socket.timeout:
                                pass
                        finally:
                            sock.close()
                finally:
                    server.shutdown()
                    server.server_close()
                    thread.join(timeout=5)
        finally:
            logger.removeHandler(capture)
        self.assertTrue(records)
        joined = '\n'.join(records)
        self.assertIn('/foo', joined)
        self.assertIn('/live.txt', joined)
        self.assertIn('cdn.example', joined)
        self.assertNotIn(SECRET, joined)
        self.assertNotIn('token=', joined)
        self.assertNotIn('user:', joined)
        for message in records:
            self.assertNotIn('\r', message)
            self.assertNotIn('\n', message)

    def test_exception_text_drops_redirect_token_stream_url_and_newlines(self):
        import urllib.error
        secret_url = 'http://cdn.example/live.m3u8?token=%s&expires=1' % SECRET
        err = urllib.error.HTTPError(
            secret_url, 302,
            "Found - Redirection to url '%s' is not allowed" % secret_url,
            None, None)
        safe = redact_exc(err)
        self.assertIn('http://cdn.example/live.m3u8', safe)
        self.assertNotIn(SECRET, safe)
        self.assertNotIn('token=', safe)
        self.assertNotIn('?', safe)
        stream = redact_exc("probe failed\r\nrtmp://edge.example/live?sign=%s" % SECRET)
        self.assertEqual(stream, 'probe failed  rtmp://edge.example/live')
        self.assertNotIn('\n', redact_exc('a\nb'))
        self.assertNotIn(SECRET, stream)

    def test_probe_verify_discovery_and_status_history_drop_redirect_token(self):
        import logging
        import shutil
        import tempfile
        import urllib.error
        from unittest import mock
        from app.config import Config
        from app.db import Database, utcnow
        from app.discovery import RepoProvider
        from app.health import HealthMonitor
        from app.scheduler import Scheduler
        from app.verifier import Verifier
        from offline_fixtures import write_test_config

        secret_url = 'http://cdn.example/live.m3u8?token=%s&expires=1' % SECRET
        err = urllib.error.HTTPError(
            secret_url, 302,
            "Found - Redirection to url '%s' is not allowed\nFORGED-LINE" % secret_url,
            None, None)

        def capture(logger_name, fn):
            records = []

            class Capture(logging.Handler):
                def emit(self, record):
                    records.append(record.getMessage())

            logger = logging.getLogger(logger_name)
            handler = Capture()
            previous = logger.level
            logger.addHandler(handler)
            logger.setLevel(logging.INFO)
            try:
                return fn(), '\n'.join(records)
            finally:
                logger.removeHandler(handler)
                logger.setLevel(previous)

        tmp = tempfile.mkdtemp(prefix='iptv-redact-exc-')
        try:
            write_test_config(tmp)
            cfg = Config(tmp)
            cfg.settings['paths']['db'] = os.path.join(tmp, 'test.db')
            cfg.settings['limits']['net_concurrency'] = 2
            cfg.settings['limits']['verify_batch'] = 4
            db = Database(cfg.db_path)
            db.migrate()
            try:
                now = utcnow()
                db.execute(
                    "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin,"
                    " status, av, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    ('http://x/alive', 'http://x/alive', 'CCTV1', 'CCTV1', 'test',
                     'active', 'video', 10, now, now))

                class BoomHealth(HealthMonitor):
                    def probe_alive(self, url, ua=None):
                        raise err

                _, health_log = capture(
                    'health', lambda: BoomHealth(cfg, db, _IdleNet()).run())
                self.assertIn('探活任务异常', health_log)
                self.assertIn('FORGED-LINE', health_log)
                self.assertNotIn('\nFORGED-LINE', health_log)
                self._assert_secret_gone(health_log)

                db.execute(
                    "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin,"
                    " status, av, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    ('http://x/cand', 'http://x/cand', 'CCTV1', 'CCTV1', 'test',
                     'candidate', None, 10, now, now))

                def verify():
                    with mock.patch('app.verifier.probe', side_effect=err):
                        return Verifier(cfg, db, _IdleNet()).run()

                _, verify_log = capture('verifier', verify)
                self.assertIn('验证探测异常', verify_log)
                self._assert_secret_gone(verify_log)

                db.execute(
                    'INSERT INTO repos(repo, branch, path, channel, enabled) '
                    'VALUES (?,?,?,?,1)',
                    ('example/list', 'main', 'a.m3u', 'jsdelivr'))

                class ListNet(_IdleNet):
                    def fetch_retry(self, url, limit=None, ua=None):
                        return b'#EXTM3U\n'

                    def jsdelivr_url(self, repo, branch, path):
                        return 'https://cdn.example/list.m3u'

                    ghproxy_url = jsdelivr_url

                def discover():
                    with mock.patch('app.discovery.parse_playlist', side_effect=err):
                        return RepoProvider(cfg, db, ListNet()).collect()

                _, discovery_log = capture('discovery', discover)
                self.assertIn('仓库 example/list 处理异常', discovery_log)
                self._assert_secret_gone(discovery_log)
            finally:
                db.close()

            sched = Scheduler(tmp)
            try:
                def fail_task():
                    raise err

                _, sched_log = capture('scheduler', lambda: sched._run_task('probe', fail_task))
                self.assertIn('任务 probe 异常', sched_log)
                self._assert_secret_gone(sched_log)
                detail = sched.history[-1]['detail']
                self.assertIn('异常:', detail)
                self._assert_secret_gone(detail)
                self.assertNotIn('\n', detail)
            finally:
                sched.db.close()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def _assert_secret_gone(self, text):
        self.assertNotIn(SECRET, text)
        self.assertNotIn('token=', text)


class _IdleNet(object):
    ua = 'test-ua'

    @staticmethod
    def enc(url):
        return url


if __name__ == '__main__':
    unittest.main()
