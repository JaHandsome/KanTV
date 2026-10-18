#!/usr/bin/env python3
"""通过 SSH 标准输入准备候选；只有 --activate 才切家庭稳定入口。"""
from __future__ import annotations

import argparse
import fcntl
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile

import lan_release as release


MANIFEST_NAMES = tuple(release.manifest_name_for_abi(abi) for abi in release.SUPPORTED_ABIS)


def checked_directory(directory, application_id, cert):
    """远端无需 Android SDK：用固定身份和清单 hash 绑定既有回滚 APK。"""
    cert = cert.lower()
    manifests = {}
    for abi, name in zip(release.SUPPORTED_ABIS, MANIFEST_NAMES):
        path = Path(directory) / name
        if path.is_symlink():
            raise release.ManifestError('MANIFEST_INVALID', '清单不能是符号链接')
        manifest = release.load_manifest_file(str(path))
        if manifest['applicationId'] != application_id:
            raise release.ManifestError(release.REASON_IDENTITY_MISMATCH, '远端包名不符')
        if manifest['signingCertSha256'] != cert:
            raise release.ManifestError(release.REASON_CERT, '远端证书不符')
        apk = Path(directory) / manifest['apkPath']
        if apk.is_symlink() or not apk.is_file():
            raise release.ManifestError(release.REASON_APK_MISSING, '回滚/候选 APK 缺失或为链接')
        if apk.stat().st_size != manifest['size']:
            raise release.ManifestError(release.REASON_SIZE, '回滚/候选 APK 大小不符')
        if release.sha256_file(str(apk)) != manifest['sha256']:
            raise release.ManifestError(release.REASON_HASH, '回滚/候选 APK 哈希不符')
        release._check_apk_abi(str(apk), abi, strict=True)
        manifests[abi] = manifest
    # 既有两个通道可以来自不同历史版本；候选则由本地 verify-dual 强制同版。
    return manifests


def apply_candidate(staging, target, application_id, cert, activate=False):
    """同一目录锁保护门禁与切换；所有本工具写入者必须遵守该锁。"""
    target = Path(target)
    lock_fd = os.open(target / '.release.lock', os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(lock_fd, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = checked_directory(target, application_id, cert)
        candidate = checked_directory(staging, application_id, cert)
        identities = {tuple(m[field] for field in ('applicationId', 'versionCode', 'versionName',
                                                  'signingCertSha256')) for m in candidate.values()}
        if len(identities) != 1 or len({m['apkPath'] for m in candidate.values()}) != 2:
            raise release.ManifestError(release.REASON_IDENTITY_MISMATCH, '候选双通道身份不一致')
        version = candidate[release.LEGACY_ABI]['versionCode']
        if version <= max(m['versionCode'] for m in current.values()):
            raise release.ManifestError(release.REASON_NOT_NEWER, '候选必须高于远端两个通道')
        # 在写任何版本文件前检查全部冲突；link() 同时封住检查后的竞争窗口。
        for manifest in candidate.values():
            path = target / manifest['apkPath']
            if path.exists() or path.is_symlink():
                if path.is_symlink() or not path.is_file() or release.sha256_file(str(path)) != manifest['sha256']:
                    raise release.ManifestError(release.REASON_HASH, '同名 APK 内容不同；拒绝覆盖')
        originals = {name: (target / name).read_text(encoding='utf-8') for name in MANIFEST_NAMES}
        candidates = target / 'candidates'
        if candidates.is_symlink():
            raise release.ManifestError('MANIFEST_INVALID', '候选目录不能是符号链接')
        candidates.mkdir(exist_ok=True)
        saved = Path(tempfile.mkdtemp(prefix=f'v{version}-', dir=candidates))
        for name in MANIFEST_NAMES:
            release.atomic_write_text(str(saved / name), (Path(staging) / name).read_text(encoding='utf-8'))
            release.atomic_write_text(str(saved / ('rollback-' + name)), originals[name])
        release._fsync_directory(str(candidates))
        for manifest in candidate.values():
            destination = target / manifest['apkPath']
            try:
                # 已核 hash 的上传临时文件与目标同一文件系统，无需再次复制大 APK。
                os.link(Path(staging) / manifest['apkPath'], destination)
            except FileExistsError:
                if destination.is_symlink() or release.sha256_file(str(destination)) != manifest['sha256']:
                    raise release.ManifestError(release.REASON_HASH, '并发同名 APK 冲突；拒绝覆盖')
        release._fsync_directory(str(target))
        if activate:
            try:
                for name in reversed(MANIFEST_NAMES):
                    release.atomic_write_text(str(target / name), (saved / name).read_text(encoding='utf-8'))
            except BaseException:
                # 保留候选和回滚快照；恢复失败必须上报，不能声称发布成功。
                failures = []
                for name in MANIFEST_NAMES:
                    try:
                        release.atomic_write_text(str(target / name), originals[name])
                    except OSError:
                        failures.append(name)
                if failures:
                    raise release.ManifestError('ROLLBACK_FAILED', '清单恢复失败；保留的 rollback 快照需人工恢复')
                raise
        return saved.name


def receive(stream, target, application_id, cert, expect_hostname, activate=False):
    if socket.gethostname() != expect_hostname:
        raise release.ManifestError(release.REASON_IDENTITY_MISMATCH, 'NAS hostname 不符')
    target = Path(target)
    if not target.is_dir() or target.is_symlink():
        raise release.ManifestError('MANIFEST_INVALID', '远端发布目录不存在或为链接')
    # 上传只进入同文件系统唯一临时目录；断流、缺文件或坏 hash 均不触碰旧 APK/清单。
    with tempfile.TemporaryDirectory(prefix='.upload-', dir=target) as staging:
        seen = set()
        total = 0
        with tarfile.open(fileobj=stream, mode='r|') as archive:
            for member in archive:
                name = member.name
                if name in seen or not member.isfile() or '/' in name or '\\' in name:
                    raise release.ManifestError('MANIFEST_INVALID', '上传文件不合法')
                limit = 16384 if name in MANIFEST_NAMES else release.MAX_APK_BYTES
                if name not in MANIFEST_NAMES and not release._APK_NAME_RE.fullmatch(name):
                    raise release.ManifestError(release.REASON_APK_PATH, '上传 APK 文件名不合法')
                total += member.size
                if member.size <= 0 or member.size > limit or total > 2 * release.MAX_APK_BYTES + 32768:
                    raise release.ManifestError(release.REASON_SIZE, '上传大小越界')
                seen.add(name)
                with archive.extractfile(member) as source, open(Path(staging) / name, 'xb') as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
        manifests = [release.load_manifest_file(str(Path(staging) / name)) for name in MANIFEST_NAMES]
        if seen != set(MANIFEST_NAMES) | {m['apkPath'] for m in manifests}:
            raise release.ManifestError('MANIFEST_INVALID', '上传文件集合不符')
        result = apply_candidate(staging, target, application_id, cert, activate)
    print(('ACTIVATED ' if activate else 'PREPARED ') + result)


def publish(staging, ssh_host, target, application_id, cert, expect_hostname, activate=False):
    manifests = release.run_verify_dual(staging, expect_application_id=application_id,
                                        expect_signing_cert_sha256=cert)
    # 正式远端发布必须有包内身份读取能力，不能使用 verify 的历史兼容跳过路径。
    for manifest in manifests.values():
        if release.read_apk_metadata(str(Path(staging) / manifest['apkPath'])) is None:
            raise release.ToolchainError('远端发布需要 aapt2 核实包内身份')
    folder = Path(__file__).resolve().parent
    # 将两份标准库脚本作为命令发给 SSH，APK 则走 stdin 流，不复制工具到 NAS。
    bootstrap = ('import types,sys; m=types.ModuleType("lan_release"); '
                 'sys.modules["lan_release"]=m; exec(' + repr((folder / 'lan_release.py').read_text()) +
                 ',m.__dict__); exec(' + repr(Path(__file__).read_text()) + ')')
    args = ['python3', '-c', bootstrap, '--receive', '--release-dir', target,
            '--expect-application-id', application_id, '--expect-signing-cert-sha256', cert,
            '--expect-nas-hostname', expect_hostname]
    if activate:
        args.append('--activate')
    with subprocess.Popen(['ssh', '-o', 'BatchMode=yes', '--', ssh_host, shlex.join(args)],
                          stdin=subprocess.PIPE) as process:
        with tarfile.open(fileobj=process.stdin, mode='w|') as archive:
            for name in (*MANIFEST_NAMES, *(str(m['apkPath']) for m in manifests.values())):
                archive.add(str(Path(staging) / name), arcname=name, recursive=False)
    if process.returncode != 0:
        raise release.ManifestError('REMOTE_FAILED', '远端候选准备/切换失败')



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staging')
    parser.add_argument('--ssh-host')
    parser.add_argument('--release-dir', required=True)
    parser.add_argument('--expect-application-id', required=True)
    parser.add_argument('--expect-signing-cert-sha256', required=True)
    parser.add_argument('--expect-nas-hostname', required=True)
    parser.add_argument('--activate', action='store_true', help='仅在用户完成真机验收且明确要求切换后使用')
    parser.add_argument('--receive', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        if args.receive:
            receive(sys.stdin.buffer, args.release_dir, args.expect_application_id,
                    args.expect_signing_cert_sha256, args.expect_nas_hostname, args.activate)
        else:
            if not args.staging or not args.ssh_host:
                parser.error('--staging and --ssh-host are required')
            publish(args.staging, args.ssh_host, args.release_dir, args.expect_application_id,
                    args.expect_signing_cert_sha256, args.expect_nas_hostname, args.activate)
    except (release.ManifestError, release.ToolchainError, OSError, tarfile.TarError) as error:
        print(f'发布失败: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
