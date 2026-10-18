#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性频道重归一化（OPTIMIZATION_ANALYSIS #26 配套工具）。

背景：discovery.ingest() 对重复 URL 只刷新 last_seen，不回改 channel_key——
扩充 config/channels.json 别名表后，已入库的未匹配源（channel_key=''）不会
自动被认领，需要本脚本按当前配置重新归一化一遍。

行为：
- 只处理 channel_key='' 的行（含 candidate/active/flaky/dead；blacklisted
  一并重算仅作留痕一致性，不影响验证队列——黑名单在入库阶段已拦截）；
- normalize_channel 命中目标频道 -> UPDATE channel_key；
- 幂等可重跑；结果统计写 meta('renormalize_last_run')；
- 不改 status/av/score——认领后的 candidate 由下一轮 verify 自然消费，
  active/dead 等状态由 health 按既有状态机管理。

用法（项目根目录）:
    python tools/renormalize_channels.py            # 执行重归一化
    python tools/renormalize_channels.py --dry-run # 只统计不写库

NAS 容器内执行:
    docker exec iptv-nas python3 /app/tools/renormalize_channels.py
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.config import Config          # noqa: E402
from app.db import Database, utcnow    # noqa: E402
from app.logutil import get_logger, setup_logging  # noqa: E402
from app.parser import ChannelParser   # noqa: E402

log = get_logger('renormalize')


def renormalize(db, parser, dry_run=False):
    """重归一化所有 channel_key='' 的源；返回 (scanned, claimed, updates)。"""
    rows = db.query("SELECT id, channel_name FROM sources WHERE channel_key=''")
    updates = []
    for r in rows:
        key = parser.normalize_channel(r['channel_name'])
        if key:
            updates.append((key, r['id']))
    if dry_run:
        return len(rows), len(updates), []
    if updates:
        db.executemany('UPDATE sources SET channel_key=? WHERE id=?', updates)
    db.set_meta('renormalize_last_run', utcnow())
    return len(rows), len(updates), updates


def main(argv):
    dry = '--dry-run' in argv
    cfg = Config(ROOT)
    setup_logging(cfg.log_dir)
    db = Database(cfg.db_path)
    db.migrate(cfg.settings)   # 传 settings：v5 回填需 host 作用域签名参数名单
    parser = ChannelParser(cfg)
    scanned, n, updates = renormalize(db, parser, dry_run=dry)
    if dry:
        log.info('[dry-run] 扫描 %d 条未匹配源, 可认领 %d 条', scanned, n)
        for key, _sid in updates[:30]:
            log.info('[dry-run] 认领 -> %s', key)
    else:
        log.info('重归一化完成: 扫描 %d 条, 认领 %d 条', scanned, n)
    db.close()
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
