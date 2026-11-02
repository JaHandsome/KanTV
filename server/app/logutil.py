# -*- coding: utf-8 -*-
"""日志工具：RotatingFileHandler(16MB x 5) + 控制台双输出。

P1-A 审计增强：轮转扩容 10MB -> 16MB（当前 + 5 个轮转副本 = 96MB 物理上限，
仍守住 P0-6 的 100MB 红线，容量较原 50MB 提升约一倍，长周期审计日志不被
早期活动挤掉）；其余"全活动覆盖"由各模块在原静默路径补记日志实现
（verifier 逐源结论 / health 非跃迁结果 DEBUG / config 热加载变更 INFO）。

用法:
    from app.logutil import setup_logging, get_logger
    setup_logging(log_dir)
    log = get_logger('discovery')
"""
import logging
import os
import re
import sys
from logging.handlers import RotatingFileHandler
from urllib.parse import urlsplit

_LOG_FILE = 'iptv-nas.log'
_MAX_BYTES = 16 * 1024 * 1024   # 16MB（P1-A 扩容：原 10MB）
_BACKUP_COUNT = 5               # 16MB x (5+1) = 96MB 物理上限 <= 100MB(P0-6)
_FORMAT = '%(asctime)s %(levelname)-7s [%(name)s] %(message)s'
_configured = False


def setup_logging(log_dir, level=logging.INFO):
    """初始化根 logger（进程内幂等，重复调用不叠加 handler）。"""
    global _configured
    if _configured:
        return
    try:
        os.makedirs(log_dir, exist_ok=True)
    except OSError:
        log_dir = None
    root = logging.getLogger()
    root.setLevel(level)
    fmt = logging.Formatter(_FORMAT)
    if log_dir:
        fh = RotatingFileHandler(os.path.join(log_dir, _LOG_FILE),
                                 maxBytes=_MAX_BYTES, backupCount=_BACKUP_COUNT,
                                 encoding='utf-8')
        fh.setFormatter(fmt)
        root.addHandler(fh)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)
    root.addHandler(sh)
    _configured = True


def get_logger(name):
    """按模块名取 logger（discovery/verifier/health/builder/scheduler/http）。"""
    return logging.getLogger(name)


_REDACTED_URL = '<redacted-url>'
_MAX_LOG_PATH = 120
_URL_IN_TEXT = re.compile(
    r'(?:https?|udp|rtp|rtsp|rtmp)://[^\s\'\"<>]+',
    re.IGNORECASE,
)


def redact_url(url):
    """日志用的 URL：只保留 scheme、host、port、path。

    签名直播地址把 token / securitykey / sign / expires 放在 query 里。
    这些值不能进入轮转日志。userinfo 与 fragment 同样丢弃。无法解析、
    或含换行/NUL 的输入返回固定占位，避免把原文写进日志。
    """
    if not isinstance(url, str) or not url or any(ch in url for ch in '\r\n\x00'):
        return _REDACTED_URL
    try:
        parts = urlsplit(url.strip())
        host = parts.hostname
        port = parts.port
    except ValueError:
        return _REDACTED_URL
    scheme = (parts.scheme or '').lower()
    if scheme not in ('http', 'https', 'udp', 'rtp', 'rtsp', 'rtmp') or not host:
        return _REDACTED_URL
    if ':' in host:
        host = '[%s]' % host
    netloc = host if port is None else '%s:%d' % (host, port)
    path = parts.path or ''
    if len(path) > _MAX_LOG_PATH:
        path = path[:_MAX_LOG_PATH] + '...'
    return '%s://%s%s' % (scheme, netloc, path)


def redact_text(value):
    """把文本里的 http(s)/组播 URL 换成 redact_url 的结果，再交给日志。"""
    text = value if isinstance(value, str) else str(value)
    if not text:
        return text
    return _URL_IN_TEXT.sub(lambda match: redact_url(match.group(0)), text)


def redact_exc(exc):
    """单行日志里的异常：去掉 URL，并把换行压成空格。

    urllib 拒绝跨协议跳转时，HTTPError 的说明文字带完整 Location
    （含 query token）。直接 ``%s`` 插进日志或状态页历史会留下签名。
    换行同样压平，避免一条异常伪造多行日志。
    """
    return redact_text(exc).replace('\r', ' ').replace('\n', ' ')


_REQUEST_TARGET = re.compile(
    r'"((?:GET|HEAD|POST|PUT|DELETE|OPTIONS|PATCH)\s+)(\S+)',
    re.IGNORECASE,
)
# 在空格、单引号、双引号处停下，避免把后面的 HTTP 版本或 %r 的引号吃掉。
_QUERY_OR_FRAGMENT = re.compile(r'[?#][^ \'"]*')


def redact_request_target(target):
    """请求目标只保留 path。query 和 fragment 里可能有签名参数。"""
    if not isinstance(target, str) or not target or any(ch in target for ch in '\r\n\x00'):
        return _REDACTED_URL
    stripped = target.strip()
    if '://' in stripped:
        return redact_url(stripped)
    path = stripped.split('?', 1)[0].split('#', 1)[0]
    if not path.startswith('/'):
        return _REDACTED_URL
    if len(path) > _MAX_LOG_PATH:
        path = path[:_MAX_LOG_PATH] + '...'
    return path


def redact_access_message(message):
    """访问日志整行：去掉 query，并在控制字符处截断。

    请求行只剥掉末尾的 CR/LF。中间的 C0、DEL，或 NEL/NBSP 这类非空格空白，
    会让请求目标的匹配提前结束，伪造的后半行和 query 原样留下；CR 还会盖住同一条审计。
    语法错误另外记 ``Bad request syntax (%r)``：控制字符已被转义，但单引号里的
    query 和 userinfo 匹配不到请求目标正则。先截断，再去掉剩余文本的 query、
    fragment，以及绝对 URL 里的 userinfo。正常行没有这些字符，结果不变。
    """
    text = message if isinstance(message, str) else str(message)
    for index, ch in enumerate(text):
        code = ord(ch)
        if code < 32 or code == 127 or (ch.isspace() and ch != ' '):
            text = text[:index]
            break
    text = _QUERY_OR_FRAGMENT.sub('', text)
    text = redact_text(text)

    def replace(match):
        return '"%s%s' % (match.group(1), redact_request_target(match.group(2)))

    return _REQUEST_TARGET.sub(replace, text, count=1)
