# -*- coding: utf-8 -*-
"""URL 身份键（url_hash / url_key / 签名参数剥离）—— 契约冻结算法的唯一事实来源。

从 app.parser 迁出（2026-09-25 重构）：HUB_CONTRACT_V1 §2 的算法集中在本模块，
parser.ChannelParser 上的同名方法只是兼容委托。改这里 = 改契约，先登记
server/docs/HUB_CONTRACT_CHANGELOG.md 并两端同步。
"""
import hashlib
import json
import re

_HOST_RE = re.compile(r'https?://([^/]+)')

# ---------- URL 归一化 ----------
def normalize_url(url):
    """URL 归一化：strip + 去 fragment + host/scheme 小写；不动 query（鉴权依赖）。

    ARCHITECTURE §7.4 红线。
    """
    u = (url or '').strip()
    if not u:
        return ''
    if '#' in u:
        u = u.split('#', 1)[0]
    m = re.match(r'^(https?)://([^/]+)(.*)$', u, re.IGNORECASE)
    if m:
        return '%s://%s%s' % (m.group(1).lower(), m.group(2).lower(), m.group(3))
    return u


def url_hash(url):
    """sha1(归一化 URL)，sources 表去重【字面】指纹（红线，语义永不变）。"""
    return hashlib.sha1(normalize_url(url).encode('utf-8')).hexdigest()


# ---------- 时效签名剥离 = 物理源身份键（P1 阻塞点） ----------
# url_hash 保留完整 query -> 同一物理源每天因时效签名轮换产生新 hash。
# url_key 剥离时效签名参数后再 hash，使签名变化不产生新身份。
# 单一事实来源：discovery（算 url_key）与 verifier（判 expired）共用 resolve_signed_params。
def resolve_signed_params(url, settings):
    """合并『全局 signed_url_params』与『host 作用域 signed_url_params_by_host』。

    规则：
      - 全局名单 `settings['signed_url_params']`（list[str]，缺失/非法 -> []）；
      - host 作用域 `settings['signed_url_params_by_host']`（dict[str, list[str]]，缺失 -> {}）；
      - host 取自 url 且小写；某键生效当且仅当 host 与该键【相等或为其子域】
        （后缀匹配：host == k or host.endswith('.' + k)，k 已小写）。team-lead 裁定 1：
        比设计 §10.2 的"包含匹配"更严格，避免 `edgeware.tvb.com.evil.net` 误命中。
        host 含端口时按注册域比较（端口不属于域）。
      - 返回：去重后的小写参数名列表；任何解析异常一律保守回退全局名单。
    """
    global_list = []
    if isinstance(settings, dict):
        raw = settings.get('signed_url_params')
        if isinstance(raw, (list, tuple)):
            global_list = [p for p in raw if isinstance(p, str) and p.strip()]
    try:
        out, seen = [], set()
        for p in global_list:
            lp = p.strip().lower()
            if lp and lp not in seen:
                seen.add(lp)
                out.append(lp)
        by_host = settings.get('signed_url_params_by_host') if isinstance(settings, dict) else None
        if isinstance(by_host, dict):
            url_host = host(url).lower().split(':', 1)[0]
            for key, vals in by_host.items():
                k = str(key or '').strip().lower()
                if not k or not isinstance(vals, (list, tuple)):
                    continue
                if url_host == k or url_host.endswith('.' + k):
                    for p in vals:
                        if isinstance(p, str) and p.strip():
                            lp = p.strip().lower()
                            if lp and lp not in seen:
                                seen.add(lp)
                                out.append(lp)
        return out
    except Exception:  # noqa: BLE001 - 解析异常保守回退全局名单
        return [p.strip().lower() for p in global_list if p and p.strip()]

def strip_signed_params(url, signed_params):
    """剥离 query 中的时效签名参数（参数名【精确匹配 + 大小写不敏感】，禁子串）。

    ★ 不变量（设计 §4.1，最关键）：URL 不含任何命中参数时，返回值与
    `normalize_url(url)`【逐字节相同】——不重排、不重新 percent-encode、不去字符。
    非 http(s) scheme：原样返回 `normalize_url(url)`（不解析 query，§4.7）。
    """
    u = normalize_url(url)
    if not u or '?' not in u:
        return u
    base, query = u.split('?', 1)
    if not base.lower().startswith(('http://', 'https://')):
        return u
    wanted = {str(p).strip().lower()
              for p in (signed_params or []) if p and str(p).strip()}
    if not wanted:
        return u
    kept, hit = [], False
    for part in query.split('&'):
        if not part:
            continue
        if part.split('=', 1)[0].lower() in wanted:
            hit = True
            continue                  # 命中即丢（重复同名全丢）
        kept.append(part)             # 保留原样（顺序 / 编码不变）
    if not hit:
        return u                      # ★ 零命中 -> 逐字节原样返回
    return base + ('?' + '&'.join(kept) if kept else '')

def url_key(url, signed_params):
    """物理源身份键：sha1(剥离签名后的归一化 URL)。

    无签名参数命中时恒等于 `url_hash(url)`（设计 §5 AC-N1 的机器可验证不变量）。
    """
    stripped = strip_signed_params(url, signed_params)
    return hashlib.sha1(stripped.encode('utf-8')).hexdigest()

# ---------- 名单指纹（P0-2 方案 C）：名单变更检测 ----------
def signed_params_fingerprint(settings):
    """生效名单（全局 + host 作用域，含 settings.json 覆盖）的稳定指纹：sha1 前 12 位。

    规范形（顺序无关，按构造定义并钉死在测试里）：
      - 全局名单：去重、strip、小写后**排序**；
      - host 作用域：host 键去空白小写；每 host 参数去重、strip、小写后排序；
        host 键再按字典序序列化（json sort_keys）。
    任何会改变实际剥离行为的变更（增删参数、改大小写/空白、改 host 键）都会改变指纹；
    名单书写顺序、JSON 键序等表面差异不会改变指纹。
    settings 缺失/非法字段一律按空名单处理（与 resolve_signed_params 的保守语义一致），
    指纹函数自身永不抛异常。
    """
    st = settings if isinstance(settings, dict) else {}
    raw_global = st.get('signed_url_params')
    glob = sorted({
        p.strip().lower()
        for p in (raw_global if isinstance(raw_global, (list, tuple)) else [])
        if isinstance(p, str) and p.strip()
    })
    hosts = {}
    raw_host = st.get('signed_url_params_by_host')
    if isinstance(raw_host, dict):
        for key, vals in raw_host.items():
            k = str(key or '').strip().lower()
            if not k or not isinstance(vals, (list, tuple)):
                continue
            acc = sorted({
                str(p).strip().lower()
                for p in vals if isinstance(p, str) and p.strip()
            })
            if acc:
                hosts[k] = acc
    canonical = json.dumps({'g': glob, 'h': hosts}, sort_keys=True, separators=(',', ':'))
    return hashlib.sha1(canonical.encode('utf-8')).hexdigest()[:12]


def host(url):
    """提取 URL 的 host（含端口），失败返回前 30 字符（移植 revival_channels.host）。"""
    m = _HOST_RE.match(url or '')
    return m.group(1) if m else (url or '')[:30]
