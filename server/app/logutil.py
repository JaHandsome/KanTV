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
import sys
from logging.handlers import RotatingFileHandler

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
