#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""种子数据一次性导入 sqlite（冷启动）。

用法（项目根目录下执行）:
    python tools/import_seed.py

幂等可重跑：url_hash UNIQUE 索引 + INSERT OR IGNORE，重复执行第二次新增 0 条。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.config import Config                       # noqa: E402
from app.db import Database, utcnow                 # noqa: E402
from app.discovery import SeedProvider, SourceDiscovery  # noqa: E402
from app.logutil import get_logger, setup_logging   # noqa: E402
from app.net import NetClient                       # noqa: E402

log = get_logger('import_seed')


def main():
    cfg = Config(ROOT)
    setup_logging(cfg.log_dir)
    db = Database(cfg.db_path)
    db.migrate(cfg.settings)   # 传 settings：v5 回填需 host 作用域签名参数名单
    net = NetClient(cfg)
    discovery = SourceDiscovery(cfg, db, net)

    seeds = SeedProvider(cfg, discovery.parser).entries()
    log.info('种子解析: %d 条待入库', len(seeds))
    new, dup, blocked = discovery.ingest(seeds, 'seed')
    db.set_meta('seed_imported', utcnow())

    row = db.query_one('SELECT COUNT(*) AS c FROM sources')
    active = db.query_one("SELECT COUNT(*) AS c FROM sources WHERE status='active'")
    cand = db.query_one("SELECT COUNT(*) AS c FROM sources WHERE status='candidate'")
    bl = db.query_one("SELECT COUNT(*) AS c FROM sources WHERE status='blacklisted'")
    log.info('导入完成: 新增 %d / 重复 %d / 黑名单拦截 %d', new, dup, blocked)
    log.info('sources 总计 %d 条 (active=%d candidate=%d blacklisted=%d)',
             row['c'], active['c'], cand['c'], bl['c'])
    db.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
