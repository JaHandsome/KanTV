#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5 迁移干跑（只读，绝不写库）。

设计依据：docs/archive/URL_HASH_NORMALIZATION_DESIGN_2026-09-10.md §3.5。

用法（项目根目录下执行）：
    python tools/migrate_v5_dryrun.py [db_path]

行为：
- 以 sqlite3 只读模式（`file:...?mode=ro`）打开库，**绝不写入**（列缺失也安全跳过）；
- 从 config/settings.json 读两级签名参数名单（通过 Config 深合并默认值）；
- 逐行计算 url_key（库内已有 url_key 者优先），输出：
    * 总行数 / 列数；
    * 待回填行数（url_key IS NULL 且非 collision-orphan）；
    * 碰撞组数与各组 keep & drop id（幸存者策略：active 优先 -> last_seen 最大 -> id 最大）；
    * 预期改键行数（url_key <> url_hash，AC-N1 基线期望 0）；
    * 预期行数增减（team-lead 裁定 5：非破坏性 -> 恒为 0）；
- 退出码：0 = 无碰撞组，可安全迁移；2 = 存在碰撞组，需人工复核后再迁移。

本工具是运维前置"看碰撞组"的唯一入口（配合迁移前强制备份），不改任何源码逻辑。
"""
import os
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.config import Config               # noqa: E402
from app.db import COLLISION_ORPHAN_STATUS  # noqa: E402
from app.parser import ChannelParser        # noqa: E402


def _load_settings(root):
    """读 config/settings.json（经 Config 深合并默认值）；失败回退默认 settings。"""
    try:
        return dict(Config(root).settings)
    except Exception:  # noqa: BLE001 - 干跑工具不因配置异常中止
        from app.config import DEFAULT_SETTINGS
        return dict(DEFAULT_SETTINGS)


def _has_column(conn, table, column):
    return any(r[1] == column for r in conn.execute('PRAGMA table_info(%s)' % table))


def dry_run(db_path, settings):
    """只读干跑：返回统计 dict（不修改数据库）。

    返回：
      {
        'rows': 总行数, 'columns': sources 列数,
        'pending_backfill': 待回填行数,
        'collisions': [{'key': uk, 'keep': id, 'keep_status': s, 'drop': [ids]}, ...],
        'orphans': 既存 collision-orphan 行数,
        'would_change_key': 预期改键行数（url_key <> url_hash）,
        'row_delta': 预期行数增减（非破坏性 -> 0）,
      }
    """
    uri = 'file:%s?mode=ro' % db_path.replace('\\', '/')
    conn = sqlite3.connect(uri, uri=True)
    try:
        conn.row_factory = sqlite3.Row
        cols = [r[1] for r in conn.execute('PRAGMA table_info(sources)')]
        has_key = 'url_key' in cols
        select = 'SELECT id, url, status, last_seen%s FROM sources' % (
            ', url_key' if has_key else '')
        rows = [dict(r) for r in conn.execute(select)]
        parser = ChannelParser(Config(ROOT))

        groups, pending, orphans, changed = {}, 0, 0, 0
        for r in rows:
            url = r.get('url') or ''
            uk = (r.get('url_key') if has_key else None)
            if not uk:
                if r.get('status') == COLLISION_ORPHAN_STATUS:
                    orphans += 1
                    continue
                uk = parser.url_key(
                    url, parser.resolve_signed_params(url, settings))
                pending += 1
            groups.setdefault(uk, []).append(r)
            if uk != parser.url_hash(url):
                changed += 1

        collisions = []
        for uk, g in groups.items():
            if len(g) <= 1:
                continue
            keep = max(g, key=lambda r: (1 if r.get('status') == 'active' else 0,
                                         r.get('last_seen') or '', r['id']))
            collisions.append({
                'key': uk,
                'keep': keep['id'],
                'keep_status': keep.get('status'),
                'drop': [r['id'] for r in g if r['id'] != keep['id']],
            })
        collisions.sort(key=lambda c: (-len(c['drop']), c['keep']))
        return {
            'rows': len(rows),
            'columns': len(cols),
            'pending_backfill': pending,
            'collisions': collisions,
            'orphans': orphans,
            'would_change_key': changed,
            'row_delta': 0,   # 非破坏性：落败行转 collision-orphan，绝不 DELETE
        }
    finally:
        conn.close()


def main(argv):
    db_path = argv[0] if argv else None
    if not db_path:
        db_path = Config(ROOT).db_path
    else:
        db_path = os.path.abspath(db_path)
    if not os.path.isfile(db_path):
        print('ERROR: 库文件不存在: %s' % db_path)
        return 1

    settings = _load_settings(ROOT)
    rep = dry_run(db_path, settings)

    print('===== url_key(v5) 迁移干跑（只读）=====')
    print('库文件            : %s' % db_path)
    print('总行数            : %d' % rep['rows'])
    print('sources 列数      : %d' % rep['columns'])
    print('待回填 url_key 行 : %d' % rep['pending_backfill'])
    print('既存孤儿行        : %d' % rep['orphans'])
    print('预期改键行数      : %d (AC-N1 基线期望 0)' % rep['would_change_key'])
    print('预期行数增减      : %+d (非破坏性: 落败行转 %s)'
          % (rep['row_delta'], COLLISION_ORPHAN_STATUS))
    print('碰撞组数          : %d' % len(rep['collisions']))
    for c in rep['collisions']:
        print('  - key=%s keep id=%s(status=%s) drop=%s'
              % (c['key'][:12], c['keep'], c['keep_status'], c['drop']))
    if rep['collisions']:
        print('\n⚠️  存在碰撞组，请人工复核后再迁移（迁移前务必备份 data/iptv.db）。')
        return 2
    print('\n✅ 无碰撞组，可安全迁移。')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
