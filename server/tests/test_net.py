# -*- coding: utf-8 -*-
"""net 单测：并发闸门 + CDN URL 构造 + 网络硬时限 + SSRF 边界（全部离线，不依赖外网 DNS）。"""
import ipaddress
import http.client
import os
import socket
import ssl
import sys
import threading
import time
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import net as netmod      # noqa: E402
from offline_fixtures import make_config  # noqa: E402
from app.net import NetClient      # noqa: E402

# 硬时限测试参数（秒）
BUDGET_S = 0.6
TIMEOUT_S = 0.3
# 预算 + 调度裕量：修复后必须在此之内抛 TimeoutError
SCHED_MARGIN_S = 0.3


def make_net(concurrency=2):
    cfg = make_config()
    cfg.settings['limits']['net_concurrency'] = concurrency
    return NetClient(cfg)


def _loopback_resolver(host, port):
    """测试专用：仅为回环 fixture 提供受控连接能力。

    生产默认策略仍拒绝回环/私网；非 fixture 目标继续走真实 ``_resolve_pinned``。
    """
    if host not in ('127.0.0.1', '::1', 'localhost'):
        return netmod._resolve_pinned(host, port)
    return list(socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM))


class TestGate(unittest.TestCase):
    def test_gate_blocks_over_concurrency(self):
        """并发闸门：第 N+1 个请求必须阻塞等待（T01 验收点）。"""
        net = make_net(concurrency=2)
        self.assertTrue(net.gate.acquire(timeout=0.1))   # 占 1
        self.assertTrue(net.gate.acquire(timeout=0.1))   # 占 2（满）
        self.assertFalse(net.gate.acquire(timeout=0.3))  # 第 3 个必须阻塞
        net.gate.release()                               # 释放 1 个
        self.assertTrue(net.gate.acquire(timeout=0.3))    # 立即可取
        net.gate.release()
        net.gate.release()

    def test_gate_threaded(self):
        """16 并发 fetch 时第 17 个请求阻塞等待（用本地回环函数模拟网络 IO）。"""
        net = make_net(concurrency=3)
        active = [0]
        peak = [0]
        lock = threading.Lock()

        def fake_io():
            with net.gate:
                with lock:
                    active[0] += 1
                    peak[0] = max(peak[0], active[0])
                time.sleep(0.05)
                with lock:
                    active[0] -= 1

        threads = [threading.Thread(target=fake_io) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertLessEqual(peak[0], 3)
        self.assertGreater(peak[0], 0)


class TestCdnUrls(unittest.TestCase):
    def setUp(self):
        self.net = make_net()

    def test_jsdelivr_url(self):
        url = self.net.jsdelivr_url('Guovin/iptv-api', 'gd', 'output/result.m3u')
        self.assertEqual(url, 'https://cdn.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u')

    def test_ghproxy_url(self):
        url = self.net.ghproxy_url('suxuang/myIPTV', 'main', 'ipv4.m3u')
        self.assertTrue(url.startswith('https://'))
        self.assertIn('suxuang/myIPTV/main/ipv4.m3u', url)

    def test_enc_leaves_ascii(self):
        self.assertEqual(NetClient.enc('http://a.com/x?a=1'), 'http://a.com/x?a=1')

    def test_enc_quotes_cjk(self):
        enc = NetClient.enc('http://a.com/中文.m3u8')
        self.assertNotIn('中', enc)
        self.assertIn('http://a.com/', enc)


# --------------------------------------------------------------------------
# 本机 HTTP fixture：/fast 立即返回；/drip 慢滴（每次 1 字节 + sleep）；
# /redirect 302 -> /fast。绝不把日志/回溯打到测试输出。
# --------------------------------------------------------------------------
class _Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):  # 静音访问日志
        pass

    def do_GET(self):
        if self.path.startswith('/policy_redirect/'):
            targets = {
                'same': '/fast',
                'private_a': 'http://10.0.0.1:%d/fast' % self.server.server_port,
                'private_b': 'http://10.0.0.2:%d/fast' % self.server.server_port,
                'public': 'http://public.example:%d/fast' % self.server.server_port,
            }
            self.send_response(302)
            self.send_header('Location', targets[self.path.rsplit('/', 1)[-1]])
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if self.path.startswith('/drip302'):
            self._drip302()
            return
        if self.path.startswith('/redirect'):
            self.send_response(302)
            self.send_header('Location', '/fast')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if self.path.startswith('/redir_private'):
            self.send_response(302)
            self.send_header('Location', 'http://10.0.0.1/')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if self.path.startswith('/redir_ftp'):
            self.send_response(302)
            self.send_header('Location', 'ftp://example.com/x')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if self.path.startswith('/drip'):
            self._drip()
            return
        self._fast()

    def _fast(self):
        body = self.server.fast_body
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass

    def _drip(self):
        total = self.server.drip_total
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(total))
        self.end_headers()
        try:
            while True:
                self.wfile.write(b'x')
                self.wfile.flush()
                time.sleep(self.server.drip_s)
        except OSError:
            # 客户端超时/断开：正常收尾，不打印回溯
            pass

    def _drip302(self):
        """302 也带慢滴响应体：安全实现必须 close 而非 drain。

        总量刻意有界（150 字节 × 20ms ≈ 3s）：若实现退回无界 drain，测试会在
        ~3s 后才完成并触及时限断言，而不是挂死。
        """
        self.send_response(302)
        self.send_header('Location', '/fast')
        self.send_header('Content-Length', str(self.server.drip302_total))
        self.end_headers()
        try:
            while True:
                self.wfile.write(b'x')
                self.wfile.flush()
                time.sleep(self.server.drip_s)
        except OSError:
            pass


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    fast_body = b'fast-body-0123456789'
    drip_s = 0.02
    drip_total = 100000000
    drip302_total = 150

    def handle_error(self, request, client_address):  # 静音 socketserver 回溯
        pass


class TestHardDeadline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = _Server(('127.0.0.1', 0), _Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        host, port = cls.server.server_address[:2]
        cls.base = 'http://%s:%d' % (host, port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2.0)

    def setUp(self):
        self.net = make_net(concurrency=2)
        # 仅为测试实例注入：允许本机回环 fixture；默认生产策略仍拒绝回环。
        self.net._resolver = _loopback_resolver

    # ---- 语义保持 ----
    def test_fast_path_preserves_semantics(self):
        url = self.base + '/fast'
        status, body, ct, final_url = self.net.fetch_ex(url, timeout=1.0)
        self.assertEqual(status, 200)
        self.assertEqual(body, _Server.fast_body)
        self.assertEqual(ct, 'application/octet-stream')
        self.assertEqual(final_url, url)

    def test_limit_truncates_body(self):
        url = self.base + '/fast'
        _st, body, _ct, _final = self.net.fetch_ex(url, limit=4, timeout=1.0)
        self.assertEqual(body, _Server.fast_body[:4])

    def test_fetch_wrapper_returns_triple(self):
        url = self.base + '/fast'
        result = self.net.fetch(url, timeout=1.0)
        self.assertEqual(
            result, (200, _Server.fast_body, 'application/octet-stream'))

    def test_redirect_final_url(self):
        url = self.base + '/redirect'
        _st, _body, _ct, final_url = self.net.fetch_ex(url, timeout=1.0)
        self.assertEqual(final_url, self.base + '/fast')

    def test_30x_slow_drip_body_does_not_hold_budget(self):
        # 302 响应体慢滴：安全实现只 close 不 drain，应迅速到达 /fast
        start = time.monotonic()
        status, body, _ct, final_url = self.net.fetch_ex(
            self.base + '/drip302', timeout=0.3, budget_s=2.0)
        elapsed = time.monotonic() - start
        self.assertEqual(status, 200)
        self.assertEqual(body, _Server.fast_body)
        self.assertEqual(final_url, self.base + '/fast')
        self.assertLess(elapsed, 1.5, '30x slow-drip 占住了预算: %.3fs' % elapsed)

    # ---- 修复后：硬时限在预算 + 裕量内触发 ----
    def test_new_deadline_raises_within_budget(self):
        url = self.base + '/drip'
        start = time.monotonic()
        with self.assertRaises(TimeoutError):
            self.net.fetch_ex(url, timeout=TIMEOUT_S, budget_s=BUDGET_S)
        elapsed = time.monotonic() - start
        self.assertLess(
            elapsed, BUDGET_S + SCHED_MARGIN_S,
            '硬时限未在预算+裕量内触发: %.3fs' % elapsed)

    # ---- 标准响应 socket 可取得（否则硬时限会退化为有界读取） ----
    def test_response_socket_resolvable(self):
        req = urllib.request.Request(self.base + '/fast')
        with urllib.request.urlopen(req, timeout=1.0) as r:
            self.assertIsNotNone(NetClient._response_socket(r))

    # ---- 每次阻塞读前确实收紧底层 socket 超时 ----
    def test_per_read_socket_timeout_is_tightened(self):
        net = make_net(concurrency=2)
        net._resolver = _loopback_resolver
        calls = []
        real = NetClient._set_response_socket_timeout

        def spy(self, r, seconds):
            calls.append(seconds)
            return real(self, r, seconds)

        with mock.patch.object(NetClient, '_set_response_socket_timeout', spy):
            with self.assertRaises(TimeoutError):
                net.fetch_ex(
                    self.base + '/drip', timeout=TIMEOUT_S, budget_s=BUDGET_S)
        self.assertTrue(calls, '未在阻塞读前调用 socket 超时收紧')
        self.assertTrue(all(s > 0 for s in calls), '收紧后的超时必须为正')

    def test_nonstandard_response_fails_closed_before_read(self):
        class ResponseWithoutSocket(object):
            fp = object()
            length = None

            def __init__(self):
                self.read_called = False

            def read1(self, _size):
                self.read_called = True
                return b'x'

        response = ResponseWithoutSocket()
        with self.assertRaises(RuntimeError):
            self.net._read_body(
                response, 1, time.monotonic() + 1.0, per_read_timeout=0.5)
        self.assertFalse(response.read_called)

    # ---- 闸门等待耗尽时不得发起网络请求 ----
    def test_gate_exhaustion_skips_open(self):
        net = make_net(concurrency=1)
        net._resolver = _loopback_resolver
        self.assertTrue(net.gate.acquire(timeout=0.1))
        try:
            with mock.patch.object(net._opener, 'open') as mocked:
                with self.assertRaises(TimeoutError):
                    net.fetch_ex(
                        self.base + '/fast', timeout=0.2, budget_s=0.3)
            mocked.assert_not_called()
        finally:
            net.gate.release()


class _FakeRedirectFP(object):
    """确定性的 30x 响应替身：记录 drain/close，绝不真正阻塞。"""

    def __init__(self):
        self.read_called = False
        self.closed = False

    def read(self, *args):
        self.read_called = True
        return b''

    def close(self):
        self.closed = True


class TestRedirectHandlerDeadline(unittest.TestCase):
    """离线确定性：30x 处理不 drain、会 close、传 deadline、按剩余预算收紧 timeout。"""

    def _make_handler(self):
        handler = netmod._PinnedRedirectHandler()
        opened = []

        class _Parent(object):
            def open(self, req, timeout=None):
                opened.append((req, timeout))
                return 'next-hop'

        handler.parent = _Parent()
        return handler, opened

    @staticmethod
    def _request(timeout=30.0, deadline=None):
        req = urllib.request.Request(
            'http://public.example/start', headers={'User-Agent': 'test'})
        req.timeout = timeout
        if deadline is not None:
            setattr(req, netmod._DEADLINE_ATTR, deadline)
        return req

    @staticmethod
    def _headers(location):
        msg = http.client.HTTPMessage()
        msg['Location'] = location
        return msg

    def test_closes_without_draining_and_passes_deadline(self):
        handler, opened = self._make_handler()
        deadline = time.monotonic() + 5.0
        req = self._request(timeout=30.0, deadline=deadline)
        fp = _FakeRedirectFP()
        result = handler.http_error_302(
            req, fp, 302, 'Found', self._headers('http://public.example/final'))
        self.assertEqual(result, 'next-hop')
        self.assertFalse(fp.read_called, '30x 处理绝不能 drain 响应体')
        self.assertTrue(fp.closed, '30x 响应必须被 close')
        self.assertEqual(len(opened), 1)
        new_req, timeout = opened[0]
        self.assertEqual(new_req.full_url, 'http://public.example/final')
        # deadline 继承到下一跳 Request
        self.assertEqual(getattr(new_req, netmod._DEADLINE_ATTR), deadline)
        # 以剩余预算收紧 timeout：>0 且不超过原 timeout / 剩余预算
        self.assertGreater(timeout, 0)
        self.assertLess(timeout, 30.0)
        self.assertLessEqual(timeout, 5.0)

    def test_all_30x_aliases_share_safe_handler(self):
        for method in ('http_error_301', 'http_error_303', 'http_error_307'):
            handler, opened = self._make_handler()
            req = self._request(deadline=time.monotonic() + 5.0)
            fp = _FakeRedirectFP()
            getattr(handler, method)(
                req, fp, int(method[-3:]), 'redirect', self._headers('/final'))
            self.assertFalse(fp.read_called, method)
            self.assertTrue(fp.closed, method)
            self.assertEqual(len(opened), 1, method)

    def test_expired_deadline_raises_before_next_hop(self):
        handler, opened = self._make_handler()
        req = self._request(deadline=time.monotonic() - 1.0)
        fp = _FakeRedirectFP()
        with self.assertRaises(TimeoutError):
            handler.http_error_302(
                req, fp, 302, 'Found',
                self._headers('http://public.example/final'))
        self.assertEqual(opened, [], '预算过期时不得开下一跳')
        self.assertTrue(fp.closed, '过期也要先释放当前响应')

    def test_unclosable_response_is_fail_closed(self):
        handler, opened = self._make_handler()
        req = self._request(deadline=time.monotonic() + 5.0)

        class _NoClose(object):
            def read(self, *args):
                raise AssertionError('不可读取')

        with self.assertRaises(netmod.NetPolicyError):
            handler.http_error_302(
                req, _NoClose(), 302, 'Found',
                self._headers('http://public.example/final'))
        self.assertEqual(opened, [])

    def test_redirect_request_still_validates_scheme(self):
        handler, opened = self._make_handler()
        req = self._request(deadline=time.monotonic() + 5.0)
        fp = _FakeRedirectFP()
        with self.assertRaises(netmod.NetPolicyError):
            handler.http_error_302(
                req, fp, 302, 'Found', self._headers('ftp://example.com/x'))
        self.assertEqual(opened, [])
        self.assertTrue(fp.closed, '策略拒绝也必须释放当前 30x 响应')


# ---------------------------------------------------------------------------
# SSRF 边界：URL 语法 / IP 分类 / 解析绑定 / 重定向拒绝（全部离线，零公网 DNS）
# ---------------------------------------------------------------------------
class TestUrlPolicy(unittest.TestCase):
    def test_rejects_non_http_schemes(self):
        for url in ('file:///etc/passwd', 'ftp://example.com/x',
                    'gopher://example.com/x', 'data:text/plain,hi',
                    'ws://example.com/x'):
            with self.assertRaises(netmod.NetPolicyError):
                netmod._validate_url(url)

    def test_rejects_userinfo(self):
        for url in ('http://user:pass@example.com/',
                    'http://@example.com/',
                    'https://user@example.com:8443/x'):
            with self.assertRaises(netmod.NetPolicyError):
                netmod._validate_url(url)

    def test_rejects_empty_host(self):
        for url in ('http:///path', 'http://:80/x', 'https:///x'):
            with self.assertRaises(netmod.NetPolicyError):
                netmod._validate_url(url)

    def test_rejects_invalid_port(self):
        for url in ('http://example.com:0/', 'http://example.com:65536/',
                    'http://example.com:99999/', 'http://example.com:abc/'):
            with self.assertRaises(netmod.NetPolicyError):
                netmod._validate_url(url)

    def test_accepts_canonical_http_https(self):
        self.assertEqual(netmod._validate_url('http://example.com/a'),
                         ('http', 'example.com', None))
        self.assertEqual(netmod._validate_url('https://example.com:8443/a'),
                         ('https', 'example.com', 8443))
        self.assertEqual(netmod._validate_url('http://[2606:4700::1111]/a'),
                         ('http', '2606:4700::1111', None))


class TestAddressPolicy(unittest.TestCase):
    def _assert_blocked(self, *addrs):
        for addr in addrs:
            self.assertFalse(netmod._is_public_ip(ipaddress.ip_address(addr)),
                             addr)

    def test_blocks_special_ipv4(self):
        self._assert_blocked(
            '0.0.0.0', '127.0.0.1', '169.254.1.1', '10.0.0.1', '172.16.0.1',
            '192.168.1.1', '100.64.0.1', '224.0.0.1', '240.0.0.1',
            '192.0.2.1', '198.51.100.1', '203.0.113.1', '255.255.255.255')

    def test_allows_public_ipv4(self):
        for addr in ('8.8.8.8', '1.1.1.1', '93.184.216.34'):
            self.assertTrue(netmod._is_public_ip(ipaddress.ip_address(addr)),
                            addr)

    def test_blocks_special_ipv6(self):
        self._assert_blocked(
            '::', '::1', 'fe80::1', 'fc00::1', 'ff02::1', '2001:db8::1')

    def test_allows_public_ipv6(self):
        self.assertTrue(netmod._is_public_ip(
            ipaddress.ip_address('2606:4700:4700::1111')))

    def test_cgnat_boundary(self):
        self._assert_blocked('100.64.0.1', '100.127.255.254')
        self.assertTrue(netmod._is_public_ip(ipaddress.ip_address('100.63.255.255')))
        self.assertTrue(netmod._is_public_ip(ipaddress.ip_address('100.128.0.1')))

    def test_blocks_192_0_0_special_purpose_whole_24(self):
        # Python 3.9 会把 192.0.0.8/.11 等判为 global；fail-closed 整段拒绝 /24
        self._assert_blocked(
            '192.0.0.0', '192.0.0.1', '192.0.0.8', '192.0.0.9',
            '192.0.0.10', '192.0.0.11', '192.0.0.255')
        # /24 边界之外按公网放行
        self.assertTrue(netmod._is_public_ip(ipaddress.ip_address('192.0.1.0')))
        self.assertFalse(netmod._is_public_ip(ipaddress.ip_address('192.0.0.8')))

    def test_ipv4_mapped_ipv6_classified_by_embedded_ipv4(self):
        self._assert_blocked(
            '::ffff:127.0.0.1', '::ffff:10.0.0.1', '::ffff:192.168.1.1',
            '::ffff:169.254.169.254', '::ffff:100.64.0.1')
        self.assertTrue(netmod._is_public_ip(
            ipaddress.ip_address('::ffff:8.8.8.8')))

    def test_sixtofour_with_private_embedded_ipv4_rejected(self):
        self._assert_blocked('2002:0a00:0001::1')   # 6to4 内嵌 10.0.0.1
        public_embedded = ipaddress.ip_address('2002:0808:0808::1')
        # 新版 stdlib 将整个 6to4 段标为 private；公网内嵌地址不能覆盖外层拒绝策略。
        self.assertEqual(not public_embedded.is_private, netmod._is_public_ip(public_embedded))


class TestPinnedResolution(unittest.TestCase):
    _PUBLIC = (socket.AF_INET, socket.SOCK_STREAM, 6, '', ('93.184.216.34', 80))
    _PRIVATE = (socket.AF_INET, socket.SOCK_STREAM, 6, '', ('10.0.0.1', 80))

    def test_rejects_when_any_resolved_address_is_restricted(self):
        for infos in ([self._PUBLIC, self._PRIVATE],
                      [self._PRIVATE, self._PUBLIC]):
            with mock.patch.object(netmod.socket, 'getaddrinfo',
                                   return_value=infos):
                with self.assertRaises(netmod.NetPolicyError):
                    netmod._resolve_pinned('10.fast.example', 80)

    def test_domain_resolved_to_public_is_not_prefix_misjudged(self):
        # 域名 "10.fast.example" 必须靠解析结果分类，不能因字符串前缀被拒
        infos = [self._PUBLIC]
        with mock.patch.object(netmod.socket, 'getaddrinfo', return_value=infos):
            addrs = netmod._resolve_pinned('10.fast.example', 80)
        self.assertEqual(addrs[0][4][0], '93.184.216.34')

    def test_all_public_addresses_are_returned(self):
        infos = [
            self._PUBLIC,
            (socket.AF_INET6, socket.SOCK_STREAM, 6, '',
             ('2606:4700::1111', 80, 0, 0)),
        ]
        with mock.patch.object(netmod.socket, 'getaddrinfo', return_value=infos):
            addrs = netmod._resolve_pinned('example.com', 80)
        self.assertEqual(len(addrs), 2)

    def test_getaddrinfo_called_exactly_once(self):
        infos = [self._PUBLIC]
        with mock.patch.object(netmod.socket, 'getaddrinfo',
                               return_value=infos) as mocked:
            netmod._resolve_pinned('example.com', 80)
        self.assertEqual(mocked.call_count, 1)

    def test_empty_resolution_rejected(self):
        with mock.patch.object(netmod.socket, 'getaddrinfo', return_value=[]):
            with self.assertRaises(netmod.NetPolicyError):
                netmod._resolve_pinned('example.com', 80)


class TestPinnedConnection(unittest.TestCase):
    def test_connect_uses_validated_sockaddr_without_reresolving(self):
        sockaddr = ('93.184.216.34', 80)
        addrs = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', sockaddr)]
        fake_sock = mock.Mock()
        with mock.patch.object(netmod.socket, 'socket',
                               return_value=fake_sock):
            with mock.patch.object(netmod.socket, 'getaddrinfo') as mocked_dns:
                conn = netmod._PinnedHTTPConnection(
                    'example.com', 80, addrs=addrs)
                conn.connect()
        mocked_dns.assert_not_called()
        fake_sock.connect.assert_called_once_with(sockaddr)

    def test_resolved_addrs_feed_connect_directly(self):
        # 防回归：_resolve_pinned 的元组形状必须与 _connect_pinned 解包一致
        infos = [(socket.AF_INET, socket.SOCK_STREAM, 6, '',
                  ('93.184.216.34', 80))]
        with mock.patch.object(netmod.socket, 'getaddrinfo', return_value=infos):
            addrs = netmod._resolve_pinned('example.com', 80)
        fake_sock = mock.Mock()
        with mock.patch.object(netmod.socket, 'socket',
                               return_value=fake_sock):
            netmod._connect_pinned(addrs, 1.0, None)
        fake_sock.connect.assert_called_once_with(('93.184.216.34', 80))

    def test_https_connect_keeps_original_hostname_for_sni(self):
        class FakeContext(object):
            verify_mode = ssl.CERT_NONE
            check_hostname = False

            def __init__(self):
                self.calls = []
                self.wrapped = mock.Mock()

            def wrap_socket(self, sock, server_hostname=None):
                self.calls.append((sock, server_hostname))
                return self.wrapped

        sockaddr = ('93.184.216.34', 443)
        addrs = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', sockaddr)]
        tcp_sock = mock.Mock()
        ctx = FakeContext()
        with mock.patch.object(netmod.socket, 'socket', return_value=tcp_sock):
            conn = netmod._PinnedHTTPSConnection(
                'example.com', 443, context=ctx, addrs=addrs)
            conn.connect()
        self.assertEqual(ctx.calls, [(tcp_sock, 'example.com')])
        self.assertIs(conn.sock, ctx.wrapped)

    def test_connect_falls_back_to_next_validated_sockaddr(self):
        bad = ('203.0.113.9', 80)
        good = ('93.184.216.34', 80)
        addrs = [
            (socket.AF_INET, socket.SOCK_STREAM, 6, '', bad),
            (socket.AF_INET, socket.SOCK_STREAM, 6, '', good),
        ]
        first, second = mock.Mock(), mock.Mock()
        first.connect.side_effect = OSError('refused')
        with mock.patch.object(netmod.socket, 'socket',
                               side_effect=[first, second]):
            conn = netmod._PinnedHTTPConnection('example.com', 80, addrs=addrs)
            conn.connect()
        first.connect.assert_called_once_with(bad)
        second.connect.assert_called_once_with(good)
        first.close.assert_called_once()


class TestSSRFIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = _Server(('127.0.0.1', 0), _Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        host, port = cls.server.server_address[:2]
        cls.base = 'http://%s:%d' % (host, port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2.0)

    def _connect_spy(self):
        real = netmod._connect_pinned
        calls = []

        def spy(addrs, timeout, source_address):
            calls.append(addrs)
            return real(addrs, timeout, source_address)

        return calls, mock.patch.object(netmod, '_connect_pinned', side_effect=spy)

    def test_initial_private_targets_rejected_before_connect(self):
        net = make_net(concurrency=2)   # 默认生产策略
        urls = [
            self.base + '/fast',
            'http://10.0.0.1/',
            'http://[::1]/',
            'http://169.254.169.254/latest/meta-data/',
            'http://192.168.1.1/',
            'http://100.64.0.1/',
        ]
        for url in urls:
            with mock.patch.object(netmod, '_connect_pinned') as pinned:
                with self.assertRaises(netmod.NetPolicyError):
                    net.fetch_ex(url, timeout=1.0)
                pinned.assert_not_called()

    def test_initial_non_http_scheme_rejected_without_network(self):
        net = make_net(concurrency=2)
        with mock.patch.object(netmod, '_connect_pinned') as pinned:
            with self.assertRaises(netmod.NetPolicyError):
                net.fetch_ex('file:///etc/passwd')
        pinned.assert_not_called()

    def test_redirect_to_private_ip_rejected_before_connect(self):
        net = make_net(concurrency=2)
        net._resolver = _loopback_resolver
        calls, patcher = self._connect_spy()
        with patcher:
            with self.assertRaises(netmod.NetPolicyError):
                net.fetch_ex(self.base + '/redir_private', timeout=1.0)
        # 只有初始回环 hop 建连；重定向到 10.0.0.1 在 connect 前被拒
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0][4][0], '127.0.0.1')

    def test_redirect_to_ftp_scheme_rejected_before_connect(self):
        net = make_net(concurrency=2)
        net._resolver = _loopback_resolver
        calls, patcher = self._connect_spy()
        with patcher:
            with self.assertRaises(netmod.NetPolicyError):
                net.fetch_ex(self.base + '/redir_ftp', timeout=1.0)
        self.assertEqual(len(calls), 1)

    def test_public_path_semantics_and_single_resolution(self):
        net = make_net(concurrency=2)
        port = self.server.server_address[1]
        loop_infos = list(
            socket.getaddrinfo('127.0.0.1', port, 0, socket.SOCK_STREAM))
        calls = []

        def resolver(host, rport):
            calls.append((host, rport))
            return loop_infos

        net._resolver = resolver
        url = 'http://public.example:%d/fast' % port
        status, body, ct, final_url = net.fetch_ex(url, timeout=1.0)
        self.assertEqual(status, 200)
        self.assertEqual(body, _Server.fast_body)
        self.assertEqual(ct, 'application/octet-stream')
        self.assertEqual(final_url, url)
        self.assertEqual(calls, [('public.example', port)])

    def test_public_path_redirect_final_url_preserved(self):
        net = make_net(concurrency=2)
        port = self.server.server_address[1]
        loop_infos = list(
            socket.getaddrinfo('127.0.0.1', port, 0, socket.SOCK_STREAM))

        def resolver(host, rport):
            return loop_infos

        net._resolver = resolver
        url = 'http://public.example:%d/redirect' % port
        _st, body, _ct, final_url = net.fetch_ex(url, timeout=1.0)
        self.assertEqual(body, _Server.fast_body)
        self.assertEqual(final_url, 'http://public.example:%d/fast' % port)

    def test_environment_proxies_are_disabled(self):
        with mock.patch.dict(os.environ, {
                'HTTP_PROXY': 'http://127.0.0.1:9',
                'HTTPS_PROXY': 'http://127.0.0.1:9',
                'ALL_PROXY': 'http://127.0.0.1:9'}):
            net = make_net(concurrency=2)
        # ProxyHandler({}) 不注册任何 *_open，默认 ProxyHandler 也被跳过：
        # 环境代理不会被使用，连接始终直连已校验 sockaddr。
        registered = [
            handler
            for handlers in net._opener.handle_open.values()
            for handler in handlers
            if isinstance(handler, urllib.request.ProxyHandler)
        ]
        self.assertEqual(registered, [])


class TestPrivateSourcePolicy(unittest.TestCase):
    @staticmethod
    def _net(origins):
        cfg = make_config()
        cfg.settings['network']['private_source_origins'] = origins
        return NetClient(cfg)

    def test_private_literal_uses_exact_sockaddr_without_dns_or_resolver(self):
        cases = [
            ('http://10.0.0.1:8080', 'http', '10.0.0.1', 8080,
             socket.AF_INET, ('10.0.0.1', 8080)),
            ('https://[fd00::abcd]:8443', 'https', 'fd00::abcd', 8443,
             socket.AF_INET6, ('fd00::abcd', 8443, 0, 0)),
        ]
        for origin, scheme, host, port, family, sockaddr in cases:
            with self.subTest(origin=origin):
                net = self._net([origin])
                net._resolver = mock.Mock(side_effect=AssertionError('resolver called'))
                with mock.patch.object(netmod.socket, 'getaddrinfo') as dns:
                    addrs = net._resolve_target(scheme, host, port)
                    sock = mock.Mock()
                    with mock.patch.object(netmod.socket, 'socket', return_value=sock):
                        netmod._connect_pinned(addrs, 1.0, None)
                self.assertEqual(addrs[0][:4],
                                 (family, socket.SOCK_STREAM, socket.IPPROTO_TCP, ''))
                sock.connect.assert_called_once_with(sockaddr)
                dns.assert_not_called()
                net._resolver.assert_not_called()

    def test_allowlist_matches_effective_port_and_canonical_ipv6(self):
        net = self._net(['https://[FD00:0:0:0:0:0:0:1]:443', 'http://10.0.0.1:80'])
        with mock.patch.object(netmod.socket, 'getaddrinfo') as dns:
            self.assertEqual(net._resolve_target('https', 'fd00::1', None)[0][4],
                             ('fd00::1', 443, 0, 0))
            self.assertEqual(net._resolve_target('http', '10.0.0.1', None)[0][4],
                             ('10.0.0.1', 80))
        dns.assert_not_called()

    def test_non_matching_ip_scheme_and_port_still_reject(self):
        net = self._net(['http://10.0.0.1:8080'])
        for url in ('http://10.0.0.2:8080/x', 'http://10.0.0.1:8081/x',
                    'https://10.0.0.1:8080/x', 'http://10.0.0.1/x'):
            with self.subTest(url=url):
                scheme, host, port = netmod._validate_url(url)
                infos = [(socket.AF_INET, socket.SOCK_STREAM, 6, '',
                          (host, port or netmod._DEFAULT_PORTS[scheme]))]
                with mock.patch.object(netmod.socket, 'getaddrinfo', return_value=infos):
                    with mock.patch.object(netmod, '_connect_pinned') as connect:
                        with self.assertRaises(netmod.NetPolicyError):
                            net.fetch_ex(url, timeout=0.5)
                connect.assert_not_called()

    def test_public_hostname_cannot_borrow_authorized_private_ip(self):
        net = self._net(['http://10.0.0.1:8080'])
        private = (socket.AF_INET, socket.SOCK_STREAM, 6, '', ('10.0.0.1', 8080))
        public = (socket.AF_INET, socket.SOCK_STREAM, 6, '', ('93.184.216.34', 8080))
        for infos in ([private], [public, private], [private, public]):
            with mock.patch.object(netmod.socket, 'getaddrinfo', return_value=infos):
                with mock.patch.object(netmod, '_connect_pinned') as connect:
                    with self.assertRaises(netmod.NetPolicyError):
                        net.fetch_ex('http://public.example:8080/x', timeout=0.5)
            connect.assert_not_called()

    def test_authorization_is_immutable_until_new_client(self):
        net = self._net(['http://10.0.0.1:8080'])
        original = net._private_source_origins
        self.assertIsInstance(original, frozenset)
        net.cfg.settings['network']['private_source_origins'].append('http://10.0.0.2:8080')
        self.assertIsNone(net._private_origin('http', '10.0.0.2', 8080))
        net.cfg.settings['network']['private_source_origins'] = []
        self.assertEqual(net._private_origin('http', '10.0.0.1', 8080),
                         ('http', '10.0.0.1', 8080))
        self.assertIsNone(NetClient(net.cfg)._private_origin('http', '10.0.0.1', 8080))

    def test_invalid_origin_syntax_rejected_with_only_field_and_index(self):
        invalid = [
            None, 1, {}, [], '', '10.0.0.1:8080', 'ftp://10.0.0.1:8080',
            'http://source.example:8080', 'http://10.0.0.0/8:8080',
            'http://*:8080', 'http://10.0.0.1', 'http://10.0.0.1:',
            'http://user@10.0.0.1:8080', 'http://10.0.0.1:8080/',
            'http://10.0.0.1:8080/path', 'http://10.0.0.1:8080?',
            'http://10.0.0.1:8080#', 'http://10.0.0.1:8080?secret=value',
            'http://10.0.0.1:0', 'http://10.0.0.1:65536',
            'http://10.0.0.1:+80', 'http://010.0.0.1:80',
            'http://[fd00::1%25eth0]:80', ' http://10.0.0.1:80',
            'http://10.0.0.1:80 ', 'http://10.0.0.1:80\n',
            'http://10.0.\t0.1:80', '\x00http://10.0.0.1:80',
        ]
        for value in invalid:
            with self.subTest(index=invalid.index(value)):
                with self.assertRaises(ValueError) as caught:
                    self._net(['http://10.0.0.1:8080', value])
                self.assertEqual(str(caught.exception),
                                 'network.private_source_origins[1]: invalid private source origin')

    def test_only_rfc1918_and_ula_ranges_can_be_authorized(self):
        forbidden = (
            '0.0.0.0', '127.0.0.1', '169.254.169.254', '100.64.0.1',
            '192.0.0.9', '192.0.2.1', '198.51.100.1', '203.0.113.1',
            '198.18.0.1', '224.0.0.1', '240.0.0.1', '255.255.255.255',
            '8.8.8.8', '172.15.255.255', '172.32.0.0', '192.169.0.1',
            '::', '::1', 'fe80::1', 'ff02::1', '2001:db8::1',
            '::ffff:10.0.0.1', '2002:0a00:0001::1',
            '2001:0:4136:e378:8000:63bf:3fff:fdd2', '2606:4700::1111',
            'fd00:ec2::254', 'fd00:0ec2:0000:0000:0000:0000:0000:0254',
            'fd20:ce::254', 'fd20:00ce:0000:0000:0000:0000:0000:0254',
        )
        for address in forbidden:
            host = '[%s]' % address if ':' in address else address
            with self.subTest(address=address):
                with self.assertRaises(ValueError):
                    self._net(['http://%s:8080' % host])
        allowed = ('10.0.0.0', '10.255.255.255', '172.16.0.0', '172.31.255.255',
                   '192.168.0.0', '192.168.255.255', 'fc00::', 'fdff:ffff::1')
        for address in allowed:
            host = '[%s]' % address if ':' in address else address
            net = self._net(['http://%s:8080' % host])
            self.assertIsNotNone(net._private_origin('http', address, 8080))

    def test_cross_origin_redirect_closes_without_drain_or_next_open(self):
        net = self._net(['http://10.0.0.1:8080', 'http://10.0.0.2:8080',
                         'https://10.0.0.1:8080', 'http://10.0.0.1:8081'])
        targets = (
            ('http://public.example/start', 'http://10.0.0.1:8080/final'),
            ('http://10.0.0.1:8080/start', 'http://10.0.0.2:8080/final'),
            ('http://10.0.0.1:8080/start', 'https://10.0.0.1:8080/final'),
            ('http://10.0.0.1:8080/start', 'http://10.0.0.1:8081/final'),
        )
        for source, target in targets:
            with self.subTest(source=source, target=target):
                handler = netmod._PinnedRedirectHandler(net)
                handler.parent = mock.Mock()
                req = urllib.request.Request(source)
                req.timeout = 1.0
                fp = _FakeRedirectFP()
                with self.assertRaises(netmod.NetPolicyError):
                    handler.http_error_302(req, fp, 302, 'Found',
                                          TestRedirectHandlerDeadline._headers(target))
                self.assertTrue(fp.closed)
                self.assertFalse(fp.read_called)
                handler.parent.open.assert_not_called()


class TestPrivateSourceIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = _Server(('127.0.0.1', 0), _Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.server.server_port

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2.0)

    def _run(self, host, path, reject=False):
        net = TestPrivateSourcePolicy._net([
            'http://10.0.0.1:%d' % self.port,
            'http://10.0.0.2:%d' % self.port,
        ])
        real_connect = netmod._connect_pinned
        calls = []

        def connect(addrs, timeout, source_address):
            calls.append(addrs)
            # 仅把已作策略决定的最终连接送往合成 fixture，保留上层真实 handler 链。
            local = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', self.port))]
            return real_connect(local, timeout, source_address)

        public = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('93.184.216.34', self.port))]
        with mock.patch.object(netmod.socket, 'getaddrinfo', return_value=public) as dns:
            with mock.patch.object(netmod, '_connect_pinned', side_effect=connect):
                url = 'http://%s:%d%s' % (host, self.port, path)
                if reject:
                    with self.assertRaises(netmod.NetPolicyError):
                        net.fetch_ex(url, timeout=1.0)
                else:
                    status, body, _, final_url = net.fetch_ex(url, timeout=1.0)
                    self.assertEqual(status, 200)
                    self.assertEqual(body, _Server.fast_body)
                    self.assertTrue(final_url.endswith('/fast'))
        self.assertEqual(dns.call_count, sum(info[0][4][0] == '93.184.216.34' for info in calls))
        return calls

    def test_authorized_private_fetch_and_same_origin_redirect(self):
        for path, hops in (('/fast', 1), ('/policy_redirect/same', 2)):
            with self.subTest(path=path):
                calls = self._run('10.0.0.1', path)
                self.assertEqual(len(calls), hops)
                self.assertTrue(all(info[0][4] == ('10.0.0.1', self.port) for info in calls))

    def test_public_redirect_to_authorized_private_rejects_before_next_connect(self):
        calls = self._run('public.example', '/policy_redirect/private_a', reject=True)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0][4], ('93.184.216.34', self.port))

    def test_private_redirect_to_other_authorized_private_rejects_before_next_connect(self):
        calls = self._run('10.0.0.1', '/policy_redirect/private_b', reject=True)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0][4], ('10.0.0.1', self.port))

    def test_private_redirect_to_public_uses_original_public_policy(self):
        calls = self._run('10.0.0.1', '/policy_redirect/public')
        self.assertEqual([info[0][4][0] for info in calls], ['10.0.0.1', '93.184.216.34'])


class TestFetchRetry(unittest.TestCase):
    """离线确定性：fetch_retry 异常分类与指数退避（不真 sleep、不触网）。"""

    RETRIES = 2
    BACKOFF = 0.5
    URL = 'http://public.example/x'
    # 永久状态：绝不重试
    PERMANENT_STATUSES = (400, 401, 403, 404, 410, 418)

    def _make(self):
        net = make_net(concurrency=2)
        net.cfg.settings['network']['retries'] = self.RETRIES
        net.cfg.settings['network']['backoff_s'] = self.BACKOFF
        return net

    def _http_error(self, code):
        return urllib.error.HTTPError(self.URL, code, 'err', {}, None)

    def _invoke(self, side_effect):
        """跑一次 fetch_retry（fetch/time.sleep 均打桩）；返回 (fetch, sleep, result, raised)。"""
        net = self._make()
        result = None
        raised = None
        with mock.patch.object(net, 'fetch', side_effect=side_effect) as fetch:
            with mock.patch.object(netmod.time, 'sleep') as sleep:
                try:
                    result = net.fetch_retry(self.URL)
                except Exception as exc:  # noqa: BLE001 - 测试断言用
                    raised = exc
        return fetch, sleep, result, raised

    def _expected_backoff(self):
        return [self.BACKOFF * (2 ** i) for i in range(self.RETRIES)]

    def test_permanent_http_status_fails_once_without_sleep(self):
        for status in self.PERMANENT_STATUSES:
            with self.subTest(status=status):
                fetch, sleep, _result, raised = self._invoke(self._http_error(status))
                self.assertIsInstance(raised, urllib.error.HTTPError)
                self.assertEqual(raised.code, status)
                self.assertEqual(fetch.call_count, 1)
                sleep.assert_not_called()

    def test_transient_http_statuses_retry_with_exponential_backoff(self):
        for status in sorted(netmod._RETRYABLE_HTTP_STATUS):
            with self.subTest(status=status):
                fetch, sleep, _result, raised = self._invoke(self._http_error(status))
                self.assertIsInstance(raised, urllib.error.HTTPError)
                self.assertEqual(raised.code, status)
                self.assertEqual(fetch.call_count, self.RETRIES + 1)
                self.assertEqual(
                    [call.args[0] for call in sleep.call_args_list],
                    self._expected_backoff())

    def test_net_policy_error_fails_once_without_sleep(self):
        fetch, sleep, _result, raised = self._invoke(
            netmod.NetPolicyError('blocked non-public address'))
        self.assertIsInstance(raised, netmod.NetPolicyError)
        self.assertEqual(fetch.call_count, 1)
        sleep.assert_not_called()

    def test_transient_network_exceptions_retry(self):
        for exc in (urllib.error.URLError('dns failure'),
                    TimeoutError('timed out'),
                    socket.timeout('socket timed out'),
                    OSError('io failure'),
                    http.client.HTTPException('bad status line')):
            with self.subTest(exc=type(exc).__name__):
                fetch, sleep, _result, raised = self._invoke(exc)
                self.assertIs(raised, exc)
                self.assertEqual(fetch.call_count, self.RETRIES + 1)
                self.assertEqual(
                    [call.args[0] for call in sleep.call_args_list],
                    self._expected_backoff())

    def test_unknown_exception_bubbles_once_without_sleep(self):
        fetch, sleep, _result, raised = self._invoke(RuntimeError('unexpected'))
        self.assertIsInstance(raised, RuntimeError)
        self.assertEqual(fetch.call_count, 1)
        sleep.assert_not_called()

    def test_second_attempt_success_returns_body(self):
        fetch, sleep, result, raised = self._invoke(
            [self._http_error(503), (200, b'ok-body', 'text/plain')])
        self.assertIsNone(raised)
        self.assertEqual(result, b'ok-body')
        self.assertEqual(fetch.call_count, 2)
        self.assertEqual(sleep.call_count, 1)

    def test_exhaustion_raises_last_exception(self):
        excs = [urllib.error.URLError('first'),
                urllib.error.URLError('second'),
                urllib.error.URLError('last')]
        fetch, sleep, _result, raised = self._invoke(excs)
        self.assertIs(raised, excs[-1])
        self.assertEqual(fetch.call_count, self.RETRIES + 1)
        self.assertEqual(sleep.call_count, self.RETRIES)


if __name__ == '__main__':
    unittest.main()
