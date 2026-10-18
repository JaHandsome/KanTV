# -*- coding: utf-8 -*-
"""配置加载：5 个 JSON（settings/channels/repos/rules/url_lists），深合并默认值，缺文件/缺字段不崩溃。

设计要点（ARCHITECTURE §7.9）：
- 每个调度 tick 重读配置（共享 Config，成功解析的域以完整字典替换）；
- 所有周期/阈值/端口/路径外置，代码内零硬编码。
"""
import copy
import ipaddress
import json
import os
import re
import threading
from urllib.parse import urlsplit

from app.logutil import get_logger

log = get_logger('config')

# settings.json 的通用默认值；本地配置缺字段时补齐，不读取部署样例生成默认值。
DEFAULT_SETTINGS = {
    'http': {
        'host': '0.0.0.0',
        'port': 8420,
        'snippet_host': 'localhost',
    },
    'schedule': {
        'discovery_hours': 6,
        'verify_hours': 2,
        'probe_minutes': 30,
    },
    'limits': {
        'net_concurrency': 16,
        'timeout_s': 8,
        'verify_batch': 200,
        'verify_budget_min': 25,
        'max_lines_per_channel': 3,
    },
    'paths': {
        'db': 'data/iptv.db',
        'output_dir': 'data/output',
        'log_dir': 'data/logs',
        'seed_dir': 'data/seed',
    },
    'network': {
        'user_agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120',
        'retries': 2,
        'backoff_s': 1.5,
        'private_source_origins': [],
        'repo_fetch_limit': 2097152,
        'probe_fetch_limit': 393216,
        'jsdelivr_prefix': 'https://cdn.jsdelivr.net/gh/',
        'ghproxy_prefix': 'https://gh-proxy.com/https://raw.githubusercontent.com/',
    },
    'health': {
        'flaky_fail_streak': 2,
        'dead_fail_streak': 3,
        'recover_ok_streak': 2,
        'dead_probe_every_rounds': 4,
        'probe_read_limit': 512,
        # v10（分片吞吐纳入线路评分）：每 8 个探活轮（4h）对 lineups 名额 +
        # 2 个候补的 hls 源做分片计时探测，按实测吞吐折算 sources.score。
        'throughput_enabled': True,
        'throughput_every_rounds': 8,      # 每 8 个探活轮(4h)一次, 控带宽
        'throughput_extra_candidates': 2,  # 每频道探测 lineups 名额(3)+2 个候补
        'throughput_segments': 2,          # 每次探测取末尾 N 个分片计时
        'throughput_seg_timeout_s': 15,    # 单分片下载超时
        'throughput_max_bytes': 8388608,   # 单分片下载上限(8MB, 覆盖 8s 高分片)
        'throughput_window': 3,            # 折算看最近 3 次检查
        # 窗口内 ratio>=该值(下载慢于实时)即按该样本折算, 不再取中位:
        # 一次实测"下不满"就足以判该源不能实时直播, 不允许被好样本稀释
        'throughput_realtime_ratio': 1.0,
    },
    'retention_days': 90,
    'verify_cache_hours': 24,
    # 2026-09-25（方案A）：质量弱频道（首发阵容吞吐干净线不足）也获得候选验证预算。
    # 背景："数量够但全是慢线"的频道过去永远排不到验证（翡翠台 9 线在役仅 1 条干净，
    # 21 个候选 4 天未复验）。False 退回"仅数量弱优先"的两层旧行为。
    'verify_quality_weak_enabled': True,
    # 2026-09-25（方案C）：陈旧拉黑（已不命中现行死源规则）低频复核复出。
    # 拉黑是单向门：规则删除/收窄后被旧规则拉黑的源永不复审（全库实测 666 个）。
    # 配额独立于 verify_batch，避免被候选洪流挤占。
    'blacklist_recheck_enabled': True,
    'blacklist_recheck_hours': 168,     # 复核间隔（7 天）
    'blacklist_recheck_per_round': 5,   # 每轮复核配额
    # P1-C/H：dead 源保留天数（last_seen 超期即清理，联动 db.cleanup）
    'dead_retention_days': 30,
    # P1-I：探活记录独立保留期（仅服务 24h 存活率查询，短保留避免库膨胀）
    'checks_retention_days': 7,
    # P1-J：channel_key 为空的孤儿源保留期（不参与验证/输出，仅用于去重比对）
    'orphan_retention_days': 30,
    # P2 #17/#26：RepoMiner 二级挖矿（递归发现新仓库，防死链放大的限额见键名）
    'discovery': {
        'miner_enabled': True,
        'miner_readme_per_round': 6,
        'miner_validate_per_round': 5,
        'miner_max_repos_per_round': 3,
    },
    # P0 收口（R4/R1）：客户端能力集与 AES 探测预算。
    # ⚠️ client_caps 默认必须是空 dict {}（而非含 caps 的宽放默认）：settings.json
    #    缺失该段时 check_caps() 走保守失败，不因默认值被放行（AC-4.5/4.6）。
    'client_caps': {},
    # P0 收口（R1）：AES-128 分片仅解密前 N 字节（64KB = 4096 块），足够 TS PMT/PES 判定窗口。
    'aes_probe_bytes': 65536,
    # P0 收口（R3）：签名 URL query 参数名（大小写不敏感 + 精确匹配，禁子串；
    # 明确不含 key/authid —— 静态访问 key 非时效签名，见设计 §4.1）。
    'signed_url_params': ['expires', 'expire', 'expires_at', 'token', 'securitykey',
                          'sign', 'signature', 'msisdn'],
    # P1 阻塞点：host 作用域签名参数名单（`ts`/`c` 等极常见参数名只在特定网关域名剥离，
    # 避免全局剥离误合并无关源）。缺省空 dict；具体域名映射外置 config/settings.json。
    'signed_url_params_by_host': {},
    # P0 收口（R3）：签名型 URL 403 的短退避小时数（替代 403 直接 30 天长冷却）。
    'expired_backoff_hours': 24,
    # 枢纽（HUB_CONTRACT_V1）：默认**关闭**且 token 为空。
    # ⚠️ 默认必须 fail-closed：缺段时不得因为"想让它能用"而给一个空 token 放行，
    #    也不得默认开着一个暴露在局域网上的回流端点（契约 §4.2 的鉴权是唯一门槛）。
    'hub': {'enabled': False, 'feedback_token': ''},
}

DEFAULT_CHANNELS = {
    'channels': [],
    'order': [],
    'channel_groups': {},
    'groups': {},
    'group_index': {},
    'aliases': {},
    # 繁体->简体字符预转换表（域内，非完整 OpenCC）。normalize_channel 首趟未命中时，
    # 用本表把名字预转换后再匹配一遍，一次性覆盖"繁体写法"这一整类漏配。
    # 缺省空 dict：不配置则预转换不生效，行为回退为单趟（零风险）。
    't2s_map': {},
    'weishi': [],
    'hkmt_keywords': [],
    'gd_keywords': [],
    'epg_names': {},
    'epg_template': 'http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}',
    'logo_template': 'https://gitee.com/suxuang/logo/raw/master/mylogo/{name}.png',
}

DEFAULT_RULES = {
    'blacklist_host_prefixes': [],
    'blacklist_domains': [],
    'flaky_domains': [],
    # P0 收口（R2）：客户端不支持的协议 scheme 白名单（probe() 第一道前置拦截，
    # 命中即 status='unsupported' 永久不探测，不发起任何网络请求）。
    'unsupported_schemes': ['rtmp', 'rtsp', 'rtp', 'udp', 'p2p', 'mitv', 'zlive', 'tvbus'],
}

DEFAULT_REPOS = {'repos': []}

# P1（HK MPD 路线）：任意 URL 清单通道（UrlListProvider）的外置配置骨架。
# 每条 list 由人工逐条指定 URL（无自动发现）；enabled 默认 false ——
# "探针就绪前不入库"的硬保证（见 HK_MPD_ROUTE_DESIGN §3.2/§8 批次约束）。
DEFAULT_URL_LISTS = {'lists': []}

# 热重载域清单：(实例属性名, 文件名, 默认值)
_DOMAINS = (
    ('settings', 'settings.json', DEFAULT_SETTINGS),
    ('channels', 'channels.json', DEFAULT_CHANNELS),
    ('rules', 'rules.json', DEFAULT_RULES),
    ('repos', 'repos.json', DEFAULT_REPOS),
    ('url_lists', 'url_lists.json', DEFAULT_URL_LISTS),
)


_PRIVATE_SOURCE_NETS = tuple(ipaddress.ip_network(value) for value in (
    '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', 'fc00::/7',
))
# 标准云元数据地址落在 ULA 内，不能通过私网源授权绕过元数据边界。
_PRIVATE_SOURCE_METADATA_IPS = frozenset(ipaddress.ip_address(value) for value in (
    'fd00:ec2::254', 'fd20:ce::254',
))
_PRIVATE_ORIGIN = re.compile(
    r'https?://(?:\[[0-9a-f:]+\]|[0-9.]+):[0-9]+', re.IGNORECASE)


def _compile_private_source_origins(settings):
    """严格 origin 白名单；只返回规范 IP，不解析 DNS，也不在错误中回显配置。"""
    field = 'network.private_source_origins'
    network = settings.get('network')
    if not isinstance(network, dict):
        raise ValueError('%s: network must be an object' % field)
    entries = network.get('private_source_origins', [])
    if not isinstance(entries, list):
        raise ValueError('%s: must be a list' % field)
    origins = set()
    for index, value in enumerate(entries):
        try:
            # fullmatch 也拒绝空白、userinfo、zone、path（包括 /）、query 和 fragment。
            if not isinstance(value, str) or not _PRIVATE_ORIGIN.fullmatch(value):
                raise ValueError()
            parts = urlsplit(value)
            ip = ipaddress.ip_address(parts.hostname)
            port = parts.port
            if (not 0 < port <= 65535 or ip in _PRIVATE_SOURCE_METADATA_IPS
                    or not any(ip in net for net in _PRIVATE_SOURCE_NETS)):
                raise ValueError()
        except ValueError:
            raise ValueError('%s[%d]: invalid private source origin' % (field, index)) from None
        origins.add((parts.scheme.lower(), str(ip), port))
    return frozenset(origins)


def _deep_merge(base, override):
    """深合并：override 覆盖 base，dict 递归，其余类型直接覆盖。"""
    out = copy.deepcopy(base)
    for k, v in (override or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def _load_json(path, default):
    """读 JSON；文件缺失/损坏时返回默认值并记 WARNING，不抛异常（仅首次加载使用）。"""
    if not os.path.isfile(path):
        log.warning('配置文件缺失, 使用默认值: %s', path)
        return copy.deepcopy(default)
    try:
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        if not isinstance(data, dict):
            raise ValueError('配置根节点必须是 JSON 对象')
        return data
    except (ValueError, OSError, UnicodeDecodeError) as e:
        log.error('配置文件解析失败, 使用默认值: %s (%s)', path, e)
        return copy.deepcopy(default)


def _load_json_strict(path):
    """读 JSON dict；文件缺失/损坏返回 None（热重载时用于"保留旧配置"判定）。

    P1-B 配置容错：编辑器半写状态/误删/类型写坏时不允许把上一轮生效配置
    替换成默认值（端口/周期/snippet_host 漂移且表面正常）。
    """
    if not os.path.isfile(path):
        return None
    try:
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        if not isinstance(data, dict):
            return None
        return data
    except (ValueError, OSError, UnicodeDecodeError):
        return None


class Config(object):
    """全局配置对象：settings / channels / rules / repos / url_lists 五域。"""

    def __init__(self, root):
        self.root = os.path.abspath(root)
        self.config_dir = os.path.join(self.root, 'config')
        # 多次读取配置的请求与热加载发布共用此锁；不在读者手中的字典上 clear/update。
        self.lock = threading.RLock()
        self.settings = {}
        self.channels = {}
        self.rules = {}
        self.repos = {}
        self.url_lists = {}
        self.load()

    # ---------- 加载 ----------
    def load(self):
        """首次加载：读 4 个配置文件并与默认值深合并。"""
        self.settings = _deep_merge(
            DEFAULT_SETTINGS,
            _load_json(os.path.join(self.config_dir, 'settings.json'), {}))
        _compile_private_source_origins(self.settings)
        self.channels = _deep_merge(
            DEFAULT_CHANNELS,
            _load_json(os.path.join(self.config_dir, 'channels.json'), {}))
        self.rules = _deep_merge(
            DEFAULT_RULES,
            _load_json(os.path.join(self.config_dir, 'rules.json'), {}))
        self.repos = _deep_merge(
            DEFAULT_REPOS,
            _load_json(os.path.join(self.config_dir, 'repos.json'), {}))
        self.url_lists = _deep_merge(
            DEFAULT_URL_LISTS,
            _load_json(os.path.join(self.config_dir, 'url_lists.json'), {}))

    def reload(self):
        """热重载（P1-B 配置容错）：

        - 任一域解析失败 -> 保留该域上一轮生效配置并记 ERROR（原实现静默回退
          默认值，导致端口/周期/snippet_host 漂移而表面正常）；
        - 解析成功 -> 完整替换该域，内容有变化时记 INFO 变更审计日志；
        - 各域独立判定：settings 损坏不影响 channels 等其他域的热更新。
        """
        updates = []
        for attr, fname, default in _DOMAINS:
            data = _load_json_strict(os.path.join(self.config_dir, fname))
            if data is None:
                log.error('配置热加载失败, 保留上一轮生效配置: %s', fname)
                continue
            merged = _deep_merge(default, data)
            if attr == 'settings':
                try:
                    _compile_private_source_origins(merged)
                except ValueError as exc:
                    log.error('配置热加载失败, 保留上一轮生效配置: %s (%s)', fname, exc)
                    continue
            updates.append((attr, fname, merged))
        with self.lock:
            for attr, fname, merged in updates:
                cur = getattr(self, attr)
                changed = sorted(k for k in set(merged) | set(cur)
                                 if merged.get(k) != cur.get(k))
                if changed:
                    log.info('配置热加载: %s 变更键 [%s]', fname, ','.join(changed))
                setattr(self, attr, merged)

    # ---------- 路径 ----------
    def path(self, rel):
        """把 settings.paths 里的相对路径解析为项目根下的绝对路径。"""
        return os.path.join(self.root, rel.replace('/', os.sep))

    @property
    def db_path(self):
        return self.path(self.settings['paths']['db'])

    @property
    def output_dir(self):
        return self.path(self.settings['paths']['output_dir'])

    @property
    def seed_dir(self):
        return self.path(self.settings['paths']['seed_dir'])

    @property
    def log_dir(self):
        return self.path(self.settings['paths']['log_dir'])

    # ---------- URL ----------
    def http_base(self):
        """http://host:port（监听地址）。"""
        http = self.settings['http']
        return 'http://%s:%d' % (http['host'], http['port'])

    def snippet_url(self):
        """tvbox_lives_snippet.json 里指向 live.txt 的订阅地址（对局域网暴露的地址）。"""
        http = self.settings['http']
        return 'http://%s:%d/live.txt' % (http['snippet_host'], http['port'])
