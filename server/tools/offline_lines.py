# -*- coding: utf-8 -*-
"""人工下线线路（按 url_key 精确点位，**不按位置**）。

背景：verifier/health 只能判"能否起播 / 能否解码"，判不了"画面在不在动"。
一条只播静止测试卡（或全黑）的源会以最快起播、完全正常的解码通过全部现有判据，
被优先投递给客户端，用户看到一张永远不动的画面。这类判定只能由人工（或脱机
画面检测）给出，故需要一个人工下线通道。

为什么不能只是"从 lineups 删一行"：
  `health.rebuild_lineups()` 每轮（默认 30min）对每个频道 **DELETE + INSERT**
  重建 lineups，取的是 `status='active' AND av LIKE 'video%' ORDER BY score DESC
  LIMIT max_lines`。只删 lineups 行 ⇒ 最长 30 分钟就被自动写回。

为什么用 `status='blacklisted'` 而不是 'dead'：
  - `rebuild_lineups` 只取 'active' ⇒ 不会被重建写回（持久）；
  - `health.run()` 只探活 'active'/'flaky'/'dead' ⇒ 不在探活集合，
    不会被"连续 2 次成功自动回归"救回（'dead' 会被救回，不持久）；
  - `verifier._select_batch` 只取 'candidate' ⇒ 不会被重新验证改写状态；
  - `discovery.ingest` 对已存在的 url_key 只刷 `last_seen`（或签名轮换刷新
    url/drm_key/kid/profile），**不重置 status** ⇒ 再次被发现也不会复活；
  - `db.cleanup` 只清理 `channel_key=''` 的黑名单/候选 ⇒ channel_key 非空者安全。

不物理删除 sources 行（对端第五节 1 的要求）：保留可追溯、可一键回滚。

用法：
    python3 tools/offline_lines.py --db data/iptv.db --targets <targets.json>            # 预演
    python3 tools/offline_lines.py --db data/iptv.db --targets <targets.json> --apply    # 落库

targets.json:
    {"reason": "画面静止（客户端全量线路体检 2026-09-15）",
     "items": [{"channel": "CCTV13", "url_key": "42d9...",
                "symptom": "画面静止", "evidence": "mad_max=0.02 bri=148.8"}]}

本工具**不做任何网络请求**，只读/写本机 SQLite。
"""
import argparse
import json
import sqlite3
import sys

OFFLINE_STATUS = 'blacklisted'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--db', required=True, help='iptv.db 路径')
    ap.add_argument('--targets', required=True, help='下线目标 JSON')
    ap.add_argument('--apply', action='store_true', help='真正落库（默认仅预演）')
    a = ap.parse_args()

    with open(a.targets, encoding='utf-8') as f:
        spec = json.load(f)
    items = spec.get('items') or []
    reason = spec.get('reason') or 'manual-offline'
    if not items:
        print('目标为空，退出', file=sys.stderr)
        return 2

    conn = sqlite3.connect(a.db)
    conn.row_factory = sqlite3.Row
    receipt = {'db': a.db, 'apply': bool(a.apply), 'reason': reason,
               'targets': len(items), 'results': []}

    for it in items:
        ch, uk = it.get('channel', ''), it.get('url_key', '')
        row = conn.execute(
            'SELECT id, channel_key, status, score, url FROM sources WHERE url_key=?',
            (uk,)).fetchone()
        rec = {'channel': ch, 'url_key': uk, 'symptom': it.get('symptom', ''),
               'evidence': it.get('evidence', '')}
        if row is None:
            rec.update({'action': 'not-found', 'note': '库内无此 url_key'})
            receipt['results'].append(rec)
            continue
        rec.update({'source_id': row['id'], 'db_channel': row['channel_key'],
                    'prev_status': row['status'], 'score': row['score'],
                    'url': row['url']})
        in_lineups = conn.execute(
            'SELECT rank FROM lineups WHERE source_id=?', (row['id'],)).fetchone()
        rec['in_lineups_rank'] = in_lineups['rank'] if in_lineups else None
        # 频道一致性：库内频道必须与对端所述频道一致，否则拒绝（防误伤）
        if ch and row['channel_key'] != ch:
            rec.update({'action': 'refused',
                        'note': '频道不一致：库内=%s 目标=%s' % (row['channel_key'], ch)})
            receipt['results'].append(rec)
            continue
        if row['status'] == OFFLINE_STATUS:
            rec.update({'action': 'already-offline'})
            receipt['results'].append(rec)
            continue
        if a.apply:
            conn.execute(
                "UPDATE sources SET status=?, verdict_reason=? WHERE id=?",
                (OFFLINE_STATUS,
                 'manual-offline: %s | %s' % (rec['symptom'] or reason,
                                              rec.get('evidence', '')),
                 row['id']))
            cur = conn.execute('DELETE FROM lineups WHERE source_id=?', (row['id'],))
            rec.update({'action': 'offlined', 'lineups_deleted': cur.rowcount})
        else:
            rec.update({'action': 'would-offline',
                        'lineups_would_delete': 1 if in_lineups else 0})
        receipt['results'].append(rec)

    if a.apply:
        conn.commit()
    conn.close()

    if a.apply:
        print('已落库 %d 条（status=%s）；请随后重排线路并重建产物'
              % (sum(1 for r in receipt['results'] if r['action'] == 'offlined'),
                 OFFLINE_STATUS), file=sys.stderr)
    else:
        print('预演模式（未落库）；加 --apply 才生效', file=sys.stderr)
    print(json.dumps(receipt, ensure_ascii=False, indent=1))
    return 0


if __name__ == '__main__':
    sys.exit(main())
