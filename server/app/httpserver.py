# -*- coding: utf-8 -*-
"""HttpServer：TVBox 订阅 HTTP 服务（P0-5 + P1-E/G + 枢纽 Hub）。

- ThreadingHTTPServer（请求线程**非 daemon** + block_on_close），只读
  data/output/ 下的原子替换产物。停机时 server_close() 会 join 已进入的
  handler，保证主库在在途请求结束前不被关闭；
- 路由：GET /live.txt /live.m3u /tvbox_lives_snippet.json /live.media3.json
  /healthz /api/status /status，未知 404；
- 枢纽四路由（HUB_CONTRACT_V1 v1.1，见 docs/HUB_CONTRACT_V1.md）：
    GET  /api/hub/v1/subscription      只读投递（无鉴权，支持 If-None-Match -> 304）
    POST /api/hub/v1/feedback          回流落库（Bearer hub_token，401/202）
    GET  /api/hub/v1/feedback/summary  只读聚合 + 冲突清单 + notices 汇总
    GET  /api/hub/v1/urlkey-vectors    url_key 权威测试向量（无鉴权，不参与 seq；§2.5）
  枢纽逻辑全部在 `app/hub.py`（独立服务层），本文件只做路由与鉴权转发；
- ⚠️ 老端点行为一字不改（`live.txt` 结构冻结、`/api/status` 与 `/healthz` 不动）；
- 契约 §4.2：**除 POST /feedback 外所有端点不需鉴权**，故 8420 仍只监听局域网；
- 唯一的写端点 = POST /feedback（其余无写操作接口）；单请求异常全捕获，绝不杀服务。
"""
import io
import json
import os
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from app import __version__
from app.net import _DeadlineReader, _TIMEOUT_EXCS
from app.logutil import get_logger

log = get_logger('http')

ROUTES = {
    '/live.txt': ('live.txt', 'text/plain; charset=utf-8'),
    '/live.m3u': ('live.m3u', 'audio/x-mpegurl'),
    '/tvbox_lives_snippet.json': ('tvbox_lives_snippet.json', 'application/json; charset=utf-8'),
    # P0 R5-b：第四件套必须可经 HTTP 下载（缺路由则自研轨失效，见设计 §8-①/AC-5.8）
    '/live.media3.json': ('live.media3.json', 'application/json; charset=utf-8'),
}

# 枢纽端点路径（集中常量，避免散落字符串）
HUB_SUBSCRIPTION = '/api/hub/v1/subscription'
HUB_FEEDBACK = '/api/hub/v1/feedback'
HUB_SUMMARY = '/api/hub/v1/feedback/summary'
HUB_VECTORS = '/api/hub/v1/urlkey-vectors'


class _BoundedHTTPServer(ThreadingHTTPServer):
    """接受请求先取槽再建线程；超额连接立即关闭，不产生等待线程。"""

    max_requests = 32
    request_timeout = 15.0

    def __init__(self, *args, **kwargs):
        self._request_slots = threading.BoundedSemaphore(self.max_requests)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._request_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


def _make_handler(output_dir, status_provider=None, hub_provider=None):
    class Handler(BaseHTTPRequestHandler):
        """只读静态文件 + 状态端点 + 枢纽三端点 handler。"""

        _status = status_provider
        _hub = hub_provider

        def setup(self):
            super().setup()
            # headers + body 共用一次总期限；在每次底层 recv 前检查，抗滴送。
            deadline = time.monotonic() + self.server.request_timeout
            self.rfile.close()
            self.rfile = io.BufferedReader(_DeadlineReader(
                self.connection.makefile('rb', 0), self.connection,
                deadline, self.server.request_timeout))

        def do_HEAD(self):
            """HEAD 复用 GET 的路由与响应头（不写 body）。

            新增于 2026-09-11（枢纽验收需要以 HEAD 读 ETag/X-Hub-Seq）。
            现状说明：BaseHTTPRequestHandler 默认对 HEAD 返回 501，此前**所有**端点
            都不支持 HEAD；本方法把既有 GET 端点一并升级为 200+响应头（**只补能力、
            不改任何 GET 的响应体**）。HEAD 仍不发 body，但按 RFC 保留 Content-Length。
            """
            self._head_only = True
            self.do_GET()

        # ---------- 路由 ----------
        def do_GET(self):
            try:
                parsed = urlparse(self.path or '/')
                path = parsed.path or '/'
                if self._hub is not None and path in (HUB_SUBSCRIPTION, HUB_SUMMARY,
                                                      HUB_VECTORS):
                    if not self._hub.enabled():
                        self._send(404, 'text/plain; charset=utf-8', b'not found')
                        return
                    q = parse_qs(parsed.query, keep_blank_values=True)
                    if path == HUB_SUBSCRIPTION:
                        code, headers, body = self._hub.subscription_response(
                            caps=(q.get('caps') or [None])[0],
                            client=(q.get('client') or [''])[0],
                            schema=(q.get('schema') or [None])[0],
                            app=(q.get('app') or [''])[0],
                            device=(q.get('device') or [''])[0],
                            if_none_match=self.headers.get('If-None-Match'))
                    elif path == HUB_VECTORS:
                        code, headers, body = self._hub.vectors_response(
                            if_none_match=self.headers.get('If-None-Match'))
                    else:
                        code, headers, body = self._hub.summary_response()
                    self._send(code, None, body, extra=headers)
                    return
                if path == '/healthz':
                    self._handle_healthz()
                    return
                if path == '/api/status':
                    self._handle_api_status()
                    return
                if path == '/status':
                    self._handle_status_page()
                    return
                route = ROUTES.get(path)
                if route is None:
                    self._send(404, 'text/plain; charset=utf-8', b'not found')
                    return
                filename, ctype = route
                fpath = os.path.join(output_dir, filename)
                try:
                    with open(fpath, 'rb') as f:
                        body = f.read()
                except OSError:
                    self._send(404, 'text/plain; charset=utf-8', b'not ready')
                    return
                self._send(200, ctype, body)
            except Exception:  # noqa: BLE001 - 单请求异常不杀服务
                log.error('HTTP 处理异常 %s\n%s', self.path, traceback.format_exc())
                try:
                    self._send(500, 'text/plain; charset=utf-8', b'internal error')
                except Exception:  # noqa: BLE001
                    pass

        def do_POST(self):
            """唯一的写端点：枢纽回流（契约 §4）。其余路径 404（不引入新写面）。"""
            try:
                parsed = urlparse(self.path or '/')
                path = parsed.path or '/'
                if self._hub is None or path != HUB_FEEDBACK:
                    self._send(404, 'text/plain; charset=utf-8', b'not found')
                    return
                if not self._hub.enabled():
                    self._send(404, 'text/plain; charset=utf-8', b'not found')
                    return
                auth_header = self.headers.get('Authorization')
                if not self._hub.feedback_authorized(auth_header):
                    self.close_connection = True
                    self._send(401, 'application/json; charset=utf-8',
                               b'{"error":"unauthorized"}')
                    return
                body = self._read_body()
                if body is None:
                    self._send(413, 'application/json; charset=utf-8',
                               b'{"error":"payload too large"}')
                    return
                code, headers, out = self._hub.feedback_response(
                    auth_header, body)
                self._send(code, None, out, extra=headers)
            except _TIMEOUT_EXCS:
                self.close_connection = True
                try:
                    self._send(408, 'application/json; charset=utf-8',
                               b'{"error":"request timeout"}')
                except OSError:
                    pass
            except Exception:  # noqa: BLE001
                log.error('HTTP POST 处理异常 %s\n%s', self.path, traceback.format_exc())
                try:
                    self._send(500, 'text/plain; charset=utf-8', b'internal error')
                except Exception:  # noqa: BLE001
                    pass

        def _read_body(self):
            """按 Content-Length 读 body；超过上限返回 None（不读满，防内存打满）。

            超限时仍把请求体**分块读掉并丢弃**（内存不保留，故不违背上限的初衷）：
            若留下未读数据就关闭连接，内核会发 RST，客户端反而收不到 413。
            超出硬上限（MAX_DRAIN_BYTES）则标记关闭连接、不再读。
            """
            from app.hub import MAX_DRAIN_BYTES, MAX_FEEDBACK_BYTES
            try:
                length = int(self.headers.get('Content-Length') or 0)
            except (TypeError, ValueError):
                return b''
            if length <= 0:
                return b''
            if length > MAX_FEEDBACK_BYTES:
                if length <= MAX_DRAIN_BYTES:
                    remaining = length
                    while remaining > 0:
                        chunk = self.rfile.read(min(65536, remaining))
                        if not chunk:
                            break
                        remaining -= len(chunk)
                else:
                    self.close_connection = True
                return None
            return self.rfile.read(length)

        # ---------- 既有端点（行为不变） ----------
        def _handle_healthz(self):
            """P1-E：JSON 健康检查；心跳超龄 -> 503（healthcheck 升级的判定依据）。"""
            if self._status is not None:
                code, data = self._status.healthz()
                body = json.dumps(data, ensure_ascii=False,
                                  separators=(',', ':')).encode('utf-8')
                self._send(code, 'application/json; charset=utf-8', body)
                return
            # 无 StatusProvider（向后兼容）：降级为旧版文本
            body = ('ok %s/%s' % ('iptv-nas', __version__)).encode('utf-8')
            self._send(200, 'text/plain; charset=utf-8', body)

        def _handle_api_status(self):
            if self._status is None:
                self._send(404, 'text/plain; charset=utf-8', b'not found')
                return
            body = json.dumps(self._status.api_status(),
                              ensure_ascii=False).encode('utf-8')
            self._send(200, 'application/json; charset=utf-8', body)

        def _handle_status_page(self):
            if self._status is None:
                self._send(404, 'text/plain; charset=utf-8', b'not found')
                return
            body = self._status.render_html().encode('utf-8')
            self._send(200, 'text/html; charset=utf-8', body)

        def _send(self, code, ctype, body, extra=None):
            """统一响应。ctype 为 None 时由 extra 里的 Content-Type 提供（枢纽用）。"""
            self.send_response(code)
            if ctype is not None:
                self.send_header('Content-Type', ctype)
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.send_header('Cache-Control', 'no-cache')
            if code != 304:
                # 304 不得带 body（部分客户端会把带 body 的 304 当 200 处理）
                self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            if code != 304 and body and not getattr(self, '_head_only', False):
                self.wfile.write(body)

        def log_message(self, fmt, *args):
            log.info('%s - %s', self.address_string(), fmt % args)

    return Handler


class HttpServer(object):
    """订阅服务封装：serve_forever 阻塞调用线程。"""

    def __init__(self, cfg, status_provider=None, hub_provider=None):
        self.cfg = cfg
        http = cfg.settings['http']
        self.httpd = _BoundedHTTPServer(
            (http['host'], int(http['port'])),
            _make_handler(cfg.output_dir, status_provider, hub_provider))
        # 关键（停机竞态）：daemon 请求线程不会被 server_close() join，主库可能
        # 在在途 handler 仍访问它时被关闭。改为非 daemon + block_on_close，交给
        # stdlib 正常生命周期：server_close() 关闭监听后 join 全部在途 handler。
        self.httpd.daemon_threads = False
        self.httpd.block_on_close = True
        # 幂等收尾：只允许一个调用者执行 shutdown/server_close，其余等待同一次完成。
        self._shutdown_lock = threading.Lock()
        self._shutdown_started = False
        self._shutdown_complete = threading.Event()
        self._shutdown_error = None

    def serve_forever(self):
        log.info('HTTP 订阅服务启动: %s (输出目录 %s, 状态页 /status, 枢纽 /api/hub/v1/*)',
                 self.cfg.http_base(), self.cfg.output_dir)
        self.httpd.serve_forever()

    def shutdown(self):
        """优雅停机（须从非 serve_forever 线程调用）。

        顺序：httpd.shutdown() 停止接收并等 serve_forever 退出 ->
        httpd.server_close() 关闭监听 socket 并 join 已进入的 handler。
        daemon_threads=False + block_on_close=True 是 stdlib 语义，故本方法
        返回即代表在途请求已结束。

        幂等：并发/重复调用只真正收尾一次，其余调用阻塞至同一次收尾完成。
        """
        with self._shutdown_lock:
            starter = not self._shutdown_started
            if starter:
                self._shutdown_started = True
        if not starter:
            self._shutdown_complete.wait()
            if self._shutdown_error is not None:
                raise self._shutdown_error
            return
        error = None
        try:
            self.httpd.shutdown()
        except Exception as e:  # noqa: BLE001 - 记录并传播，禁止未排空时关闭主库
            error = e
            log.error('HTTP shutdown 异常', exc_info=True)
        finally:
            try:
                self.httpd.server_close()
            except Exception as e:  # noqa: BLE001
                if error is None:
                    error = e
                log.error('HTTP server_close 异常', exc_info=True)
            self._shutdown_error = error
            self._shutdown_complete.set()
        if error is not None:
            raise error
