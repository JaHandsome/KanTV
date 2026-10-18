#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导出 url_key 测试向量 -> docs/urlkey_vectors.json（HUB_CONTRACT_V1 **v1.1** §2.5）。

契约要求：「服务端提供只读端点 `GET /api/hub/v1/urlkey-vectors`，返回
`[{ "url": …, "url_key": … }]`，**≥40 组**」「客户端必须用该端点内容逐条断言自身实现；
**不一致即视为未通过，禁止上线**」。

本工具产出的向量**由服务端权威实现直接计算**（`ChannelParser.url_key` +
`ChannelParser.resolve_signed_params`），因此它就是客户端要实现的行为的机器可读定义。

用法（项目根目录）:
    python tools/export_urlkey_vectors.py                    # 默认本地 data/iptv.db
    python tools/export_urlkey_vectors.py --db /path/x.db    # 指定库（生产库请只读打开）
    python tools/export_urlkey_vectors.py --out docs/urlkey_vectors.json

覆盖的向量组（见 stdout 的 REPORT）：
  A 裸 URL（无 query）
  B 签名值变化（sig/token/expire/expires/sign/securitykey/msisdn/ts...）-> url_key 必须不变
  C 静态凭据 key/authid -> **必须保留**，变化后 url_key 必须改变
  D query 顺序（**v1.1 §2.4 已明确撤回"顺序无关"要求**，见下）
  E fragment
  F scheme/host 大小写
  G 非 http(s) scheme
  H 真实样本（生产库 dash/flv/hls + 真实 TVB MPD 签名 URL）
  I v1.1 §2.1/§2.3 边界（strip / 非 http / 空 authority / authority 含 userinfo+port /
    零命中逐字节不变量 / 空段处理）

⚠️ D 组不是"两条相等的向量"，而是**两条不相等的真实值** —— v1.1 §2.4 已把 v1 的
「query 顺序被打乱 → url_key 必须相同」**明确撤回**，并禁止对 query 排序
（`normalize_url` 与 `url_hash` 共用，`url_hash` 是 `sources` 表的唯一索引键）。
D 组留在向量里是为了把"服务端确实不排序"钉死成可执行断言。
"""
import argparse
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.config import DEFAULT_SETTINGS, Config     # noqa: E402
from app.parser import ChannelParser                # noqa: E402

# 真实 TVB MPD 签名 URL 的来源（每日 24h 轮换；只要 host/path 不变，url_key 恒定）
TVB_SAMPLE_JSON = os.path.join(ROOT, 'docs', 'live.media3.sample.json')


def _spec_vectors():
    """规格向量 -> [(group, url, note)]。note 仅供人读，不进产物。"""
    v = []
    A = 'A 裸 URL / 无签名参数'
    B = 'B 签名值变化必须不影响 url_key'
    C = 'C 静态凭据 key/authid 必须保留'
    D = 'D query 顺序（v1.1 §2.4 明确**不排序**，故两条不相等）'
    E = 'E fragment'
    F = 'F scheme/host 大小写'
    G = 'G 非 http(s) scheme'
    I = 'I v1.1 §2.1/§2.3 边界（normalize/strip 的逐字节不变量）'

    # A
    v.append((A, 'http://a/b.m3u8', '无 query'))
    v.append((A, 'https://example.com/live/ch/index.mpd', '无 query，mpd'))
    v.append((A, 'http://a/b.m3u8?', '空 query'))
    v.append((A, 'http://a/b.m3u8?foo=1&bar=2', '无命中参数 -> 恒等于 url_hash'))

    # B —— TVB MPD（host 作用域名单：sig/ts/c/ext_start_limit/int_start_limit）
    tvb_a = ('http://edgeware.tvb.com/index.mpd?sig=aaaa1111&ext_start_limit='
             '&int_start_limit=&ts=1700000000&c=')
    tvb_b = ('http://edgeware.tvb.com/index.mpd?sig=bbbb2222&ext_start_limit='
             '&int_start_limit=&ts=1700086400&c=')
    v.append((B, tvb_a, 'TVB MPD D1（真实形态，见 HANDOFF §5.3）'))
    v.append((B, tvb_b, 'TVB MPD 次日签名 -> url_key 必须与 D1 相同'))
    v.append((B, 'http://h/p.m3u8?token=AAA&ch=1', '全局 token'))
    v.append((B, 'http://h/p.m3u8?token=BBB&ch=1', 'token 轮换 -> url_key 必须不变'))
    v.append((B, 'http://h/p.m3u8?expires=1700000000&ch=1', '全局 expires'))
    v.append((B, 'http://h/p.m3u8?expires=1700086400&ch=1', 'expires 轮换 -> 不变'))
    v.append((B, 'http://h/p.m3u8?msisdn=13800000000&SecurityKey=AA&ch=1',
              '运营商计费签名（msisdn/securitykey，大小写不敏感）'))
    v.append((B, 'http://h/p.m3u8?msisdn=13900000000&SecurityKey=BB&ch=1',
              '同源次日 -> 必须与上一条相同'))
    v.append((B, 'http://h/p?sign=xx&signature=yy&ch=1', 'sign + signature'))
    v.append((B, 'http://h/p?sign=zz&signature=ww&ch=1', '轮换 -> 不变'))

    # C —— key / authid 必须保留（变化 -> url_key 必须改变）
    v.append((C, 'http://120.196.235.42:9901/tsfile/live/1006_1.m3u8?key=fixture-auth-eec577e289ee',
              '静态访问 key（必须保留；契约 §2 明文点名 key/authid）'))
    v.append((C, 'http://120.196.235.42:9901/tsfile/live/1006_1.m3u8?key=other',
              'key 变化 -> url_key **必须改变**（与上一条不同）'))
    v.append((C, 'http://h/p?authid=123&ch=1', 'authid 静态凭据'))
    v.append((C, 'http://h/p?authid=456&ch=1', 'authid 变化 -> url_key 必须改变'))
    v.append((C, 'http://112.30.73.119:229/tsfile/live/0003_2.m3u8'
                 '?key=fixture-auth-eec577e289ee&playlive=0&authid=0',
              '真实生产样本：key+authid 与普通参数共存（两者都保留）'))

    # D —— 顺序（含已知偏差）
    v.append((D, 'http://h/p?a=1&b=2', '基准序'))
    v.append((D, 'http://h/p?b=2&a=1', '⚠️ 仅顺序不同：服务端 url_key 与上一条**不同**'))
    v.append((D, 'http://h/p?sign=x&a=1&b=2', '剥 sign 后剩 a=1&b=2'))
    v.append((D, 'http://h/p?b=2&sign=x&a=1', '剥 sign 后剩 b=2&a=1（保留原序）'))
    v.append((D, 'http://h/p?b=2&a=1', '⚠️ 与上一条不同（同上）'))

    # E —— fragment
    v.append((E, 'http://h/p?foo=1#frag', 'fragment 丢弃 -> 与 A 组同类形态'))
    v.append((E, 'http://h/p#frag', '仅 fragment'))
    v.append((E, 'http://h/p?token=zzz#frag', '签名 + fragment'))

    # F —— 大小写
    v.append((F, 'HTTP://H/P?foo=1', 'scheme/host 归一为小写；path 原样保留'))
    v.append((F, 'HTTP://H/p?foo=1', '与下一条 url_key **必须相同**（scheme/host 大小写不敏感）'))
    v.append((F, 'http://h/p?foo=1', '基准（同 F 上一条）'))
    v.append((F, 'Https://H/p?FOO=1', 'query 参数名大小写**敏感**（契约未归一化 query）'))
    v.append((F, 'http://h/P', 'path 大小写**敏感**（契约「path 原样保留」）'))

    # G —— 非 http(s)
    v.append((G, 'rtmp://h/p?token=x', '非 http(s)：不解析 query，原样 hash'))
    v.append((G, 'udp://h/p?sign=y', '同上'))

    # I —— v1.1 §2.1/§2.3 的逐字节边界（客户端最容易实现错的地方）
    v.append((I, '  http://a/b.m3u8  ', '§2.1 步骤1：strip -> 与 A 组裸 URL 同键'))
    v.append((I, 'foo', '§2.1 步骤5：非 http(s) -> 原样返回'))
    v.append((I, '://x', '§2.1 步骤5：无 scheme -> 原样返回'))
    v.append((I, 'http://', '§2.1 步骤5：`://` 后无字符 -> 原样返回'))
    v.append((I, 'HTTP://User:PaSS@Host:8420/PaTh?q=1#f',
              '§2.1：authority **整体**小写（含 userinfo+port）；path/query 原样（大小写保留）'))
    v.append((I, 'http://h/p?a=1&&b=2',
              '★ §2.3 步骤6：零命中 -> 与 normalize_url **逐字节相同**（含空段 `&&`）'))
    v.append((I, 'http://h:8420/p?token=#frag',
              '§2.3 步骤7：命中且 kept 为空 -> 返回 base，**不带 `?`**'))
    v.append((I, 'http://h/p?sign=x&', '§2.3 步骤5：空段丢弃；命中且 kept 为空 -> 无 `?`'))
    v.append((I, 'http://h/p?sig=x',
              '★ sig 仅对 edgeware.tvb.com 生效 -> 此 host 零命中，必须逐字节原样'))
    v.append((I, 'http://h/p?key=K&sign=s&authid=A&token=t',
              '混合：剥 sign/token、**保留 key/authid 且保持原序**'))
    v.append((I, 'http://edgeware.tvb.com/x.mpd?ts=1&sig=2',
              '★ host 作用域只对 edgeware.tvb.com **及其子域**生效（此处为主域）'))
    v.append((I, 'http://edgeware.tvb.com.evil.net/x.mpd?ts=1&sig=2',
              '★ 仿冒域：host 作用域**不得**命中（后缀匹配须带 `.`）'))
    # ARCH P2-2：§2.2 的两个反直觉陷阱（契约忠实、双方一致，不得"修正"）
    v.append((I, 'http://u:p@edgeware.tvb.com/x.mpd?sig=A&ts=1',
              '★ §2.2 陷阱①：host 按第一个 `:` 截断 -> 含 userinfo 时 host="u"，'
              'host 作用域名单失效，sig/ts **不剥离**'))
    v.append((I, 'HTTPS://edgeware.tvb.com/x.mpd?sig=A&ts=1',
              '★ §2.2 陷阱②：host 正则 `https?://` 区分大小写 -> 大写 scheme 失配，'
              'host 退化为 url[:30]，sig/ts **不剥离**'))
    return v


def _db_vectors(db_path, per_kind=3):
    """从库中取真实样本 -> [(group, url, note)]。库不存在/无表时返回空列表。

    容错：本地开发库是 v2 快照（**没有 container 列**），生产库是 v7；
    故这里按 PRAGMA 探测可用列，缺 container 时退化为按 URL 后缀识别。
    """
    import sqlite3
    if not db_path or not os.path.isfile(db_path):
        print('[warn] 库不存在，跳过真实样本: %s' % db_path)
        return []
    out = []
    try:
        conn = sqlite3.connect('file:%s?mode=ro' % db_path, uri=True)
        conn.row_factory = sqlite3.Row
        cols = {r[1] for r in conn.execute('PRAGMA table_info(sources)')}
        if not cols:
            print('[warn] 库中无 sources 表，跳过真实样本')
            conn.close()
            return []
        has_c = 'container' in cols
        has_st = 'status' in cols
        act = " AND status='active'" if has_st else ''
        if has_c:
            dash_w = "container='dash'"
            flv_w = "container='flv'"
            hls_w = "container='hls'"
        else:
            dash_w = "lower(url) LIKE '%%.mpd%%'"
            flv_w = "lower(url) LIKE '%%.flv%%'"
            hls_w = ("lower(url) NOT LIKE '%%.mpd%%' AND lower(url) NOT LIKE '%%.flv%%'")
        specs = (
            # dash 不加 status 过滤：生产库里 .mpd 往往仍是 candidate（HK 路线门禁未开），
            # 而向量要覆盖的正是"URL 形态"，与状态无关。
            ('H 真实样本 · 库内 dash', dash_w, 'dash/mpd'),
            ('H 真实样本 · 库内 flv', flv_w + act, 'flv'),
            # 优先带 query 的 HLS（覆盖签名剥离），不足再补不带 query 的
            ('H 真实样本 · 库内 hls（带 query）',
             hls_w + act + " AND url LIKE '%?%'", 'hls'),
            ('H 真实样本 · 库内 hls', hls_w + act, 'hls'),
        )
        for group, where, note in specs:
            rows = conn.execute(
                'SELECT id, channel_key, url FROM sources WHERE %s ORDER BY id LIMIT ?'
                % where, (int(per_kind),)).fetchall()
            for r in rows:
                out.append((group, r['url'],
                            '%s id=%s key=%s' % (note, r['id'], r['channel_key'] or '-')))
        conn.close()
    except sqlite3.Error as e:
        print('[warn] 读取库失败，跳过真实样本: %s' % e)
        return []
    return out


def _tvb_mpd_vectors(path=TVB_SAMPLE_JSON):
    """从真实 TVB 样本 json 取 MPD 签名 URL（生产库当前无 TVB MPD 行）。"""
    if not os.path.isfile(path):
        print('[warn] 缺 TVB 样本，跳过: %s' % path)
        return []
    try:
        obj = json.load(open(path, encoding='utf-8'))
    except (ValueError, OSError) as e:
        print('[warn] TVB 样本解析失败: %s' % e)
        return []
    out = []
    for ch in obj.get('channels') or []:
        for line in ch.get('lines') or []:
            url = line.get('url') or ''
            # 样本 URL 带签名 query（…index.mpd?sig=…），故判 '.mpd' 子串而非后缀
            if '.mpd' in url:
                out.append(('H 真实样本 · TVB MPD（签名 24h 轮换）', url,
                            'kid=%s channel=%s' % (line.get('kid'), ch.get('key'))))
                break
    return out


def build(db_path=None, per_kind=3):
    """-> (vectors, groups_report)。vectors 为契约 §2 规定的 [{url, url_key}]。"""
    spec = _spec_vectors()
    real = (_tvb_mpd_vectors() if db_path is None else _tvb_mpd_vectors())\
        + _db_vectors(db_path, per_kind=per_kind)
    allv = spec + real
    vectors, seen = [], set()
    for group, url, note in allv:
        if url in seen:
            continue
        seen.add(url)
        vectors.append((group, url, note))
    return vectors, real


def main(argv=None):
    ap = argparse.ArgumentParser(description='导出 HUB_CONTRACT_V1 §2 的 url_key 测试向量')
    ap.add_argument('--db', default=os.path.join(ROOT, 'data', 'iptv.db'),
                    help='真实样本来源库（默认本地 data/iptv.db；只读打开）')
    ap.add_argument('--out', default=os.path.join(ROOT, 'docs', 'urlkey_vectors.json'))
    ap.add_argument('--per-kind', type=int, default=3, help='每类真实样本条数（默认 3）')
    args = ap.parse_args(argv)

    # 权威实现的签名参数名单：优先读真实 settings.json（与生产同源）
    cfg = Config(ROOT)
    settings = cfg.settings or DEFAULT_SETTINGS
    signed_global = settings.get('signed_url_params')
    signed_host = settings.get('signed_url_params_by_host')
    print('签名参数名单（全局）: %s' % signed_global)
    print('签名参数名单（host 作用域）: %s' % signed_host)

    vectors, real = build(db_path=args.db, per_kind=args.per_kind)

    out, report = [], {}
    for group, url, note in vectors:
        params = ChannelParser.resolve_signed_params(url, settings)
        key = ChannelParser.url_key(url, params)
        out.append({'url': url, 'url_key': key})
        report.setdefault(group, []).append((url, key, params))

    os.makedirs(os.path.dirname(args.out) or '.', exist_ok=True)
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')

    print('\n===== 向量覆盖（%d 条，真实样本 %d 条）=====' % (len(out), len(real)))
    for group in sorted(report):
        print('\n[%s] %d 条' % (group, len(report[group])))
        for url, key, params in report[group]:
            print('  %s  %s' % (key, url))
            if params:
                print('      └ 剥离名单: %s' % ','.join(params))
    print('\n已写出: %s（%d 条）' % (args.out, len(out)))

    # 自检：合同要求的三条不变量（在服务端权威实现下逐条断言）
    bad = []
    by_url = {v['url']: v['url_key'] for v in out}
    tvb_a = 'http://edgeware.tvb.com/index.mpd?sig=aaaa1111&ext_start_limit=&int_start_limit=&ts=1700000000&c='
    tvb_b = 'http://edgeware.tvb.com/index.mpd?sig=bbbb2222&ext_start_limit=&int_start_limit=&ts=1700086400&c='
    if by_url.get(tvb_a) != by_url.get(tvb_b):
        bad.append('TVB 跨日签名 url_key 不相等')
    k1 = 'http://120.196.235.42:9901/tsfile/live/1006_1.m3u8?key=fixture-auth-eec577e289ee'
    k2 = 'http://120.196.235.42:9901/tsfile/live/1006_1.m3u8?key=other'
    if by_url.get(k1) == by_url.get(k2):
        bad.append('key 变化未改变 url_key（静态凭据被误剥离）')
    if by_url.get('HTTP://H/p?foo=1') != by_url.get('http://h/p?foo=1'):
        bad.append('scheme/host 大小写未归一')
    print('\n自检: %s' % ('全部通过' if not bad else '失败 -> ' + '; '.join(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
