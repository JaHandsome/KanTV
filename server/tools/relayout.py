#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只做线路重排 + 产物重建，**不跑验证轮**。

用于人工改库（如 tools/offline_lines.py 下线线路）后立即收口：
lineups 由每轮探活末尾重建，最长要等一个周期（默认 30min）才生效；
本脚本把这步单独提前执行，避免客户端在这段时间里继续拿到已下线线路。

与 tools/manual_verify.py 的区别：不跑 Verifier，不消耗验证预算、不改候选状态。

用法（容器内）：
    docker exec iptv-nas python3 /app/data/relayout.py
"""
import sys

sys.path.insert(0, '/app')

from app.builder import OutputBuilder   # noqa: E402
from app.config import Config           # noqa: E402
from app.db import Database             # noqa: E402
from app.health import HealthMonitor    # noqa: E402
from app.net import NetClient           # noqa: E402

cfg = Config('/app')
db = Database(cfg.db_path)
db.migrate(cfg.settings)                # 传 settings：v5 回填需 host 作用域签名参数名单
net = NetClient(cfg)

HealthMonitor(cfg, db, net).rebuild_lineups()
out = OutputBuilder(cfg, db).build()
print('BUILD:', out)
db.close()
