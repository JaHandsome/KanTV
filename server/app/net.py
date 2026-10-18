# -*- coding: utf-8 -*-
"""NetClient：全部外网请求的唯一出口（ARCHITECTURE §7.5 红线）。

- 超时（默认 settings.limits.timeout_s）、UA 伪装、TLS 证书及主机名验证；
- 全局并发闸门（threading.BoundedSemaphore，上限 settings.limits.net_concurrency）；
- 网络请求预算：从进入 fetch_ex（等待并发闸门之前）起算 deadline，
  闸门等待、状态行、响应头和响应体共用预算，连接 timeout 不超过剩余预算；
- fetch_retry：仅对瞬态网络异常与指定 HTTP 状态重试 + 指数退避；策略拒绝与
  永久状态立即失败；
- SSRF 边界：初始 URL 与每个重定向目标都必须是规范 http/https（拒绝 userinfo、
  空 host、非法端口）；解析结果必须全部为公网单播地址；实际连接只使用同一次
  已校验解析得到的 sockaddr（防 DNS rebinding），HTTPS 仍以原 hostname 作 SNI；
  禁用环境代理；
- jsdelivr_url / ghproxy_url：GitHub 仓库内容的两个 CDN 通道 URL 构造；
- 含非 ASCII 字符的 URL 自动 quote（移植探针 enc()）。
"""
import http.client
import io
import ipaddress
import re
import socket
import ssl
import string
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import quote, urljoin, urlparse, urlsplit, urlunparse

from app.logutil import get_logger

log = get_logger('net')

# quote 时保留的字符（与 probe_sources5.enc 完全一致）
_SAFE = ":/?#[]@!$&'()*+,;=%~-._"

# 响应体分块读取的块大小（每次 read1 最多请求这么多字节）
_READ_CHUNK = 65536

# 视为"预算耗尽"的底层异常：过期 socket 读会抛 socket.timeout（3.10+ 即 TimeoutError）
_TIMEOUT_EXCS = (TimeoutError, socket.timeout)

# fetch_retry 明确视为瞬态的 HTTP 状态：其余状态（含永久 4xx）立即失败。
_RETRYABLE_HTTP_STATUS = frozenset((408, 425, 429, 500, 502, 503, 504))

# 挂在 urllib.request.Request 上的全局 deadline 属性名：初始请求携带，
# 每个重定向 Request 继承，保证整条重定向链共享同一次预算。
_DEADLINE_ATTR = '_net_deadline'


def _tighten_timeout(timeout, remaining):
    """把单跳 timeout 收紧到剩余预算；无显式 timeout 时直接用剩余预算。"""
    if timeout is None or timeout is socket._GLOBAL_DEFAULT_TIMEOUT:
        return remaining
    return min(timeout, remaining)

# ---------------------------------------------------------------------------
# SSRF 边界：URL 语法校验 + 公网单播地址校验 + 连接地址绑定
# ---------------------------------------------------------------------------
_ALLOWED_SCHEMES = ('http', 'https')
_DEFAULT_PORTS = {'http': 80, 'https': 443}
# CGNAT 100.64.0.0/10：Python 3.9 的 is_private 为 False，必须显式拒绝
_CGNAT_NET = ipaddress.ip_network('100.64.0.0/10')
# IANA 特殊用途地址：Python 3.9 会把 192.0.0.8/.9/.10/.11 等判为 global，
# 按 fail-closed 策略整段拒绝 192.0.0.0/24（含 .9/.10，不做例外）。
_SPECIAL_PURPOSE_NETS = (
    ipaddress.ip_network('192.0.0.0/24'),
)
# 文档/测试网段（部分已含于 is_private，显式列出以便审计）
_DOC_NETS = (
    ipaddress.ip_network('192.0.2.0/24'),
    ipaddress.ip_network('198.51.100.0/24'),
    ipaddress.ip_network('203.0.113.0/24'),
    ipaddress.ip_network('2001:db8::/32'),
)


class NetPolicyError(urllib.error.URLError):
    """SSRF 策略拒绝：URL 语法或目标地址不合规（调用方按网络异常处理）。"""


class NetResolveError(NetPolicyError):
    """DNS 解析结果不可用：不可解析 / 解析为非公网地址 / 未解析出地址。

    仍继承 NetPolicyError（fail-closed 的安全拒绝，fetch_retry 对两者都立即失败），
    但单独成类供上层把"整批解析失败"识别为传输层故障（可熔断）；URL 语法、重定向、
    handler 等策略拒绝保持普通 NetPolicyError（不属于传输故障，不参与熔断）。
    """


def _is_public_ip(ip):
    """目标 IP 是否为可安全直连的公网单播地址。

    拒绝 unspecified/loopback/link-local/private/multicast/reserved/documentation、
    CGNAT 以及 192.0.0.0/24 等 Python 3.9 误判为 global 的 IANA 特殊用途网段；
    IPv4-mapped IPv6 按其内嵌 IPv4 分类；6to4/Teredo 若内嵌受限 IPv4
    同样拒绝。注意域名（如 ``10.fast.example``）不能传入本函数，必须先解析为 IP，
    否则会被字符串前缀误判。
    """
    if isinstance(ip, str):
        ip = ipaddress.ip_address(ip)
    if isinstance(ip, ipaddress.IPv6Address):
        mapped = ip.ipv4_mapped
        if mapped is not None:
            return _is_public_ip(mapped)
        sixtofour = getattr(ip, 'sixtofour', None)
        if sixtofour is not None and not _is_public_ip(sixtofour):
            return False
        teredo = getattr(ip, 'teredo', None)
        if teredo is not None and not _is_public_ip(teredo[1]):
            return False
    if (ip.is_private or ip.is_loopback or ip.is_link_local
            or ip.is_multicast or ip.is_reserved or ip.is_unspecified):
        return False
    if isinstance(ip, ipaddress.IPv4Address) and ip in _CGNAT_NET:
        return False
    for net in _SPECIAL_PURPOSE_NETS:
        if ip in net:
            return False
    for net in _DOC_NETS:
        if ip in net:
            return False
    return True


def _validate_url(url):
    """校验 URL 语法，返回 ``(scheme, host, port)``；不合规抛 NetPolicyError。"""
    parts = urlsplit(url)
    scheme = (parts.scheme or '').lower()
    if scheme not in _ALLOWED_SCHEMES:
        raise NetPolicyError('unsupported URL scheme: %r' % (parts.scheme,))
    if '@' in parts.netloc:
        raise NetPolicyError('userinfo is not allowed in URL: %r' % (url,))
    host = parts.hostname
    if not host:
        raise NetPolicyError('URL host is empty: %r' % (url,))
    try:
        port = parts.port
    except ValueError:
        raise NetPolicyError('URL port is invalid: %r' % (url,)) from None
    if port is not None and not (0 < port <= 65535):
        raise NetPolicyError('URL port out of range: %r' % (url,))
    return scheme, host, port


def _resolve_pinned(host, port):
    """解析 host:port 一次并校验全部结果，返回已校验的 getaddrinfo 元组列表。

    任一结果为非公网单播地址即整体拒绝；连接阶段只使用本函数返回的 sockaddr，
    不再触发第二次解析（防 DNS rebinding）。解析结果不可用（不可解析/非公网/空）
    统一抛 NetResolveError（NetPolicyError 子类，供上层按传输层故障识别）；
    getaddrinfo 失败仍抛普通 URLError。
    """
    try:
        infos = socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise urllib.error.URLError(exc) from exc
    addrs = []
    for family, socktype, proto, canonname, sockaddr in infos:
        try:
            ip = ipaddress.ip_address(sockaddr[0])
        except ValueError:
            raise NetResolveError(
                'unparsable resolved address: %r' % (sockaddr[0],)) from None
        if not _is_public_ip(ip):
            raise NetResolveError('blocked non-public address: %s' % (ip,))
        addrs.append((family, socktype, proto, canonname, sockaddr))
    if not addrs:
        raise NetResolveError('no address resolved for host: %r' % (host,))
    return addrs


def _connect_pinned(addrs, timeout, source_address):
    """只使用已校验的 sockaddr 逐个建连；不再调用 getaddrinfo。"""
    if not addrs:
        raise urllib.error.URLError('no validated address to connect')
    last_exc = None
    connect_deadline = (None if timeout is socket._GLOBAL_DEFAULT_TIMEOUT or timeout is None
                        else time.monotonic() + timeout)
    for family, socktype, proto, _canonname, sockaddr in addrs:
        sock = None
        try:
            sock = socket.socket(family, socktype, proto)
            if connect_deadline is not None:
                remaining = connect_deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('net connect exceeded budget')
                sock.settimeout(remaining)
            if source_address:
                sock.bind(source_address)
            sock.connect(sockaddr)
        except OSError as exc:
            last_exc = exc
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass
            continue
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        return sock
    if last_exc is not None:
        raise last_exc
    raise urllib.error.URLError('no validated address to connect')


class NetBodyLimitError(ValueError):
    """响应在上限内未能证明完整；不得用于整片吞吐测量。"""


class _DeadlineReader(io.RawIOBase):
    """每次底层 recv 前收紧期限，覆盖状态行、headers 和 chunk framing。"""

    def __init__(self, raw, sock, deadline, timeout):
        self._raw = raw
        self._sock = sock
        self._deadline = deadline
        self._timeout = timeout

    def readable(self):
        return True

    def readinto(self, buffer):
        remaining = self._deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('HTTP read exceeded budget')
        self._sock.settimeout(_tighten_timeout(self._timeout, remaining))
        return self._raw.readinto(buffer)

    def fileno(self):
        return self._raw.fileno()

    def close(self):
        try:
            self._raw.close()
        finally:
            super().close()


class _DeadlineSocket(object):
    """保留真实 socket 行为，仅替换 HTTPResponse 创建的读取流。"""

    def __init__(self, sock, deadline, timeout):
        self._sock = sock
        self._deadline = deadline
        self._timeout = timeout

    def __getattr__(self, name):
        return getattr(self._sock, name)

    def sendall(self, data, *args, **kwargs):
        remaining = self._deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('HTTP send exceeded budget')
        self._sock.settimeout(_tighten_timeout(self._timeout, remaining))
        return self._sock.sendall(data, *args, **kwargs)

    def makefile(self, mode, buffering=None):
        if mode != 'rb':
            return self._sock.makefile(mode, buffering)
        raw = _DeadlineReader(self._sock.makefile('rb', 0), self._sock,
                              self._deadline, self._timeout)
        return io.BufferedReader(raw)


class _PinnedHTTPConnection(http.client.HTTPConnection):
    """HTTP 连接：connect 只使用外部注入的、已校验的 sockaddr。"""

    def __init__(self, host, port=None, timeout=socket._GLOBAL_DEFAULT_TIMEOUT,
                 source_address=None, addrs=None, deadline=None):
        http.client.HTTPConnection.__init__(
            self, host, port, timeout=timeout, source_address=source_address)
        self._pinned_addrs = tuple(addrs or ())
        self._deadline = deadline

    def connect(self):
        self.sock = _connect_pinned(
            self._pinned_addrs, self.timeout, self.source_address)
        if self._deadline is not None:
            self.sock = _DeadlineSocket(self.sock, self._deadline, self.timeout)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """HTTPS 连接：TCP 层使用已校验 sockaddr，TLS 仍以原始 hostname 作 SNI。"""

    def __init__(self, host, port=None, timeout=socket._GLOBAL_DEFAULT_TIMEOUT,
                 source_address=None, context=None, addrs=None, deadline=None):
        http.client.HTTPSConnection.__init__(
            self, host, port, timeout=timeout, source_address=source_address,
            context=context)
        self._pinned_addrs = tuple(addrs or ())
        self._deadline = deadline

    def connect(self):
        self.sock = _connect_pinned(
            self._pinned_addrs, self.timeout, self.source_address)
        if self._deadline is not None:
            remaining = self._deadline - time.monotonic()
            if remaining <= 0:
                self.sock.close()
                raise TimeoutError('net TLS exceeded budget')
            self.sock.settimeout(_tighten_timeout(self.timeout, remaining))
        server_hostname = self._tunnel_host or self.host
        try:
            self.sock = self._context.wrap_socket(
                self.sock, server_hostname=server_hostname)
        except Exception:
            sock, self.sock = self.sock, None
            try:
                sock.close()
            except OSError:
                pass
            raise
        if self._deadline is not None:
            self.sock = _DeadlineSocket(self.sock, self._deadline, self.timeout)


class _PinnedHTTPHandler(urllib.request.HTTPHandler):
    """每个 http hop 先校验 URL 并解析绑定地址，再交给 do_open 连接。"""

    def __init__(self, client):
        urllib.request.HTTPHandler.__init__(self)
        self._client = client

    def http_open(self, req):
        scheme, host, port = _validate_url(req.full_url)
        if scheme != 'http':
            raise NetPolicyError('http handler got %r URL' % (scheme,))
        addrs = self._client._resolver(host, port or _DEFAULT_PORTS['http'])

        def factory(host_arg, timeout=None, **kwargs):
            deadline = getattr(req, _DEADLINE_ATTR, None)
            if deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('net DNS/connect exceeded budget')
                timeout = _tighten_timeout(timeout, remaining)
            return _PinnedHTTPConnection(
                host_arg, timeout=timeout, addrs=addrs,
                deadline=deadline)

        return self.do_open(factory, req)


class _PinnedHTTPSHandler(urllib.request.HTTPSHandler):
    """每个 https hop 先校验 URL 并解析绑定地址；TLS SNI 保持原 hostname。"""

    def __init__(self, client, context):
        urllib.request.HTTPSHandler.__init__(self, context=context)
        self._client = client

    def https_open(self, req):
        scheme, host, port = _validate_url(req.full_url)
        if scheme != 'https':
            raise NetPolicyError('https handler got %r URL' % (scheme,))
        addrs = self._client._resolver(host, port or _DEFAULT_PORTS['https'])

        def factory(host_arg, timeout=None, **kwargs):
            deadline = getattr(req, _DEADLINE_ATTR, None)
            if deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('net DNS/connect exceeded budget')
                timeout = _tighten_timeout(timeout, remaining)
            return _PinnedHTTPSConnection(
                host_arg, timeout=timeout, context=self._context, addrs=addrs,
                deadline=deadline)

        return self.do_open(factory, req)


class _PinnedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """重定向 hop 同样只允许规范 http/https，并把全局 deadline 传递到下一跳。

    Python 3.9 标准库 ``http_error_302`` 会在跳转前无界 ``fp.read()`` 掉当前 30x
    响应体：恶意合法公网源站只要对 30x 响应体做 slow-drip，就能在每次重定向前把
    ``NetClient.gate`` 长期占住，绕过总 budget/deadline。这里保留标准库的
    Location/URI 规范化、redirect_request、循环检测和状态码/方法语义，只把
    ``fp.read()`` 改为"确认可安全 close 当前响应"，并把原请求的 deadline 传给
    重定向 Request、按剩余预算收紧每跳 timeout；过期则抛 TimeoutError 且不再开
    下一跳。DNS 解析仍受标准库限制（无法强制中断），此处不改变该既有说明。
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        try:
            _validate_url(newurl)
        except Exception:
            # 策略拒绝也立即释放当前 30x 响应，避免连接留待 GC。
            self._close_redirect_response(fp)
            raise
        new = urllib.request.HTTPRedirectHandler.redirect_request(
            self, req, fp, code, msg, headers, newurl)
        if new is not None:
            deadline = getattr(req, _DEADLINE_ATTR, None)
            if deadline is not None:
                setattr(new, _DEADLINE_ATTR, deadline)
        return new

    @staticmethod
    def _close_redirect_response(fp):
        """关闭当前 30x 响应而不读取其响应体；不可安全关闭时 fail-closed。"""
        if fp is None:
            raise NetPolicyError('redirect response is missing')
        close = getattr(fp, 'close', None)
        if not callable(close):
            raise NetPolicyError('redirect response is not closable')
        try:
            close()
        except Exception as exc:  # noqa: BLE001 - 关不掉就绝不继续下一跳
            raise NetPolicyError(
                'cannot safely close redirect response: %r' % (exc,)) from exc

    def http_error_302(self, req, fp, code, msg, headers):
        # 以下 Location/URI 规范化与 Python 3.9 标准库逐行一致。
        if "location" in headers:
            newurl = headers["location"]
        elif "uri" in headers:
            newurl = headers["uri"]
        else:
            return

        urlparts = urlparse(newurl)
        if urlparts.scheme not in ('http', 'https', 'ftp', ''):
            raise urllib.error.HTTPError(
                newurl, code,
                "%s - Redirection to url '%s' is not allowed" % (msg, newurl),
                headers, fp)
        if not urlparts.path and urlparts.netloc:
            urlparts = list(urlparts)
            urlparts[2] = "/"
        newurl = urlunparse(urlparts)
        newurl = quote(newurl, encoding="iso-8859-1", safe=string.punctuation)
        newurl = urljoin(req.full_url, newurl)

        new = self.redirect_request(req, fp, code, msg, headers, newurl)
        if new is None:
            return

        # 循环检测：与标准库一致（状态码/方法语义由 redirect_request 保留）。
        if hasattr(req, 'redirect_dict'):
            visited = new.redirect_dict = req.redirect_dict
            if (visited.get(newurl, 0) >= self.max_repeats or
                    len(visited) >= self.max_redirections):
                raise urllib.error.HTTPError(
                    req.full_url, code, self.inf_msg + msg, headers, fp)
        else:
            visited = new.redirect_dict = req.redirect_dict = {}
        visited[newurl] = visited.get(newurl, 0) + 1

        # 关键：只 close，不 drain。慢滴响应体不再能占住 gate。
        self._close_redirect_response(fp)

        deadline = getattr(req, _DEADLINE_ATTR, None)
        if deadline is None:
            timeout = req.timeout
        else:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('net redirect exceeded budget')
            timeout = _tighten_timeout(req.timeout, remaining)
        return self.parent.open(new, timeout=timeout)

    http_error_301 = http_error_303 = http_error_307 = http_error_302


class NetClient(object):
    """统一网络客户端：并发闸门在实例级共享，业务模块共用同一个实例。"""

    def __init__(self, cfg):
        self.cfg = cfg
        self._ssl_ctx = ssl.create_default_context()
        # DNS 解析 + 公网地址校验：实例级注入点，生产默认策略为 _resolve_pinned，
        # 只有测试实例可以替换（不允许在默认策略中放行回环/私网）。
        self._resolver = _resolve_pinned
        # 关闭环境代理（ProxyHandler({})），避免代理端再次解析目标绕过本地绑定；
        # 自定义 http/https/redirect handler 保证每个 hop 重新校验并绑定地址。
        self._opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            _PinnedRedirectHandler(),
            _PinnedHTTPHandler(self),
            _PinnedHTTPSHandler(self, self._ssl_ctx),
        )
        # 并发闸门：全局 ≤ net_concurrency（改配置需重启进程生效，见部署说明）
        self.gate = threading.BoundedSemaphore(
            int(cfg.settings['limits']['net_concurrency']))

    # ---------- 基础 ----------
    @property
    def ua(self):
        return self.cfg.settings['network']['user_agent']

    @staticmethod
    def enc(url):
        """含中文等非 ASCII 字符的 URL 需要 quote 后再请求。"""
        if re.search(r'[^\x00-\x7f]', url):
            return quote(url, safe=_SAFE)
        return url

    # ---------- 硬时限辅助 ----------
    @staticmethod
    def _remaining(deadline):
        """距 deadline 的剩余秒数（可能 ≤ 0，表示已耗尽）。"""
        return deadline - time.monotonic()

    @staticmethod
    def _default_budget(timeout):
        """未显式给定 budget_s 时，由单次 timeout 派生默认总预算。"""
        return max(timeout, timeout * 2.5)

    @staticmethod
    def _response_socket(r):
        """尽力取回标准 urllib 响应的底层 socket。

        标准 http.client.HTTPResponse.fp 是 io.BufferedReader，底层 socket
        位于 ``fp.raw._sock``；HTTPS 下 _sock 为 SSLSocket，其 settimeout 同样
        约束后续阻塞读。取不到时返回 None（由调用方退化处理，绝不静默放弃）。
        """
        fp = getattr(r, 'fp', None)
        if fp is None:
            return None
        if isinstance(fp, socket.socket):
            return fp
        raw = getattr(fp, 'raw', None)
        for obj in (raw, fp):
            if obj is None:
                continue
            if isinstance(obj, socket.socket):
                return obj
            sock = getattr(obj, '_sock', None)
            if isinstance(sock, socket.socket):
                return sock
        return None

    def _set_response_socket_timeout(self, r, seconds):
        """把响应底层 socket 的阻塞读超时收紧到 seconds；失败返回 False。"""
        sock = self._response_socket(r)
        if sock is None:
            return False
        try:
            sock.settimeout(seconds)
        except (OSError, ValueError):
            return False
        return True

    @staticmethod
    def _body_complete(r):
        """在发起下一次阻塞读之前，判断响应体是否已读完（避免误判超时）。"""
        if getattr(r, 'fp', None) is None:
            return True
        return getattr(r, 'length', None) == 0

    def _read_body(self, r, limit, deadline, per_read_timeout, require_complete=False):
        """响应体分块读取：每次阻塞读前把 socket 超时收紧到剩余预算内。"""
        read1 = getattr(r, 'read1', None)
        if not callable(read1) or self._response_socket(r) is None:
            # 继续调用普通 read() 会重新引入 slow-drip 无上限；遇到非标准响应
            # 宁可拒绝，也不能悄悄失去本函数承诺的响应体时限。
            raise RuntimeError('net response does not support bounded body reads')
        chunks = []
        total = 0
        eof = False
        expected_length = getattr(r, 'length', None)
        while total < limit:
            if self._body_complete(r):
                break
            remaining = self._remaining(deadline)
            if remaining <= 0:
                raise TimeoutError('net body read exceeded budget')
            # 关键：在可能阻塞的 read 之前收紧底层 socket 超时，
            # 而不是只在 read 返回后再检查 deadline。
            if not self._set_response_socket_timeout(
                    r, min(per_read_timeout, remaining)):
                raise RuntimeError('net response socket timeout is unavailable')
            n = min(_READ_CHUNK, limit - total)
            try:
                chunk = read1(n)
            except _TIMEOUT_EXCS:
                raise TimeoutError('net body read exceeded budget')
            except Exception:
                # SSL 等路径可能把过期读包成别的异常；预算已尽则归一为 TimeoutError
                if self._remaining(deadline) <= 0:
                    raise TimeoutError('net body read exceeded budget')
                raise
            if not chunk:
                eof = True
                break
            chunks.append(chunk)
            total += len(chunk)
        if require_complete:
            if expected_length is not None and total != expected_length:
                raise NetBodyLimitError('response length does not match Content-Length')
            if not (eof or self._body_complete(r)):
                raise NetBodyLimitError('response not complete within byte limit')
        return b''.join(chunks)

    def fetch_ex(self, url, ua=None, limit=None, timeout=None, budget_s=None,
                 require_complete=False):
        """单次 GET，额外暴露重定向后的**最终 URL**：返回 (status, body, ct, final_url)。

        - final_url 取自 ``response.geturl()``；Python 3.9 urllib 默认跟随
          301/302/303/307，故
          final_url 即最终落点。DASH 媒体分片 base、HLS 签名/会话重定向，均须以 final_url
          为基准解析（否则分片 403 —— 见 HK_MPD_ROUTE_DESIGN §3 / H1 实测）。
        - limit=None 时取 393216（保持 fetch 的历史默认）。
        - require_complete=True 时，已知长度须完全匹配，未知长度须读到 EOF；
          未知长度恰好触顶无法证明完整则保守拒绝，读取量不超过 limit。
        - budget_s=None 时取 ``max(timeout, timeout * 2.5)``；deadline 从进入本函数
          （等待并发闸门之前）起算，闸门等待、每一跳重定向和分块读取共享同一预算：
          30x 响应体只 close 不 drain，重定向 Request 继承 deadline，每跳连接
          timeout 收紧到剩余预算，过期抛 TimeoutError。标准库 DNS 解析本身无法强制
          中断，仍可能超出墙钟预算；响应返回后会立即重新检查，不把它误报为全链路
          强杀保证。
        - 异常（URLError/HTTPError/timeout/ssl）原样抛出；预算耗尽抛 TimeoutError；
          并发闸门 self.gate 语义不变。
        """
        if timeout is None:
            timeout = float(self.cfg.settings['limits']['timeout_s'])
        timeout = float(timeout)
        if limit is None:
            limit = 393216
        if budget_s is None:
            budget_s = self._default_budget(timeout)
        budget_s = float(budget_s)

        # 硬时限必须在等待并发闸门之前建立
        deadline = time.monotonic() + budget_s

        # 初始目标在进入 opener 前即拒绝非 http/https、userinfo、空 host、非法端口，
        # 防止非 http(s) scheme 落到 FileHandler/FTPHandler。
        safe_url = self.enc(url)
        _validate_url(safe_url)
        req = urllib.request.Request(
            safe_url,
            headers={'User-Agent': ua or self.ua, 'Accept-Encoding': 'identity'})
        # 初始请求携带全局 deadline，重定向链每一跳都会继承它。
        setattr(req, _DEADLINE_ATTR, deadline)

        acquired = False
        try:
            remaining = self._remaining(deadline)
            if remaining <= 0:
                raise TimeoutError('net budget exhausted before gate')
            # 闸门等待同样使用剩余预算
            acquired = self.gate.acquire(timeout=remaining)
            if not acquired:
                raise TimeoutError('net budget exhausted while waiting for gate')

            remaining = self._remaining(deadline)
            if remaining <= 0:
                raise TimeoutError('net budget exhausted before connect')
            # opener.open 的 timeout 不得超过剩余预算
            connect_timeout = min(timeout, remaining)
            with self._opener.open(req, timeout=connect_timeout) as r:
                status = r.status
                ct = r.headers.get('Content-Type', '')
                final_url = r.geturl()
                body = self._read_body(r, limit, deadline, timeout, require_complete)
                return (status, body, ct, final_url)
        finally:
            if acquired:
                self.gate.release()

    def fetch_complete(self, url, ua=None, limit=393216, timeout=None):
        """完整下载（已知长度恰好等于上限可接受；未知长度触顶保守拒绝）。"""
        status, body, ct, _final = self.fetch_ex(
            url, ua=ua, limit=limit, timeout=timeout, require_complete=True)
        return status, body, ct

    def fetch(self, url, ua=None, limit=393216, timeout=None, budget_s=None):
        """单次 GET：返回 (status, body_bytes, content_type)（fetch_ex 的薄封装，签名不变）。

        budget_s 为尾部新增可选参数（默认由 timeout 派生），其余语义与 fetch_ex 一致。
        异常（URLError/HTTPError/timeout/ssl）原样抛给调用方，
        由调用方在 tick 粒度捕获（禁止冒泡杀进程）。
        """
        # budget_s 未显式传入时保持历史调用形状，兼容测试桩和调用方对
        # fetch_ex 的同签名替换；默认预算仍由真实 fetch_ex 内部派生。
        kwargs = {'ua': ua, 'limit': limit, 'timeout': timeout}
        if budget_s is not None:
            kwargs['budget_s'] = budget_s
        status, body, content_type, _final_url = self.fetch_ex(url, **kwargs)
        return status, body, content_type

    def fetch_retry(self, url, ua=None, limit=393216, timeout=None, retries=None, backoff=None):
        """带重试+指数退避的 GET：成功返回 body bytes，全部失败抛最后一个异常。

        仅重试瞬态故障：普通 OSError（含 URLError/TimeoutError）、
        http.client.HTTPException，以及 _RETRYABLE_HTTP_STATUS 中的 HTTPError。
        NetPolicyError（SSRF 策略拒绝）与其他 HTTP 状态（含永久 4xx）立即失败；
        未分类异常直接冒泡。捕获顺序把 HTTPError/NetPolicyError 放在 OSError
        之前，避免其被当作普通网络异常吞并。
        """
        net = self.cfg.settings['network']
        if retries is None:
            retries = int(net['retries'])
        if backoff is None:
            backoff = float(net['backoff_s'])
        last_exc = None
        for attempt in range(retries + 1):
            try:
                _st, body, _ct = self.fetch(url, ua=ua, limit=limit, timeout=timeout)
                return body
            except NetPolicyError:
                raise
            except urllib.error.HTTPError as e:
                if e.code not in _RETRYABLE_HTTP_STATUS:
                    raise
                last_exc = e
            except (OSError, http.client.HTTPException) as e:
                last_exc = e
            if attempt < retries:
                time.sleep(backoff * (2 ** attempt))
        raise last_exc

    # ---------- CDN 通道 ----------
    def jsdelivr_url(self, repo, branch, path):
        """https://cdn.jsdelivr.net/gh/{repo}@{branch}/{path}"""
        return '%s%s@%s/%s' % (
            self.cfg.settings['network']['jsdelivr_prefix'], repo, branch, path)

    def ghproxy_url(self, repo, branch, path):
        """ghproxy 镜像通道（备用）：{prefix}{repo}/{branch}/{path}"""
        return '%s%s/%s/%s' % (
            self.cfg.settings['network']['ghproxy_prefix'], repo, branch, path)
