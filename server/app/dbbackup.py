# -*- coding: utf-8 -*-
"""sqlite 在线备份工具（纯标准库；供独立运维/QA 进程以只读源连接使用）。

本模块从 app.db 原样搬出（纯搬移、零行为变更）：_readonly_uri / _quick_check_ok /
_remove_quiet / online_backup。app.db 以 re-export 保持旧 import 路径可用
（server/tests/test_db_backup.py from app.db import online_backup 等）。

生产主进程零调用；行为与搬移前逐字一致。
"""
import os
import pathlib
import sqlite3
import tempfile

# ---------- 在线备份（纯标准库；供独立运维/QA 进程以只读源连接使用） ----------
def _readonly_uri(path):
    """文件系统路径 -> SQLite 只读 URI（mode=ro）。

    pathlib.as_uri() 对路径做百分号转义，避免路径中的 `?`/`#`/`%` 被误当 URI 定界符。
    显式 mode=ro 且**不**加 immutable=1 —— immutable 会让 SQLite 忽略 WAL，
    活库中已提交但未 checkpoint 的事务会被静默漏掉。
    """
    return pathlib.Path(path).expanduser().resolve().as_uri() + '?mode=ro'


def _quick_check_ok(path):
    """目标快照校验：仅当 PRAGMA quick_check 严格返回单个 'ok' 才为真（其余均视为损坏）。"""
    conn = sqlite3.connect(_readonly_uri(path), uri=True)
    try:
        rows = conn.execute('PRAGMA quick_check').fetchall()
    finally:
        conn.close()
    return rows == [('ok',)]


def _remove_quiet(path):
    """尽力删除路径（不存在/无权限均忽略）；用于临时文件与陈旧 sidecar 清理。"""
    try:
        os.remove(path)
    except OSError:
        pass


def online_backup(src_path, dst_path):
    """把（可能处于 WAL 模式的）源库在线备份为一致快照并原子发布到 dst_path。

    流程：只读源连接（mode=ro，WAL 可见）-> Connection.backup 写目标同目录唯一临时文件
    -> 关闭连接后以只读连接跑 quick_check -> 严格为 ok 才 os.replace 原子发布。
    任一步失败都删除临时文件（含其 -wal/-shm/-journal）且保留既有目标不变；
    拒绝源/目标指向同一真实路径。不修改源库、不主动 checkpoint。返回发布后的目标绝对路径。
    """
    src_real = os.path.realpath(os.path.expanduser(os.fspath(src_path)))
    dst_real = os.path.realpath(os.path.expanduser(os.fspath(dst_path)))
    same = src_real == dst_real
    if not same and os.path.exists(src_real) and os.path.exists(dst_real):
        same = os.path.samefile(src_real, dst_real)   # 同时拦下硬链接
    if same:
        raise ValueError('拒绝备份：源与目标指向同一路径 %s' % src_real)

    dst_dir = os.path.dirname(dst_real) or os.curdir
    os.makedirs(dst_dir, exist_ok=True)

    # 唯一临时文件必须与目标同目录，os.replace 才是同文件系统内的原子替换。
    fd, tmp_path = tempfile.mkstemp(
        prefix=os.path.basename(dst_real) + '.', suffix='.tmp', dir=dst_dir)
    os.close(fd)
    try:
        src_conn = sqlite3.connect(_readonly_uri(src_real), uri=True)
        try:
            dst_conn = sqlite3.connect(tmp_path)
            try:
                src_conn.backup(dst_conn)      # 含已提交 WAL，落盘一致快照
                dst_conn.commit()
                # backup 会把源库头的 WAL 标记一并复制过去；只读连接无法为 WAL 库建 -shm，
                # 故把快照归一到 rollback-journal 模式（只改目标头，数据不变）——
                # 既让后续只读 quick_check 可打开，也让发布出的快照自包含。
                mode = dst_conn.execute('PRAGMA journal_mode=DELETE').fetchone()
                if not mode or str(mode[0]).lower() != 'delete':
                    raise RuntimeError('备份无法归一为自包含 DELETE journal 模式')
            finally:
                dst_conn.close()
        finally:
            src_conn.close()
        if not _quick_check_ok(tmp_path):
            raise RuntimeError('备份校验失败（quick_check != ok），未发布：%s' % dst_real)
        os.replace(tmp_path, dst_real)
        # 旧目标的 sidecar 属于被替换掉的上一版；快照已自包含，清掉防误用。
        # 仅在替换成功后执行：失败路径不碰它们（保留旧目标完整）。
        for suffix in ('-wal', '-shm', '-journal'):
            _remove_quiet(dst_real + suffix)
        return dst_real
    finally:
        # 成功时 tmp_path 主文件已被 replace 移走，此处清理其 sidecar；失败时整体清理。
        for suffix in ('', '-wal', '-shm', '-journal'):
            _remove_quiet(tmp_path + suffix)
