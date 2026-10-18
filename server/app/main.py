#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""iptv-nas 入口（ARCHITECTURE §6.5）：

1. Config.load() -> 2. Database.connect + migrate
3. sources 为空 -> 自动种子导入（冷启动兜底）
4. OutputBuilder.build()（HTTP 秒可用） -> 5. Scheduler daemon 线程
6. HttpServer.serve_forever() 阻塞主线程（进程由 systemd/计划任务守护）

用法:
    python app/main.py            # 常驻服务
    python app/main.py --version  # 查询版本
"""
import os
import signal
import sys
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import __version__, schema              # noqa: E402
from app.builder import OutputBuilder           # noqa: E402
from app.config import Config                   # noqa: E402
from app.db import Database                     # noqa: E402
from app.discovery import SeedProvider, SourceDiscovery  # noqa: E402
from app.hub import Hub                          # noqa: E402
from app.httpserver import HttpServer           # noqa: E402
from app.logutil import get_logger, setup_logging  # noqa: E402
from app.net import NetClient                   # noqa: E402
from app.scheduler import Scheduler             # noqa: E402
from app.status import StatusProvider           # noqa: E402

log = get_logger('main')


def seed_if_empty(cfg, db, net):
    """空库冷启动：自动执行 import_seed 逻辑（种子兜底）。"""
    row = db.query_one('SELECT COUNT(*) AS c FROM sources')
    if row and row['c'] > 0:
        return
    log.info('sources 为空, 执行冷启动种子导入...')
    discovery = SourceDiscovery(cfg, db, net)
    stats = discovery.run()
    log.info('冷启动种子导入完成: %s', stats)


EXIT_JOIN_TIMEOUT = 10


def finalize_resources(scheduler, http, db, join_timeout=EXIT_JOIN_TIMEOUT):
    """退出收尾（顺序即契约，抽为可测 helper，避免测试运行真正 main）。

    1) 通知调度器停止，并立即启动 HTTP 收尾（停止接收）；
    2) 等待 HTTP 在途 handler 全部结束（`HttpServer.shutdown()` 幂等，无论
       信号 worker 是否已在收尾，本调用都会阻塞到在途请求结束）；
    3) 等待调度器线程退出（其自持连接在线程末关闭）；
    4) 最后关闭主库（StatusProvider / Hub / HTTP handler 共用该连接）。
    """
    scheduler.stop()
    http.shutdown()
    scheduler.join(timeout=join_timeout)
    db.close()


def main(argv):
    if '--version' in argv:
        print('iptv-nas %s' % __version__)
        return 0

    cfg = Config(ROOT)
    setup_logging(cfg.log_dir)
    log.info('iptv-nas %s 启动, 项目根: %s', __version__, ROOT)

    # P1-A：任何线程未捕获异常兜底记录（防静默死线程，日志全活动闭环）
    def _thread_excepthook(args):
        log.error('线程 %s 未捕获异常: %s',
                  args.thread.name if args.thread is not None else '?',
                  args.exc_value,
                  exc_info=(args.exc_type, args.exc_value, args.exc_traceback))
    threading.excepthook = _thread_excepthook

    db = Database(cfg.db_path)
    db.migrate(cfg.settings)   # 传 settings：v5 回填需 host 作用域签名参数名单
    # P0-2 方案 C：签名名单指纹守卫。部署后首次启动若名单相对上次锚点有变化，
    # 这里触发一次全表 url_key 重建（幂等；指纹不变则零操作）。调度 tick 另有同守卫，
    # 覆盖热重载名单变更。
    try:
        rebuilt = schema.ensure_url_keys_fresh(db, cfg.settings)
        if rebuilt is not None:
            log.info('签名名单指纹变化, 已全表重算 url_key: %s', rebuilt)
    except Exception as e:  # noqa: BLE001 - 不阻塞启动，调度 tick 会重试
        log.error('url_key 指纹守卫异常(调度 tick 将重试): %s', e)
    net = NetClient(cfg)

    # 空库冷启动 + 首次构建（HTTP 立即可用）
    seed_if_empty(cfg, db, net)
    builder = OutputBuilder(cfg, db)
    builder.build()

    # 调度线程（首轮立即 probe -> verify -> discovery）
    scheduler = Scheduler(ROOT, cfg=cfg)
    # 状态聚合（healthz / api/status / 状态页），持调度器引用做心跳判活（P1-E/G）
    status_provider = StatusProvider(cfg, db, scheduler)
    scheduler.start()

    # HTTP 订阅服务（主线程阻塞）
    # 枢纽（HUB_CONTRACT_V1）：独立服务层，读 DB + 写回流表，不侵入四模块
    hub = Hub(cfg, db)
    if not hub.enabled():
        log.info('枢纽 Hub 未启用（settings.hub.enabled=false），三端点返回 404')
    http = HttpServer(cfg, status_provider, hub)

    def _shutdown(signum, _frame):
        log.info('收到信号 %s, 优雅退出', signum)
        scheduler.stop()
        # shutdown 必须从非 serve_forever 线程调用；HttpServer.shutdown() 幂等，
        # 重复信号只会多起一个等待同一收尾完成的线程，效果等价于一次。
        threading.Thread(target=http.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    try:
        http.serve_forever()
    finally:
        # 竞态修复：serve_forever 返回不代表在途 handler 已结束。finalize_resources
        # 会等 HTTP 收尾（含 join 在途 handler）完成后才关闭主库。
        finalize_resources(scheduler, http, db)
        log.info('iptv-nas 已退出')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
