# -*- coding: utf-8 -*-
"""ChannelParser：M3U/TXT 双格式解析 + 频道名/URL 归一化。

移植来源（架构红线：移植不重写）：
- parse_m3u / host_score / WEISHI / key_of 的 CCTV 正则  -> revival_channels.py
- parse_txt 的 HKMT_KW/GD_KW 关键词与顺序                -> iptv_pipeline.py
- 关键词与别名表外置于 config/channels.json（改配置不改代码）。
"""
import base64
import binascii
import hashlib
import json
import re
from collections import namedtuple

from app import urlkey

# 一条候选源：name=仓库标注频道名, group=分组标注, url=播放地址, ua=EXTINF 里的 http-user-agent
Entry = namedtuple('Entry', ['name', 'group', 'url', 'ua'])

# 全角数字/加号 -> 半角（发现层频道名常见全角，如 CCTV５+）
_FW_DIGITS = str.maketrans('０１２３４５６７８９＋', '0123456789+')

_CCTV_RE = re.compile(r'CCTV[-–—]?(\d{1,2})(\+?)')
_HOST_RE = re.compile(r'https?://([^/]+)')

# #KODIPROP 关注键（inputstream.adaptive.*）-> 归一后的短键名。
# 只认这三个；其余 KODIPROP（如 drm_type）与无关指令一律忽略，避免污染落库字段。
KODIPROP_KEYS = {
    'inputstream.adaptive.manifest_type': 'manifest_type',
    'inputstream.adaptive.license_type': 'license_type',
    'inputstream.adaptive.license_key': 'license_key',
}

# 源名噪声词（画质 / 备用标记）：社区清单高频出现，剥离后再做频道归一化。
# 2026-09-10 修复复盘：'凤凰中文[1920*1080]'、'无线新闻 FHD'、'TVB翡翠台（备用）'
# 等名称超出 alias 兜底的 len+4 容差，归一化返回 '' → channel_key 为空 →
# verifier._select_batch 要求 channel_key != '' → 这类源**【永不进入验证队列】**。
# 该漏斗每轮 discovery 以约 1.2% 的比例持续产生，故在归一化入口统一清洗。
_QUALITY_NOISE = (r'(?:\d{3,4}\s*[*x×]\s*\d{3,4}|[2348]\s*K|UHD|FHD|HD|SD|'
                  r'1080[PI]?|720[PI]?|576[PI]?|480[PI]?|'
                  r'高清|超清|标清|蓝光|流畅|备用|后备|重播|测试|'
                  r'backup|bak|back)')
# 括号噪声：[1920*1080] / (720p) / （备用） —— 括号内出现噪声词即整段剥离
_PAREN_NOISE_RE = re.compile(
    r'\s*[\[\(（【][^\]\)）】]{0,24}' + _QUALITY_NOISE + r'[^\]\)）】]{0,24}[\]\)）】]',
    re.I)
# 尾部噪声：'无线新闻 FHD' / '北京文艺HD' —— 尾部连续噪声段剥离
_TAIL_NOISE_RE = re.compile(
    r'(?:[-_·|]?' + _QUALITY_NOISE + r'){1,3}$', re.I)
# 仅归因名称的处理上限；超长原名/URL仍保留在 Entry 中，不截断为真实频道。
_MAX_CHANNEL_NAME = 512


def _strip_quality_suffix(name):
    """剥离频道名中的画质/备用噪声（循环至稳定，兼容多段叠加）。

    仅做噪声剥离，不改变频道语义——不匹配的别名（如"翡翠剧集台(北美)"）不受影响。
    清洗结果为空时回退原名，避免把纯噪声名清成空串。
    """
    if len(name) > _MAX_CHANNEL_NAME:
        return name
    # 删除空白后再匹配，避免相邻空白量词的组合回溯。括号语义沿用原规则。
    n = name
    prev = None
    while prev != n and n:
        prev = n
        n = _PAREN_NOISE_RE.sub('', n)
        # 尾部匹配使用无空白副本；切回原文索引，保留合法名称内部空白。
        positions = [i for i, char in enumerate(n) if not char.isspace()]
        compact = ''.join(n[i] for i in positions)
        match = _TAIL_NOISE_RE.search(compact)
        if match:
            n = n[:positions[match.start()]].rstrip(' \t\r\n-_·|')
    n = n.strip()
    return n or name


def _b64_url_decode16(s):
    """base64 / base64url（含无填充）解码为 16 字节；失败返回 None。"""
    s = (s or '').strip()
    if not s:
        return None
    pad = '=' * (-len(s) % 4)
    for cand in (s, s.replace('-', '+').replace('_', '/')):
        try:
            b = base64.b64decode(cand + pad)
        except (ValueError, binascii.Error):
            continue
        if len(b) == 16:
            return b
    return None


class ChannelParser(object):
    """频道与 URL 的归一化/分类/评分（纯函数集合，持有 channels 配置引用）。"""

    def __init__(self, cfg):
        self.cfg = cfg

    # ---------- 解析 ----------
    @staticmethod
    def parse_m3u(text):
        """解析 #EXTM3U 文本 -> [Entry]。兼容无逗号的 #EXTINF 与 http-user-agent。"""
        out = []
        pend = None  # (name, group, ua)
        for line in text.splitlines():
            line = line.strip()
            if line.startswith('#EXTINF'):
                m = re.search(r'group-title="([^"]*)"', line)
                g = m.group(1) if m else ''
                ua = re.search(r'http-user-agent="([^"]*)"', line)
                nm = line.split(',', 1)[-1].strip() if ',' in line else ''
                pend = (nm, g, ua.group(1) if ua else None)
            elif line and not line.startswith('#') and pend is not None:
                nm, g, ua = pend
                pend = None
                out.append(Entry(nm, g, line, ua))
        return out

    @staticmethod
    def parse_txt(text):
        """解析 TVBox TXT 文本 -> [Entry]。

        格式：`组名,#genre#` 分组行 + `频道名,url1#url2#url3` 频道行；
        无逗号/以 # 开头的行跳过（兼容 liu673cn/xbb 等非标仓库）。
        """
        out = []
        group = ''
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith('#') or line.startswith('##'):
                continue
            if ',' not in line:
                continue
            left, right = line.split(',', 1)
            left = left.strip()
            right = right.strip()
            if not left or not right:
                continue
            if right == '#genre#':
                group = left
                continue
            for url in right.split('#'):
                url = url.strip()
                if url:
                    out.append(Entry(left, group, url, None))
        return out

    @staticmethod
    def parse_kodiprop(playlist_text):
        """解析 m3u 中的 #KODIPROP 三元组 -> {url: {...}}（纯函数，旁路补齐 DRM 元数据）。

        逐行扫描；缓存当前生效的 KODIPROP（只认 ``inputstream.adaptive`` 的
        manifest_type / license_type / license_key）；**遇到 URL 行即把缓存绑定到该 URL
        并清空缓存**（防跨条目串味——KODIPROP 在 URL 前，不能取 EXTINF 的下一行）。

        返回每个 URL 的**两个并列密钥键**（刻意并存，勿合并，勿"顺手归一"）：
        - ``license_key``：**原始字符串**（``license_key=`` 之后的原文），**逐字节保留** ——
          discovery 取其原样落库为 ``sources.drm_key``（客户端要的就是这串原样 JSON 喂
          ``LocalMediaDrmCallback``）；若改写成"解析后再序列化"，base64url 大小写/空白会被
          改写而破坏密钥保真。
        - ``license_key_obj``：``json.loads(license_key)`` 的**解析后对象**，仅供服务端读取
          ``kid`` 等字段；坏 JSON / 非对象 -> None（该条目不带 DRM 信息，**不抛异常**）。

        注：设计文档 §3.4 措辞为"解析为对象"，实现改为 raw + obj 并存 —— 消费方一处用 obj、
        一处用 raw（原样落库），两者不可互相替代（QA 判此为非缺陷）。

        返回 ``{url: {'manifest_type','license_type','license_key','license_key_obj'}}``；
        无 #KODIPROP 的 URL 不出现在结果中。现有 ``parse_m3u`` 不感知本函数。
        """
        out = {}
        cur = {}
        for raw in (playlist_text or '').splitlines():
            line = raw.strip()
            if line.startswith('#KODIPROP'):
                body = line.split(':', 1)[1] if ':' in line else ''
                if '=' in body:
                    k, v = body.split('=', 1)
                    short = KODIPROP_KEYS.get(k.strip())
                    if short:
                        cur[short] = v.strip()
                continue
            if line and not line.startswith('#'):
                if cur:
                    raw_lk = cur.get('license_key')
                    obj = None
                    if raw_lk:
                        try:
                            parsed = json.loads(raw_lk)
                            obj = parsed if isinstance(parsed, dict) else None
                        except ValueError:
                            obj = None
                    out[line] = {
                        'manifest_type': cur.get('manifest_type'),
                        'license_type': cur.get('license_type'),
                        'license_key': raw_lk,
                        'license_key_obj': obj,
                    }
                    cur = {}
        return out

    # ---------- 频道名归一化 ----------
    def _match(self, name):
        """频道名匹配链（顺序与逻辑为红线，勿改）：全角转半角 -> 剥离画质/备用噪声后缀
        -> 精确键 -> alias 精确 -> CCTV 前缀 -> 卫视包含 -> alias 包含兜底；未匹配返回 ''。

        本 helper 即原 `normalize_channel` 的匹配主体（逐字提取）。`normalize_channel`
        在其之上加了一趟"繁体->简体预转换"，本函数保持单趟语义不变（零回归基础）。
        """
        if not name:
            return ''
        if len(str(name)) > _MAX_CHANNEL_NAME:
            return ''
        n = str(name).strip().translate(_FW_DIGITS)
        n = _strip_quality_suffix(n)
        targets = set(self.cfg.channels.get('channels') or [])
        if not targets:
            return ''
        if n in targets:
            return n
        aliases = self.cfg.channels.get('aliases') or {}
        if n in aliases and aliases[n] in targets:
            return aliases[n]
        # CCTV 前缀归一：CCTV-5 / CCTV5+ / CCTV17农业 -> CCTV5 / CCTV5+ / CCTV17
        m = _CCTV_RE.match(n)
        if m:
            num = int(m.group(1))
            if 1 <= num <= 17:
                k = 'CCTV%d%s' % (num, m.group(2))
                if k in targets:
                    return k
        # 卫视精确包含匹配（len 容差 +3，移植 revival_channels）
        weishi = self.cfg.channels.get('weishi') or []
        for w in sorted(weishi, key=len, reverse=True):
            if w in n and len(n) <= len(w) + 3 and w in targets:
                return w
        # 别名关键词兜底（如 翡翠台高清 -> TVB翡翠台）
        for alias, key in sorted(aliases.items(), key=lambda kv: -len(kv[0])):
            if alias in n and len(n) <= len(alias) + 4 and key in targets:
                return key
        return ''

    def _t2s_table(self):
        """繁体->简体字符转换表（惰性构建 + 按映射对象身份缓存）。

        映射外置于 channels.json 的 `t2s_map`；配置热重载会以新 dict 对象替换该值，
        故按对象身份（`is`）失效缓存：既避免每次调用重建 maketrans，又保证热更新即时生效。
        缓存同时持有映射对象引用，杜绝 id 复用导致的旧表误命中。
        """
        mapping = self.cfg.channels.get('t2s_map') or {}
        cache = getattr(self, '_t2s_cache', None)
        if cache is not None and cache[0] is mapping:
            return cache[1]
        table = str.maketrans(mapping) if mapping else {}
        self._t2s_cache = (mapping, table)
        return table

    def _to_simplified(self, name):
        """繁体 -> 简体字符级预转换（域内字符表，非完整 OpenCC）。

        仅做逐字符映射，不做分词/词组；未命中的字符原样保留。表为空时原样返回。
        """
        if not name:
            return name
        table = self._t2s_table()
        if not table:
            return name
        return str(name).translate(table)

    def normalize_channel(self, name):
        """发现层频道名 -> 目标频道键（channels.json 的 channels 之一）；未匹配返回 ''。

        两趟匹配：
        1. 按【原名】走 `_match`（链路/顺序/容差一字不改，零回归）；
        2. 首趟未命中时，把名字做繁体->简体预转换后再走一遍 `_match`。

        第 2 趟把"繁体写法"这一整类漏配一次性消掉（取代往 aliases 手工补繁体条目）。
        转换后与原名相同则直接判未匹配，避免无谓重跑。
        """
        key = self._match(name)
        if key:
            return key
        conv = self._to_simplified(name)
        if conv == name:
            return ''
        return self._match(conv)

    # ---------- URL 归一化 ----------
    @staticmethod
    def normalize_url(url):
        """URL 归一化：strip + 去 fragment + host/scheme 小写；不动 query（鉴权依赖）。

        ARCHITECTURE §7.4 红线。实现已迁至 `app.urlkey`（契约冻结算法唯一事实来源）。
        """
        return urlkey.normalize_url(url)

    @classmethod
    def url_hash(cls, url):
        """sha1(归一化 URL)，sources 表去重【字面】指纹（红线，语义永不变）。"""
        return urlkey.url_hash(url)


    # ---------- 时效签名剥离 = 物理源身份键 ----------
    # 实现已迁至 `app.urlkey`（契约 §2 冻结算法的唯一事实来源）；以下为兼容委托。
    @staticmethod
    def resolve_signed_params(url, settings):
        """合并全局与 host 作用域签名参数名单（契约 §2.2/§2.3，实现见 app.urlkey）。"""
        return urlkey.resolve_signed_params(url, settings)

    @staticmethod
    def strip_signed_params(url, signed_params):
        """剥离 query 中的时效签名参数（契约 §2 不变量见 app.urlkey）。"""
        return urlkey.strip_signed_params(url, signed_params)

    @classmethod
    def url_key(cls, url, signed_params):
        """物理源身份键：sha1(剥离签名后的归一化 URL)（实现见 app.urlkey）。"""
        return urlkey.url_key(url, signed_params)


    # ---------- ClearKey kid 归一化 ----------
    @staticmethod
    def kid_hex(kid):
        """ClearKey kid（base64url 无填充 / UUID / 32 位 hex）归一为 32 位小写 hex。

        DB 的 ``kid`` 列约定为 32 位 hex（``cenc:default_KID`` 风格）；而清单内联密钥的
        ``kid`` 是 base64url。无法解析时返回 ''（调用方据此跳过 DRM 字段，绝不写垃圾）。
        """
        s = (kid or '').strip()
        if not s:
            return ''
        hx = re.sub(r'[^0-9a-fA-F]', '', s)
        if '-' in s and len(hx) == 32:           # UUID 形态
            return hx.lower()
        b = _b64_url_decode16(s)
        if b is not None:                         # base64url -> 16 字节
            return binascii.hexlify(b).decode('ascii')
        if len(hx) == 32:                         # 裸 hex
            return hx.lower()
        return ''

    @staticmethod
    def host(url):
        """提取 URL 的 host（含端口），失败返回前 30 字符（实现见 app.urlkey）。"""
        return urlkey.host(url)


    # ---------- host 评分 ----------
    @staticmethod
    def host_score(h):
        """host 质量分（原样移植 revival_channels.host_score 分段）。

        100=电信网关段（入库前已被 rules.json 拦截，此处保留原值）；
        80/70=已知好 host；0=已知死 host；10=默认。
        """
        h = h or ''
        if h.startswith('39.134') or h.startswith('39.135'):
            return 100
        if any(k in h for k in ('183.207', '140.207', '223.110', '112.17.', '112.50',
                                '111.40', '117.169', '112.30', '120.196')):
            return 80
        if any(k in h for k in ('satellitepull', 'tencentplay', 'myqcloud', 'hidns',
                                'epg.pw', 'gztv.com', 'cztv.com', 'hebtv', 'hljtv',
                                'nmtv.cn', 'lanzou', 'ysp.cctv.cn', 'mgtv')):
            return 70
        if any(k in h for k in ('38.75.136', '107.150.60', '63.141.230', '198.204.228',
                                '204.12.221', '207.56.13', 'bztv.tvbus.cc', 'zby.130519',
                                'live.264788', 'stream1.freetv.fun', 'litv.tzh911',
                                'php.jdshipin', 'hls-gateway.vpstv', 'h5cdn3.kylintv',
                                '7612-5516', 'kwimgs')):
            return 0
        return 10
