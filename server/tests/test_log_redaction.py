# -*- coding: utf-8 -*-
"""日志脱敏：签名 query 与 userinfo 不得进入日志文本或策略异常。"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.logutil import redact_access_message, redact_request_target, redact_text, redact_url  # noqa: E402
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


if __name__ == '__main__':
    unittest.main()
