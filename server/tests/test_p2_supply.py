# -*- coding: utf-8 -*-
"""P2 供给扩容回归测试（2026-09-08 迭代：#26 扩别名 + 仓库扩充 + RepoMiner 挖矿）。

覆盖：
1. 新增频道别名归一（含回归：CCTV 前缀正则、翡翠台包含匹配不被破坏）；
2. tools/renormalize_channels.py 存量源重归一化（认领/跳过/幂等/dry-run）；
3. RepoProvider 通道契约（collect 返回 [(origin, entries)]，单仓失败不中断）；
4. RepoMinerProvider 挖矿（README 提取/自仓过滤/已知仓库过滤/校验入库/限量）。

全部离线可跑：python tests/run_offline.py
"""
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import write_test_config, make_config  # noqa: E402
from app.config import Config            # noqa: E402
from app.db import Database              # noqa: E402
from app.discovery import RepoMinerProvider, RepoProvider, SourceDiscovery  # noqa: E402
from app.parser import ChannelParser     # noqa: E402
from tools.renormalize_channels import renormalize  # noqa: E402


def make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-p2-supply-')
    write_test_config(tmp)
    return tmp


class TestAliasExpansion(unittest.TestCase):
    """#26 扩别名：新别名生效，既有归一语义不回归。"""

    @classmethod
    def setUpClass(cls):
        cls.parser = ChannelParser(make_config())

    def test_new_aliases(self):
        cases = {
            '旅游卫视': '海南卫视',
            'TVBS欢乐台': 'TVBS',
            'TVBS新闻台': 'TVBS',
            'TVB新闻': '无线新闻',
            'cctv5+': 'CCTV5+',
            'cctv13': 'CCTV13',
            'TBVJ2': 'J2',
            'TVBJ2': 'J2',
            '广东体育频道': '广东体育',
            '珠江台': '广东珠江',
        }
        for name, expect in cases.items():
            self.assertEqual(self.parser.normalize_channel(name), expect, name)

    def test_existing_normalization_not_broken(self):
        cases = {
            'CCTV-5': 'CCTV5',
            'CCTV5体育': 'CCTV5',
            'CCTV5+': 'CCTV5+',
            '翡翠台HD': 'TVB翡翠台',
            '湖南卫视高清': '湖南卫视',
            '凤凰卫视资讯台': '凤凰资讯台',
        }
        for name, expect in cases.items():
            self.assertEqual(self.parser.normalize_channel(name), expect, name)

    def test_longtail_still_unmatched(self):
        """白名单外长尾频道不得被误认领（守卫过度匹配）。"""
        for name in ('CHC家庭影院', '猫和老鼠', '山西卫视', '五星体育'):
            self.assertEqual(self.parser.normalize_channel(name), '', name)


class TestRenormalize(unittest.TestCase):
    """存量源重归一化：认领 / 跳过 / 幂等 / dry-run 不写库。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(os.path.join(self.root, 't.db'))
        self.db.migrate()
        self.parser = ChannelParser(self.cfg)

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def _insert(self, name, url):
        self.db.execute(
            'INSERT INTO sources(url, url_hash, channel_name, channel_key, origin,'
            ' status, av, score, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)',
            (url, self.parser.url_hash(url), name, '', 'test', 'candidate',
             None, 10.0, '2026-09-08T00:00:00Z', '2026-09-08T00:00:00Z'))

    def test_claim_and_skip(self):
        self._insert('旅游卫视', 'http://a.example/1.m3u8')
        self._insert('TVBS欢乐台', 'http://a.example/2.m3u8')
        self._insert('猫和老鼠', 'http://a.example/3.m3u8')
        scanned, n, _ = renormalize(self.db, self.parser)
        self.assertEqual(scanned, 3)
        self.assertEqual(n, 2)
        rows = {r['channel_name']: r['channel_key'] for r in self.db.query(
            'SELECT channel_name, channel_key FROM sources')}
        self.assertEqual(rows['旅游卫视'], '海南卫视')
        self.assertEqual(rows['TVBS欢乐台'], 'TVBS')
        self.assertEqual(rows['猫和老鼠'], '')

    def test_idempotent_and_dry_run(self):
        self._insert('旅游卫视', 'http://a.example/1.m3u8')
        renormalize(self.db, self.parser)
        _scanned, n2, _ = renormalize(self.db, self.parser)
        self.assertEqual(n2, 0)          # 第二次无可认领（幂等）
        self._insert('TVBS欢乐台', 'http://a.example/2.m3u8')
        _s, n3, _ = renormalize(self.db, self.parser, dry_run=True)
        self.assertEqual(n3, 1)          # dry-run 仍可统计
        row = self.db.query_one(
            "SELECT channel_key FROM sources WHERE channel_name='TVBS欢乐台'")
        self.assertEqual(row['channel_key'], '')  # dry-run 未写库


class FakeMinerNet(object):
    """离线假网络：按 URL 返回预置字节或抛异常。"""

    def __init__(self, bodies):
        self.bodies = bodies

    def jsdelivr_url(self, repo, branch, path):
        return 'https://cdn.jsdelivr.net/gh/%s@%s/%s' % (repo, branch, path)

    def ghproxy_url(self, repo, branch, path):
        return 'https://gh-proxy.com/https://raw.githubusercontent.com/%s/%s/%s' % (
            repo, branch, path)

    def fetch(self, url, ua=None, limit=None, timeout=None):
        v = self.bodies[url]
        if isinstance(v, Exception):
            raise v
        return (200, v, 'text/plain')   # 与 NetClient.fetch 三元组契约一致

    def fetch_retry(self, url, **_kw):
        return self.fetch(url)[1]   # 与 NetClient.fetch_retry 契约一致：返回 body bytes


README = """
# Hub 仓库
- 直链A: https://raw.githubusercontent.com/newa/repo/main/live.m3u
- 直链B: https://cdn.jsdelivr.net/gh/newb/repo@master/tv/iptv.m3u
- 自引用: https://raw.githubusercontent.com/hub/repo/main/self.m3u
- 已知仓: https://raw.githubusercontent.com/hub/repo/main/live.m3u
- 直链A重复: https://raw.githubusercontent.com/newa/repo/main/live.m3u
"""

HOME_README = """
# Hub2 仓库
- 主页推荐: [YueChan](https://github.com/newc/repo)
- blob 形态: https://github.com/newd/repo/blob/main/live.m3u
- 镜像直链: https://testingcf.jsdelivr.net/gh/newe/repo@main/itvlist.m3u
- 自身主页: https://github.com/hub/repo
- 尾随斜杠(不认): https://github.com/newf/repo/
- 主页重复: https://github.com/newc/repo
"""

HOME_LIST_JSON = ('{"type":"gh","name":"newc/repo","files":['
                  '{"type":"file","name":"IPTV.m3u","size":100},'
                  '{"type":"file","name":"README.md","size":10},'
                  '{"type":"file","name":"GNTV.m3u","size":50},'
                  '{"type":"file","name":"Global.m3u","size":50},'
                  '{"type":"file","name":"Radio.m3u","size":50}'
                  ']}').encode('utf-8')

M3U_OK = ('#EXTM3U\n#EXTINF:-1,CCTV1\nhttp://a.example/1.m3u8\n'
          '#EXTINF:-1,CCTV2\nhttp://a.example/2.m3u8\n'
          '#EXTINF:-1,CCTV3\nhttp://a.example/3.m3u8\n')


class TestRepoProviderContract(unittest.TestCase):
    """通道契约：collect 返回 [(origin, entries)]，单仓失败不中断。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(os.path.join(self.root, 't.db'))
        self.db.migrate()
        self.net = FakeMinerNet({
            'https://cdn.jsdelivr.net/gh/ok/repo@main/live.m3u':
                M3U_OK.encode('utf-8'),
        })
        self.db.execute(
            "INSERT OR IGNORE INTO repos(repo, branch, path, channel, enabled, note)"
            " VALUES ('ok/repo','main','live.m3u','jsdelivr',1,'')")
        self.db.execute(
            "INSERT OR IGNORE INTO repos(repo, branch, path, channel, enabled, note)"
            " VALUES ('dead/repo','main','live.m3u','jsdelivr',1,'')")

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def test_collect_groups_and_error_isolation(self):
        prov = RepoProvider(self.cfg, self.db, self.net)
        groups = prov.collect()
        self.assertEqual(len(groups), 1)
        origin, entries = groups[0]
        self.assertEqual(origin, 'jsdelivr:ok/repo@main')
        self.assertEqual(len(entries), 3)
        dead = self.db.query_one("SELECT * FROM repos WHERE repo='dead/repo'")
        self.assertIsNotNone(dead['last_fetch'])
        self.assertIsNone(dead['last_ok'])
        ok = self.db.query_one("SELECT * FROM repos WHERE repo='ok/repo'")
        self.assertIsNotNone(ok['last_ok'])


class TestRepoMiner(unittest.TestCase):
    """挖矿：提取/过滤/校验/限量入库。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        # 2026-09-26 起真实 settings.json 已关挖矿（miner_enabled=false）；
        # 本类测试挖矿本体，显式打开，不随生产配置漂移。
        self.cfg.settings['discovery']['miner_enabled'] = True
        self.db = Database(os.path.join(self.root, 't.db'))
        self.db.migrate()
        self.net = FakeMinerNet({
            'https://cdn.jsdelivr.net/gh/hub/repo@main/README.md':
                README.encode('utf-8'),
            'https://cdn.jsdelivr.net/gh/newa/repo@main/live.m3u':
                M3U_OK.encode('utf-8'),
            'https://cdn.jsdelivr.net/gh/newb/repo@master/tv/iptv.m3u':
                RuntimeError('404'),
        })
        self.db.execute(
            "INSERT OR IGNORE INTO repos(repo, branch, path, channel, enabled, note)"
            " VALUES ('hub/repo','main','live.m3u','jsdelivr',1,'')")

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def test_extract_candidates_filters_self_and_known(self):
        miner = RepoMinerProvider(self.cfg, self.db, self.net)
        known = {('hub/repo', 'main', 'live.m3u')}
        cands = miner.extract_candidates(README, 'hub/repo', 'main', known)
        self.assertEqual(cands, [('newa/repo', 'main', 'live.m3u'),
                                 ('newb/repo', 'master', 'tv/iptv.m3u')])

    def test_collect_validates_and_limits(self):
        miner = RepoMinerProvider(self.cfg, self.db, self.net)
        added = miner.collect()
        self.assertEqual(added, 1)   # newa 校验通过; newb 404 被拒
        rows = self.db.query("SELECT * FROM repos WHERE repo LIKE 'new%'")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['repo'], 'newa/repo')
        self.assertEqual(rows[0]['enabled'], 1)
        self.assertIn('miner:', rows[0]['note'])
        # 幂等：再挖一轮，README 候选已在 known 中，不再新增
        self.assertEqual(miner.collect(), 0)

    def test_disabled_miner_is_noop(self):
        self.cfg.settings['discovery']['miner_enabled'] = False
        miner = RepoMinerProvider(self.cfg, self.db, self.net)
        self.assertEqual(miner.collect(), 0)


class TestRepoMinerHomeAndMirror(unittest.TestCase):
    """挖矿 2026-09-08 扩展：主页形态 + 镜像域名直链 + 主页展开入库。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        # 同上：生产已关挖矿，测试显式打开。
        self.cfg.settings['discovery']['miner_enabled'] = True
        self.db = Database(os.path.join(self.root, 't.db'))
        self.db.migrate()
        self.net = FakeMinerNet({
            'https://cdn.jsdelivr.net/gh/hub/repo@main/README.md':
                HOME_README.encode('utf-8'),
            'https://cdn.jsdelivr.net/gh/newd/repo@main/live.m3u':
                M3U_OK.encode('utf-8'),
            'https://cdn.jsdelivr.net/gh/newe/repo@main/itvlist.m3u':
                M3U_OK.encode('utf-8'),
            'https://data.jsdelivr.com/v1/packages/gh/newc/repo@main':
                HOME_LIST_JSON,
            'https://cdn.jsdelivr.net/gh/newc/repo@main/IPTV.m3u':
                M3U_OK.encode('utf-8'),
        })
        self.db.execute(
            "INSERT OR IGNORE INTO repos(repo, branch, path, channel, enabled, note)"
            " VALUES ('hub/repo','main','live.m3u','jsdelivr',1,'')")

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def test_extract_home_and_mirror_candidates(self):
        miner = RepoMinerProvider(self.cfg, self.db, self.net)
        known = {('hub/repo', 'main', 'live.m3u')}
        cands = miner.extract_candidates(HOME_README, 'hub/repo', 'main', known)
        # 按 _MINER_PATTERNS 顺序(jsDelivr 先于 blob) + 主页候选殿后;
        # 尾随斜杠/自身主页/重复主页均排除
        self.assertEqual(cands, [('newe/repo', 'main', 'itvlist.m3u'),
                                 ('newd/repo', 'main', 'live.m3u'),
                                 ('newc/repo', '', '')])

    def test_collect_home_expansion_ingests_repo(self):
        miner = RepoMinerProvider(self.cfg, self.db, self.net)
        added = miner.collect()
        # 主页展开默认每仓每轮只收 1 个文件(根目录首个 m3u: IPTV.m3u);
        # blob/镜像两个直链候选也各自入库
        self.assertEqual(added, 3)
        rows = {r['repo']: (r['branch'], r['path'], r['enabled'])
                for r in self.db.query("SELECT * FROM repos WHERE repo LIKE 'new%'")}
        self.assertEqual(rows['newc/repo'], ('main', 'IPTV.m3u', 1))
        self.assertEqual(rows['newd/repo'], ('main', 'live.m3u', 1))
        self.assertEqual(rows['newe/repo'], ('main', 'itvlist.m3u', 1))
        self.assertIn('home展开', self.db.query_one(
            "SELECT note FROM repos WHERE repo='newc/repo'")['note'])
        # 幂等：同 repo 已在表, 再挖不重复入库
        self.assertEqual(miner.collect(), 0)

    def test_expand_home_takes_first_m3u_capped_at_3(self):
        miner = RepoMinerProvider(self.cfg, self.db, self.net)
        out = miner._expand_home('newc/repo')
        self.assertEqual(out, [('main', 'IPTV.m3u'), ('main', 'GNTV.m3u'),
                               ('main', 'Global.m3u')])   # 根目录清单文件, 上限 3, README 排除

    def test_disabled_repo_readme_still_mined(self):
        """禁用仓库(last_ok 非空)的 README 仍可作挖矿入口。"""
        self.db.execute(
            "UPDATE repos SET enabled=0, last_ok='2026-09-08T00:00:00' "
            "WHERE repo='hub/repo'")
        miner = RepoMinerProvider(self.cfg, self.db, self.net)
        added = miner.collect()
        self.assertGreaterEqual(added, 1)   # 禁用仓库的 README 候选仍被提取入库


class TestDiscoveryRunOffline(unittest.TestCase):
    """run() 聚合：挖矿异常不杀整轮，仓库通道正常入库。"""

    def setUp(self):
        self.root = make_root()
        self.cfg = Config(self.root)
        self.db = Database(os.path.join(self.root, 't.db'))
        self.db.migrate()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.root, ignore_errors=True)

    def test_run_survives_miner_crash(self):
        class BoomNet(FakeMinerNet):
            def fetch(self, url, **kw):
                raise RuntimeError('boom')
        self.db.execute(
            "INSERT OR IGNORE INTO repos(repo, branch, path, channel, enabled, note)"
            " VALUES ('hub/repo','main','live.m3u','jsdelivr',1,'')")
        disc = SourceDiscovery(self.cfg, self.db, BoomNet({}))
        stats = disc.run()   # 不应抛异常
        self.assertIn('discovered', stats)


if __name__ == '__main__':
    unittest.main()
