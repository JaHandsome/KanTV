# -*- coding: utf-8 -*-
"""SourceDiscovery：源发现（P0-1 + OPTIMIZATION #17/#26）。

通道（Provider 抽象，ARCHITECTURE §6.1 + 优化报告 #17 落地）：
- SeedProvider：data/seed/ 本地种子（实测账本 + 网关图 + 预验证线路），冷启动兜底，仅导入一次；
- RepoProvider：repos 表（首跑从 config/repos.json 导入）经 jsDelivr 拉取，失败切 ghproxy；
- RepoMinerProvider：二级挖矿（优化报告"递归挖矿"供给项）——从已启用仓库的 README
  提取新仓库播放清单直链，逐一实拉校验后限量入库 repos 表，下轮起自动参与发现；
- UrlListProvider：config/url_lists.json 人工指定的任意 URL 清单（HK MPD 路线，
  enabled 默认 false）——拉 .m3u 并解析 #KODIPROP，产出带 DRM 字段的 entries。

入库规则：
- url_key 去重（剥离时效签名后的物理源身份），签名轮换只刷新 url（不新增行）；
  url_hash 仍按字面计算入库（兼容保留，UNIQUE 索引防字面重复）；
- rules.json 死源规则在入库阶段拦截：黑名单 -> status=blacklisted（入库但不进验证队列）；
- flaky 域名 -> 降权（score 压到 5 以下，验证排序靠后）；
- 未匹配目标频道的源也入库（channel_key=''），验证只消费目标频道。
"""
import json
import os
import re
import time

from app.db import COLLISION_ORPHAN_STATUS, delivery_profile, utcnow
from app.logutil import get_logger
from app.parser import ChannelParser

log = get_logger('discovery')

# ---------- HK MPD 路线：KODIPROP -> sources 列 映射常量 ----------
# 协议常量集中命名，避免内联魔法值（落库映射表见 HK_MPD_ROUTE_DESIGN §3.4）。
CLEARKEY_KEYFORMAT = 'urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e'  # ClearKey 系统 UUID
DASH_CLEARKEY_CAPS = {'dash': True, 'dash_clearkey': True}             # caps_required 常量
KP_MANIFEST_MPD = 'mpd'           # manifest_type 取值
KP_LICENSE_CLEARKEY = 'clearkey'  # license_type 取值
ENC_CENC = 'cenc'                 # mp4protection 的 value

# 投递画像（profile）派生：唯一判据在 db.delivery_profile（明文/AES-128 且无 DRM -> 'direct'，
# 否则 'media3-json'）。本模块只保留 explicit override 语义并委托该叶子函数——安全关键字段
# 绝不依赖 DDL 的列默认值（sources.profile DEFAULT 'direct'），否则任何"绕过 record() 直接
# 置 status='active'"的入库路径都会把 DRM 源标成 'direct' 并渲染进 live.txt/live.m3u。


def derive_profile(encryption, drm, explicit=None):
    """按源侧加密/DRM 事实派生投递画像（纯函数，explicit 语义的调用入口）。

    - ``explicit`` 非 None -> 尊重显式值（允许调用方覆盖）；
    - 否则委托唯一叶子函数 ``db.delivery_profile``（fail-closed：``encryption`` 非明文/
      非 AES-128（``cenc``/``cbcs``/``sample-aes``）或 ``drm`` 非空 -> ``'media3-json'``）。

    ★ 为什么判据不能只看 ``drm``：``sample-aes`` 的 ``encryption='sample-aes'`` 而 ``drm=''``
    （见 ``classify_encryption``），它同理需要密钥机制，只看 drm 会把它漏成 ``'direct'``（fail-open）。
    """
    if explicit is not None:
        return explicit
    return delivery_profile(encryption, drm)


# 进程内刷新记账（§7.1）：UrlListProvider 每轮新建实例，门控置于模块级以跨轮生效。
_URL_LIST_LAST_FETCH = {}

# RepoMiner：README 中可提取为"仓库播放清单"的直链形态
#   raw.githubusercontent.com/{user}/{repo}/{branch}/{path}.(m3u|m3u8|txt)
#   {cdn,testingcf,fastly,gcore,...}.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}.(m3u|m3u8|txt)
#   github.com/{user}/{repo}/blob/{branch}/{path}.(m3u|m3u8|txt)
# 注：jsDelivr 域名放宽为任意子域（testingcf/fastly/gcore 等镜像在社区 README 常见，
#     2026-09-08 漏检复盘：dongyubin/IPTV 引用 YueChan/Live 用的正是 testingcf 镜像形态）
_MINER_PATTERNS = (
    re.compile(r'raw\.githubusercontent\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)'
               r'/(?:refs/heads/)?([^/\s]+?)/([\w./-]+\.(?:m3u|m3u8|txt))'),
    re.compile(r'(?:[A-Za-z0-9-]+\.)*jsdelivr\.net/gh/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)'
               r'@([^/\s]+?)/([\w./-]+\.(?:m3u|m3u8|txt))'),
    re.compile(r'github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/blob/'
               r'([^/\s]+?)/([\w./-]+\.(?:m3u|m3u8|txt))'),
)

# 仓库主页形态（github.com/{user}/{repo}）：README"推荐/致谢"清单最常见形态。
# 2026-09-08 漏检复盘：Meroser/IPTV、0610840119/iptv-api 等引用 YueChan/Live 均为此形态，
# 直链正则无法提取。主页候选提取为 (repo, '', '')，由 _expand_home 展开为具体文件再校验。
# 前瞻排除 /blob/、/tree/ 等带路径后缀形态（已由上面直链正则覆盖）。
_MINER_HOME_PATTERN = re.compile(
    r'github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?=[\s)\]`"\'<>?,;!]|$)')


def parse_playlist(parser, text, path):
    """按内容/扩展名选择解析器（RepoProvider 与 RepoMinerProvider 共用）。"""
    if text.lstrip().startswith('#EXTM3U') or (path or '').lower().endswith(('.m3u', '.m3u8')):
        return parser.parse_m3u(text)
    return parser.parse_txt(text)


class SeedProvider(object):
    """本地种子通道：读取 data/seed/ 下的实测账本/网关图/预验证线路。"""

    def __init__(self, cfg, parser):
        self.cfg = cfg
        self.parser = parser

    def _load_json(self, filename):
        path = os.path.join(self.cfg.seed_dir, filename)
        if not os.path.isfile(path):
            return None
        try:
            with open(path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except (ValueError, OSError, UnicodeDecodeError) as e:
            log.error('种子文件解析失败: %s (%s)', path, e)
            return None

    def entries(self):
        """返回 [dict(url, name, channel_key, origin, status, av, score)]。"""
        out = []

        def add(url, name, key, origin, status, av):
            host = self.parser.host(url)
            out.append({
                'url': url, 'name': name, 'channel_key': key, 'origin': origin,
                'status': status, 'av': av,
                'score': self.parser.host_score(host),
            })

        # 1) 实测账本：ok 源直接 active（av 优先取自频道源统计.json 的媒体级验证结论）
        res = self._load_json('全国频道实测结果.json') or {}
        stat = self._load_json('频道源统计.json') or {}
        av_map, dead_map = {}, set()
        for _n, r in stat.items():
            for d in r.get('detail') or []:
                if d.get('url'):
                    av_map[d['url']] = d.get('av')
            for d in r.get('dead_reverified') or []:
                if d.get('url'):
                    dead_map.add(d['url'])
        for name, s in res.items():
            key = self.parser.normalize_channel(name)
            for item in s.get('all') or []:
                if not item:
                    continue
                url, st = item[0], (item[1] if len(item) > 1 else '')
                why = item[2] if len(item) > 2 else ''
                if not url:
                    continue
                if url in av_map:
                    av = av_map[url] or ''
                    status = 'active' if av.startswith('video') else 'candidate'
                    add(url, name, key, 'seed:shice', status, av or None)
                elif url in dead_map:
                    add(url, name, key, 'seed:shice', 'candidate', None)
                elif st == 'ok':
                    add(url, name, key, 'seed:shice', 'active', 'video')
                else:
                    add(url, name, key, 'seed:shice', 'candidate', None)

        # 2) 预验证 8 条（港澳台，此前未入实测账本）
        pre = self._load_json('preverified.json') or {}
        for name, url in pre.items():
            add(url, name, self.parser.normalize_channel(name),
                'seed:preverified', 'active', 'video')

        # 3) 网关图：396 频道 659 条网关线路（多数会被黑名单规则拦截）
        gw = self._load_json('gateway_map.json') or {}
        for name, urls in gw.items():
            key = self.parser.normalize_channel(name)
            for url in urls or []:
                if url:
                    add(url, name, key, 'gateway_map', 'candidate', None)
        return out


class RepoProvider(object):
    """仓库清单通道：repos 表逐仓拉取解析（jsDelivr 优先，ghproxy 备用）。

    collect() 返回 [(origin, entries), ...]；单仓失败只记日志不中断整轮。
    """

    def __init__(self, cfg, db, net, parser=None):
        self.cfg = cfg
        self.db = db
        self.net = net
        self.parser = parser or ChannelParser(cfg)

    def _fetch_repo(self, row):
        """拉取单个仓库文件：jsDelivr 优先，失败切 ghproxy；返回 (text, ok) 或 (None, False)。"""
        limit = int(self.cfg.settings['network']['repo_fetch_limit'])
        urls = [self.net.jsdelivr_url(row['repo'], row['branch'], row['path'])]
        if row['channel'] == 'jsdelivr':
            urls.append(self.net.ghproxy_url(row['repo'], row['branch'], row['path']))
        for url in urls:
            try:
                body = self.net.fetch_retry(url, limit=limit)
                return body.decode('utf-8', 'ignore'), url
            except Exception as e:  # noqa: BLE001 - 单仓失败只记日志不中断
                log.error('仓库拉取失败 %s (%s): %s', row['repo'], url[:80], e)
        return None, None

    def collect(self):
        out = []
        repos = self.db.query('SELECT * FROM repos WHERE enabled=1')
        for row in repos:
            try:
                text, used_url = self._fetch_repo(row)
                now = utcnow()
                if text is None:
                    self.db.execute('UPDATE repos SET last_fetch=? WHERE id=?', (now, row['id']))
                    continue
                entries = parse_playlist(self.parser, text, row['path'])
                out.append(('%s:%s@%s' % (row['channel'], row['repo'], row['branch']), entries))
                self.db.execute('UPDATE repos SET last_fetch=?, last_ok=? WHERE id=?',
                                (now, now, row['id']))
                log.info('仓库 %s@%s/%s: 解析 %d 条',
                         row['repo'], row['branch'], row['path'], len(entries))
            except Exception as e:  # noqa: BLE001 - 单仓异常不杀整轮
                log.error('仓库 %s 处理异常: %s', row['repo'], e)
        return out


class RepoMinerProvider(object):
    """二级挖矿通道（OPTIMIZATION #17 前置 + "递归挖矿"供给项落地）。

    从仓库（启用仓优先，禁用仓 README 也可作入口，上限 miner_readme_per_round）
    拉取 README.md，提取新仓库播放清单候选——直链三种形态（raw / 任意子域 jsDelivr /
    github blob）+ 仓库主页形态（github.com/{user}/{repo}，经 _expand_home 展开），
    逐条实拉 + 解析校验（>=3 条目才算有效）后，限量（miner_max_repos_per_round）
    INSERT OR IGNORE 入 repos 表——下轮发现起自动参与拉取；
    已存在仓库由 UNIQUE 约束天然去重。

    collect() 返回本轮新增仓库数（不产出源条目，repos 写入由本类完成）。
    """

    def __init__(self, cfg, db, net, parser=None):
        self.cfg = cfg
        self.db = db
        self.net = net
        self.parser = parser or ChannelParser(cfg)

    # ---------- 配置 ----------
    def _disc_cfg(self):
        d = self.cfg.settings.get('discovery')
        return d if isinstance(d, dict) else {}

    # ---------- README 拉取 ----------
    def _fetch_readme(self, repo, branch):
        try:
            url = self.net.jsdelivr_url(repo, branch, 'README.md')
            _st, body, _ct = self.net.fetch(url, limit=524288)
            return body.decode('utf-8', 'ignore')
        except Exception as e:  # noqa: BLE001 - 单个 README 失败不中断
            log.info('挖矿: README 拉取跳过 %s (%s)', repo, str(e)[:60])
            return None

    # ---------- 候选提取 ----------
    def extract_candidates(self, text, self_repo, self_branch, known):
        """从 README 文本提取 (repo, branch, path) 候选，过滤自身与已知仓库。

        直链候选：(repo, branch, path)，branch/path 齐全；
        主页候选：(repo, '', '')，仅含仓库（github.com/{user}/{repo}），
        由 _expand_home 在校验阶段展开。同仓库直链候选优先于主页候选。
        """
        out, seen = [], set()
        if not text:
            return out
        for pat in _MINER_PATTERNS:
            for m in pat.finditer(text):
                repo, branch, path = ('%s/%s' % (m.group(1), m.group(2)),
                                      m.group(3), m.group(4).lstrip('/'))
                if repo == self_repo and branch == self_branch:
                    continue
                if (repo, branch, path) in known or (repo, branch, path) in seen:
                    continue
                seen.add((repo, branch, path))
                out.append((repo, branch, path))
        repos_with_direct = {r for r, _b, _p in seen}
        for m in _MINER_HOME_PATTERN.finditer(text):
            repo = '%s/%s' % (m.group(1), m.group(2))
            if repo == self_repo or repo in repos_with_direct or repo in seen:
                continue
            seen.add(repo)
            out.append((repo, '', ''))
        return out

    # ---------- 主页候选展开 ----------
    def _expand_home(self, repo):
        """主页候选展开：data API 探测 main/master 分支，取根目录清单文件（上限 3）。

        GitHub 分支不出现在 jsDelivr 版本列表（只列 tags），故用双探测法；
        命中一个分支即止，防双分支重复校验。返回 [(branch, path), ...]。
        """
        out = []
        for branch in ('main', 'master'):
            try:
                url = 'https://data.jsdelivr.com/v1/packages/gh/%s@%s' % (repo, branch)
                _st, body, _ct = self.net.fetch(url, limit=262144)
                data = json.loads(body.decode('utf-8', 'ignore'))
                names = [f.get('name', '') for f in data.get('files') or []
                         if f.get('type') == 'file']
                if not names:
                    continue
                cands = [n for n in names
                         if n.lower().endswith(('.m3u', '.m3u8', '.txt'))][:3]
                if cands:
                    out.extend((branch, n) for n in cands)
                    break
            except Exception as e:  # noqa: BLE001 - 分支探测失败尝试下一分支
                log.info('挖矿: 主页展开失败 %s@%s (%s)', repo, branch, str(e)[:60])
        return out

    # ---------- 校验 ----------
    def _validate(self, repo, branch, path):
        """实拉 + 解析校验：>=3 条目才认有效；返回 (ok, entry_count)。"""
        try:
            url = self.net.jsdelivr_url(repo, branch, path)
            _st, body, _ct = self.net.fetch(
                url, limit=int(self.cfg.settings['network']['repo_fetch_limit']))
            text = body.decode('utf-8', 'ignore')
            entries = parse_playlist(self.parser, text, path)
            return len(entries) >= 3, len(entries)
        except Exception as e:  # noqa: BLE001 - 校验失败即丢弃该候选
            log.info('挖矿: 候选校验失败 %s@%s/%s (%s)', repo, branch, path, str(e)[:60])
            return False, 0

    # ---------- 主流程 ----------
    def collect(self):
        d = self._disc_cfg()
        if not d.get('miner_enabled', False):
            return 0
        readme_n = max(1, int(d.get('miner_readme_per_round', 6)))
        validate_n = max(1, int(d.get('miner_validate_per_round', 5)))
        max_add = max(0, int(d.get('miner_max_repos_per_round', 3)))
        if max_add == 0:
            return 0

        rows = self.db.query(
            'SELECT repo, branch FROM repos WHERE enabled=1 OR last_ok IS NOT NULL '
            'ORDER BY enabled DESC, last_ok DESC LIMIT ?', (readme_n,))
        # 禁用仓库 README 仍可作为挖矿入口（仅读 README，不参与清单拉取）——
        # 2026-09-08 漏检复盘：清单内唯一引用 YueChan/Live 的 0610840119/iptv-api 当时被禁用
        known = {(r['repo'], r['branch'], r['path'])
                 for r in self.db.query('SELECT repo, branch, path FROM repos')}
        existing_repos = {r['repo'] for r in self.db.query('SELECT repo FROM repos')}
        cands = []
        for row in rows:
            text = self._fetch_readme(row['repo'], row['branch'])
            cands.extend(self.extract_candidates(text, row['repo'], row['branch'], known))
        if not cands:
            return 0
        added = 0
        for repo, branch, path in cands[:validate_n]:
            if added >= max_add:
                break
            if not branch:   # 主页候选：已在表则跳过；否则展开为文件候选再校验
                if repo in existing_repos:
                    continue
                file_cands = self._expand_home(repo)
            else:
                file_cands = [(branch, path)]
            for br, p in file_cands:
                if (repo, br, p) in known:
                    continue
                ok, n = self._validate(repo, br, p)
                if not ok:
                    continue
                self.db.execute(
                    'INSERT OR IGNORE INTO repos(repo, branch, path, channel, enabled, note) '
                    'VALUES (?,?,?,?,1,?)',
                    (repo, br, p, 'jsdelivr',
                     'miner: %s 校验通过(%d 条)%s'
                     % (utcnow()[:10], n, ' home展开' if not branch else '')))
                added += 1
                log.info('挖矿: 新仓库入库 %s@%s/%s (%d 条)', repo, br, p, n)
                break   # 每仓每轮只收 1 个文件, 与直链候选语义一致
        if cands[validate_n:]:
            log.info('挖矿: 本轮剩余 %d 条候选未校验(下轮优先)', len(cands) - validate_n)
        return added


class UrlListProvider(object):
    """任意 URL 清单通道（config/url_lists.json 驱动，人工逐条指定）。

    与 RepoProvider **同构**：实现同一契约 ``collect() -> [(origin, entries), ...]``，
    产出 entries（dict，携带 KODIPROP 派生的 DRM 字段）交同一个 ``ingest()``；
    **不新增任何库表**（HK_MPD_ROUTE_DESIGN §3.2）。

    ★ 为何不构成"扩挖矿白名单"（红线论证，§3.3）：
      红线管辖对象 = ``_MINER_PATTERNS`` / ``_expand_home``，即"从 README **自动提取**的
      候选文件"的扩展名集合；其动机是拦住**自动、开放、不可控**的发现通道涌入未知 MPD。
      本类与之正交：
        1) **零自动发现**——不读 README、不做任何正则提取；清单 URL 由 url_lists.json
           人工逐条指定，有限、可审计、带 enabled 开关（等价于"把一条手工 repos 记录
           换成手工的任意 URL"）；
        2) **拉取对象本身是 .m3u**（早已在挖矿白名单内，RepoProvider 一直在拉）；
        3) **.mpd 只是清单内的"条目 URL"**——条目 URL 历来不受白名单管辖（现状就含
           ``rtmp://`` / ``p2p://`` 条目，靠 rules.json 拦截）。
      ⇒ 本类不触碰 ``_MINER_PATTERNS`` / ``_expand_home`` 一行代码，不新增任何被自动
        发现的文件扩展名。

    时效（§7.1）：``enabled=false`` -> collect() **零网络调用**（"探针就绪前不入库"的硬
    保证）；``refresh_minutes`` 由进程内记账门控；清单拉取失败**不删除已有源**。
    """

    def __init__(self, cfg, net, parser=None):
        self.cfg = cfg
        self.net = net
        self.parser = parser or ChannelParser(cfg)

    def _lists(self):
        """取 url_lists 配置中的 list 项（缺失/非法一律按空处理）。"""
        data = getattr(self.cfg, 'url_lists', None)
        if not isinstance(data, dict):
            return []
        return [x for x in (data.get('lists') or []) if isinstance(x, dict)]

    def _fetch_limit(self):
        net = self.cfg.settings.get('network') if isinstance(self.cfg.settings, dict) else None
        return int((net or {}).get('repo_fetch_limit', 2097152))

    def _fetch_list(self, item):
        """拉取单条清单文本；失败返回 None（不中断整轮，不删除已有源）。"""
        url = item.get('url')
        if not url:
            return None
        try:
            body = self.net.fetch_retry(url, ua=item.get('user_agent'),
                                        limit=self._fetch_limit())
            return body.decode('utf-8', 'ignore')
        except Exception as e:  # noqa: BLE001 - 单清单失败只记日志
            log.error('URL 清单拉取失败 %s (%s): %s', item.get('id'), str(url)[:80], e)
            return None

    def _entries(self, item, text):
        """清单文本 -> [entry dict]（list 条目 URL + KODIPROP 派生的 DRM 字段）。"""
        origin = 'urllist:%s' % (item.get('id') or '')
        props = ChannelParser.parse_kodiprop(text)
        out = []
        for e in ChannelParser.parse_m3u(text):
            url = (e.url or '').strip()
            if not url:
                continue
            rec = {
                'url': url,
                'name': e.name,
                'channel_key': self.parser.normalize_channel(e.name),
                'origin': origin,
                'status': 'candidate',
                'av': None,
                'score': self.parser.host_score(self.parser.host(url)),
            }
            kp = props.get(url) or {}
            if (kp.get('manifest_type') or '').strip().lower() == KP_MANIFEST_MPD:
                rec['container'] = 'dash'
            obj = kp.get('license_key_obj')
            if (kp.get('license_type') or '').strip().lower() == KP_LICENSE_CLEARKEY \
                    and isinstance(obj, dict):
                rec['drm'] = KP_LICENSE_CLEARKEY
                rec['encryption'] = ENC_CENC
                rec['keyformat'] = CLEARKEY_KEYFORMAT
                keys = obj.get('keys') or []
                if keys and isinstance(keys[0], dict):
                    kid = ChannelParser.kid_hex(keys[0].get('kid'))
                    if kid:
                        rec['kid'] = kid
                if kp.get('license_key'):
                    rec['drm_key'] = kp['license_key']   # 原始 JSON 原样（base64url 保留）
                rec['caps_required'] = json.dumps(DASH_CLEARKEY_CAPS)
            out.append(rec)
        return out

    def collect(self):
        out = []
        for item in self._lists():
            if not item.get('enabled', False):
                continue        # ★ enabled=false -> 零网络调用（探针就绪前不入库）
            if self._refresh_blocked(item):
                continue
            text = self._fetch_list(item)
            if text is None:
                continue
            entries = self._entries(item, text)
            if entries:
                out.append(('urllist:%s' % (item.get('id') or ''), entries))
                log.info('URL 清单 %s: 解析 %d 条', item.get('id'), len(entries))
        return out

    def _refresh_blocked(self, item):
        """refresh_minutes 门控（进程内记账）：未到重拉周期则本轮跳过（首次不拦）。"""
        refresh = item.get('refresh_minutes')
        if refresh is None:
            return False
        key = item.get('id') or item.get('url')
        last = _URL_LIST_LAST_FETCH.get(key)
        now = time.monotonic()
        if last is not None and (now - last) < float(refresh) * 60:
            log.info('URL 清单 %s: 距上次拉取未满 %s 分钟，跳过', item.get('id'), refresh)
            return True
        _URL_LIST_LAST_FETCH[key] = now
        return False


class SourceDiscovery(object):
    """源发现编排：Provider 遍历 -> 规则过滤 -> 去重入库。

    业务四模块互不依赖红线不变：本类只写 sources/repos，不感知 verifier/health。
    """

    def __init__(self, cfg, db, net, parser=None):
        self.cfg = cfg
        self.db = db
        self.net = net
        self.parser = parser or ChannelParser(cfg)

    # ---------- 规则 ----------
    def apply_rules(self, url):
        """死源规则判定：返回 (blocked, flaky)。"""
        host = self.parser.host(url).lower()
        rules = self.cfg.rules
        for pfx in rules.get('blacklist_host_prefixes') or []:
            if host.startswith(pfx):
                return True, False
        for dom in rules.get('blacklist_domains') or []:
            if dom in host:
                return True, False
        for dom in rules.get('flaky_domains') or []:
            if dom in host:
                return False, True
        return False, False

    # ---------- 仓库清单 ----------
    def ensure_repos(self):
        """同步显式配置的启停与备注；保留抓取历史及未列入配置的挖矿记录。"""
        rows = []
        for r in self.cfg.repos.get('repos') or []:
            rows.append((r.get('repo', ''), r.get('branch', 'main'),
                         r.get('path', ''), r.get('channel', 'jsdelivr'),
                         1 if r.get('enabled', True) else 0, r.get('note', '')))
        if rows:
            self.db.executemany(
                'INSERT INTO repos(repo, branch, path, channel, enabled, note) '
                'VALUES (?,?,?,?,?,?) '
                'ON CONFLICT(repo, branch, path, channel) DO UPDATE SET '
                'enabled=excluded.enabled, note=excluded.note', rows)
        total = self.db.query_one('SELECT COUNT(*) AS c FROM repos')['c']
        log.info('repos 表同步完成, 共 %d 条仓库配置', total)

    # ---------- 入库 ----------
    def _signed_params(self, url):
        """两级签名参数名单（全局 ∪ host 作用域），委托 ChannelParser（单一事实来源）。

        与 verifier 的签名判定共用同一函数，避免两处各写一套剥离名单（设计 §1.2）。
        """
        return self.parser.resolve_signed_params(url, self.cfg.settings)

    def ingest(self, entries, default_origin):
        """候选入库：url_key 去重 upsert（批量）；返回 (new, dup, blocked)。

        P0 优化：原来是每条 entry 一次查重 + 一次独立 commit（数千条 = 数千次
        fsync + 数千次锁往返）；现在内存去重 + 分块 IN 批量查重 + executemany
        两次批量提交。语义保持：黑名单源入库但 status=blacklisted 不进验证队列；
        重复发现媒体事实未变时只刷新 last_seen。差异：同批次内的重复 URL 计入 new 而非 dup。

        P1 阻塞点（设计 §2.4）：查重键由 `url_hash` 改为 `url_key`（剥离时效签名后的
        物理源身份）；`url_hash` 仍按字面计算并入库（兼容保留）。四条分支：
          - url_key 未命中 且 url_hash 未命中 -> INSERT（新源）；
          - url_key 命中 且 url 字面相同 -> 刷 last_seen，并核对同源清单媒体快照；
          - url_key 命中 且 url 字面不同 -> 签名轮换：刷 last_seen + 刷新 url 为最新
            签名 URL + url_observed_at；**保留 last_verified**（归一化后逐字节相同 =>
            物理内容未变，无需重验）；
          - url_key 未命中 但 url_hash 命中（P0-2 兜底）-> 名单变更后的陈旧 key 行：
            按当前名单重算 url_key 并刷新该行（此分支不可能撞键：uk 已被持有即被主匹配命中）；
            collision-orphan 行被同 url 命中时恢复 candidate（身份归还）。

        entries 元素可为 dict（SeedProvider / UrlListProvider）或 Entry namedtuple（仓库解析）。
        dict 可携带 container/drm/encryption/keyformat/kid/drm_key/caps_required/profile
        （HK MPD 路线），透传落库；同源清单媒体快照整体更新，事实变化撤销旧验证与线路。
        ★ profile 为安全关键字段：由 derive_profile() 委托唯一叶子 db.delivery_profile
        按 DRM/加密事实**派生**（fail-closed），绝不依赖 sources.profile 的列默认值 'direct'。
        """
        now = utcnow()
        # 1) 预处理：解析/规则判定/评分，按 url_key 内存去重（保留首条）
        #    记录以 dict 承载：除身份字段外，透传 container/drm/encryption/keyformat/
        #    kid/drm_key/caps_required/profile（HK MPD 路线；dict 分支才有，Entry 分支恒 None）。
        #    profile 由 derive_profile() 派生（显式值优先）——唯一判据见 db.delivery_profile。
        seen = {}
        blocked = 0
        for raw in entries:
            if isinstance(raw, dict):
                e = raw
                rec = {
                    'url': (e.get('url') or '').strip(),
                    'name': e.get('name') or '',
                    'channel_key': e.get('channel_key') or '',
                    'status': e.get('status') or 'candidate',
                    'av': e.get('av'),
                    'origin': e.get('origin') or default_origin,
                    'score': e.get('score'),
                    'container': e.get('container'),
                    'encryption': e.get('encryption'),
                    'drm': e.get('drm'),
                    'keyformat': e.get('keyformat'),
                    'kid': e.get('kid'),
                    'drm_key': e.get('drm_key'),
                    'caps_required': e.get('caps_required'),
                    'profile': e.get('profile'),
                }
            else:
                rec = {
                    'url': (raw.url or '').strip(),
                    'name': raw.name or '',
                    'channel_key': '',
                    'status': 'candidate',
                    'av': None,
                    'origin': default_origin,
                    'score': None,
                    'container': None,
                    'encryption': None,
                    'drm': None,
                    'keyformat': None,
                    'kid': None,
                    'drm_key': None,
                    'caps_required': None,
                    'profile': None,
                }
            # 字段存在性区分“没声明”与“显式清空”；普通列表不冒充明文媒体快照。
            rec['_media_fields'] = tuple(
                field for field in ('container', 'encryption', 'drm', 'keyformat',
                                    'kid', 'drm_key', 'caps_required', 'profile')
                if isinstance(raw, dict) and field in raw)
            rec['_dict_entry'] = isinstance(raw, dict)
            # ★ fail-closed：按 DRM/加密事实派生 profile（显式值优先），绝不依赖列默认 'direct'。
            rec['profile'] = derive_profile(
                rec.get('encryption'), rec.get('drm'), rec.get('profile'))
            url = rec['url']
            if not url or '://' not in url:
                continue
            is_blocked, is_flaky = self.apply_rules(url)
            if is_blocked:
                blocked += 1
            h = self.parser.url_hash(url)
            uk = self.parser.url_key(url, self._signed_params(url))
            if not rec['channel_key']:
                rec['channel_key'] = self.parser.normalize_channel(rec['name'])
            score = rec['score']
            if score is None:
                score = self.parser.host_score(self.parser.host(url))
            score = float(score)
            if is_flaky:
                score = min(score, 5.0)   # flaky 降权：验证排序靠后
            status = rec['status']
            if is_blocked:
                status = 'blacklisted'
            if uk not in seen:
                rec.update({
                    'url_hash': h,
                    'url_key': uk,
                    'key_source': 'normalized' if uk != h else 'raw',
                    'status': status,
                    'score': score,
                })
                seen[uk] = rec

        # 2) 批量查重：url_key 主匹配 + url_hash 兜底匹配（P0-2 方案 C）。
        #    url_hash 兜底兜住「名单变更后存量行 url_key 陈旧」：url 字面未变时新行
        #    INSERT OR IGNORE 会被 uq_sources_url 静默吞掉（行冻结），兜底命中后按
        #    当前名单重算 url_key 并刷新该行，陈旧 key 在 ingest 路径就地自愈。
        media_fields = ('container', 'encryption', 'drm', 'keyformat', 'kid',
                        'drm_key', 'caps_required', 'profile')
        existing = {}   # url_key -> row
        by_hash = {}    # url_hash -> row（uq_sources_url 唯一 -> 至多一行）
        keys = list(seen)
        hashes = [rec['url_hash'] for rec in seen.values()]
        for i in range(0, len(keys), 500):
            chunk = keys[i:i + 500]
            hchunk = hashes[i:i + 500]
            marks = ','.join(['?'] * len(chunk))
            hmarks = ','.join(['?'] * len(hchunk))
            for row in self.db.query(
                    'SELECT * FROM sources'
                    ' WHERE url_key IN (%s) OR url_hash IN (%s)' % (marks, hmarks),
                    chunk + hchunk):
                if row['url_key']:
                    existing[row['url_key']] = row
                if row['url_hash'] not in by_hash:
                    by_hash[row['url_hash']] = row

        # 3) 清单声明作为一个快照刷新；无媒体声明的普通发现不覆盖探测事实。
        to_insert, to_touch, to_refresh, to_invalidate = [], [], [], []
        new = dup = 0
        for uk, rec in seen.items():
            old = existing.get(uk) or by_hash.get(rec['url_hash'])
            if old is not None:
                dup += 1
                sid = old['id']
                same_origin = old['origin'] == rec['origin']
                # 旧内联密钥确证清单所有权；该声明撤销时完整清理一次。
                revoked_key = (same_origin and rec['_dict_entry']
                               and old['drm_key'] and not rec['drm_key'])
                declared_changed = any(old[field] != rec[field]
                                       for field in rec['_media_fields'])
                # 修正历史 direct + 已声明 DRM 的 fail-open，不把 need-key 等
                # verifier 结论当作清单默认画像变化而反复撤验。
                unsafe_direct = (old['profile'] == 'direct' and rec['profile'] != 'direct'
                                 and any(field in rec['_media_fields']
                                         for field in ('encryption', 'drm', 'profile')))
                # 沿用新签名 URL 的 last-writer 语义；明确声明变化时来源和
                # 媒体快照一起换，避免 B 的 URL 混用 A 的密钥与验证。
                refresh_media = (revoked_key or
                                 ((same_origin or old['url'] != rec['url'])
                                  and (declared_changed or unsafe_direct)))
                media = tuple(rec[field] if refresh_media else old[field]
                              for field in media_fields)
                changed = refresh_media and media != tuple(old[field] for field in media_fields)
                if old['url'] == rec['url'] and old['url_key'] == uk and not changed:
                    to_touch.append((now, sid))
                else:
                    observed_at = now if old['url'] != rec['url'] else old['url_observed_at']
                    origin = rec['origin'] if refresh_media else old['origin']
                    to_refresh.append((now, rec['url'], rec['url_hash'], uk,
                                       rec['key_source'], observed_at, origin) + media + (sid,))
                if changed or old['status'] == COLLISION_ORPHAN_STATUS:
                    to_invalidate.append(sid)
            else:
                to_insert.append((
                    rec['url'], rec['url_hash'], rec['url_key'], rec['key_source'],
                    rec['name'], rec['channel_key'], rec['origin'], rec['status'],
                    rec['av'], rec['score'], now, now, now,
                    rec['container'], rec['encryption'], rec['drm'], rec['keyformat'],
                    rec['kid'], rec['drm_key'], rec['caps_required'], rec['profile']))
                new += 1
        # 媒体快照、验证失效与线路撤回须同一事务，避免中间态被投递。
        with self.db.transaction() as conn:
            if to_insert:
                conn.executemany(
                    'INSERT OR IGNORE INTO sources'
                    '(url, url_hash, url_key, key_source, channel_name, channel_key, origin,'
                    ' status, av, score, first_seen, last_seen, url_observed_at,'
                    ' container, encryption, drm, keyformat, kid, drm_key, caps_required,'
                    ' profile)'
                    ' VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', to_insert)
            if to_touch:
                conn.executemany(
                    'UPDATE sources SET last_seen=? WHERE id=?', to_touch)
            if to_refresh:
                conn.executemany(
                    'UPDATE sources SET last_seen=?, url=?, url_hash=?, url_key=?, key_source=?,'
                    ' url_observed_at=?, origin=?, container=?, encryption=?, drm=?, keyformat=?, kid=?,'
                    ' drm_key=?, caps_required=?, profile=? WHERE id=?', to_refresh)
            if to_invalidate:
                conn.executemany(
                    "UPDATE sources SET status=CASE WHEN status='blacklisted' THEN status "
                    "ELSE 'candidate' END, av=NULL, last_verified=NULL, verdict_reason=NULL, "
                    "backoff_until=NULL WHERE id=?", [(sid,) for sid in to_invalidate])
                conn.executemany('DELETE FROM lineups WHERE source_id=?',
                                [(sid,) for sid in to_invalidate])
        return new, dup, blocked

    # ---------- 主流程 ----------
    def run(self):
        """一轮源发现：种子兜底 + Provider 遍历，返回统计 dict。"""
        stats = {'discovered': 0, 'new': 0, 'dup': 0, 'blocked': 0}
        self.ensure_repos()

        # 种子导入（仅一次；tools/import_seed.py 与 main.py 冷启动共用本逻辑）
        if self.db.get_meta('seed_imported') is None:
            seeds = SeedProvider(self.cfg, self.parser).entries()
            n, d, b = self.ingest(seeds, 'seed')
            stats['discovered'] += len(seeds)
            stats['new'] += n
            stats['dup'] += d
            stats['blocked'] += b
            self.db.set_meta('seed_imported', utcnow())
            log.info('种子导入完成: %d 条 (新增 %d, 重复 %d, 黑名单 %d)',
                     len(seeds), n, d, b)

        # 挖矿通道（先于仓库拉取：新入库仓库下轮才参与，本轮只扩 repos 表）
        try:
            added = RepoMinerProvider(self.cfg, self.db, self.net, self.parser).collect()
            if added:
                stats['repos_added'] = added
        except Exception as e:  # noqa: BLE001 - 挖矿异常不影响仓库通道
            log.error('挖矿通道异常: %s', e)

        # 仓库通道
        for origin, entries in RepoProvider(self.cfg, self.db, self.net, self.parser).collect():
            try:
                n, d, b = self.ingest(entries, origin)
                stats['discovered'] += len(entries)
                stats['new'] += n
                stats['dup'] += d
                stats['blocked'] += b
                log.info('通道 %s: 新增 %d, 重复 %d, 黑名单 %d', origin, n, d, b)
            except Exception as e:  # noqa: BLE001 - 单通道入库异常不杀整轮
                log.error('通道 %s 入库异常: %s', origin, e)

        # 任意 URL 清单通道（HK MPD 路线；enabled 默认 false -> 零网络调用）
        try:
            for origin, entries in UrlListProvider(self.cfg, self.net, self.parser).collect():
                try:
                    n, d, b = self.ingest(entries, origin)
                    stats['discovered'] += len(entries)
                    stats['new'] += n
                    stats['dup'] += d
                    stats['blocked'] += b
                    log.info('通道 %s: 新增 %d, 重复 %d, 黑名单 %d', origin, n, d, b)
                except Exception as e:  # noqa: BLE001 - 单通道入库异常不杀整轮
                    log.error('通道 %s 入库异常: %s', origin, e)
        except Exception as e:  # noqa: BLE001 - URL 清单通道异常不影响主流程
            log.error('URL 清单通道异常: %s', e)

        log.info('源发现完成: %s', stats)
        return stats
