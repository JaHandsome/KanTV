"""Synthetic regressions for R05/06/07 and S01/03/05/06; no local config/data."""
import contextlib
import copy
import http.client
from pathlib import Path
import socket
import socketserver
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import DEFAULT_SETTINGS
from app.db import Database
from app.discovery import SourceDiscovery
from app.health import HealthMonitor
from app.httpserver import _BoundedHTTPServer, _make_handler
from app.hub import Hub
from app.net import NetBodyLimitError, NetClient
from app.parser import ChannelParser, Entry, _strip_quality_suffix


def config():
    return SimpleNamespace(settings=copy.deepcopy(DEFAULT_SETTINGS), rules={},
                           channels={'channels': ['CCTV1', '翡翠台'],
                                     'aliases': {'翡翠': '翡翠台'},
                                     't2s_map': {'臺': '台'}})


@contextlib.contextmanager
def upstream(respond, tls=None):
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            sock = self.request
            try:
                if tls is not None:
                    sock = tls.wrap_socket(sock, server_side=True)
                sock.settimeout(2)
                request = b''
                while b'\r\n\r\n' not in request:
                    chunk = sock.recv(4096)
                    if not chunk:
                        return
                    request += chunk
                respond(sock, request.split(b' ')[1].decode())
            except (OSError, ssl.SSLError):
                pass
            finally:
                sock.close()
    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True
    server = Server(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)


def local_net(port):
    net = NetClient(config())
    # Test-only DNS injection: production _resolve_pinned still refuses loopback.
    net._resolver = lambda host, ignored: [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', port))]
    return net


def send(sock, body=b'ok', headers=b''):
    sock.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: ' + str(len(body)).encode() + b'\r\n' + headers + b'\r\n' + body)


class TestOutgoingBoundary(unittest.TestCase):
    def test_status_and_header_drips_obey_absolute_deadline_and_release_gate(self):
        for prefix, drip in [(b'', b'HTTP/1.1 200 OK\r\n'),
                             (b'HTTP/1.1 200 OK\r\nX-Slow: ', b'a' * 30),
                             (b'HTTP/1.1 100 Continue\r\n', b'X-Slow: ' + b'a' * 30)]:
            def respond(sock, path):
                if path == '/fast':
                    send(sock)
                    return
                sock.sendall(prefix)
                for char in drip:
                    sock.sendall(bytes([char]))
                    time.sleep(.03)
            with upstream(respond) as port:
                net = local_net(port)
                started = time.monotonic()
                with self.assertRaises((TimeoutError, OSError)):
                    net.fetch_ex('http://synthetic.invalid/slow', timeout=1, budget_s=.16)
                self.assertLess(time.monotonic() - started, .5)
                self.assertEqual(net.fetch('http://synthetic.invalid/fast')[1], b'ok')

    def test_redirect_does_not_drain_and_body_and_zero_limit_work(self):
        def respond(sock, path):
            if path == '/redirect':
                sock.sendall(b'HTTP/1.1 302 Found\r\nLocation: /fast\r\nContent-Length: 999999\r\n\r\n')
                time.sleep(.6)
            elif path == '/slowbody':
                sock.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\n')
                for _ in range(30):
                    sock.sendall(b'x')
                    time.sleep(.03)
            elif path == '/interim':
                sock.sendall(b'HTTP/1.1 100 Continue\r\n\r\n')
                send(sock)
            else:
                send(sock)
        with upstream(respond) as port:
            net = local_net(port)
            self.assertEqual(net.fetch('http://synthetic.invalid/interim')[1], b'ok')
            result = net.fetch_ex('http://synthetic.invalid/redirect', timeout=1, budget_s=.2)
            self.assertEqual(result[1:], (b'ok', '', 'http://synthetic.invalid/fast'))
            self.assertEqual(net.fetch('http://synthetic.invalid/fast', limit=0)[1], b'')
            started = time.monotonic()
            with self.assertRaises((TimeoutError, OSError)):
                net.fetch('http://synthetic.invalid/slowbody', budget_s=.16)
            self.assertLess(time.monotonic() - started, .5)

    def test_complete_download_distinguishes_known_and_unknown_limits(self):
        def respond(sock, path):
            if path == '/short':
                sock.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\n1234')
            elif path == '/unknown':
                sock.sendall(b'HTTP/1.1 200 OK\r\n\r\n1234')
            elif path == '/chunked':
                sock.sendall(b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n1234\r\n0\r\n\r\n')
            else:
                send(sock, b'12345' if path == '/large' else b'1234')
        with upstream(respond) as port:
            net = local_net(port)
            self.assertEqual(net.fetch_complete('http://synthetic.invalid/exact', limit=4)[1], b'1234')
            with self.assertRaises(NetBodyLimitError):
                net.fetch_complete('http://synthetic.invalid/short', limit=10)
            for path in ('/large', '/unknown', '/chunked'):
                with self.assertRaises(NetBodyLimitError):
                    net.fetch_complete('http://synthetic.invalid' + path, limit=4)
            self.assertEqual(net.fetch_complete('http://synthetic.invalid/unknown', limit=5)[1], b'1234')
            self.assertEqual(net.fetch_complete('http://synthetic.invalid/chunked', limit=5)[1], b'1234')
            self.assertEqual(net.fetch('http://synthetic.invalid/large', limit=4)[1], b'1234')

    def test_tls_ca_hostname_and_sni(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            def openssl(*args):
                subprocess.run(['openssl', *args], cwd=root, check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                    '-subj', '/CN=Synthetic Review CA', '-keyout', 'ca.key', '-out', 'ca.pem')
            openssl('req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=trusted.invalid',
                    '-keyout', 'server.key', '-out', 'server.csr')
            (root / 'extensions').write_text('subjectAltName=DNS:trusted.invalid\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n')
            openssl('x509', '-req', '-in', 'server.csr', '-CA', 'ca.pem', '-CAkey', 'ca.key',
                    '-CAcreateserial', '-days', '1', '-extfile', 'extensions', '-out', 'server.pem')
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(root / 'server.pem', root / 'server.key')
            sni = []
            ctx.set_servername_callback(lambda sock, hostname, original: sni.append(hostname))
            def respond(sock, path):
                if path == '/slow':
                    sock.sendall(b'HTTP/1.1 200 OK\r\nX-Slow: ')
                    for _ in range(30):
                        sock.sendall(b'x')
                        time.sleep(.03)
                else:
                    send(sock)
            with upstream(respond, tls=ctx) as port:
                untrusted = local_net(port)
                with self.assertRaises(OSError):
                    untrusted.fetch('https://trusted.invalid/')
                trusted = local_net(port)
                trusted._ssl_ctx.load_verify_locations(cafile=str(root / 'ca.pem'))
                self.assertEqual(trusted.fetch('https://trusted.invalid/')[1], b'ok')
                started = time.monotonic()
                with self.assertRaises(OSError):
                    trusted.fetch('https://trusted.invalid/slow', budget_s=.16)
                self.assertLess(time.monotonic() - started, .5)
                self.assertEqual(trusted.fetch('https://trusted.invalid/')[1], b'ok')
                with self.assertRaises(OSError):
                    trusted.fetch('https://wrong.invalid/')
            self.assertIn('trusted.invalid', sni)
            self.assertIn('wrong.invalid', sni)


class TestParserBoundary(unittest.TestCase):
    def test_adversarial_names_are_bounded_and_not_truncated_to_channel(self):
        parser = ChannelParser(config())
        for name in ['CCTV1' + ' ' * 10000 + '?', 'CCTV1' + '(' * 10000,
                     '[' + ' ' * 10000 + 'HD]', 'CCTV1' + ' ' * 300 + '?']:
            started = time.monotonic()
            _strip_quality_suffix(name)
            parser.normalize_channel(name)
            self.assertLess(time.monotonic() - started, .1)
            if len(name) > 512:
                self.assertEqual(parser.normalize_channel(name), '')
        name = 'CCTV1' + ' ' * 10000 + '?'
        self.assertEqual(parser.parse_m3u('#EXTM3U\n#EXTINF:-1,' + name + '\nhttp://synthetic.invalid/a')[0].name, name)
        self.assertEqual(parser.parse_txt(name + ',http://synthetic.invalid/a')[0].name, name)

    def test_quality_noise_traditional_and_semantic_suffixes(self):
        parser = ChannelParser(config())
        for name in ('CCTV1 [1920 * 1080]', 'CCTV1HD', 'CCTV1 - FHD HD', '翡翠臺(高清)'):
            self.assertEqual(parser.normalize_channel(name), 'CCTV1' if name.startswith('CCTV') else '翡翠台')
        self.assertEqual(_strip_quality_suffix('翡翠台(北美)'), '翡翠台(北美)')
        self.assertEqual(_strip_quality_suffix('HD'), 'HD')


class TestMediaSnapshot(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cfg = config()
        self.cfg.settings['signed_url_params'] = ['sig']
        self.db = Database(str(Path(self.tmp.name) / 'test.sqlite'))
        self.db.migrate(self.cfg.settings)
        self.discovery = SourceDiscovery(self.cfg, self.db, None)
        self.entry = {'url': 'https://synthetic.invalid/a.mpd?sig=one', 'name': 'CCTV1',
                      'origin': 'urllist:synthetic', 'container': 'dash', 'encryption': 'cenc',
                      'drm': 'clearkey', 'kid': 'synthetic-kid', 'drm_key': 'synthetic-key',
                      'keyformat': 'synthetic-format', 'caps_required': '{"dash":true}'}
        self.discovery.ingest([self.entry], 'urllist:synthetic')
        self.db.execute("UPDATE sources SET status='active', av='video', last_verified='2026-01-01', verdict_reason='ok'")
        self.sid = self.row()['id']
        self.db.execute('INSERT INTO lineups(channel_key,rank,source_id,updated_at) VALUES (?,?,?,?)', ('CCTV1', 1, self.sid, '2026-01-01'))

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def row(self):
        return self.db.query_one('SELECT * FROM sources')

    def test_same_url_metadata_change_invalidates_old_proof_and_lineup(self):
        changed = dict(self.entry, drm='widevine', kid='new-kid', drm_key=None,
                       encryption='cbcs', keyformat='new-format', caps_required='{"widevine":true}')
        self.discovery.ingest([changed], changed['origin'])
        row = self.row()
        for field in ('container', 'drm', 'kid', 'drm_key', 'encryption', 'keyformat', 'caps_required'):
            self.assertEqual(row[field], changed[field])
        self.assertEqual(row['status'], 'candidate')
        self.assertIsNone(row['av'])
        self.assertIsNone(row['last_verified'])
        self.assertEqual(self.db.query('SELECT * FROM lineups'), [])

    def test_only_signature_rotation_keeps_verification_and_source_identity(self):
        old = self.row()
        self.discovery.ingest([dict(self.entry, url=self.entry['url'].replace('one', 'two'))], self.entry['origin'])
        row = self.row()
        self.assertEqual(row['id'], old['id'])
        self.assertEqual(row['url_key'], old['url_key'])
        self.assertEqual(row['last_verified'], old['last_verified'])
        self.assertEqual(row['status'], 'active')

    def test_plain_hls_preserves_probe_facts_and_revocation_does_not_loop(self):
        plain = {'url': 'https://synthetic.invalid/plain', 'origin': 'urllist:plain', 'name': 'CCTV1'}
        self.discovery.ingest([plain], plain['origin'])
        self.db.execute("UPDATE sources SET status='active', av='video', container='hls', "
                        "encryption='none', last_verified='2026-01-01' WHERE origin=?", (plain['origin'],))
        self.discovery.ingest([plain], plain['origin'])
        row = self.db.query_one('SELECT * FROM sources WHERE origin=?', (plain['origin'],))
        self.assertEqual(row['status'], 'active')
        self.assertEqual(row['container'], 'hls')
        self.assertEqual(row['last_verified'], '2026-01-01')
        revoked = {'url': self.entry['url'], 'origin': self.entry['origin'], 'name': 'CCTV1'}
        self.discovery.ingest([revoked], revoked['origin'])
        self.db.execute("UPDATE sources SET status='active', av='video', container='dash', "
                        "encryption='none', last_verified='2026-02-01' WHERE id=?", (self.sid,))
        self.discovery.ingest([revoked], revoked['origin'])
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (self.sid,))
        self.assertEqual(row['status'], 'active')
        self.assertEqual(row['last_verified'], '2026-02-01')

    def test_unchanged_container_declaration_preserves_probe_enrichment(self):
        entry = {'url': 'https://synthetic.invalid/container-only',
                 'origin': 'urllist:container', 'name': 'CCTV1', 'container': 'dash'}
        self.discovery.ingest([entry], entry['origin'])
        self.db.execute("UPDATE sources SET status='drm', av=NULL, drm='widevine', "
                        "encryption='cenc', profile='need-key', last_verified='2026-01-01' "
                        "WHERE origin=?", (entry['origin'],))
        self.discovery.ingest([entry], entry['origin'])
        row = self.db.query_one('SELECT * FROM sources WHERE origin=?', (entry['origin'],))
        self.assertEqual(row['status'], 'drm')
        self.assertEqual(row['drm'], 'widevine')
        self.assertEqual(row['profile'], 'need-key')
        self.assertEqual(row['last_verified'], '2026-01-01')

    def test_signed_url_with_changed_key_replaces_entire_media_snapshot(self):
        changed = dict(self.entry, url=self.entry['url'].replace('one', 'two'),
                       drm='widevine', drm_key=None, kid='new-kid',
                       encryption='cbcs', keyformat=None, caps_required='{"widevine":true}')
        self.discovery.ingest([changed], changed['origin'])
        row = self.row()
        self.assertEqual(row['id'], self.sid)
        self.assertEqual(row['url'], changed['url'])
        self.assertEqual(row['drm'], 'widevine')
        self.assertIsNone(row['drm_key'])
        self.assertIsNone(row['keyformat'])
        self.assertEqual(row['status'], 'candidate')
        self.assertIsNone(row['last_verified'])

    def test_cross_origin_new_url_with_explicit_media_is_one_new_snapshot(self):
        before = self.row()
        changed = dict(self.entry, origin='urllist:other',
                       url=self.entry['url'].replace('one', 'two'), drm='widevine',
                       encryption='cbcs', drm_key=None, kid='new-kid',
                       keyformat=None, caps_required='{"widevine":true}')
        self.discovery.ingest([changed], changed['origin'])
        row = self.row()
        self.assertEqual(row['id'], before['id'])
        self.assertEqual(row['url_key'], before['url_key'])
        self.assertEqual(row['url'], changed['url'])
        self.assertEqual(row['origin'], changed['origin'])
        self.assertEqual(row['drm'], 'widevine')
        self.assertIsNone(row['drm_key'])
        self.assertIsNone(row['keyformat'])
        self.assertEqual(row['status'], 'candidate')
        self.assertIsNone(row['last_verified'])
        self.assertEqual(self.db.query('SELECT * FROM lineups'), [])

    def test_cross_origin_signature_only_with_same_media_keeps_verification(self):
        before = self.row()
        rotated = dict(self.entry, origin='urllist:other',
                       url=self.entry['url'].replace('one', 'two'))
        self.discovery.ingest([rotated], rotated['origin'])
        row = self.row()
        self.assertEqual(row['id'], before['id'])
        self.assertEqual(row['url'], rotated['url'])
        self.assertEqual(row['url_key'], before['url_key'])
        self.assertEqual(row['drm_key'], before['drm_key'])
        self.assertEqual(row['status'], 'active')
        self.assertEqual(row['last_verified'], before['last_verified'])

    def test_removal_clears_same_origin_facts_but_other_origin_cannot_mix_them(self):
        self.discovery.ingest([dict(self.entry, origin='urllist:other', drm='widevine')], 'urllist:other')
        self.assertEqual(self.row()['drm'], 'clearkey')
        self.discovery.ingest([{'url': self.entry['url'], 'origin': self.entry['origin'], 'name': 'CCTV1'}], self.entry['origin'])
        self.assertIsNone(self.row()['drm'])
        self.assertIsNone(self.row()['drm_key'])
        self.assertEqual(self.row()['profile'], 'direct')
        self.assertIsNone(self.row()['last_verified'])


class TestThroughputEvidence(unittest.TestCase):
    def test_final_base_is_used_at_every_master_depth_and_for_segment(self):
        class Net:
            ua = 'synthetic'
            calls = []
            def fetch_ex(self, url, **kwargs):
                self.calls.append(url)
                if len(self.calls) == 1:
                    return 200, b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8', '', 'https://synthetic.invalid/redirected/master.m3u8'
                return 200, b'#EXTM3U\n#EXTINF:4,\nsegment.ts', '', 'https://cdn.invalid/session/media.m3u8'
            def fetch_complete(self, url, **kwargs):
                self.calls.append(url)
                return 200, b'segment', ''
        net = Net()
        result = HealthMonitor(config(), None, net).probe_throughput({'container': 'hls', 'url': 'https://synthetic.invalid/initial'})
        self.assertEqual(result[0], 1)
        self.assertEqual(net.calls, ['https://synthetic.invalid/initial', 'https://synthetic.invalid/redirected/child.m3u8', 'https://cdn.invalid/session/segment.ts'])

    def test_prefix_without_completion_evidence_is_not_a_success(self):
        class Net:
            ua = 'synthetic'
            def fetch(self, url, **kwargs):
                return (200, b'#EXTM3U\n#EXTINF:4,\nseg.ts', '') if 'm3u8' in url else (200, b'abcd', '')
        cfg = config()
        cfg.settings['health']['throughput_max_bytes'] = 4
        result = HealthMonitor(cfg, None, Net()).probe_throughput({'container': 'hls', 'url': 'https://synthetic.invalid/a.m3u8'})
        self.assertEqual(result[0], 0)
        self.assertIsNone(result[1])


class TestInboundBoundary(unittest.TestCase):
    def test_cap_is_before_thread_creation_and_failed_start_returns_slot(self):
        class Server(_BoundedHTTPServer):
            max_requests = 1
        with tempfile.TemporaryDirectory() as root:
            server = Server(('127.0.0.1', 0), _make_handler(root))
            request = mock.Mock()
            with mock.patch.object(server, 'process_request_thread'), mock.patch('socketserver.ThreadingMixIn.process_request') as start:
                self.assertTrue(server._request_slots.acquire(False))
                server.process_request(request, ('synthetic', 1))
                start.assert_not_called()
                server._request_slots.release()
                start.side_effect = RuntimeError('synthetic start failure')
                with self.assertRaises(RuntimeError):
                    server.process_request(request, ('synthetic', 1))
                self.assertTrue(server._request_slots.acquire(False))
                server._request_slots.release()
            server.server_close()

    def test_real_hub_auth_uses_current_token_and_response_defends_independently(self):
        hub = Hub.__new__(Hub)
        token = ['synthetic-one']
        hub.token = lambda: token[0]
        self.assertTrue(hub.feedback_authorized('Bearer synthetic-one'))
        token[0] = 'synthetic-two'
        self.assertFalse(hub.feedback_authorized('Bearer synthetic-one'))
        self.assertTrue(hub.feedback_authorized('Bearer synthetic-two'))
        self.assertEqual(hub.feedback_response('Bearer synthetic-one', b'{}')[0], 401)
        token[0] = ''
        self.assertFalse(hub.feedback_authorized('Bearer '))

    def test_inbound_headers_and_body_share_deadline_and_unauthorized_skips_body(self):
        class Provider:
            token = 'synthetic-one'
            def enabled(self): return True
            def feedback_authorized(self, auth): return auth == 'Bearer ' + self.token
            def feedback_response(self, auth, body): return 202, {}, b'accepted'
        provider = Provider()
        with tempfile.TemporaryDirectory() as root:
            server = _BoundedHTTPServer(('127.0.0.1', 0), _make_handler(root, hub_provider=provider))
            server.request_timeout = .18
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                for prefix, suffix in [(b'GET /healthz HTTP/1.0\r\nX: ', b'a' * 30),
                                       (b'POST /api/hub/v1/feedback HTTP/1.0\r\nAuthorization: Bearer synthetic-one\r\nContent-Length: 30\r\n\r\n', b'x' * 30)]:
                    with socket.create_connection(server.server_address, timeout=1) as sock:
                        sock.sendall(prefix)
                        started = time.monotonic()
                        for char in suffix:
                            try:
                                sock.sendall(bytes([char]))
                            except OSError:
                                break
                            time.sleep(.03)
                        self.assertLess(time.monotonic() - started, .5)
                with socket.create_connection(server.server_address, timeout=1) as sock:
                    sock.sendall(b'POST /api/hub/v1/feedback HTTP/1.0\r\nContent-Length: 30000\r\n\r\n')
                    self.assertIn(b'401', sock.recv(4096))
                provider.token = 'synthetic-two'
                conn = http.client.HTTPConnection(*server.server_address, timeout=1)
                conn.request('POST', '/api/hub/v1/feedback', b'{}', {'Authorization': 'Bearer synthetic-two'})
                response = conn.getresponse()
                self.assertEqual(response.status, 202)
                response.read()
                conn.close()
            finally:
                server.shutdown()
                server.server_close()
                thread.join(2)


if __name__ == '__main__':
    unittest.main()
