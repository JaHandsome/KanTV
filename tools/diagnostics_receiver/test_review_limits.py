"""Synthetic inbound resource-boundary regressions, using only temporary storage."""
import contextlib
import http.client
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest import mock

import receiver


@contextlib.contextmanager
def running_server():
    with tempfile.TemporaryDirectory() as root:
        config = receiver.ReceiverConfig(token='synthetic-token', storage_dir=Path(root))
        server = receiver.create_server('127.0.0.1', 0, config)
        server.request_timeout = .18
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)


class TestRequestLimits(unittest.TestCase):
    def test_accepted_request_cap_precedes_thread_creation_and_start_failure_releases(self):
        with running_server() as server:
            slots = server._request_slots
            for _ in range(server.max_requests):
                self.assertTrue(slots.acquire(False))
            request = mock.Mock()
            with mock.patch('socketserver.ThreadingMixIn.process_request') as start:
                server.process_request(request, ('synthetic', 1))
                start.assert_not_called()
                request.shutdown.assert_called_once()
                request.close.assert_called_once()
                slots.release()
                start.side_effect = RuntimeError('synthetic start failure')
                with self.assertRaises(RuntimeError):
                    server.process_request(request, ('synthetic', 1))
                self.assertTrue(slots.acquire(False))
            for _ in range(server.max_requests):
                slots.release()

    def test_header_and_body_drips_end_and_slot_is_reusable(self):
        with running_server() as server:
            for prefix in (b'GET /health HTTP/1.0\r\nX-Slow: ',
                           b'POST /v1/reports HTTP/1.0\r\nAuthorization: Bearer synthetic-token\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n'):
                with socket.create_connection(server.server_address, timeout=1) as sock:
                    sock.sendall(prefix)
                    started = time.monotonic()
                    for _ in range(30):
                        try:
                            sock.sendall(b'x')
                        except OSError:
                            break
                        time.sleep(.03)
                    self.assertLess(time.monotonic() - started, .5)
                conn = http.client.HTTPConnection(*server.server_address, timeout=1)
                conn.request('GET', '/health')
                response = conn.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(response.read(), b'ok\n')
                conn.close()

    def test_auth_precedes_body_and_framing_error_codes_are_preserved(self):
        with running_server() as server:
            requests = [
                (b'Content-Length: 10000\r\n', 401),
                (b'Authorization: Bearer synthetic-token\r\nTransfer-Encoding: chunked\r\n', 411),
                (b'Authorization: Bearer synthetic-token\r\nContent-Type: application/json\r\nContent-Length: 1\r\nContent-Length: 1\r\n', 411),
                (b'Authorization: Bearer synthetic-token\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n', 413),
            ]
            for headers, status in requests:
                with socket.create_connection(server.server_address, timeout=1) as sock:
                    sock.sendall(b'POST /v1/reports HTTP/1.0\r\n' + headers + b'\r\n')
                    response = sock.recv(4096)
                    self.assertIn(str(status).encode(), response.split(b'\r\n')[0])


if __name__ == '__main__':
    unittest.main()
