# -*- coding: utf-8 -*-
"""Read-only DASH addressing probe for a user-supplied local playlist.

Run from the configured server root: python3 tools/nas_probe_real.py <playlist.m3u> --channel <name>
This diagnostic may emit real media URLs and redirect information. Keep its output
private; never commit, attach to public issues, or run against an unapproved instance.
It does not contain an SSH target, deploy files, or write the database.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urljoin, urlsplit, urlunsplit

_THIS = os.path.dirname(os.path.abspath(__file__))
for _cand in (os.path.dirname(_THIS), os.getcwd()):
    if os.path.isdir(os.path.join(_cand, 'app')):
        ROOT = _cand
        break
else:
    ROOT = os.getcwd()
sys.path.insert(0, ROOT)

from app.config import Config          # noqa: E402
from app.net import NetClient          # noqa: E402
from app.parser import ChannelParser   # noqa: E402
from app import verifier               # noqa: E402

ET = verifier.ET


# ----------------------------- 网络小工具 -----------------------------
class _Recorder(urllib.request.HTTPRedirectHandler):
    """记录每一次重定向（code + Location + 落点），用于还原 302 链。"""

    def __init__(self):
        self.hops = []

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.hops.append({'code': code, 'from': req.full_url,
                          'location': headers.get('Location'), 'to': newurl})
        return super(_Recorder, self).redirect_request(req, fp, code, msg, headers, newurl)


def trace_redirects(net, url, timeout=12):
    """手工跟随重定向并记录每一跳（只读 GET，读 1 字节即断开）。"""
    rec = _Recorder()
    opener = urllib.request.build_opener(
        rec, urllib.request.HTTPSHandler(context=net._ssl_ctx))
    req = urllib.request.Request(url, headers={'User-Agent': net.ua,
                                               'Accept-Encoding': 'identity'})
    try:
        r = opener.open(req, timeout=timeout)
        out = {'final_status': r.status, 'final_url': r.geturl(), 'hops': rec.hops}
        r.read(1)
        return out
    except urllib.error.HTTPError as e:
        return {'error': 'HTTPError', 'code': e.code, 'hops': rec.hops}
    except Exception as e:  # noqa: BLE001
        return {'error': type(e).__name__, 'msg': str(e)[:150], 'hops': rec.hops}


def _attach_query(url, base_final_url):
    """把 base 的 query 拼回（用于对照实验：分片带 sig 会不会 200）。"""
    if '?' in url:
        return url
    q = urlsplit(base_final_url).query
    return url + ('?' + q if q else '')


def _head(b, n=24):
    return b[:n].hex() if isinstance(b, (bytes, bytearray)) else ''


def _boxes(b):
    tags = []
    for t in (b'ftyp', b'styp', b'moof', b'mdat', b'moov', b'sidx'):
        if t in b[:8192]:
            tags.append(t.decode('ascii'))
    return tags


_NUM_TOKEN_RE = re.compile(r'\$(Number|Time)%0(\d+)d\$')


def fill_segment_template(tmpl, rid, start_num=1, seg_start_t=0):
    """替换 SegmentTemplate 的 $RepresentationID$/$Number$/$Time$（含 %0Nd 形态）。

    ★ $Time$ 必须用 SegmentTimeline 首个 ``S@t`` 的**真实值**（SegmentTimeline 下
    ``$Time$`` 取 ``S@t``；``SegmentTemplate@duration`` 固定时长路径才用 ``$Number$``）。
    原脚本漏替换 ``$Time$`` -> 分片 URL 残留字面量 -> 恒 404（无效证据）。
    纯函数，便于离线单测（tests/test_probe_real_addressing.py）。
    """
    p = (tmpl or '').replace('$RepresentationID$', rid or '')
    p = p.replace('$Number$', str(start_num))
    p = p.replace('$Time$', str(seg_start_t))

    def _sub(m):
        val = seg_start_t if m.group(1) == 'Time' else start_num
        return str(val).zfill(int(m.group(2)))

    return _NUM_TOKEN_RE.sub(_sub, p)


def expand_timeline(timeline_el, tmpl_duration=0):
    """把 SegmentTimeline 展开为各分片的起始时间（$Time$ 候选值）列表。

    每个 ``S`` 的 ``@t``（缺省承继上一个 S 的结束）、``@d``（缺省用 template@duration）、
    ``@r``（重复次数）。live DVR 窗口会滚动，**首个 S@t 的段可能已滚出窗口 -> 404**，
    故需取样多个位置。纯函数，便于离线单测。
    """
    times = []
    cur = None
    if timeline_el is None:
        return times
    for sv in timeline_el:
        if verifier._tag_local(sv.tag) != 'S':
            continue
        t = verifier._attr_local(sv, 't')
        d = verifier._attr_local(sv, 'd')
        r = verifier._attr_local(sv, 'r')
        t = int(t) if (t is not None and str(t).lstrip('-').isdigit()) else cur
        d = int(d) if (d is not None and str(d).isdigit()) else int(tmpl_duration or 0)
        r = int(r) if (r is not None and str(r).isdigit()) else 0
        if t is None:
            t = 0
        for i in range(r + 1):
            times.append(t + i * d)
        cur = t + (r + 1) * d
    return times


def _try_fetch(net, url, ua, limit):
    try:
        st, body, ct, final = net.fetch_ex(url, ua=ua, limit=limit)
        return {'status': st, 'bytes': len(body), 'ct': (ct or '')[:40],
                'final': final, 'head_hex': _head(body), 'boxes': _boxes(body)}
    except Exception as e:  # noqa: BLE001
        return {'error': type(e).__name__, 'code': getattr(e, 'code', None),
                'msg': str(e)[:140]}


def _pick(entries, keys):
    for e in entries:
        if (e.name or '').strip() in keys:
            return e
    for e in entries:
        nm = (e.name or '').strip()
        if any(k in nm for k in keys):
            return e
    return None


# ----------------------------- 单频道收口 -----------------------------
def report_one(cfg, p, net, entry, kodiprop, probe_limit):
    url = (entry.url or '').strip()
    kp = kodiprop.get(url) or {}
    lt = (kp.get('license_type') or '').strip().lower()
    obj = kp.get('license_key_obj')
    exp_drm = 'clearkey' if lt == 'clearkey' else None
    exp_kid = None
    if exp_drm and isinstance(obj, dict):
        keys = obj.get('keys') or []
        if keys and isinstance(keys[0], dict):
            exp_kid = ChannelParser.kid_hex(keys[0].get('kid'))
    out = {'name': entry.name, 'url': url,
           'manifest_type': kp.get('manifest_type'), 'license_type': kp.get('license_type'),
           'expect_drm': exp_drm, 'expect_kid': exp_kid}

    # 1) 302 链（原始 MPD URL）
    out['redirect_trace'] = trace_redirects(net, url)
    fin = out['redirect_trace'].get('final_url') or url
    out['final_has_query'] = ('?' in fin)
    out['final_query'] = urlsplit(fin).query[:80]

    # 2) 真实 probe_dash 路径（包装 fetch_ex 记录每一步）
    calls = []
    orig = net.fetch_ex

    def logged(u, ua=None, limit=None, timeout=None):
        try:
            st, body, ct, f = orig(u, ua=ua, limit=limit, timeout=timeout)
            calls.append({'url': u, 'status': st, 'bytes': len(body), 'final': f,
                          'final_has_query': ('?' in (f or ''))})
            return st, body, ct, f
        except Exception as e:  # noqa: BLE001
            calls.append({'url': u, 'error': type(e).__name__,
                          'code': getattr(e, 'code', None), 'msg': str(e)[:120]})
            raise
    net.fetch_ex = logged
    try:
        res = verifier.probe(net, url, net.ua, limit=probe_limit,
                             unsupported_schemes=[], aes_probe_bytes=None,
                             expect_drm=exp_drm, expect_kid=exp_kid)
    finally:
        net.fetch_ex = orig
    out['probe_calls'] = calls
    out['probe_result'] = res

    # 3) 分片寻址复算（与 probe_dash 同一套 helper）：init / media 模板 + base 链
    seg = {}
    try:
        st, body, _ct, final = net.fetch_ex(url, ua=net.ua,
                                            limit=max(probe_limit, verifier._MPD_FETCH_LIMIT))
        root = ET.fromstring(body)
        periods = list(verifier._iter_local(root, 'Period'))
        period = periods[0] if periods else None
        adapt = None
        if period is not None:
            for a in verifier._iter_local(period, 'AdaptationSet'):
                ct = (verifier._attr_local(a, 'contentType') or '').lower()
                mt = (verifier._attr_local(a, 'mimeType') or '').lower()
                if ct == 'video' or mt == 'video/mp4':
                    adapt = a
                    break
        rep = None
        if adapt is not None:
            for rp in verifier._iter_local(adapt, 'Representation'):
                rep = rp
                break
        base = final
        chain = [verifier._direct_baseurl(root)]
        if period is not None:
            chain.append(verifier._direct_baseurl(period))
        if adapt is not None:
            chain.append(verifier._direct_baseurl(adapt))
        if rep is not None:
            chain.append(verifier._direct_baseurl(rep))
        for b in chain:
            if b:
                base = urljoin(base, b)
        init_tmpl = media_tmpl = None
        start_num = 1
        seg_duration = None      # SegmentTemplate@duration（固定时长路径）
        timeline_el = None       # SegmentTimeline（$Time$ 与候选时间来源）
        for scope in (adapt, rep):
            if scope is None:
                continue
            for tmpl in verifier._iter_local(scope, 'SegmentTemplate'):
                init_tmpl = verifier._attr_local(tmpl, 'initialization') or init_tmpl
                media_tmpl = verifier._attr_local(tmpl, 'media') or media_tmpl
                s = verifier._attr_local(tmpl, 'startNumber')
                if s and str(s).isdigit():
                    start_num = int(s)
                d = verifier._attr_local(tmpl, 'duration')
                if d and str(d).isdigit():
                    seg_duration = int(d)
                if timeline_el is None:
                    for tl in verifier._iter_local(tmpl, 'SegmentTimeline'):
                        timeline_el = tl
                        break
        times = expand_timeline(timeline_el, seg_duration)
        seg_start_t = times[0] if times else 0
        rid = (verifier._attr_local(rep, 'id') if rep is not None else '') or ''

        def _seg_url_for(t):
            pth = fill_segment_template(media_tmpl, rid, start_num, t)
            return urljoin(base, pth) if pth else ''

        init_path = fill_segment_template(init_tmpl, rid, start_num, seg_start_t)
        seg = {'basechain': chain, 'base': base, 'final_has_query': ('?' in (final or '')),
               'init_tmpl': init_tmpl, 'media_tmpl': media_tmpl, 'start': start_num,
               'timeline_segments': len(times), 'seg_start_t': seg_start_t,
               'seg_duration': seg_duration,
               'init_url': urljoin(base, init_path) if init_path else ''}
        if seg['init_url']:
            seg['init_fetch'] = _try_fetch(net, seg['init_url'], net.ua, 131072)
            seg['init_url_with_sig'] = _attach_query(seg['init_url'], final)
            if seg['init_url_with_sig'] != seg['init_url']:
                seg['init_with_sig_fetch'] = _try_fetch(net, seg['init_url_with_sig'],
                                                        net.ua, 131072)
        # 候选分片：首/中/末-1/末（live DVR 窗口滚动，首个 S@t 的段可能已滚出 -> 404）
        cand_t = []
        if times:
            n = len(times)
            for i in sorted(set([0, n // 2, max(0, n - 2), n - 1])):
                cand_t.append(times[i])
        elif media_tmpl:
            cand_t = [0]
        cand = []
        for t in cand_t:
            u = _seg_url_for(t)
            if not u:
                continue
            cand.append({'t': t, 'url': u, 'fetch': _try_fetch(net, u, net.ua, 393216)})
        seg['seg_candidates'] = cand
        ok = next((c for c in cand if c['fetch'].get('status') == 200), None)
        if ok:
            seg['seg_ok_t'] = ok['t']
            seg['seg_ok_url'] = ok['url']
            seg['seg_url'] = ok['url']
            uu = urlsplit(ok['url'])
            noq = urlunsplit((uu.scheme, uu.netloc, uu.path, '', uu.fragment))
            seg['seg_url_noq'] = noq
            seg['seg_noq_fetch'] = _try_fetch(net, noq, net.ua, 393216)
            ws = _attach_query(ok['url'], final)
            seg['seg_url_with_sig'] = ws
            if ws != noq:
                seg['seg_with_sig_fetch'] = _try_fetch(net, ws, net.ua, 393216)
        elif cand:
            seg['seg_url'] = cand[0]['url']
    except Exception as e:  # noqa: BLE001
        seg['error'] = '%s: %s' % (type(e).__name__, str(e)[:160])
    out['segment'] = seg
    return out


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('playlist', help='User-supplied local M3U file')
    args.add_argument('--channel', action='append', required=True, help='Channel name; repeat for multiple channels')
    options = args.parse_args()
    m3u = options.playlist
    with open(m3u, 'r', encoding='utf-8', errors='replace') as f:
        text = f.read()
    cfg = Config(ROOT)
    p = ChannelParser(cfg)
    net = NetClient(cfg)
    probe_limit = int(cfg.settings['network'].get('probe_fetch_limit', 393216))
    entries = p.parse_m3u(text)
    kodiprop = p.parse_kodiprop(text)

    wanted = [(name, [name]) for name in options.channel]
    rep = {'root': ROOT, 'm3u': m3u, 'entries': len(entries),
           'probe_limit': probe_limit, 'probe_fetch_limit_src': 'settings.network',
           'channels': []}
    for label, keys in wanted:
        e = _pick(entries, keys)
        if e is None:
            rep['channels'].append({'label': label, 'error': 'not_found',
                                    'names_sample': [x.name for x in entries][:40]})
            continue
        try:
            rep['channels'].append(report_one(cfg, p, net, e, kodiprop, probe_limit))
        except Exception as ex:  # noqa: BLE001
            rep['channels'].append({'label': label, 'error': type(ex).__name__ + ': ' + str(ex)[:160]})
    print(json.dumps(rep, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
