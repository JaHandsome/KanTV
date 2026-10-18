# -*- coding: utf-8 -*-
"""Verifier：候选源多级验证（P0-2）。

验证算法逐函数移植 C:\\Users\\A\\AppData\\Local\\Temp\\probe_sources5.py（实测可用，架构红线）：
- parse_ts / ts_judge：TS PMT 流类型 + PES 计数
- mp4_judge：fMP4 盒 + avcC/hvcC 等编码嗅探
- sniff_segment：分片内容判定（嵌套 m3u8 跟随、TS/fMP4/ADTS/大分片码率推断）
- probe_m3u8：master 逐级跟随(<=3)、ENDLIST 点播拒绝、末分片实收 + 首分片重试、MAP init 段补充
- probe_flv：FLV tag 9 视频判定（补上原文件缺失的 import struct）
改动点（仅工程化，不改判定逻辑）：fetch 换 NetClient（超时/并发闸门/UA/异常语义一致）。

run()：按 score DESC 批量取 candidate（24h 内已验证的跳过），ThreadPoolExecutor 并发 probe；
本批结果先收集、再判定、再逐条落库。仅当本批计划结果全部正常收齐且未超时，且 >=3 条全部
为传输层失败（NetResolveError/SSLError/SSLEOFError/URLError/OSError...）时整批熔断——不落库、
冻结 sources/verifications，避免把"本次整体断网"逐源写成 fail 及指数退避。预算时间到即返回。
"""
import json
import re
import struct
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urljoin, urlparse

from app.config import DEFAULT_RULES, DEFAULT_SETTINGS
from app.db import DELIVERABLE_PROFILES, delivery_profile, iso_plus_hours, utcnow
from app.throughput import (THROUGHPUT_FAIL_RATIO, THROUGHPUT_REALTIME_RATIO,
                            is_transport_reason, throughput_window_factor)
from app.logutil import get_logger
from app.parser import ChannelParser

log = get_logger('verifier')

VT = {0x01: 'MPEG1视频', 0x02: 'MPEG2视频', 0x1B: 'H.264视频', 0x24: 'H.265视频', 0x80: '视频(私有)'}
AT = {0x03: 'MP3', 0x04: 'MP3', 0x0F: 'AAC', 0x11: 'AAC(LATM)', 0x81: 'AC3', 0x91: 'AAC'}
VIDEO_CODECS = ('avc1', 'avc3', 'hev1', 'hvc1', 'mp4v', 'vp09', 'av01', 'dvhe', 'dvh1')
AUDIO_CODECS = ('mp4a', 'ac-3', 'ec-3', 'opus', 'flac', 'alac')

# P1-C：失败候选退避 —— 连续 fail >=2 次进入指数冷却（3 天 -> 7 天 -> 30 天）；
# 致命 HTTP 状态码（403 拒绝/404 下线）直接长冷却，短期无恢复意义。
BACKOFF_DAYS = [3, 7, 30]
COOLDOWN_CODES = (403, 404)
COOLDOWN_DAYS = 30

# 探测结果 type -> container（record() 在探针未显式给 container 时的兜底推断）
_CONTAINER_BY_TYPE = {'HLS': 'hls', 'FLV': 'flv', 'DASH': 'dash', 'unsupported': ''}

# --- DASH / ClearKey（HK MPD 路线，HK_MPD_ROUTE_DESIGN §5） ---
# ClearKey 的标准 keyformat UUID（media3 ClearKey 配置用；与 discovery 清单落库同一常量值）
CLEARKEY_KEYFORMAT = 'urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e'
# ContentProtection@schemeIdUri：cenc 通用保护标识（携带 cenc:default_KID）
_MP4PROTECTION = 'urn:mpeg:dash:mp4protection:2011'
# 其他 DRM scheme 识别（schemeIdUri 含 system id 或名字即认；仅用于"裸 MPD"回退分类）
_WIDEVINE_ID = 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed'
_PLAYREADY_ID = '9a04f079-9840-4286-ab92-e65be0885f95'
_CLEARKEY_ID = 'e2719d58-a985-b3c9-781a-b030af78d30e'
# 实测（2026-09-10）：TVB TVB Plus 的 MPD（SegmentTimeline 密集）约 975KB > 设计稿的 512KB，
# 按 512KB 抓取会被截断致 XML 解析失败。MPD 抓取下限抬到 4MB（正文仍很小，仅防截断）。
_MPD_FETCH_LIMIT = 4 * 1024 * 1024


def _fail_result(exc, why_prefix=''):
    """探测异常 -> 失败结果 dict；HTTPError 携带状态码供退避判定（P1-C）。"""
    out = {'alive': False, 'why': why_prefix + type(exc).__name__}
    code = getattr(exc, 'code', None)
    if code is not None:
        out['code'] = int(code)
    return out


def _fetch_ex(net, url, ua, limit):
    """取 (status, body, content_type, final_url)。

    优先用 ``NetClient.fetch_ex``（暴露 302 后的最终 URL —— DASH 分片/init 与 HLS 变体
    的 base 必须用最终落点，否则 302 源全部 403，见 HK_MPD_ROUTE_DESIGN §5 / H1 实测）；
    离线测试桩只实现 ``fetch`` 时退化为 final_url=请求 URL（既有单测行为逐字不变）。
    """
    fx = getattr(net, 'fetch_ex', None)
    if fx is not None:
        st, body, ct, final = fx(url, ua=ua, limit=limit)
        return st, body, ct, (final or url)
    st, body, ct = net.fetch(url, ua=ua, limit=limit)
    return st, body, ct, url


def _tag_local(tag):
    """ElementTree tag -> 去命名空间前缀的本地名（'{ns}MPD' -> 'MPD'）。"""
    return tag.split('}', 1)[1] if '}' in tag else tag


def _iter_local(root, name):
    """迭代本地名 == name 的全部元素（忽略命名空间前缀）。"""
    for e in root.iter():
        if _tag_local(e.tag) == name:
            yield e


def _attr_local(elem, name):
    """取元素属性（忽略命名空间前缀：``cenc:default_KID`` 与 ``default_KID`` 等价）。"""
    if elem is None:
        return None
    if name in elem.attrib:
        return elem.attrib[name]
    for k, v in elem.attrib.items():
        if k.split('}', 1)[-1] == name:
            return v
    return None


def _direct_baseurl(elem):
    """取元素【直接子级】BaseURL 文本（去空白）；无则 None。

    DASH 寻址链：MPD -> Period -> AdaptationSet -> Representation 各层 BaseURL 逐级拼接。
    实测 TVB 的 BaseURL（如 ``6/`` ``8/``）在 **Period** 层，缺它则 init/分片 404。
    """
    if elem is None:
        return None
    for ch in list(elem):
        if _tag_local(ch.tag) == 'BaseURL' and (ch.text or '').strip():
            return ch.text.strip()
    return None


# =====================================================================================
# P0 收口 R1：纯 Python AES-128-CBC（标准库无 AES，D5 禁新增第三方依赖）
# 参考：FIPS-197（AES 分组密码）、RFC 8216 §4.3.2.4/§5.2（HLS 加密与 IV 推导）
# =====================================================================================
_AES_SBOX_HEX = (
    '637c777bf26b6fc53001672bfed7ab76'
    'ca82c97dfa5947f0add4a2af9ca472c0'
    'b7fd9326363ff7cc34a5e5f171d83115'
    '04c723c31896059a071280e2eb27b275'
    '09832c1a1b6e5aa0523bd6b329e32f84'
    '53d100ed20fcb15b6acbbe394a4c58cf'
    'd0efaafb434d338545f9027f503c9fa8'
    '51a3408f929d38f5bcb6da2110fff3d2'
    'cd0c13ec5f974417c4a77e3d645d1973'
    '60814fdc222a908846eeb814de5e0bdb'
    'e0323a0a4906245cc2d3ac629195e479'
    'e7c8376d8dd54ea96c56f4ea657aae08'
    'ba78252e1ca6b4c6e8dd741f4bbd8b8a'
    '703eb5664803f60e613557b986c11d9e'
    'e1f8981169d98e949b1e87e9ce5528df'
    '8ca1890dbfe6426841992d0fb054bb16')
_AES_SBOX = bytes.fromhex(_AES_SBOX_HEX)
# 逆 S-box 由正 S-box 反查生成（避免手抄出错）
_inv = bytearray(256)
for _i, _v in enumerate(_AES_SBOX):
    _inv[_v] = _i
_AES_INV_SBOX = bytes(_inv)
del _inv, _i, _v
_AES_RCON = (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36)


def _gf_mul(a, b):
    """GF(2^8) 乘法（模不可约多项式 0x11b，供 InvMixColumns 使用）。"""
    p = 0
    for _ in range(8):
        if b & 1:
            p ^= a
        hi = a & 0x80
        a = (a << 1) & 0xFF
        if hi:
            a ^= 0x1B
        b >>= 1
    return p


def _expand_key(key):
    """AES-128 key expansion：返回 11 个 16 字节轮密钥（11 轮，FIPS-197 §5.2）。

    key 必须 16 字节（否则抛 ValueError）。轮密钥字节序与状态列序对齐，
    故第 r 轮密钥 = w[4r..4r+3] 依次拼接。
    """
    if len(key) != 16:
        raise ValueError('AES-128 key 必须为 16 字节 (got %d)' % len(key))
    nk, nr = 4, 10
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    for i in range(nk, 4 * (nr + 1)):
        temp = list(w[i - 1])
        if i % nk == 0:
            temp = temp[1:] + temp[:1]                 # RotWord
            temp = [_AES_SBOX[b] for b in temp]        # SubWord
            temp[0] ^= _AES_RCON[i // nk - 1]
        w.append([w[i - nk][j] ^ temp[j] for j in range(4)])
    rk = []
    for r in range(nr + 1):
        blk = bytearray()
        for c in range(4):
            blk += bytes(w[4 * r + c])
        rk.append(bytes(blk))
    return rk


def _add_round_key(state, rk):
    """就地 AddRoundKey：state 与轮密钥逐字节异或。"""
    for i in range(16):
        state[i] ^= rk[i]


def _inv_shift_rows(state):
    """InvShiftRows：第 r 行循环右移 r 字节（列主序扁平下标 = r + 4*c）。"""
    out = list(state)
    for r in range(4):
        for c in range(4):
            out[r + 4 * c] = state[r + 4 * ((c - r) % 4)]
    return out


def _inv_mix_columns(state):
    """InvMixColumns：每列左乘固定多项式 {0e,0b,0d,09}（就地）。"""
    for c in range(4):
        a0, a1, a2, a3 = state[4 * c], state[4 * c + 1], state[4 * c + 2], state[4 * c + 3]
        state[4 * c] = _gf_mul(a0, 14) ^ _gf_mul(a1, 11) ^ _gf_mul(a2, 13) ^ _gf_mul(a3, 9)
        state[4 * c + 1] = _gf_mul(a0, 9) ^ _gf_mul(a1, 14) ^ _gf_mul(a2, 11) ^ _gf_mul(a3, 13)
        state[4 * c + 2] = _gf_mul(a0, 13) ^ _gf_mul(a1, 9) ^ _gf_mul(a2, 14) ^ _gf_mul(a3, 11)
        state[4 * c + 3] = _gf_mul(a0, 11) ^ _gf_mul(a1, 13) ^ _gf_mul(a2, 9) ^ _gf_mul(a3, 14)


def _aes_decrypt_block(block, round_keys):
    """单块 AES-128 解密（FIPS-197 InvCipher）。

    AddRoundKey(rk10) -> 9×(InvShiftRows/InvSubBytes/AddRoundKey/InvMixColumns)
    -> InvShiftRows/InvSubBytes/AddRoundKey(rk0)。
    """
    if len(block) != 16:
        raise ValueError('AES 分组必须为 16 字节 (got %d)' % len(block))
    s = list(block)
    _add_round_key(s, round_keys[10])
    for rnd in range(9, 0, -1):
        s = _inv_shift_rows(s)
        s = [_AES_INV_SBOX[b] for b in s]
        _add_round_key(s, round_keys[rnd])
        _inv_mix_columns(s)
    s = _inv_shift_rows(s)
    s = [_AES_INV_SBOX[b] for b in s]
    _add_round_key(s, round_keys[0])
    return bytes(s)


def _pkcs7_unpad(data):
    """去除 PKCS7 填充（仅整段解密时使用；前缀解密不得去填充，见 §6.3）。"""
    if not data:
        return data
    pad = data[-1]
    if 1 <= pad <= 16 and data[-pad:] == bytes([pad]) * pad:
        return data[:-pad]
    return data


def _aes_cbc_decrypt(ciphertext, key, iv, max_bytes=None):
    """纯 Python AES-128-CBC 解密（§6.3）。

    - key/iv 必须 16 字节、密文长度必须 16 字节对齐，否则抛 ValueError；
    - max_bytes 非 None 时**仅解密前 ceil(max_bytes/16) 个块**：CBC 链式要求从块 0
      顺序解密（无法只解第 k 块），且**不做 PKCS7 去填充**（分片真正的 padding 只在
      整段末尾，前缀去填充会破坏 TS/fMP4 结构）；
    - max_bytes 为 None 时解密全段并做 PKCS7 去填充。
    """
    if len(key) != 16:
        raise ValueError('AES-128 key 必须为 16 字节')
    if len(iv) != 16:
        raise ValueError('IV 必须为 16 字节')
    if len(ciphertext) % 16 != 0:
        raise ValueError('密文长度必须 16 字节对齐')
    rk = _expand_key(key)
    total = len(ciphertext) // 16
    if max_bytes is None:
        nblocks = total
    else:
        nblocks = min((int(max_bytes) + 15) // 16, total)
    out = bytearray()
    prev = iv
    for i in range(nblocks):
        blk = ciphertext[i * 16:(i + 1) * 16]
        dec = _aes_decrypt_block(blk, rk)
        out += bytes(a ^ b for a, b in zip(dec, prev))
        prev = blk
    if max_bytes is None:
        return _pkcs7_unpad(bytes(out))
    return bytes(out[:int(max_bytes)])


def _parse_hls_iv(iv_str):
    """HLS 显式 IV（`0x` + 32 位十六进制）-> 16 字节；缺失/非法返回 None。"""
    if not iv_str:
        return None
    s = iv_str.strip()
    if s[:2].lower() == '0x':
        s = s[2:]
    if len(s) != 32:
        return None
    try:
        return bytes.fromhex(s)
    except ValueError:
        return None


def _derive_hls_iv(media_sequence, seg_index, explicit_iv):
    """HLS IV 处理（RFC 8216 §5.2）：显式 IV 优先；缺省用
    (media_sequence + seg_index) 的 128-bit 大端表示（前导补零）。"""
    if explicit_iv is not None:
        return explicit_iv
    return (int(media_sequence) + int(seg_index)).to_bytes(16, 'big')


# ---------- #EXT-X-KEY 解析（P0 R1，纯函数、零依赖） ----------
_EXT_X_KEY_ATTR_RE = re.compile(r'([A-Za-z0-9-]+)=("[^"]*"|[^,]*)')


def parse_ext_x_key(playlist_text):
    """单次遍历媒体清单，返回【与分片下标对齐】的密钥视图（RFC 8216 §4.3.2.4）。

    修正 PRD FR-1 "以最后一个 KEY 为准" 的错误口径（设计 §8-⑧）：#EXT-X-KEY 只作用于
    其后所有分片，直到下一个 KEY；METHOD=NONE 之后的分片 keys[i]=None。逐行扫描，
    遇非注释行（分片 URI）即把"当前生效 KEY"追加进 keys，保证 keys 与 segs 同序同长。

    返回：{'keys':list[dict|None], 'segs':list[str], 'media_sequence':int, 'all':list[dict]}。
    每个 KEY dict：{'method','uri','iv','keyformat','keyformatversions'}。
    """
    segs, keys, all_keys = [], [], []
    media_sequence = 0
    current = None
    for raw in (playlist_text or '').splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith('#EXT-X-KEY'):
            body = line.split(':', 1)[1] if ':' in line else ''
            attrs = {}
            for m in _EXT_X_KEY_ATTR_RE.finditer(body):
                name = m.group(1).upper()
                val = m.group(2)
                if len(val) >= 2 and val[0] == '"' and val[-1] == '"':
                    val = val[1:-1]
                attrs[name] = val
            key = {
                'method': (attrs.get('METHOD') or '').strip(),
                'uri': (attrs.get('URI') or '').strip(),
                'iv': (attrs.get('IV') or '').strip(),
                'keyformat': (attrs.get('KEYFORMAT') or '').strip(),
                'keyformatversions': (attrs.get('KEYFORMATVERSIONS') or '').strip(),
            }
            all_keys.append(key)
            current = None if key['method'].upper() == 'NONE' else key
        elif line.startswith('#EXT-X-MEDIA-SEQUENCE'):
            try:
                media_sequence = int(line.split(':', 1)[1].strip())
            except (IndexError, ValueError):
                media_sequence = 0
        elif not line.startswith('#'):
            segs.append(line)
            keys.append(current)
    return {'keys': keys, 'segs': segs, 'media_sequence': media_sequence, 'all': all_keys}


# DRM KEYFORMAT 识别：既有可读子串（widevine / playready），也有标准 UUID 形态
# （HLS KEYFORMAT 实际下发的是 urn:uuid:...，不含可读名）。
_WIDEVINE_HINTS = ('widevine', 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed')
_PLAYREADY_HINTS = ('playready', '9a04f079-9840-4286-ab92-e65be0885f95')


def classify_encryption(key):
    """把"作用于被测分片的那一条 KEY"归约为 (encryption, drm, keyformat)。

    key=None / METHOD=NONE           -> ('', '', '')
    AES-128                          -> ('aes128', '', kf)            # 密钥在清单内，非 DRM
    SAMPLE-AES + identity/缺省 kf    -> ('sample-aes', '', kf)        # D1：客户端暂不支持
    SAMPLE-AES(-CTR) + widevine urn  -> ('cenc'|'cbcs', 'widevine', kf)
    SAMPLE-AES(-CTR) + playready urn -> ('cenc'|'cbcs', 'playready', kf)
    约定：SAMPLE-AES -> cbcs（AES-CBC 模式），SAMPLE-AES-CTR -> cenc（AES-CTR）。
    """
    if not key:
        return ('', '', '')
    method = (key.get('method') or '').upper()
    kf = key.get('keyformat') or ''
    if method in ('', 'NONE'):
        return ('', '', '')
    if method == 'AES-128':
        return ('aes128', '', kf)
    if method in ('SAMPLE-AES', 'SAMPLE-AES-CTR'):
        kfl = kf.lower()
        if not kfl or 'identity' in kfl:
            return ('sample-aes', '', kf)
        enc = 'cenc' if method == 'SAMPLE-AES-CTR' else 'cbcs'
        if any(h in kfl for h in _WIDEVINE_HINTS):
            return (enc, 'widevine', kf)
        if any(h in kfl for h in _PLAYREADY_HINTS):
            return (enc, 'playready', kf)
        return (enc, 'cenc-unknown', kf)
    return ('', '', kf)


# ---------- 能力门禁（P0 R4，纯函数） ----------
def build_caps_required(container, encryption, drm):
    """源侧客观事实 -> 客户端能力需求（"播这个源需要客户端具备什么"）。

    ('hls','sample-aes','')     -> {'hls_sample_aes': True}
    ('hls','aes128','')         -> {}            # media3 原生即支持，无需求
    ('dash','cenc','clearkey')  -> {'dash_clearkey': True}
    ('hls','cenc','widevine')   -> {'hls_cenc': True, 'widevine': True}
    """
    req = {}
    enc = (encryption or '').lower()
    drm = (drm or '').lower()
    if enc == 'sample-aes':
        req['hls_sample_aes'] = True
    elif enc in ('cenc', 'cbcs'):
        if container == 'hls':
            req['hls_cenc'] = True
        if drm == 'clearkey':
            req['dash_cbcs_clearkey' if enc == 'cbcs' else 'dash_clearkey'] = True
        if drm == 'widevine':
            req['widevine'] = True
        if drm == 'playready':
            req['playready'] = True
    return req


def check_caps(caps_required, client_caps):
    """能力门禁：返回 (allowed, missing_key)。

    保守失败：client_caps 非 dict / 缺 'version' / 缺 'caps' -> (False,'caps_config_invalid')
    并记 ERROR；caps_required 为空（明文/AES-128）恒通过，明文源不受配置缺失影响。
    否则逐项检查 caps_required ⊆ client_caps['caps']，返回首个缺失（或 false）键。
    """
    if not caps_required:
        return (True, '')
    if (not isinstance(client_caps, dict) or 'version' not in client_caps
            or not isinstance(client_caps.get('caps'), dict)):
        log.error('客户端能力集缺失或非法(client_caps), 保守失败: 不判 ok, 加密源转 need_client_cap')
        return (False, 'caps_config_invalid')
    caps = client_caps['caps']
    for k in sorted(caps_required):
        if not caps.get(k):
            return (False, k)
    return (True, '')


def _settings_from_net(net):
    """从 NetClient 取 settings（离线 FakeNet 无 cfg 时返回空 dict）。"""
    cfg = getattr(net, 'cfg', None)
    s = getattr(cfg, 'settings', None)
    return s if isinstance(s, dict) else {}


def _rules_from_net(net):
    """从 NetClient 取 rules（离线 FakeNet 无 cfg 时返回空 dict）。"""
    cfg = getattr(net, 'cfg', None)
    r = getattr(cfg, 'rules', None)
    return r if isinstance(r, dict) else {}


def _is_signed_url(url, params):
    """URL query 参数名做【大小写不敏感 + 精确匹配】（禁子串，否则 sign 命中 design）。

    明确不含 key/authid（静态访问 key，非时效签名，见设计 §4.1）。
    """
    wanted = {str(p).lower() for p in (params or [])}
    if not wanted:
        return False
    query = urlparse(url or '').query
    names = {part.split('=', 1)[0].lower() for part in query.split('&') if part}
    return bool(names & wanted)


def _resolve_aes(net, base, ua, key, media_sequence, seg_index, probe_bytes):
    """解析 AES-128 密钥并构造 sniff_segment 的 aes 参数。

    返回 (aes_arg|None, key_present: bool, err: str|None)。密钥经 net.fetch 获取
    （红线①）；相对 URI 用 urljoin(媒体清单 URL, uri)。密钥不可达 / 长度 != 16 ->
    返回 err（上层判 abnormal，不惩罚源）。
    """
    if not key or not key.get('uri'):
        return None, False, 'key_unreachable'
    uri = key['uri']
    kurl = urljoin(base, net.enc(uri) if not uri.startswith('http') else uri)
    try:
        _st, kdata, _ct = net.fetch(kurl, ua=ua, limit=64)
    except Exception:  # noqa: BLE001 - 密钥不可达不抛异常，交上层判 abnormal
        return None, False, 'key_unreachable'
    if len(kdata) != 16:
        return None, False, 'key_unreachable'
    iv = _derive_hls_iv(media_sequence, seg_index, _parse_hls_iv(key.get('iv')))
    return {'key': bytes(kdata), 'iv': iv, 'max_bytes': int(probe_bytes)}, True, None


def parse_ts(data):
    """解析 TS：返回 (PMT流类型描述列表, 视频PES计数, 音频PES计数)。"""
    types, vids, auds = [], 0, 0
    pmt_pid = None
    packets = [data[i:i + 188] for i in range(0, min(len(data), 376000) - 187, 188)]
    # PAT
    for p in packets[:400]:
        if len(p) < 188 or p[0] != 0x47:
            continue
        pid = ((p[1] & 0x1F) << 8) | p[2]
        if pid != 0:
            continue
        af = (p[3] >> 4) & 3
        off = 4 + (int.from_bytes(p[4:5], 'big') + 1 if af & 2 else 0)
        if af & 1 == 0:
            continue
        body = p[off + 1:]
        i = 0
        while i + 7 < len(body):
            prog = int.from_bytes(body[i:i + 2], 'big')
            if prog:
                pmt_pid = ((body[i + 2] & 0x1F) << 8) | body[i + 3]
                break
            i += 4
        break
    # PMT
    if pmt_pid:
        for p in packets[:600]:
            if len(p) < 188 or p[0] != 0x47:
                continue
            pid = ((p[1] & 0x1F) << 8) | p[2]
            if pid != pmt_pid:
                continue
            af = (p[3] >> 4) & 3
            off = 4 + (int.from_bytes(p[4:5], 'big') + 1 if af & 2 else 0)
            if not (af & 1):
                continue
            sec = p[off + 1:]
            if not sec or sec[0] != 0x02:
                continue
            pil = ((sec[9] & 0x0F) << 8) | sec[10]
            i = 12 + pil
            end = len(sec) - 4
            while i + 5 <= end:
                st = sec[i]
                epid = ((sec[i + 1] & 0x1F) << 8) | sec[i + 2]
                esil = ((sec[i + 3] & 0x0F) << 8) | sec[i + 4]
                if st in VT:
                    types.append(VT[st])
                elif st in AT:
                    types.append(AT[st])
                i += 5 + esil
            if types:
                break
    # PES 计数
    d = data
    i = 0
    while i < len(d) - 3:
        if d[i] == 0 and d[i + 1] == 0 and d[i + 2] == 1:
            sid = d[i + 3]
            if 0xE0 <= sid <= 0xEF:
                vids += 1
                i += 3
            elif 0xC0 <= sid <= 0xDF:
                auds += 1
                i += 3
            else:
                i += 3
        else:
            i += 1
    return types, vids, auds


def ts_judge(data):
    """TS 分片判定：video(TS:...)/audio-only(TS:...)/TS未判定。"""
    if len(data) < 188:
        return '分片过小(%dB)' % len(data)
    types, vids, auds = parse_ts(data)
    v = [t for t in types if '视频' in t]
    a = [t for t in types if t not in v]
    if v:
        return 'video(TS:%s)' % ','.join(sorted(set(v)))
    if types and not v and a:
        return 'audio-only(TS:%s)' % ','.join(sorted(set(a)))
    if vids >= 3:
        return 'video(TS:PES视频%d处)' % vids
    if auds >= 10 and vids < 3:
        return 'audio-only(TS:音频PES%d/视频%d)' % (auds, vids)
    if auds >= 3 and vids == 0:
        return 'audio-only(TS:PES音频%d处)' % auds
    return 'TS未判定(v%d/a%d)' % (vids, auds)


def mp4_judge(data):
    """fMP4/mp4 分片判定：盒类型 + 编码 box 嗅探。"""
    low = data
    if (len(data) > 8 and data[4:8] in (b'ftyp', b'styp', b'moof'))\
            or (len(data) > 4 and data[0:4] == b'ftyp') or b'moof' in data[:4096]:
        box = data[4:8]
    else:
        box = None
    kinds = set()
    for pat, k in ((b'avcC', 'H.264'), (b'hvcC', 'H.265'), (b'hev1', 'H.265'),
                   (b'hvc1', 'H.265'), (b'mp4a', 'AAC'), (b'Opus', 'Opus'), (b'ac-3', 'AC3')):
        if pat in low:
            kinds.add(k)
    vid = [k for k in kinds if k in ('H.264', 'H.265')]
    aud = [k for k in kinds if k in ('AAC', 'Opus', 'AC3')]
    if box:
        tag = 'fMP4(%s)' % box.decode('ascii', 'ignore')
    else:
        tag = 'mp4碎片'
    if vid:
        return 'video(%s:%s)' % (tag, ','.join(vid))
    if aud and not vid:
        return 'audio-only(%s:%s)' % (tag, ','.join(aud))
    return '%s:未知编码' % tag


def sniff_segment(net, url, ua, depth=0, limit=393216, aes=None):
    """分片内容嗅探：嵌套 m3u8 跟随 + TS/fMP4/ADTS/大分片判定。

    P1-H：limit 从配置 probe_fetch_limit 传入（原硬编码 393216）。
    P0 R1：aes={'key':bytes,'iv':bytes,'max_bytes':int} 时先取密文，仅解密前
    ceil(max_bytes/16) 个块（不去填充）后交现有 ts_judge()/mp4_judge() 判编码；
    长度非对齐/解密失败 -> 返回 'aes-undecryptable:<原因>'（不抛异常）。
    """
    try:
        _st, data, ct = net.fetch(url, ua=ua, limit=limit)
    except Exception as e:  # noqa: BLE001
        return 'sniff-fail:' + type(e).__name__
    if aes:
        try:
            if len(data) < 16 or len(data) % 16 != 0:
                return 'aes-undecryptable:not-aligned'
            data = _aes_cbc_decrypt(data, aes['key'], aes['iv'],
                                    max_bytes=aes.get('max_bytes'))
        except (ValueError, KeyError, TypeError) as e:  # noqa: BLE001
            return 'aes-undecryptable:%s' % (e,)
    if len(data) < 1000:
        if data.startswith(b'#EXTM3U'):
            if depth >= 4:
                return '嵌套过深'
            lines = [l.strip() for l in data.decode('utf-8', 'ignore').splitlines() if l.strip()]
            uri = next((x for x in lines if not x.startswith('#')), None)
            if not uri:
                return '嵌套空列表'
            nu = urljoin(url, net.enc(uri) if not uri.startswith('http') else uri)
            r = sniff_segment(net, nu, ua, depth + 1, limit=limit)
            return r + '(嵌套%d)' % (depth + 1) if not r.endswith(')') else r + '|嵌套%d' % (depth + 1)
        if len(data) < 100:
            return '分片过小(%dB)' % len(data)
        if b'<!DOCTYPE' in data or b'<html' in data.lower():
            return 'html页'
        return '分片过小(%dB,%s)' % (len(data), ct[:18])
    if data[:3] == b'ID3' or (data[0] == 0xFF and (data[1] & 0xE0) == 0xE0):
        return 'audio-only(ADTS/MP3)'
    if data[:4] == b'\x1a\x45\xdf\xa3':
        return 'webm/mkv'
    if (len(data) > 12 and data[4:8] in (b'ftyp', b'styp'))\
            or b'moof' in data[:8192] or b'styp' in data[:8192]:
        j = mp4_judge(data)
        if '未知' in j and len(data) >= 300000:
            return 'video(大分片%dKB,码率推断)' % (len(data) // 1024)
        return j
    if len(data) > 189 and data[0] == 0x47 and data[188] == 0x47:
        return ts_judge(data)
    hits = sum(1 for o in range(0, min(len(data), 1880) - 188, 188) if data[o] == 0x47)
    if hits >= 8:
        return ts_judge(data)
    j = mp4_judge(data)
    if '未知' not in j:
        return j
    if len(data) >= 300000:
        return 'video(大分片%dKB,码率推断)' % (len(data) // 1024)
    return '未知内容(%dB,%s)' % (len(data), ct[:18])


def probe_m3u8(net, url, ua, limit=393216, aes_probe_bytes=None):
    """HLS 探测：master 跟随 -> ENDLIST 拒绝 -> 分片实收 + 媒体级判定 + 加密判定。

    P1-H：列表/分片 fetch limit 从配置 probe_fetch_limit 传入；
    init 段维持 131072 常量（moov 盒很小，无需跟随主 limit）。
    P0 R1：解析 #EXT-X-KEY（与分片下标对齐）-> classify_encryption -> AES-128 取密钥
    解密后嗅探。关键：末分片失败回退首分片时必须改用 keys[0]（设计 §8-⑧）。
    结果新增键：encryption / drm / keyformat / key_present(bool) / kid('')。
    """
    req_url = net.enc(url) if re.search(r'[^\x00-\x7f]', url) else url
    try:
        _st, pl, _ct, final = _fetch_ex(net, req_url, ua, limit)
    except Exception as e:  # noqa: BLE001
        return _fail_result(e)
    if not pl.startswith(b'#EXTM3U'):
        return {'alive': False, 'why': '非m3u8'}
    # ★ base 必须用【最终 URL】（302 落点）：否则带会话/签名的重定向源分片全部 403。
    cur, depth, codecs_all = (final or url), 0, []
    # 循环检测：请求 URL 与 _fetch_ex 的 final_url 都计入（自引用 / A->B->A）
    seen = {req_url, cur}
    while b'#EXT-X-STREAM-INF' in pl:
        if depth >= 3:
            # 3 次下行后正文仍是 master：fail-closed，绝不把嵌套 master 当媒体列表
            return {'alive': False, 'why': 'master嵌套过深'}
        lines = [l.strip() for l in pl.decode('utf-8', 'ignore').splitlines() if l.strip()]
        # 每层重新收集该层变体：只取该层首个合法变体（不做兄弟变体失败回退）
        sel_uri = None
        sel_codecs = None
        for i, l in enumerate(lines):
            if not l.startswith('#EXT-X-STREAM-INF'):
                continue
            uri = None
            for x in lines[i + 1:]:
                if x.startswith('#EXT-X-STREAM-INF'):
                    break       # URI 必须归属本 STREAM-INF，不得借用后续兄弟的 URI
                if not x.startswith('#'):
                    uri = x
                    break
            if uri:
                sel_uri = uri
                m = re.search(r'CODECS="([^"]*)"', l)
                sel_codecs = m.group(1) if m else None
                break
        if sel_uri is None:
            return {'alive': False, 'why': 'master无变体'}
        # CODECS 只收集被选中分支的声明；同层未选兄弟不混入
        if sel_codecs:
            codecs_all.append(sel_codecs)
        nxt = urljoin(cur, net.enc(sel_uri) if not sel_uri.startswith('http') else sel_uri)
        if nxt in seen:
            return {'alive': False, 'why': 'master循环'}
        seen.add(nxt)
        try:
            _st, pl, _ct, final = _fetch_ex(net, nxt, ua, limit)
        except Exception as e:  # noqa: BLE001
            return _fail_result(e, '变体:')
        if not pl.startswith(b'#EXTM3U'):
            return {'alive': False, 'why': '变体非m3u8'}
        # 每级跟随都更新 base 到该级的最终 URL（多级 master 同样可能 302）
        cur = final or nxt
        if cur != nxt and cur in seen:
            return {'alive': False, 'why': 'master循环'}
        seen.add(cur)
        depth += 1
    if b'#EXT-X-ENDLIST' in pl:
        return {'alive': False, 'why': '点播(ENDLIST)'}
    # 跨层 CODECS 冲突：只认所选链上最后一个非空声明；codecs 输出仍为所选链声明列表
    last_codecs = codecs_all[-1] if codecs_all else ''
    has_v = any(vc in last_codecs for vc in VIDEO_CODECS) if codecs_all else None
    has_a = any(ac in last_codecs for ac in AUDIO_CODECS) if codecs_all else None

    text = pl.decode('utf-8', 'ignore')
    kv = parse_ext_x_key(text)
    segs = kv['segs']
    keys = kv['keys']
    media_seq = kv['media_sequence']
    mapm = re.search(r'#EXT-X-MAP:URI="([^"]+)"', text)
    if not segs:
        return {'alive': False, 'why': '媒体列表无分片'}

    probe_bytes = aes_probe_bytes
    if probe_bytes is None:
        probe_bytes = int(_settings_from_net(net).get(
            'aes_probe_bytes', DEFAULT_SETTINGS['aes_probe_bytes']))

    def _seg_url(u):
        return urljoin(cur, net.enc(u) if not u.startswith('http') else u)

    # 被测分片 = 末分片；KEY 必须取【该分片下标对应】的 keys[idx]（RFC 8216 §4.3.2.4）
    idx = len(segs) - 1
    key = keys[idx] if idx < len(keys) else None
    enc, drm, kf = classify_encryption(key)
    key_present = False
    aes_arg = None
    if enc == 'aes128':
        aes_arg, key_present, err = _resolve_aes(net, cur, ua, key, media_seq, idx, probe_bytes)
        if err:
            # 密钥不可达/长度≠16 -> abnormal（不惩罚源，不得判 fail，见 FR-1 边界条件）
            return {'alive': False, 'why': 'key_unreachable',
                    'verdict_reason': 'key_unreachable',
                    'encryption': 'aes128', 'drm': '', 'keyformat': kf,
                    'key_present': False, 'kid': '', 'container': 'hls', 'type': 'HLS'}
    elif enc == 'sample-aes':
        # 密钥在清单内可自取（P0 不解密），可播性由客户端能力门禁决定
        key_present = bool(key and key.get('uri'))
    # cenc/cbcs（含 widevine/playready）：license 场景 P0 恒取不到密钥 -> key_present False

    sn = sniff_segment(net, _seg_url(segs[-1]), ua, limit=limit, aes=aes_arg)
    if (sn.startswith('sniff-fail') or sn.startswith('aes-undecryptable')) and len(segs) > 1:
        # 末分片失败回退首分片：必须改用 keys[0] 对应的密钥（若仍用 keys[-1] 会解出乱码，
        # 把健康源误判为不可用，见设计 §8-⑧）
        key0 = keys[0] if len(keys) > 0 else None
        enc, drm, kf = classify_encryption(key0)
        aes_arg0 = None
        key_present0 = False
        if enc == 'aes128':
            aes_arg0, key_present0, _err0 = _resolve_aes(
                net, cur, ua, key0, media_seq, 0, probe_bytes)
        elif enc == 'sample-aes':
            key_present0 = bool(key0 and key0.get('uri'))
        sn = sniff_segment(net, _seg_url(segs[0]), ua, limit=limit, aes=aes_arg0) + '(重试)'
        key_present = key_present0
    if mapm:
        try:
            _st2, init, _ct2, _f2 = _fetch_ex(net, _seg_url(mapm.group(1)), ua, 131072)
            j = mp4_judge(init)
            if 'video' in j:
                sn = j + '+init段'
            elif 'audio-only' in j and not sn.startswith('video'):
                sn = j + '+init段'
        except Exception:  # noqa: BLE001 - init 段失败不影响主判定
            pass
    out = {'alive': True, 'note': sn, 'codecs': codecs_all,
           'encryption': enc, 'drm': drm, 'keyformat': kf,
           'key_present': key_present, 'kid': '', 'container': 'hls'}
    if codecs_all and has_v:
        out['av'] = 'video(CODECS)'
    elif codecs_all and has_a and not has_v:
        out['av'] = 'audio-only(CODECS)'
    elif sn.startswith('video'):
        out['av'] = 'video'
    elif sn.startswith('audio-only'):
        out['av'] = 'audio-only'
    elif sn.startswith('分片过小') or sn.startswith('sniff-fail') or sn.startswith('html'):
        out['av'] = '不稳定'
    elif sn.startswith('aes-undecryptable'):
        # 密钥可取但解不出（内容/密钥不符）-> 未判定（record 判 abnormal，不得判 fail）
        out['av'] = '未判定(' + sn[:40] + ')'
        out['verdict_reason'] = 'aes_undecryptable'
    else:
        out['av'] = '未判定(' + sn[:40] + ')'
    return out


def probe_dash(net, url, ua, limit=524288, expect_drm=None, expect_kid=None):
    """DASH/MPD 探测（最小子集，HK_MPD_ROUTE_DESIGN §5）。

    流程（§5.2）：fetch MPD -> 断言含 ``<MPD`` -> 解析 ``MPD@type``（``static`` 判"点播
    不上线"，等价 HLS 的 ENDLIST 拒绝）-> 首个 Period 中 ``contentType="video"``（兼容
    ``mimeType="video/mp4"``）的 AdaptationSet -> 解析 ``ContentProtection`` 取
    ``cenc:default_KID`` -> 按 ``SegmentTemplate@initialization`` 构 init URL（以 MPD 的
    **final_url** 为 base）-> 复用既有 ``mp4_judge()`` 判编码（**不改其判定算法**）。

    ★ DRM 分类"清单优先"（§5.3）：``expect_drm='clearkey'``（来自清单 license_type）时
    **以清单为准**，输出 ``drm='clearkey'`` / ``keyformat=CLEARKEY_KEYFORMAT`` /
    ``encryption='cenc'``，**不因 MPD 存在 Widevine/PlayReady 节点而降级**；前置条件是
    ``default_KID`` 存在且非全零，缺失 -> ``cenc-unknown`` -> abnormal（保守）。
    ``expect_drm=None``（未走清单的裸 MPD）时退回按 MPD 声明分类，且**不得**据
    mp4protection 直接判 clearkey。kid 一致性护栏（§5.4）：``expect_kid`` 非空须等于
    ``default_KID``（都归一成 32 位 hex），不等 -> abnormal('kid_mismatch')。

    返回 dict（键名与 HLS 路径对齐，供 record() 复用）。
    """
    try:
        # MPD 抓取下限 4MB：TVB 某些 MPD（SegmentTimeline 密集）>512KB，按调用方 limit 会截断
        _st, body, _ct, final = _fetch_ex(
            net, net.enc(url) if re.search(r'[^\x00-\x7f]', url) else url, ua,
            max(limit, _MPD_FETCH_LIMIT))
    except Exception as e:  # noqa: BLE001
        r = _fail_result(e)
        r.update(container='dash', type='DASH')
        return r
    if b'<MPD' not in body:
        return {'alive': False, 'why': '非MPD', 'container': 'dash', 'type': 'DASH'}
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return {'alive': False, 'why': 'MPD解析失败', 'container': 'dash', 'type': 'DASH'}

    mpd_type = (_attr_local(root, 'type') or '').lower()
    if mpd_type != 'dynamic':
        # static（点播）不上线；abnormal（不惩罚源，等同 HLS ENDLIST 拒绝）
        return {'alive': False, 'why': '点播(%s)' % (mpd_type or 'unknown'),
                'verdict_reason': 'vod(static)', 'container': 'dash', 'type': 'DASH'}

    periods = list(_iter_local(root, 'Period'))
    if not periods:
        return {'alive': False, 'why': '无Period', 'verdict_reason': 'no_period',
                'container': 'dash', 'type': 'DASH'}
    period = periods[0]

    adapt = None
    for a in _iter_local(period, 'AdaptationSet'):
        ct = (_attr_local(a, 'contentType') or '').lower()
        mt = (_attr_local(a, 'mimeType') or '').lower()
        if ct == 'video' or mt == 'video/mp4':
            adapt = a
            break
    if adapt is None:
        return {'alive': False, 'why': '无视频轨道', 'verdict_reason': 'no_video',
                'container': 'dash', 'type': 'DASH'}

    # ---- ContentProtection 解析（AdaptationSet + 其 Representation 下） ----
    default_kid_raw = None
    enc_value = 'cenc'
    declared = set()
    for cp in _iter_local(adapt, 'ContentProtection'):
        low = (_attr_local(cp, 'schemeIdUri') or '').lower()
        if 'mp4protection' in low:
            v = (_attr_local(cp, 'value') or 'cenc').lower()
            enc_value = v or 'cenc'
            dk = _attr_local(cp, 'default_KID')
            if dk:
                default_kid_raw = dk
        if _CLEARKEY_ID in low or 'clearkey' in low:
            declared.add('clearkey')
        if _WIDEVINE_ID in low or 'widevine' in low:
            declared.add('widevine')
        if _PLAYREADY_ID in low or 'playready' in low:
            declared.add('playready')

    kid = ChannelParser.kid_hex(default_kid_raw) if default_kid_raw else ''
    if kid and set(kid) == {'0'}:
        kid = ''                       # 全零 default_KID 视为缺失

    if expect_drm == 'clearkey':
        # 清单优先：先过 kid 一致性护栏，再据 default_KID 存在性分流
        want = ChannelParser.kid_hex(expect_kid) if expect_kid else ''
        if want and (not kid or kid != want):
            return {'alive': False, 'why': 'kid不一致', 'verdict_reason': 'kid_mismatch',
                    'container': 'dash', 'type': 'DASH', 'encryption': enc_value, 'kid': kid}
        if not kid:
            return {'alive': False, 'why': '无default_KID', 'verdict_reason': 'no_kid',
                    'container': 'dash', 'type': 'DASH', 'encryption': enc_value,
                    'drm': 'cenc-unknown', 'keyformat': '', 'kid': ''}
        drm, kf = 'clearkey', CLEARKEY_KEYFORMAT
    else:
        # 未走清单：按 MPD 声明分类（不得据 mp4protection 直接判 clearkey）
        if 'clearkey' in declared:
            drm, kf = 'clearkey', CLEARKEY_KEYFORMAT
        elif 'widevine' in declared:
            drm, kf = 'widevine', ''
        elif 'playready' in declared:
            drm, kf = 'playready', ''
        else:
            drm, kf = 'cenc-unknown', ''

    # ---- 编码判定走 init 段（复用 mp4_judge，不改判定算法） ----
    rep = None
    for rp in _iter_local(adapt, 'Representation'):
        rep = rp
        break
    init_tmpl = None
    for tmpl in _iter_local(adapt, 'SegmentTemplate'):
        t = _attr_local(tmpl, 'initialization')
        if t:
            init_tmpl = t
            break
    if not init_tmpl and rep is not None:
        for tmpl in _iter_local(rep, 'SegmentTemplate'):
            t = _attr_local(tmpl, 'initialization')
            if t:
                init_tmpl = t
                break
    av = ''
    if init_tmpl:
        rid = (_attr_local(rep, 'id') if rep is not None else '') or ''
        init_path = init_tmpl.replace('$RepresentationID$', rid)
        # ★ 寻址 base = final_url ⊕ 各层 BaseURL（MPD/Period/AdaptationSet/Representation）。
        #   实测 TVB 的 BaseURL（如 '6/' '8/'）在 Period 层，缺它 init/分片 404。
        base = final
        for _b in (_direct_baseurl(root), _direct_baseurl(period),
                   _direct_baseurl(adapt), _direct_baseurl(rep)):
            if _b:
                base = urljoin(base, _b)
        try:
            _s2, init_body, _c2, _f2 = _fetch_ex(net, urljoin(base, init_path), ua, 131072)
            j = mp4_judge(init_body)
            if 'video' in j:
                av = 'video'
            elif 'audio-only' in j:
                av = 'audio-only'
        except Exception:  # noqa: BLE001 - init 段失败不影响 DRM 判定
            av = ''

    key_present = bool(drm == 'clearkey' and kid)
    out = {'alive': True, 'container': 'dash', 'type': 'DASH',
           'encryption': enc_value, 'drm': drm, 'keyformat': kf, 'kid': kid,
           'key_present': key_present, 'note': 'MPD:%s' % (mpd_type or 'dynamic')}
    if av:
        out['av'] = av
    elif drm == 'clearkey' and kid:
        # §5.4 av 空缺兜底：密钥配套但编码未判定 -> 未判定（record 判 abnormal，不判 ok/fail）
        out['av'] = '未判定(init段未判定)'
        out['verdict_reason'] = 'init_undetermined'
    else:
        out['av'] = '未判定(无视频判定)'
    return out


def probe_flv(net, url, ua, limit=262144):
    """FLV 探测：tag 9 视频 / tag 8 音频 / onMetaData 分辨率。"""
    try:
        _st, data, ct = net.fetch(url, ua=ua, limit=limit)
    except Exception as e:  # noqa: BLE001
        return {'alive': False, 'why': type(e).__name__}
    if data[:3] != b'FLV':
        return {'alive': False, 'why': '非FLV:%s' % ct[:20]}
    vt = at = 0
    w = h = None
    pos = 13
    while pos + 11 < len(data):
        tagtype = data[pos]
        size = int.from_bytes(data[pos + 1:pos + 4], 'big')
        if tagtype == 9:
            vt += 1
        elif tagtype == 8:
            at += 1
        chunk = data[pos + 11:pos + 11 + min(size, 600)]
        if b'onMetaData' in chunk:
            i = chunk.find(b'width')
            if i > 0:
                try:
                    w = struct.unpack('>d', chunk[i + 5:i + 13])[0]
                except Exception:  # noqa: BLE001
                    pass
            j = chunk.find(b'height')
            if j > 0:
                try:
                    h = struct.unpack('>d', chunk[j + 5:j + 13])[0]
                except Exception:  # noqa: BLE001
                    pass
        pos += 11 + size + 4
        if vt > 3:
            break
    av = 'video' if vt > 0 else ('audio-only' if at > 0 else 'unknown')
    res = {'alive': True, 'av': av, 'note': 'FLV 视频tag%d/音频tag%d' % (vt, at)}
    if w and h:
        res['note'] += ' %dx%d' % (int(w), int(h))
    return res


def probe(net, url, ua=None, limit=393216, unsupported_schemes=None, aes_probe_bytes=None,
          expect_drm=None, expect_kid=None):
    """入口探测（P0 R2：第一道为 scheme 前置拦截）。

    1) scheme ∈ rules.unsupported_schemes -> {'unsupported':True,...}（不发起任何网络请求）；
    2) 扩展名分发：.flv -> probe_flv；.mpd -> probe_dash；其余 -> probe_m3u8（其内部按
       内容校验 #EXTM3U）。说明：设计 §2.1 的"无扩展名首字节内容分发"由 probe_m3u8 既有
       的 #EXTM3U 校验承担（判定算法保持不变）。
    expect_drm / expect_kid：可选，来自清单（source.drm / source.kid），仅对 .mpd 生效，
    供 probe_dash 做"清单优先"的 DRM 分类与 kid 一致性护栏；缺省 None 时既有语义不变。
    """
    schemes = unsupported_schemes
    if schemes is None:
        schemes = _rules_from_net(net).get('unsupported_schemes')
    if schemes is None:
        schemes = DEFAULT_RULES.get('unsupported_schemes') or []
    scheme = url.split('://', 1)[0].lower() if '://' in url else ''
    if scheme and scheme in {str(s).lower() for s in schemes}:
        return {'alive': False, 'unsupported': True, 'reason': 'scheme:' + scheme,
                'why': 'scheme:' + scheme, 'type': 'unsupported', 'container': ''}
    low = url.lower()
    path = low.split('?', 1)[0]
    if '.flv' in path[-6:]:
        r = probe_flv(net, url, ua, limit=limit)
        r['type'] = 'FLV'
        r['container'] = 'flv'
        return r
    if path.endswith('.mpd'):
        r = probe_dash(net, url, ua, limit=limit,
                       expect_drm=expect_drm, expect_kid=expect_kid)
        r['type'] = 'DASH'
        r['container'] = r.get('container') or 'dash'
        return r
    r = probe_m3u8(net, url, ua, limit=limit, aes_probe_bytes=aes_probe_bytes)
    r['type'] = 'HLS'
    r['container'] = r.get('container') or 'hls'
    return r


def classify_verdict(result, client_caps):
    """把"探测结果 + 客户端能力集"归约为 (verdict, profile, verdict_reason, caps_required)。

    纯函数（无副作用），record() 与统计口径共用，杜绝判定漂移。
    公开签名 ``(result, client_caps)`` 保持不变：record() 负责先把"有效事实"（探针明确值
    优先、库中现值回退）合入 result 再调用，故本函数只认传入的事实。
    ok 分支的 profile 走唯一叶子 ``db.delivery_profile(enc, drm)``（不再内联只看 enc）。
    """
    alive = bool(result.get('alive'))
    av = result.get('av') or ''
    container = result.get('container') or _CONTAINER_BY_TYPE.get(result.get('type'), '')
    enc = result.get('encryption') or ''
    drm = result.get('drm') or ''
    key_present = bool(result.get('key_present'))
    reason = result.get('verdict_reason') or ''
    caps_required = build_caps_required(container, enc, drm)
    profile = None
    if result.get('unsupported'):
        verdict = 'unsupported'
        reason = result.get('reason') or reason or 'unsupported'
    elif alive and av.startswith('video'):
        if enc and not key_present:
            verdict, profile, reason = 'need_key', 'need-key', 'missing_key'
        else:
            allowed, missing = check_caps(caps_required, client_caps)
            if allowed:
                verdict = 'ok'
                profile = delivery_profile(enc, drm)
            else:
                verdict, profile = 'need_client_cap', 'unsupported-drm'
                reason = 'caps:' + missing
    elif alive:
        verdict = 'abnormal'
        reason = reason or (result.get('note') or result.get('why') or 'abnormal')
    elif reason:
        verdict = 'abnormal'   # 密钥不可达等"不惩罚源"的失败
    else:
        verdict = 'fail'
    return verdict, profile, reason, caps_required


class Verifier(object):
    """候选源验证编排：批量取 candidate -> 并发 probe -> 收集整批 -> 判定熔断 -> 逐条落库。"""

    def __init__(self, cfg, db, net):
        self.cfg = cfg
        self.db = db
        self.net = net

    # ---------- 配置读取（零硬编码，全部外置） ----------
    def _client_caps(self):
        """客户端版本化能力集（缺失/写坏 -> None，交 check_caps 走保守失败）。"""
        return self.cfg.settings.get('client_caps')

    def _signed_params(self, url=''):
        """签名 URL query 参数名清单（全局 ∪ host 作用域，单一事实来源）。

        委托 `ChannelParser.resolve_signed_params`（与 discovery 共用同一函数，
        避免两处各写一套名单）。大小写不敏感 + 精确匹配，见 _is_signed_url。

        url 缺省 ''：此时仅返回全局名单（向后兼容无参调用）。
        """
        return ChannelParser.resolve_signed_params(url, self.cfg.settings)

    @staticmethod
    def _parse_caps_req(raw):
        """caps_required（JSON 字符串）-> dict；非法/空 -> {}。"""
        if not raw:
            return {}
        try:
            v = json.loads(raw)
        except (ValueError, TypeError):
            return {}
        return v if isinstance(v, dict) else {}

    def record(self, source, result, duration_ms):
        """单条验证结果落库：verifications + sources 更新（P0 R3 六分类）。

        判定优先级（FR-3/FR-4）：
          1) scheme 不支持 -> unsupported（status='unsupported'，永久）；
          2) 密钥缺口优先：encryption!='' 且 key_present==False -> need_key（不论 caps）；
          3) 能力门禁不满足 -> need_client_cap（status='drm'）；
          4) 视频已验证且门禁满足 -> ok；仅音频/不稳/未判定 -> abnormal；
             网络异常/非媒体 -> fail。
        退避：fail 累积 streak(3/7/30 天)；403/404 长冷却；签名 URL 的 403 -> expired + 短退避；
        unsupported/need_key/need_client_cap 的 fail_streak 不变（不参与指数退避）。

        ★ DRM 身份保真：drm/kid 只在 ok 分支取探测结果；其余分支（unsupported/need_key/
        need_client_cap/abnormal/fail）以**库中清单值为准**（结果缺失或带错都不覆盖），
        否则瞬时失败 / static / 非MPD / kid_mismatch 会清空清单 provenance，导致下一轮
        expect_drm 漂移、源被永久踢出 media3-json 投递。drm_key 任何分支都不写。

        ★ 有效事实（effective facts）：判定前先把 container/encryption/drm 合成为
        "探针明确值优先、库中现值回退"，并令 key_present = 探针明确值 or 库内非空 drm_key，
        再交给 classify_verdict。这样存量 DRM 源遇到"未携带 DRM 事实"的探针结果时，
        profile 仍由 delivery_profile 派生（media3-json），不会漂移成 active/direct；
        能力门禁（caps_required）也基于同一份有效事实。失败探针不得清空 provenance。
        """
        now = utcnow()
        alive = bool(result.get('alive'))
        av = result.get('av')
        note = (result.get('note') or result.get('why') or '')[:80]

        def _src(name):
            """取库中现值（source 可能是 dict 或 sqlite3.Row）。"""
            if isinstance(source, dict):
                return source.get(name)
            try:
                return source[name]
            except (KeyError, IndexError, TypeError):
                return None

        # 三列"物理事实"：探测结果优先，缺失回退库中现值（避免空串覆盖有值）。
        container = (result.get('container')
                     or _CONTAINER_BY_TYPE.get(result.get('type'), '')
                     or _src('container') or '')
        encryption = result.get('encryption') or _src('encryption') or ''
        keyformat = result.get('keyformat') or _src('keyformat') or ''

        # DRM 身份（drm/kid）：★ 清单 provenance 绝不能被非 ok 的探测结果清空（缺陷修复）。
        #   ok 分支：探测结果优先（走 expect_drm 路径时结果即权威），缺失回退库值；
        #   非 ok 分支：**库中现值优先**，缺失才用探测值 —— 瞬时失败 / static / 非MPD /
        #   kid_mismatch 等结果不带（或带错）drm/kid，若直接写入会让下一轮 expect_drm 漂移，
        #   源被永久踢出 media3-json 投递（QA 缺陷 B）。
        res_drm = result.get('drm') or ''
        res_kid = result.get('kid') or ''
        cur_drm = _src('drm') or ''
        cur_kid = _src('kid') or ''
        drm = res_drm or cur_drm          # ok 分支用（result 优先）
        kid = res_kid or cur_kid
        ns_drm = cur_drm or res_drm       # 非 ok 分支用（清单/库值优先）
        ns_kid = cur_kid or res_kid

        # 有效事实（effective facts）：探针明确值优先、库中现值回退，供 classify_verdict 使用。
        # ★ key_present：探针明确值 **或** 库中已有非空内联密钥（drm_key）——否则明明存有
        #   ClearKey JSON 也会被缺失 DRM 事实的探针结果误判成 missing_key（need_key）。
        key_present = bool(result.get('key_present')) or bool(_src('drm_key'))
        effective = dict(result)
        effective.update({'container': container, 'encryption': encryption,
                          'drm': drm, 'key_present': key_present})

        verdict, profile, verdict_reason, caps_required = classify_verdict(
            effective, self._client_caps())
        caps_json = json.dumps(caps_required, sort_keys=True)

        self.db.execute(
            'INSERT INTO verifications(source_id, ts, result, av, note, duration_ms) '
            'VALUES (?,?,?,?,?,?)',
            (source['id'], now, verdict, av, note, duration_ms))

        cols = ('container=?, encryption=?, drm=?, keyformat=?, kid=?, caps_required=?, '
                'verdict_reason=?')
        # ok：写探测值；其余分支：保留清单身份（ns_*）
        tail = (container, encryption, ns_drm, keyformat, ns_kid, caps_json, verdict_reason or None)
        ok_tail = (container, encryption, drm, keyformat, kid, caps_json, verdict_reason or None)

        if verdict == 'ok':
            # 视频已验证 -> 上线；audio-only/不稳定/未判定保持 candidate（不占线路位）
            self.db.execute(
                "UPDATE sources SET av=?, status='active', profile=?, last_verified=?, "
                'ok_streak=0, fail_streak=0, backoff_until=NULL, ' + cols + ' WHERE id=?',
                (av, profile, now) + ok_tail + (source['id'],))
            log.info('源 #%s 验证通过 -> active (%s)', source['id'], source['url'][:60])
        elif verdict == 'unsupported':
            self.db.execute(
                "UPDATE sources SET status='unsupported', av=?, last_verified=?, "
                'backoff_until=NULL, ' + cols + ' WHERE id=?',
                (av, now) + tail + (source['id'],))
            log.info('源 #%s scheme 不支持 -> unsupported (%s)', source['id'], verdict_reason)
        elif verdict in ('need_key', 'need_client_cap'):
            self.db.execute(
                "UPDATE sources SET status='drm', profile=?, av=?, last_verified=?, "
                'backoff_until=NULL, ' + cols + ' WHERE id=?',
                (profile, av, now) + tail + (source['id'],))
            log.info('源 #%s 加密待处理 -> drm/%s (%s)',
                     source['id'], profile, verdict_reason)
        elif verdict == 'abnormal':
            self.db.execute(
                'UPDATE sources SET av=?, last_verified=?, backoff_until=NULL, ' + cols
                + ' WHERE id=?',
                (av, now) + tail + (source['id'],))
        else:  # fail
            streak = int(source['fail_streak'] or 0) + 1
            code = result.get('code')
            hours = 0
            if code is not None and int(code) in COOLDOWN_CODES:
                if _is_signed_url(source['url'], self._signed_params(source['url'])):
                    # 带签名参数的 403 -> 时效过期（短退避），不适用"403 直接 30 天"
                    verdict_reason = 'expired'
                    hours = int(self.cfg.settings.get('expired_backoff_hours',
                                                      DEFAULT_SETTINGS['expired_backoff_hours']))
                else:
                    hours = COOLDOWN_DAYS * 24
            elif streak >= 2:
                hours = BACKOFF_DAYS[min(streak - 2, len(BACKOFF_DAYS) - 1)] * 24
            until = iso_plus_hours(hours) if hours else None
            self.db.execute(
                'UPDATE sources SET av=?, last_verified=?, fail_streak=?, backoff_until=?, '
                + cols + ' WHERE id=?',
                (av, now, streak, until)
                + (container, encryption, ns_drm, keyformat, ns_kid, caps_json,
                   verdict_reason or None)
                + (source['id'],))
            if until:
                log.info('源 #%s 验证失败(fail_streak=%d), 冷却至 %s (%s)',
                         source['id'], streak, until, source['url'][:60])
        return verdict

    def reevaluate_drm_pool(self):
        """客户端能力版本变更后的只读重判（FR-4 行为 4）。

        仅扫 status='drm' 的源；输入是库内已存 caps_required；**不发起任何网络请求**、
        **不写 verifications**、原子提交。密钥缺口（verdict_reason 标记）不受能力变更
        影响，保持 need_key；但库内已有非空内联密钥（drm_key）时不存在缺口，按能力重判。
        投递画像统一由唯一叶子 ``db.delivery_profile`` 派生。返回 {'scanned':n,'promoted':m}。
        """
        caps = self._client_caps()
        rows = self.db.query("SELECT * FROM sources WHERE status='drm'")
        scanned = promoted = 0
        with self.db.transaction() as conn:
            for r in rows:
                scanned += 1
                reason = r.get('verdict_reason') or ''
                # 有效 key 事实：库内已有非空内联密钥 -> 不存在密钥缺口（不因历史 reason 停留 need-key）
                has_inline_key = bool(r.get('drm_key'))
                if not has_inline_key and reason in ('missing_key', 'key_unreachable'):
                    new_status, new_profile, new_reason = 'drm', 'need-key', 'missing_key'
                else:
                    allowed, missing = check_caps(
                        self._parse_caps_req(r.get('caps_required')), caps)
                    if allowed:
                        new_status = 'active'
                        new_profile = delivery_profile(r.get('encryption'), r.get('drm'))
                        new_reason = None
                        promoted += 1
                    else:
                        new_status, new_profile = 'drm', 'unsupported-drm'
                        new_reason = 'caps:' + missing
                conn.execute(
                    'UPDATE sources SET status=?, profile=?, verdict_reason=? WHERE id=?',
                    (new_status, new_profile, new_reason, r['id']))
        if scanned:
            log.info('能力门禁重判: 扫描 %d 条 drm 源, 提升 %d 条', scanned, promoted)
        return {'scanned': scanned, 'promoted': promoted}

    # 数量弱/质量弱阈值：有效线路或首发阵容干净线不足该数即弱（两处对齐）。
    _WEAK_MIN_LINES = 2

    def _line_counts(self):
        """每频道有效线路数（active+video 且可投递 profile）——数量弱唯一口径。"""
        marks = ','.join('?' * len(DELIVERABLE_PROFILES))
        return {r['channel_key']: int(r['c']) for r in self.db.query(
            "SELECT channel_key, COUNT(*) AS c FROM sources "
            "WHERE status='active' AND av LIKE 'video%%' AND channel_key != '' "
            "AND profile IN (%s) GROUP BY channel_key" % marks, DELIVERABLE_PROFILES)}

    def _clean_line_counts(self, channel_keys):
        """每频道首发阵容（top max_lines active+video，按 score）里吞吐干净的线数。

        干净 := 最近窗口折算因子 == 1.0（下载稳定快于实时）；无吞吐数据（非 hls
        或未轮到吞吐探测）按干净计 —— "没测过"不等于"差"，避免把新上线/非 hls
        频道错划成质量弱。窗口语义与 health.apply_throughput_scores 同源
        （复用 throughput.throughput_window_factor；ok=0 按 THROUGHPUT_FAIL_RATIO 罚分计入）。
        2026-09-25（方案A）：此前"数量够但全是慢线"的频道永远排不到验证预算
        （翡翠台实测：9 条在役仅 1 条干净，21 个候选 4 天未复验）。
        """
        h = self.cfg.settings['health']
        window = max(1, int(h.get('throughput_window', 3)))
        realtime = float(h.get('throughput_realtime_ratio',
                               THROUGHPUT_REALTIME_RATIO))
        max_lines = int(self.cfg.settings['limits']['max_lines_per_channel'])
        out = {}
        for key in {k for k in channel_keys if k}:
            tops = self.db.query(
                "SELECT id FROM sources WHERE channel_key=? AND status='active' "
                "AND av LIKE 'video%' ORDER BY score DESC, id ASC LIMIT ?",
                (key, max_lines))
            clean = 0
            for t in tops:
                checks = self.db.query(
                    'SELECT ok, ratio FROM throughput_checks WHERE source_id=? '
                    'ORDER BY ts DESC, id DESC LIMIT ?', (t['id'], window))
                ratios = []
                for c in checks:
                    if c['ok']:
                        if c['ratio'] is not None:
                            ratios.append(float(c['ratio']))
                    else:
                        ratios.append(THROUGHPUT_FAIL_RATIO)
                if not ratios:
                    clean += 1          # 无吞吐数据 -> 保守按干净
                    continue
                if throughput_window_factor(ratios, realtime) >= 1.0:
                    clean += 1
            out[key] = clean
        return out

    def _select_batch(self, batch_size):
        """选取本轮待验证候选。

        过滤：candidate + 目标频道 + 24h 验证缓存未到期 + 退避冷却未到期（P1-C）；
        排序（P1-F 弱频道优先，2026-09-25 扩展为三层）：
          tier0 数量弱：有效线路（active+video 且 profile ∈ DELIVERABLE_PROFILES）
                < _WEAK_MIN_LINES —— 看不了的频道最紧急；
          tier1 质量弱：首发阵容吞吐干净线不足 _WEAK_MIN_LINES —— "数量够但全是
                慢线"的频道（原两层设计里永远排不到验证预算）；
          tier2 其他。同级按 score DESC —— 验证预算优先补短板，避免单线路频道被
                多线路频道的高分源持续挤占（P0 R3：有效线路统计加 profile 过滤，
                杜绝 DRM 源造成假绿）。
        开关 settings.verify_quality_weak_enabled=False 时退回两层旧行为。
        """
        cache_cutoff = iso_plus_hours(-int(self.cfg.settings['verify_cache_hours']))
        rows = self.db.query(
            "SELECT * FROM sources WHERE status='candidate' AND channel_key != '' "
            'AND (last_verified IS NULL OR last_verified < ?) '
            'AND (backoff_until IS NULL OR backoff_until < ?)',
            (cache_cutoff, utcnow()))
        line_counts = self._line_counts()
        quality_enabled = bool(self.cfg.settings.get(
            'verify_quality_weak_enabled',
            DEFAULT_SETTINGS['verify_quality_weak_enabled']))
        clean_counts = (self._clean_line_counts({r['channel_key'] for r in rows})
                        if quality_enabled else {})

        def tier(ch):
            if line_counts.get(ch or '', 0) < Verifier._WEAK_MIN_LINES:
                return 0
            if (quality_enabled
                    and clean_counts.get(ch or '', Verifier._WEAK_MIN_LINES)
                    < Verifier._WEAK_MIN_LINES):
                return 1
            return 2

        rows.sort(key=lambda r: (tier(r['channel_key']),
                                 -float(r['score'] or 0.0)))
        return rows[:batch_size]

    def _rule_blocked(self, url):
        """现行死源规则判定（与 discovery.apply_rules 同一语义：host 前缀/域名子串）。"""
        host = ChannelParser.host(url).lower()
        rules = self.cfg.rules
        for pfx in rules.get('blacklist_host_prefixes') or []:
            if host.startswith(pfx):
                return True
        for dom in rules.get('blacklist_domains') or []:
            if dom in host:
                return True
        return False

    def _select_blacklist_recheck(self, quota):
        """陈旧拉黑复核：已不命中现行死源规则的 blacklisted 源，低频重回验证队列。

        背景（2026-09-25 实测）：拉黑是单向门 —— 规则删除/收窄后被旧规则拉黑的源
        永不复审（全库 3027 个拉黑源中 666 个已不命中现行规则；翡翠台 5 个此类源
        实测 2 个当前可播且吞吐优于首发线）。复核走 record() 既有六分类：ok 即
        转 active 复出；fail 保持 blacklisted 并记退避，不反复打扰。仍命中现行
        规则的不复核（尊重现行规则）；channel_key='' 的不复核（不参与输出，复核
        纯浪费预算，孤儿清理会按期回收）。配额独立于 verify_batch：候选按日滚动
        即饱和批次，若只放低优层级永远轮不到。
        """
        if quota <= 0:
            return []
        hours = int(self.cfg.settings.get(
            'blacklist_recheck_hours', DEFAULT_SETTINGS['blacklist_recheck_hours']))
        cutoff = iso_plus_hours(-hours)
        rows = self.db.query(
            "SELECT * FROM sources WHERE status='blacklisted' AND channel_key != '' "
            'AND (last_verified IS NULL OR last_verified < ?) '
            'AND (backoff_until IS NULL OR backoff_until < ?) '
            'ORDER BY score DESC, last_verified ASC',
            (cutoff, utcnow()))
        out = []
        for r in rows:
            if not self._rule_blocked(r['url']):
                out.append(r)
                if len(out) >= quota:
                    break
        return out


    @staticmethod
    def _should_circuit_open(results):
        """首次验证整批熔断判定（保守，纯函数、无副作用）。

        results 为已收集的 ``(row, result, dur_ms)`` 列表，且仅应在"本批计划结果
        全部正常收齐且未超时"时调用（run() 负责该完整性门禁）。返回 True 当且仅当：
        样本 >=3、全部 ``alive=False``，且每条失败原因为传输层异常
        （``result['verdict_reason']`` 优先，否则 ``result['why']``，见
        app.throughput.is_transport_reason）。任一 HTTP 状态 / HTML 错误页 / 协议内容
        失败（含 reason 缺失）或任一条存活即拒绝熔断。
        """
        if len(results) < 3:
            return False
        for _row, result, _dur in results:
            if result.get('alive'):
                return False
            reason = result.get('verdict_reason') or result.get('why')
            if not is_transport_reason(reason):
                return False
        return True

    def run(self):
        """一轮增量验证：batch_size 条、time_budget 分钟预算；返回统计 dict。

        批内先收集全部结果，再做整批熔断判定，最后逐条 record()：熔断时零落库
        （不写 verifications、不改 sources 状态/streak/backoff/last_verified），
        返回 ``circuit_open=True``。超时或结果未收齐时禁用熔断，按原逻辑落库。
        """
        limits = self.cfg.settings['limits']
        batch_size = int(limits['verify_batch'])
        budget_s = float(limits['verify_budget_min']) * 60
        concurrency = int(limits['net_concurrency'])
        probe_limit = int(self.cfg.settings['network'].get('probe_fetch_limit', 393216))
        schemes = self.cfg.rules.get('unsupported_schemes')
        probe_bytes = self.cfg.settings.get('aes_probe_bytes', DEFAULT_SETTINGS['aes_probe_bytes'])
        batch = self._select_batch(batch_size)
        # 2026-09-25（方案C）：陈旧拉黑复核配额，独立于候选批次追加
        if self.cfg.settings.get('blacklist_recheck_enabled',
                                 DEFAULT_SETTINGS['blacklist_recheck_enabled']):
            quota = int(self.cfg.settings.get(
                'blacklist_recheck_per_round',
                DEFAULT_SETTINGS['blacklist_recheck_per_round']))
            recheck = self._select_blacklist_recheck(quota)
            if recheck:
                log.info('验证轮: 追加陈旧拉黑复核 %d 条', len(recheck))
                batch = batch + recheck
        if not batch:
            log.info('验证轮: 无待验证候选')
            return {'batch': 0, 'ok': 0, 'abnormal': 0, 'fail': 0, 'unsupported': 0,
                    'need_key': 0, 'need_client_cap': 0, 'timeout': False,
                    'circuit_open': False}
        log.info('验证轮: 取 %d 条候选 (预算 %.0f 分钟)', len(batch), budget_s / 60)
        deadline = time.time() + budget_s
        stats = {'batch': len(batch), 'ok': 0, 'abnormal': 0, 'fail': 0,
                 'unsupported': 0, 'need_key': 0, 'need_client_cap': 0, 'timeout': False,
                 'circuit_open': False}
        ua = self.net.ua

        def work(row):
            t0 = time.time()
            # expect_drm/expect_kid 来自清单落库的源行（_select_batch 用 SELECT *）：
            # 通行 HK MPD 路线做"清单优先"DRM 分类（§5.3）；非清单源两列为 None，语义不变。
            r = probe(self.net, row['url'], ua, limit=probe_limit,
                      unsupported_schemes=schemes, aes_probe_bytes=probe_bytes,
                      expect_drm=row['drm'], expect_kid=row['kid'])
            return row, r, int((time.time() - t0) * 1000)

        # 先收集整批结果：超时即停止收集并 cancel 未完成任务（ThreadPoolExecutor
        # 上下文退出会等待运行中的任务，不能强杀）。future 异常记日志跳过——
        # 结果数因此小于 batch，长度门禁会禁用熔断（未知异常不算完整样本）。
        results = []
        with ThreadPoolExecutor(max_workers=concurrency) as ex:
            futs = [ex.submit(work, row) for row in batch]
            try:
                for f in as_completed(futs):
                    try:
                        row, result, dur = f.result()
                    except Exception as e:  # noqa: BLE001 - 单源探测异常不杀整轮
                        log.error('验证探测异常: %s', e)
                        continue
                    results.append((row, result, dur))
                    if time.time() > deadline:
                        stats['timeout'] = True
                        break
            finally:
                if stats['timeout']:
                    for f in futs:
                        f.cancel()

        # 熔断门禁：仅"计划结果全部正常收齐且未超时"才允许判定，避免用超时前的
        # 部分样本错误代表整批；判定为真时本轮零落库。
        complete = (not stats['timeout']) and len(results) == len(batch)
        if complete and self._should_circuit_open(results):
            log.warning('验证轮: 本批 %d 条候选全部传输层失败, 触发整批熔断(零落库)',
                        len(results))
            stats['circuit_open'] = True
            log.info('验证轮完成(熔断): %s', stats)
            return stats

        # 非熔断：对已收集结果按原逻辑逐条 record；单条落库失败不中断。
        for row, result, dur in results:
            try:
                verdict = self.record(row, result, dur)
            except Exception as e:  # noqa: BLE001 - 单条落库失败不中断
                log.error('验证结果落库失败 source=%s: %s', row['id'], e)
                continue
            if verdict in stats:
                stats[verdict] += 1
        log.info('验证轮完成: %s', stats)
        return stats
