#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性手动验证轮（部署后加速消化新增候选；跑完自动重排线路+重建输出）。"""
import sys

sys.path.insert(0, '/app')

from app.builder import OutputBuilder   # noqa: E402
from app.config import Config           # noqa: E402
from app.db import Database             # noqa: E402
from app.health import HealthMonitor    # noqa: E402
from app.net import NetClient           # noqa: E402
from app.verifier import Verifier       # noqa: E402

cfg = Config('/app')
cfg.settings['limits']['verify_budget_min'] = 8   # 手动轮压缩预算，8 分钟内完成
db = Database(cfg.db_path)
db.migrate(cfg.settings)   # 传 settings：v5 回填需 host 作用域签名参数名单
net = NetClient(cfg)
stats = Verifier(cfg, db, net).run()
HealthMonitor(cfg, db, net).rebuild_lineups()
out = OutputBuilder(cfg, db).build()
print('VERIFY:', stats)
print('BUILD:', out)
db.close()
