# -*- coding: utf-8 -*-
"""QA 补充测试 3：冷启动链路（P0 全链路的启动顺序，ARCHITECTURE §6.5）。

覆盖（全部离线，仓库通道用抛异常的 FakeNet 模拟外网不可达）：
- 空 data/ 首次启动：migrate -> 种子自动导入 -> builder.build() -> HTTP 文件就绪；
- 断网（外网全挂）不影响冷启动与本地输出；
- 种子导入幂等（seed_imported 标记，第二轮不重复导入）；
- main.seed_if_empty 的判断逻辑（非空库跳过）。
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import main as app_main             # noqa: E402
from app.builder import OutputBuilder        # noqa: E402
from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                # noqa: E402
from app.db import Database                  # noqa: E402
from app.discovery import SourceDiscovery    # noqa: E402


class DeadNet(object):
    """外网全挂的假网络（探活/拉取全部失败）。"""

    ua = 'qa-test-ua'

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        raise OSError('network unreachable (QA simulated)')

    def jsdelivr_url(self, repo, branch, path):
        return 'https://cdn.jsdelivr.net/gh/%s@%s/%s' % (repo, branch, path)

    def ghproxy_url(self, repo, branch, path):
        return 'https://gh-proxy.com/https://raw.githubusercontent.com/%s/%s/%s' % (
            repo, branch, path)


def make_root():
    """临时项目根：公开配置与虚构种子，db/output/seed 均在临时目录。"""
    tmp = tempfile.mkdtemp(prefix='iptv-qa-cold-')
    write_test_config(tmp)
    cfg = Config(tmp)
    cfg.settings['paths']['db'] = os.path.join(tmp, 'data', 'iptv.db')
    cfg.settings['paths']['output_dir'] = os.path.join(tmp, 'data', 'output')
    seed_dir = os.path.join(tmp, 'data', 'seed')
    os.makedirs(seed_dir)
    cfg.settings['paths']['seed_dir'] = seed_dir
    # 保留规模、已验证线路与黑名单路径的回归覆盖；所有 URL 均为虚构值。
    seeds = {'CCTV1': {'all': [
        ['https://seed.example.invalid/%d.m3u8' % i, 'ok' if i < 8 else 'fail']
        for i in range(501)]}}
    with open(os.path.join(seed_dir, '全国频道实测结果.json'), 'w', encoding='utf-8') as f:
        json.dump(seeds, f)
    with open(os.path.join(seed_dir, 'preverified.json'), 'w', encoding='utf-8') as f:
        json.dump({'CCTV2': 'https://preverified.example.invalid/live.m3u8'}, f)
    cfg.rules['blacklist_domains'].append('blocked.example.invalid')
    with open(os.path.join(seed_dir, 'gateway_map.json'), 'w', encoding='utf-8') as f:
        json.dump({'CCTV1': ['https://blocked.example.invalid/live.m3u8']}, f)
    return tmp, cfg


class TestColdStart(unittest.TestCase):
    def setUp(self):
        self.tmp, self.cfg = make_root()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_cold_start_offline_full_chain(self):
        """空库 + 外网全挂：种子导入 -> 构建输出 -> live.txt 可用。"""
        db = Database(self.cfg.db_path)
        db.migrate()
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM sources')['c'], 0)

        # main.seed_if_empty 等价链路（外网挂时 run() 内部吞掉仓库拉取失败）
        app_main.seed_if_empty(self.cfg, db, DeadNet())

        total = db.query_one('SELECT COUNT(*) AS c FROM sources')['c']
        active = db.query_one("SELECT COUNT(*) AS c FROM sources WHERE status='active'")['c']
        self.assertGreater(total, 500)        # 合成账本 + 网关图 + 预验证
        self.assertGreater(active, 5)         # 合成已验证线路
        self.assertEqual(db.get_meta('seed_imported') is not None, True)

        # 黑名单规则在种子阶段已生效（合成网关域名被拦截）
        bl = db.query_one("SELECT COUNT(*) AS c FROM sources WHERE status='blacklisted'")['c']
        self.assertGreater(bl, 0)

        result = OutputBuilder(self.cfg, db).build()
        self.assertGreater(result['channels'], 0)
        live = os.path.join(self.cfg.output_dir, 'live.txt')
        self.assertTrue(os.path.isfile(live))
        with open(live, encoding='utf-8') as f:
            content = f.read()
        self.assertIn(',#genre#', content)
        self.assertTrue(content.endswith('\n'))
        db.close()

    def test_seed_import_idempotent(self):
        """第二轮发现轮：seed_imported 已置位 -> 不重复导入（新增 0）。"""
        db = Database(self.cfg.db_path)
        db.migrate()
        disc = SourceDiscovery(self.cfg, db, DeadNet())
        disc.run()
        first = db.query_one('SELECT COUNT(*) AS c FROM sources')['c']
        stats = disc.run()   # 第二轮
        self.assertEqual(stats['new'], 0)
        self.assertEqual(db.query_one('SELECT COUNT(*) AS c FROM sources')['c'], first)
        db.close()

    def test_seed_if_empty_skips_nonempty(self):
        """非空库不再触发种子导入。"""
        db = Database(self.cfg.db_path)
        db.migrate()
        now = db.query_one('SELECT COUNT(*) AS c FROM sources')  # noqa: F841
        from app.db import utcnow
        db.execute(
            "INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status,"
            " score, first_seen, last_seen) VALUES ('http://x','h','n','','qa','candidate',"
            "10,?,?)", (utcnow(), utcnow()))
        before = db.query_one('SELECT COUNT(*) AS c FROM sources')['c']
        app_main.seed_if_empty(self.cfg, db, DeadNet())
        after = db.query_one('SELECT COUNT(*) AS c FROM sources')['c']
        self.assertEqual(before, after)     # 只有预置的 1 条，种子未导入
        db.close()


if __name__ == '__main__':
    unittest.main()
