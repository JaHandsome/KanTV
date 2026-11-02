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
_URL_IN_TEXT = re.compile(r'https?://[^\s\'\"<>]+', re.IGNORECASE)


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
    """把文本里的 http(s) URL 换成 redact_url 的结果，再交给日志。"""
    text = value if isinstance(value, str) else str(value)
    if not text:
        return text
    return _URL_IN_TEXT.sub(lambda match: redact_url(match.group(0)), text)
