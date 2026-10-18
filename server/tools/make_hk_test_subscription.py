#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「香港源测试订阅样本」—— 用真实渲染代码产出，契约与实际部署一致。

用途：用户的自研 media3 播放器需要**先能加载一条 DRM 频道**才能验证"能不能出画"。
但线上 `url_lists[].enabled=false`，且 DRM 源按设计**不进 `live.txt`/`live.m3u`**、
只进第四件套 `live.media3.json`，所以用户手上拿不到可测的产物。本脚本离线合成。

产出两份：
  1. `docs/live.media3.sample.json`    —— 契约与生产一致（schema_version=2，含内联 clearkey）。
                                         这是自研客户端**应当消费**的格式。
  2. `docs/live.kodiprop.sample.m3u`   —— 源清单原始 `#KODIPROP` 形态。
                                         给「原生支持 KODIPROP 的播放器」(Kodi/TVB 类) 冒烟测试。

用法：
  python tools/make_hk_test_subscription.py            # 用本地已有的 data/_hk/xiaotan_fresh.m3u
  python tools/make_hk_test_subscription.py --fetch    # 先从上游拉当日清单再生成（需外网）

⚠️ 上游签名 24 小时有效；`--fetch` 得到的样本当日有效，逾期需重跑。
"""
import argparse
import json
import os
import ssl
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.builder import OutputBuilder            # noqa: E402
from app.config import Config                    # noqa: E402
from app.db import Database, utcnow              # noqa: E402
from app.discovery import SourceDiscovery        # noqa: E402
from app.parser import ChannelParser             # noqa: E402

LIST_URL = 'https://xiaotan.860775.xyz/mytvsuper.m3u'
UA = ('Mozilla/5.0 (Linux; Android 9; SHIELD Android TV Build/PPR1.180610.011) '
      'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36')
SRC = os.path.join(ROOT, 'data', '_hk', 'xiaotan_fresh.m3u')
OUT_JSON = os.path.join(ROOT, 'docs', 'live.media3.sample.json')
OUT_M3U = os.path.join(ROOT, 'docs', 'live.kodiprop.sample.m3u')
# 上游台名白名单 —— 覆盖用户关注的港台目标键
WANT = ('無線新聞台', '無綫新聞台', 'TVB Plus', '翡翠台', '明珠台')
CLEARKEY_UUID_V1 = 'urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e'


def fetch_list(dest):
    """从上游拉当日清单并落盘（本机沙箱出口异常时改在 NAS 上跑）。"""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(LIST_URL, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=30, context=ctx) as r:
        body = r.read()
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'wb') as f:
        f.write(body)
    print('上游清单已刷新: %s (%d 字节)' % (dest, len(body)))


def entries_from_playlist(text):
    """解析清单 -> [{name, url, kodiprop}]，保留原始 #KODIPROP 行（供 m3u 样本原样输出）。"""
    lines = [l.rstrip() for l in text.splitlines()]
    out = []
    for i, ln in enumerate(lines):
        if not ln.startswith('#EXTINF'):
            continue
        name = ln.split(',')[-1].strip()
        if name not in WANT:
            continue
        props, url = [], None
        for j in range(i + 1, min(i + 10, len(lines))):
            if lines[j].startswith('#KODIPROP:'):
                props.append(lines[j])
            elif lines[j].startswith('http'):
                url = lines[j]
                break
        if url:
            out.append({'name': name, 'url': url, 'kodiprop': props, 'extinf': ln})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--fetch', action='store_true', help='先从上游拉当日清单')
    args = ap.parse_args()

    if args.fetch:
        fetch_list(SRC)
    if not os.path.isfile(SRC):
        print('缺少 %s（当日清单）；加 --fetch 或先手工放置' % SRC)
        return 1
    text = open(SRC, encoding='utf-8', errors='ignore').read()
    ents = entries_from_playlist(text)
    if not ents:
        print('清单里没找到 %s' % (WANT,))
        return 1
    print('从当日清单取到 %d 条：%s' % (len(ents), [e['name'] for e in ents]))

    # 解析 KODIPROP（用真实解析器，保证与生产一致）
    props = ChannelParser.parse_kodiprop(text)

    # ---- 用真实 ingest 路径落临时库，再用真实渲染器产出 live.media3.json ----
    tmpdir = tempfile.mkdtemp(prefix='hk_sample_')
    cfg = Config(ROOT)
    db = Database(os.path.join(tmpdir, 't.db'))
    db.migrate(cfg.settings)
    disc = SourceDiscovery(cfg, db, None)

    rows = []
    for e in ents:
        p = props.get(e['url'])
        if not p:
            print('  跳过（无 KODIPROP）：%s' % e['name'])
            continue
        raw = p.get('license_key')
        obj = p.get('license_key_obj')
        if isinstance(raw, dict):
            raw = json.dumps(raw, ensure_ascii=False)
        if not isinstance(obj, dict) or not obj.get('keys'):
            print('  跳过（密钥不可解析）：%s' % e['name'])
            continue
        kid_b64 = obj['keys'][0].get('kid', '')
        kid_hex = ChannelParser.kid_hex(kid_b64)
        key = ChannelParser(cfg).normalize_channel(e['name'])
        if not key:
            print('  跳过（归一失败）：%s' % e['name'])
            continue
        if any(r['key'] == key for r in rows):
            print('  跳过（目标键 %s 已收录）：%s' % (key, e['name']))
            continue
        rows.append({'name': e['name'], 'key': key, 'kid_hex': kid_hex, 'raw': raw})
        disc.ingest([{
            'url': e['url'], 'name': e['name'], 'channel_key': key,
            'origin': 'sample:url-list', 'status': 'active', 'av': 'video',
            'container': 'dash', 'encryption': 'cenc', 'drm': 'clearkey',
            'keyformat': CLEARKEY_UUID_V1, 'kid': kid_hex, 'drm_key': raw,
            'caps_required': json.dumps({'dash': True, 'dash_clearkey': True}),
        }], 'sample:url-list')
        sid = db.query_one('SELECT id FROM sources WHERE channel_key=?', (key,))['id']
        # 本脚本为了离线合成样本，跳过了真实验证流程（probe → record()）。而 `profile`
        # 是由 `derive_profile()`（ingest 路径）或 record() 判定 ok 时写入的；为了让样本
        # 与真实产物一致，这里显式置为 'media3-json'，否则会显示 'direct'
        # —— 那是"可直投 TVBox 轨"的意思，与真实产物不符，会把客户端实现带偏。
        db.execute("UPDATE sources SET profile='media3-json' WHERE id=?", (sid,))
        db.execute('INSERT OR REPLACE INTO lineups(channel_key, source_id, rank, updated_at)'
                   ' VALUES (?,?,?,?)', (key, sid, 0, utcnow()))

    b = OutputBuilder(cfg, db)
    collect = getattr(b, '_collect_media3_items', None)
    items = collect() if collect else b._collect_items(
        profiles=tuple(__import__('app.db', fromlist=['x']).DELIVERABLE_PROFILES))
    doc = json.loads(b.render_media3_json(items))
    db.close()

    with open(OUT_JSON, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write('\n')
    n_line = sum(len(c.get('lines') or []) for c in doc.get('channels') or [])
    print('写入 %s（schema_version=%s，%d 频道 / %d 线路）'
          % (OUT_JSON, doc.get('schema_version'), len(doc.get('channels') or []), n_line))

    # ---- 源清单原始 KODIPROP 形态，供 Kodi/TVB 类播放器冒烟测试 ----
    with open(OUT_M3U, 'w', encoding='utf-8', newline='\n') as f:
        f.write('#EXTM3U\n')
        for e in ents:
            f.write(e['extinf'] + '\n')
            for line in e['kodiprop']:
                f.write(line + '\n')
            f.write(e['url'] + '\n')
    print('写入 %s（%d 条，含 #KODIPROP）' % (OUT_M3U, len(ents)))

    # ---- 交叉核对：清单 kid(base64url) 是否等于入库 kid(hex) ----
    print()
    print('内联密钥核对：')
    for r in rows:
        print('  %-10s key=%-10s kid(base64url→hex)=%s' % (r['name'], r['key'], r['kid_hex']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
