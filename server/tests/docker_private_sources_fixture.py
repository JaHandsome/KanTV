#!/usr/bin/env python3
"""Synthetic HTTP/HLS fixture for the isolated Docker acceptance runner."""
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import threading
from urllib.parse import parse_qs, urlsplit


def make_ts_video():
    """Same synthetic PAT/PMT/PES semantics as test_verifier.make_ts_video."""
    pat = bytearray(b'\xff' * 188)
    pat[0:4] = b'\x47\x00\x00\x50'
    pat[4:9] = b'\x00\x00\x01\xe0\x50'
    pmt = bytearray(b'\xff' * 188)
    pmt[0:4] = b'\x47\x00\x50\x50'
    pmt[4:6] = b'\x00\x02'
    pmt[14:16] = b'\x00\x00'
    pmt[17:22] = b'\x1b\xe1\x00\x00\x00'
    pes = (b'\x00\x00\x01\xe0' + b'\xaa' * 20) * 45
    return bytes(pat) + bytes(pmt) + pes


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    counts = Counter()
    lock = threading.Lock()
    redirect_target = None

    def log_message(self, *_):
        pass

    def do_GET(self):
        route = urlsplit(self.path)
        path = route.path
        if path == '/stats':
            with self.lock:
                return self.send(json.dumps(dict(self.counts)).encode(), 'application/json')
        if path == '/control/redirect':
            type(self).redirect_target = parse_qs(route.query)['target'][0]
            return self.send(b'ok')
        with self.lock:
            self.counts['%d %s' % (self.server.server_port, path)] += 1
        if path == '/playlist.m3u':
            body = ('#EXTM3U\n#EXTINF:-1,CCTV-1\nhttp://%s/master.m3u8\n'
                    % self.headers['Host']).encode()
            return self.send(body, 'application/vnd.apple.mpegurl')
        if path == '/master.m3u8':
            return self.send(b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nmedia.m3u8\n',
                             'application/vnd.apple.mpegurl')
        if path == '/media.m3u8':
            return self.send(b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n'
                             b'#EXTINF:6,\nsegment.ts\n', 'application/vnd.apple.mpegurl')
        if path == '/segment.ts':
            return self.send(make_ts_video(), 'video/mp2t')
        if path == '/same_redirect':
            return self.redirect('/master.m3u8')
        if path == '/cross_redirect' and self.redirect_target:
            return self.redirect(self.redirect_target)
        return self.send(b'not found', status=404)

    def send(self, body, content_type='text/plain', status=200):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def redirect(self, target):
        self.send_response(302)
        self.send_header('Location', target)
        self.send_header('Content-Length', '0')
        self.end_headers()


def main():
    servers = []
    for port in map(int, os.environ.get('FIXTURE_PORTS', '8080').split(',')):
        server = ThreadingHTTPServer(('0.0.0.0', port), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        servers.append(server)
    threading.Event().wait()


if __name__ == '__main__':
    main()
