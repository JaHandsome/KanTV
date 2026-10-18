#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DASH/MPD + ClearKey 分片级探针 —— 补齐 `verifier.probe_dash()` 未覆盖的最后一环。

背景：`app/verifier.py::probe_dash()` 只取 **MPD 清单本身**（解析 ContentProtection /
default_KID / SegmentTemplate），**不取媒体分片**。这是当初有意为之（"诊断用途而非门禁"），
但代价是无法回答"签名到底覆不覆盖分片""分片能不能真的拿到"。

本脚本补上这一段：解析 MPD 的 BaseURL 链 + SegmentTimeline，按首/中/次末/末采样，
实际拉 init 段与媒体分片，并交叉核对 `kid` 与 MPD 的 `cenc:default_KID`。

用法：
  # 从清单里按台名取（清单条目含 #KODIPROP 的 clearkey）
  python tools/probe_mpd_segments.py --list data/_hk/xiaotan_fresh.m3u --name 無線新聞台
  # 或直接给 MPD + 密钥
  python tools/probe_mpd_segments.py --url '<mpd_url>' --kid <kid_hex> --key <key_hex>

只读诊断，不写库。⚠️ 外部网络抓取在本机沙箱可能异常，异常时改在 NAS 容器内跑。
"""
import argparse
import base64
import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urljoin

UA = ('Mozilla/5.0 (Linux; Android 9; SHIELD Android TV Build/PPR1.180610.011) '
      'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36')
_ctx = ssl.create_default_context()
_ctx.check_hostname = False
_ctx.verify_mode = ssl.CERT_NONE


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get(url, limit=8 << 20, follow=True, timeout=25):
    op = (urllib.request.build_opener() if follow
          else urllib.request.build_opener(_NoRedirect))
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': '*/*'})
    try:
        with op.open(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read(limit), r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read(4096), url
    except Exception as e:  # noqa: BLE001
        return None, {}, ('ERR %s' % e).encode(), url


def parse_entries(path):
    """解析 m3u -> [{name, url, kodiprop}]。要求 EXTINF 在前、URL 在后。"""
    out, cur = [], {}
    for raw in open(path, encoding='utf-8', errors='replace'):
        ln = raw.rstrip('\n')
        if ln.startswith('#KODIPROP'):
            k, _, v = ln.split(':', 1)[1].partition('=')
            cur.setdefault('kodiprop', {})[k.strip()] = v.strip()
        elif ln.startswith('#EXTINF'):
            m = re.search(r'tvg-name="([^"]*)"', ln)
            cur['name'] = m.group(1) if m else ln.rsplit(',', 1)[-1].strip()
        elif ln.strip() and not ln.startswith('#'):
            cur['url'] = ln.strip()
            out.append(cur)
            cur = {}
    return out


def expand_timeline(adaptation_set):
    """展开 SegmentTimeline 为分段起始时间列表（S@t 继承 / @d / @r）。"""
    m = re.search(r'<SegmentTimeline>(.*?)</SegmentTimeline>', adaptation_set, re.S)
    if not m:
        return None
    times, t = [], None
    for s in re.findall(r'<S\b[^>]*/?>', m.group(1)):
        g = dict(re.findall(r'(\w+)="(-?\d+)"', s))
        if 't' in g:
            t = int(g['t'])
        d, r = int(g['d']), int(g.get('r', 0))
        for _ in range(r + 1):
            times.append(t)
            t += d
    return times


def probe(url, kid_hex=None, key_hex=None, pick_rep=None):
    print('MPD : %s' % url[:160])
    st, hd, body, _ = get(url, limit=1024, follow=False)
    print('  直连  : HTTP %s  Location=%s' % (st, hd.get('Location', '(none)')[:120]))

    st, hd, body, final = get(url, follow=True)
    print('  跟随  : HTTP %s  %d bytes  ct=%s' % (st, len(body), hd.get('Content-Type')))
    if st != 200:
        print('  body: %s' % body[:300])
        return 1
    txt = body.decode('utf-8', 'replace')

    kids = {k.replace('-', '').lower() for k in re.findall(r'cenc:default_KID="([^"]+)"', txt)}
    print('  default_KID: %s' % (kids or '(none)'))
    if kid_hex:
        print('  KID 一致   : %s' % (kids == {kid_hex.lower()}))
    for cp in re.findall(r'<ContentProtection[^>]*>', txt)[:6]:
        print('  CP  : %s' % cp.strip()[:170])

    reps = re.findall(r'<Representation[^>]*id="([^"]+)"', txt)
    print('  Representation: %s' % reps[:8])

    # 选一个视频 Representation（默认第一个 v*）
    rep = pick_rep or next((r for r in reps if r.startswith('v')), reps[0] if reps else None)
    if not rep:
        print('  !! 无 Representation，终止')
        return 1
    seg_init = seg_media = tl = None
    for a in re.findall(r'<AdaptationSet\b.*?</AdaptationSet>', txt, re.S):
        if rep not in a:
            continue
        stm = re.search(r'<SegmentTemplate\b[^>]*>', a)
        if not stm:
            continue
        mi = re.search(r'initialization="([^"]+)"', stm.group(0))
        mm = re.search(r'media="([^"]+)"', stm.group(0))
        seg_init, seg_media = (mi.group(1) if mi else None), (mm.group(1) if mm else None)
        tl = expand_timeline(a)
        break
    bases = re.findall(r'<BaseURL[^>]*>([^<]*)</BaseURL>', txt)
    print('  rep=%s  init=%s  media=%s  BaseURL=%s  时间点=%s'
          % (rep, seg_init, seg_media, bases, len(tl) if tl else 0))

    def build(suffix):
        base = urljoin(final, ''.join(bases)) if bases else final
        if not base.endswith('/'):
            base += '/'
        return urljoin(base, suffix.replace('$RepresentationID$', rep))

    if seg_init:
        u = build(seg_init)
        st, hd, b, _ = get(u, limit=65536)
        print('  INIT  : HTTP %s  %d bytes  ct=%s  magic=%s'
              % (st, len(b), hd.get('Content-Type'), b[:16].hex()))
        print('          %s' % u[:160])
    if tl and seg_media:
        for label, idx in (('首个', 0), ('中间', len(tl) // 2), ('次末', -3), ('末个', -1)):
            u = build(seg_media.replace('$Time$', str(tl[idx])))
            st, hd, b, _ = get(u, limit=8 << 20)
            print('  SEG(%-4s): HTTP %s  %d bytes  ct=%s  t=%s'
                  % (label, st, len(b), hd.get('Content-Type'), tl[idx]))
        # 分片是否依赖 token
        if '?' in u:
            st2, _, b2, _ = get(u.split('?')[0], limit=65536)
            print('  SEG(去token): HTTP %s  %d bytes' % (st2, len(b2)))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', help='m3u 清单路径')
    ap.add_argument('--name', help='台名（子串匹配）')
    ap.add_argument('--url', help='直接给 MPD URL')
    ap.add_argument('--kid', help='KID (hex)')
    ap.add_argument('--key', help='KEY (hex)')
    ap.add_argument('--rep', help='指定 Representation id')
    a = ap.parse_args()

    t0 = time.time()
    if a.list:
        if not a.name:
            print('--list 需配 --name')
            return 2
        ents = parse_entries(a.list)
        hit = [e for e in ents if a.name in e.get('name', '')]
        if not hit:
            print('未匹配；清单台名：%s' % [e.get('name') for e in ents])
            return 2
        e = hit[0]
        print('台名: %s' % e['name'])
        kid_hex = key_hex = None
        ck = (e.get('kodiprop') or {}).get('inputstream.adaptive.license_key')
        if ck:
            try:
                o = json.loads(ck)['keys'][0]
                kid_hex = base64.urlsafe_b64decode(
                    o['kid'] + '=' * (-len(o['kid']) % 4)).hex()
                key_hex = base64.urlsafe_b64decode(
                    o['k'] + '=' * (-len(o['k']) % 4)).hex()
                print('KID : %s\nKEY : %s' % (kid_hex, key_hex))
            except Exception as ex:  # noqa: BLE001
                print('密钥解析失败: %s' % ex)
        ts = re.search(r'[?&]ts=(\d+)', e['url'])
        if ts:
            left = (int(ts.group(1)) - time.time()) / 3600
            print('签名剩余: %.2f 小时' % left)
        rc = probe(e['url'], kid_hex, key_hex, a.rep)
    elif a.url:
        rc = probe(a.url, a.kid, a.key, a.rep)
    else:
        ap.print_help()
        return 2
    print('\n耗时 %.1fs' % (time.time() - t0))
    return rc


if __name__ == '__main__':
    sys.exit(main())
