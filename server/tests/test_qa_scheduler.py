# -*- coding: utf-8 -*-
"""QA 补充测试 2：调度器异常隔离 + 任务互斥 + 原子写（P0-4/P0-5 关键路径）。

覆盖：
- probe/verify/discovery/builder 任一抛异常，tick() 吞掉异常继续（不杀进程/线程）；
- 同一 tick 内前序任务失败不阻断后续任务；异常后下一 tick 恢复正常；
- running 互斥：上轮未完成的任务本轮跳过（不重复执行）；
- 构建中断（模拟 os.replace 失败/渲染中途崩溃）不留残缺 live.txt；
- 并发读写：重建期间读到的永远是完整文件。
"""
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.builder import OutputBuilder       # noqa: E402
from offline_fixtures import write_test_config, make_config  # noqa: E402
from app.db import Database, utcnow         # noqa: E402
from app.scheduler import TASK_ORDER, Scheduler  # noqa: E402


def make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-qa-sched-')
    write_test_config(tmp)
    return tmp


class FakeComponent(object):
    """可编程假业务组件：按脚本执行（记录调用 / 抛异常）。"""

    def __init__(self, name, calls, script):
        self.name = name
        self.calls = calls
        self.script = script   # list: 'ok' 或 Exception 实例

    def run(self):
        self.calls.append(self.name)
        act = self.script.pop(0) if self.script else 'ok'
        if isinstance(act, Exception):
            raise act
        return {'fake': True}

    def build(self):
        return self.run()


class TestSchedulerIsolation(unittest.TestCase):
    def setUp(self):
        self.tmp = make_root()
        self.sched = Scheduler(self.tmp)

    def tearDown(self):
        try:
            self.sched.db.close()
        except Exception:
            pass
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _wire(self, calls, probe='ok', verify='ok', discovery='ok', builder='ok'):
        def comp(name, act):
            return FakeComponent(name, calls, [act] if isinstance(act, Exception) else [])
        self.sched.health = comp('probe', probe)
        self.sched.verifier = comp('verify', verify)
        self.sched.discovery = comp('discovery', discovery)
        b = comp('rebuild', builder)
        self.sched.builder = b
        return b

    def test_tick_swallows_all_task_exceptions(self):
        """四个任务全部抛异常，tick() 不向上传播（无人值守硬要求）。"""
        calls = []
        boom = RuntimeError('network down')
        self._wire(calls, probe=boom, verify=boom, discovery=boom, builder=boom)
        try:
            self.sched.tick()   # 不得抛出
        except Exception as e:  # noqa: BLE001
            self.fail('tick() 泄漏异常: %r' % e)
        # 三个周期任务都被尝试过
        self.assertEqual(set(calls) & {'probe', 'verify', 'discovery'},
                         {'probe', 'verify', 'discovery'})
        # 全部失败后 running 标志复位（下一 tick 还能跑）
        self.assertFalse(any(self.sched.running.values()))

    def test_failed_task_does_not_block_later_tasks_in_same_tick(self):
        """probe 崩了，verify/discovery 照常执行。"""
        calls = []
        self._wire(calls, probe=RuntimeError('probe boom'))
        self.sched.tick()
        self.assertIn('probe', calls)
        self.assertIn('verify', calls)
        self.assertIn('discovery', calls)

    def test_recovery_on_next_tick(self):
        """上一 tick 全崩，下一 tick 恢复正常并完成 rebuild。"""
        calls = []
        boom = RuntimeError('x')
        self._wire(calls, probe=boom, verify=boom, discovery=boom)
        self.sched.tick()
        calls.clear()
        self._wire(calls)
        self.sched.tick()
        self.assertEqual(calls, ['probe', 'verify', 'discovery', 'rebuild'])   # 含顺序
        self.assertGreater(self.sched.last_run['probe'], 0)

    def test_priority_order_probe_first(self):
        """探活最轻优先：probe -> verify -> discovery -> rebuild。"""
        calls = []
        self._wire(calls)
        self.sched.tick()
        self.assertEqual(calls, ['probe', 'verify', 'discovery', 'rebuild'])

    def test_mutex_skips_running_task(self):
        """上轮 verify 未完成（running=True），本轮直接跳过不重复执行。"""
        calls = []
        self._wire(calls)
        self.sched.running['verify'] = True
        self.sched.tick()
        self.assertNotIn('verify', calls)

    def test_task_order_constant(self):
        self.assertEqual(TASK_ORDER, ['probe', 'verify', 'discovery', 'rebuild'])


class TestAtomicBuildIntegrity(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='iptv-qa-atomic-')
        cfg = make_config()
        cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')
        cfg.settings['paths']['db'] = os.path.join(self.tmp, 'test.db')
        self.cfg = cfg
        self.db = Database(cfg.db_path)
        self.db.migrate()
        now = utcnow()
        self.db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " av, score, first_seen, last_seen) VALUES ('http://x/c1','h1','CCTV1','CCTV1',"
            "'qa','active','video',10,?,?)", (now, now))

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _live_txt(self):
        p = os.path.join(self.cfg.output_dir, 'live.txt')
        if not os.path.isfile(p):
            return None
        with open(p, encoding='utf-8') as f:
            return f.read()

    def test_interrupted_render_keeps_old_file_intact(self):
        """渲染中途崩溃（live.m3u 渲染抛异常）-> live.txt 保持上一版完整内容。"""
        b = OutputBuilder(self.cfg, self.db)
        b.build()
        old_txt = self._live_txt()
        self.assertIn('CCTV1', old_txt)

        with mock.patch.object(b, 'render_m3u', side_effect=RuntimeError('render boom')):
            with self.assertRaises(RuntimeError):
                b.build()
        # live.txt 未被破坏（上一版完好，而不是空/半截）
        self.assertEqual(self._live_txt(), old_txt)
        # 没有残留 .tmp
        self.assertEqual([f for f in os.listdir(self.cfg.output_dir) if f.endswith('.tmp')], [])

    def test_crash_before_replace_keeps_old_file(self):
        """os.replace 前一刻崩溃 -> 目标文件仍是旧内容（读者永远看不到半截）。"""
        b = OutputBuilder(self.cfg, self.db)
        b.build()
        old_txt = self._live_txt()
        with mock.patch('app.builder.os.replace', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                b.build()
        self.assertEqual(self._live_txt(), old_txt)

    def test_concurrent_read_never_partial(self):
        """重建期间并发读取：读到的永远是完整合法内容（原子性验收 P0-5②）。

        平台差异（QA 实测结论，已记录缺陷报告）：
        - POSIX（NAS 目标环境）：os.replace 与并发读完全兼容，0 错误；
        - Windows：目标文件被占用时 os.replace 抛 PermissionError —— 构建失败但
          旧文件保持完整（不会出现半截内容），scheduler 会捕获并在下轮重试。
          本测试在 Windows 上验证降级契约：内容永不残缺。
        """
        b = OutputBuilder(self.cfg, self.db)
        b.build()
        valid = set()
        stop = threading.Event()
        read_errors = []
        replace_errors = []

        def reader():
            while not stop.is_set():
                try:
                    with open(os.path.join(self.cfg.output_dir, 'live.txt'),
                              encoding='utf-8') as f:
                        content = f.read()
                    if content:
                        valid.add(content)
                except (OSError, UnicodeDecodeError) as e:
                    read_errors.append(e)
                time.sleep(0.001)

        t = threading.Thread(target=reader)
        t.start()
        try:
            for _ in range(30):
                try:
                    b.build()
                except PermissionError as e:   # Windows 并发读占用
                    replace_errors.append(e)
        finally:
            stop.set()
            t.join(timeout=5)
        # 无论哪个平台：读到的永远是完整版本（有头有尾、以换行结束），绝不残缺
        self.assertTrue(valid)
        for c in valid:
            self.assertTrue(c.startswith('📺央视CCTV,#genre#'))
            self.assertTrue(c.endswith('\n'))
        if os.name == 'posix':
            self.assertEqual(read_errors, [])
            self.assertEqual(replace_errors, [])
        else:
            # Windows 降级契约（QA 实测缺陷，已记报告）：
            # 1) 替换窗口内读侧可能瞬时 PermissionError（HTTP 层表现为 404 not ready）；
            # 2) 读占用时 os.replace 可能失败（scheduler 捕获，下轮重试）；
            # 但任何成功读到的内容必须是完整版本，绝不残缺。
            self.assertTrue(valid, '应至少成功读到一次完整内容')


if __name__ == '__main__':
    unittest.main()
