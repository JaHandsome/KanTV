#!/usr/bin/env python3
"""Fail closed on common private files, personal paths and deployment defaults; never print values."""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys

PRIVATE_FILE = re.compile(r'(^|/)(\.env(?:\..*)?|settings\.json|hub_client_token\.txt|[^/]*\.(?:jks|keystore|p12|apk|aab))$')
PRIVATE_DIR = re.compile(r'(^|/)(?:\.signing|\.private|\.archive|deliveries|runtime|bundled-default)(/|$)|^server/data/')
PERSONAL_PATH = re.compile(r'/Users/[A-Za-z0-9_.-]{2,}(?:/|\b)|/volume\d+/')
PRIVATE_IP = re.compile(r'(?<![\d.])(?:10\.(?:\d{1,3}\.){2}\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})(?![\d.])')
SECRET = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{25,}|\bgithub_pat_[A-Za-z0-9_]{35,}|\bAKIA[A-Z0-9]{16}|\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}')
EXAMPLE_IPS = {'10.0.2.2', '192.168.1.10', '192.168.1.100', '192.168.1.2'}
LEGACY_FFMPEG_AAR_PATHS = {
    'app/libs/media3-decoder-ffmpeg-1.11.0-mp3.aar',
    'app/libs/media3-decoder-ffmpeg-1.11.0.aar',
}
LEGACY_FFMPEG_AAR_SHA256 = 'f0ddd7d528320d508713be055e7fa69b24529fecc89d2ee0746916eceefeff38'


def public_paths(root: Path, *, export_candidate: bool = False) -> list[str]:
    result = subprocess.run(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'],
                            cwd=root, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError('public check requires a Git checkout')
    names = sorted(set(filter(None, result.stdout.decode().split('\0'))))
    if not export_candidate:
        return [name for name in names if (root/name).is_file()]
    ignored = subprocess.run(['git', 'check-ignore', '--no-index', '-z', '--stdin'], cwd=root,
                             input=('\0'.join(names)+'\0').encode(), capture_output=True, check=False)
    if ignored.returncode not in (0, 1):
        raise RuntimeError('cannot determine ignored private files')
    excluded = set(ignored.stdout.decode().split('\0'))
    return [name for name in names if name not in excluded and (root/name).is_file()]


def violations(name: str, data: bytes) -> list[tuple[int, str]]:
    hits = []
    if name in LEGACY_FFMPEG_AAR_PATHS or hashlib.sha256(data).hexdigest() == LEGACY_FFMPEG_AAR_SHA256:
        hits.append((0, 'legacy-ffmpeg-binary'))
    if PRIVATE_FILE.search(name) or PRIVATE_DIR.search(name) or name in {
        'docs/PLAYBACK_STABLE_BASELINE.json', 'docs/M3U_BASELINE_GECKO_20261014.json',
        'docs/GITHUB_CLOSEOUT_V53_20261014.json',
    }:
        hits.append((0, 'private-file'))
    if b'\x00' in data:
        return hits
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        return hits
    is_test = '/src/test/' in name or '/src/androidTest/' in name or name.startswith('server/tests/') or name.startswith('tools/test') or '/test_' in name
    for number, line in enumerate(text.splitlines(), 1):
        if PERSONAL_PATH.search(line):
            hits.append((number, 'personal-path'))
        if SECRET.search(line):
            hits.append((number, 'secret-format'))
        if not is_test and any(m.group() not in EXAMPLE_IPS for m in PRIVATE_IP.finditer(line)):
            hits.append((number, 'private-deployment-address'))
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--export-candidate', action='store_true', help='Filter private tracked legacy files only while preparing an export; never use in CI')
    args = parser.parse_args()
    root = args.root.resolve()
    names = public_paths(root, export_candidate=args.export_candidate)
    failures = [(name, line, reason) for name in names for line, reason in violations(name, (root/name).read_bytes())]
    for name, line, reason in failures:
        print(f'{name}:{line}: {reason}')
    print(f'Public-tree check: {len(names)} files, {len(failures)} violations (values suppressed)')
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
