# -*- coding: utf-8 -*-
"""Scheduler：定时流水线调度（P0-4）。

- daemon 线程 while True 循环，按 `last_run + 周期` 判到期；
- 优先级：probe > verify > discovery > rebuild（探活最轻优先插队）；
- running 字典互斥防重入（上轮未完自动跳过，不排队堆积）；
- 每 tick 重读配置（热加载，改周期/阈值下轮生效）；
- 每个 tick 的每个任务 try/except 到任务粒度，任何异常只记日志继续循环（无人值守硬要求）。
"""
import threading
import time

from app import schema
from app.builder import OutputBuilder
from app.config import DEFAULT_SETTINGS, Config
from app.db import Database, utcnow
from app.discovery import SourceDiscovery
from app.health import HealthMonitor
from app.logutil import get_logger
from app.net import NetClient
from app.verifier import Verifier

log = get_logger('scheduler')

# 任务优先级（越小越先执行）
TASK_ORDER = ['probe', 'verify', 'discovery', 'rebuild']
# 周期字段名 -> settings.schedule 键
PERIOD_KEYS = {
    'probe': ('probe_minutes', 60.0),
    'verify': ('verify_hours', 3600.0),
    'discovery': ('discovery_hours', 3600.0),
    'rebuild': (None, None),   # rebuild 无独立周期，由 probe/verify 触发
}


class Scheduler(threading.Thread):
    """流水线调度线程。"""

    def __init__(self, root, cfg=None):
        super(Scheduler, self).__init__(daemon=True, name='scheduler')
        self.root = root
        # threading.Thread 已定义内部方法 _stop()，不能用 Event 覆盖它；
        # 否则 join() 收尾时会把 Event 当函数调用并抛 TypeError。
        self._stop_event = threading.Event()
        self.last_run = {t: 0.0 for t in TASK_ORDER}
        self.running = {t: False for t in TASK_ORDER}
        # P1-E 调度心跳：最近一次 tick 时间（healthz 判活依据，None=尚未运行）
        self.last_tick_time = None
        # P1-G 状态页：最近流水线任务结果（内存环形，上限 50 条）
        self.history = []
        # 正式入口与 HTTP/Hub/状态页共享配置；独立工具仍可只传 root。
        self.cfg = cfg if cfg is not None else Config(root)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)   # 传 settings：v5 回填需 host 作用域签名参数
        self.net = NetClient(self.cfg)
        self.discovery = SourceDiscovery(self.cfg, self.db, self.net)
        self.verifier = Verifier(self.cfg, self.db, self.net)
        self.health = HealthMonitor(self.cfg, self.db, self.net)
        self.builder = OutputBuilder(self.cfg, self.db)

    # ---------- 周期 ----------
    def _period(self, task):
        """任务周期（秒）。

        P0 僵尸模式防御：settings.json 的 schedule 写成非法类型/值时，
        回退默认周期而非抛异常（否则调度线程会死于 _sleep_seconds）。
        """
        key, unit = PERIOD_KEYS[task]
        if key is None:
            return None
        default = DEFAULT_SETTINGS['schedule'][key]
        sched = self.cfg.settings.get('schedule')
        raw = default
        if isinstance(sched, dict):
            raw = sched.get(key, default)
        try:
            return float(raw) * unit
        except (TypeError, ValueError):
            log.error('配置 schedule.%s 非法(%r), 回退默认值 %s', key, raw, default)
            return float(default) * unit

    def _tick_fns(self):
        return {
            'probe': self.health.run,
            'verify': self.verifier.run,
            'discovery': self.discovery.run,
            'rebuild': self.builder.build,
        }

    def _run_task(self, name, fn):
        """互斥执行单个任务；异常只记日志（单轮异常不得杀进程）。

        P1-G：每次执行结果写入 history（供 /status 与 /api/status 展示）。
        """
        if self.running.get(name):
            log.warning('任务 %s 上轮未完成, 本轮跳过', name)
            return False
        self.running[name] = True
        t0 = time.time()
        try:
            fn()
            self.last_run[name] = time.time()
            self._push_history(name, True, '完成, 耗时 %.1fs' % (time.time() - t0))
            log.info('任务 %s 完成, 耗时 %.1fs', name, time.time() - t0)
            return True
        except Exception as e:  # noqa: BLE001 - tick 粒度异常兜底
            self._push_history(name, False, '异常: %s' % e)
            log.error('任务 %s 异常: %s', name, e, exc_info=True)
            return False
        finally:
            self.running[name] = False

    def _push_history(self, task, ok, detail):
        """追加一条任务结果到环形历史（上限 50 条，超出裁剪最旧）。"""
        self.history.append({'task': task, 'ts': utcnow(), 'ok': bool(ok),
                             'detail': str(detail)[:120]})
        if len(self.history) > 50:
            del self.history[:-50]

    def tick(self):
        """一次调度判定：重读配置 -> 找到期任务 -> 按优先级串行执行。"""
        # P1-E 心跳：tick 开始即刷新（后续任何异常都不影响心跳判定）
        self.last_tick_time = time.time()
        # 订阅生成不能夹在名单发布与 url_key 刷新之间。
        with self.cfg.lock:
            previous_settings = self.cfg.settings
            try:
                self.cfg.reload()   # 热加载（JSON 小文件，开销可忽略）
            except Exception as e:  # noqa: BLE001
                log.error('配置热加载失败, 沿用旧配置: %s', e)
            # P0-2 方案 C：签名名单指纹守卫。配置热加载可能改了 signed_url_params（含
            # settings.json 覆盖）——指纹变化即全表按当前名单重算 url_key（幂等，指纹不变
            # 零操作）。挂在这里同时覆盖「启动首轮 tick」与「热重载名单变更」两个触发点。
            try:
                rebuilt = schema.ensure_url_keys_fresh(self.db, self.cfg.settings)
                if rebuilt is not None:
                    log.info('签名名单指纹变化, 已全表重算 url_key: %s', rebuilt)
            except Exception as e:  # noqa: BLE001 - 失败由下一轮 tick 重试，不杀调度
                # DB 仍持旧签名语义时，不向 Hub 暴露新名单；其他配置域照常热更新。
                self.cfg.settings = previous_settings
                log.error('url_key 指纹守卫异常(下一轮重试): %s', e)
        now = time.time()
        fns = self._tick_fns()
        due = [t for t in TASK_ORDER
               if self._period(t) is not None and now - self.last_run[t] >= self._period(t)]
        changed = False
        for task in due:
            if self._run_task(task, fns[task]):
                changed = True
                if task == 'verify':
                    # P0 修复：verify 激活的源立即进 lineups（原实现要等
                    # 下一轮探活才重排，最长滞后 30min，靠 builder 回退兜底）
                    try:
                        self.health.rebuild_lineups()
                        log.info('verify 后线路重排完成')
                    except Exception as e:  # noqa: BLE001 - 失败由下一轮探活兜底
                        log.error('verify 后线路重排异常(下一轮探活兜底): %s', e)
        # 状态可能变更的任务跑完后，紧跟一次输出重建
        if changed and not self.running.get('rebuild'):
            self._run_task('rebuild', fns['rebuild'])
        # 数据清理（低频：每天最多一次，跟随 verify 周期触发）
        try:
            last_cleanup = float(self.db.get_meta('last_cleanup', '0') or 0)
            if now - last_cleanup > 86400:
                self.db.cleanup(
                    int(self.cfg.settings.get('retention_days', 90)),
                    int(self.cfg.settings.get('dead_retention_days', 30)),
                    int(self.cfg.settings.get('checks_retention_days', 7)),
                    int(self.cfg.settings.get('orphan_retention_days', 30)))
                self.db.set_meta('last_cleanup', str(int(now)))
        except Exception as e:  # noqa: BLE001
            log.error('数据清理异常: %s', e)

    def _sleep_seconds(self):
        """距下一个到期任务的时间（上限 30s，保证热加载响应性）。"""
        now = time.time()
        waits = []
        for t in TASK_ORDER:
            p = self._period(t)
            if p is not None:
                waits.append(max(1.0, self.last_run[t] + p - now))
        return min(min(waits) if waits else 30.0, 30.0)

    # ---------- 主循环 ----------
    def run(self):
        sched = self.cfg.settings.get('schedule')
        if not isinstance(sched, dict):
            log.error('配置 settings.schedule 非法(%r), 周期将回退默认值', sched)
            sched = {}
        log.info('调度器启动: discovery=%sh verify=%sh probe=%smin',
                 sched.get('discovery_hours', '?'),
                 sched.get('verify_hours', '?'),
                 sched.get('probe_minutes', '?'))
        while not self._stop_event.is_set():
            try:
                self.tick()
            except Exception as e:  # noqa: BLE001 - 任何 tick 异常不杀线程
                log.error('调度 tick 异常: %s', e, exc_info=True)
            try:
                self._stop_event.wait(self._sleep_seconds())
            except Exception as e:  # noqa: BLE001 - P0 僵尸模式防御：休眠计算异常退避而非杀线程
                log.error('调度休眠计算异常, 退避 30s: %s', e)
                self._stop_event.wait(30.0)
        try:
            self.db.close()
        except Exception:  # noqa: BLE001
            pass
        log.info('调度器退出')

    def stop(self):
        self._stop_event.set()
