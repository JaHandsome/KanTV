#!/usr/bin/env python3
"""Reject private commit emails and the legacy FFmpeg AAR in publication history."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

from check_public_tree import LEGACY_FFMPEG_AAR_PATHS

LEGACY_FFMPEG_AAR_BLOBS = {'a64d0d6ffa2e7788fbee002486927ae08381f30f'}


def git(root: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
    env = os.environ.copy()
    env['GIT_NO_REPLACE_OBJECTS'] = '1'
    result = subprocess.run(['git', *args], cwd=root, input=input_bytes, env=env,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError('Git history inspection failed')
    return result.stdout


def check_history(root: Path, *, all_refs: bool = False,
                  legacy_blobs: set[str] | None = None) -> list[tuple[str, str]]:
    """Return safe (category, object id/path label) violations without reading blob data."""
    root = root.resolve()
    grafts_value = git(root, 'rev-parse', '--git-path', 'info/grafts').decode().strip()
    grafts_path = Path(grafts_value)
    if not grafts_path.is_absolute():
        grafts_path = root / grafts_path
    if grafts_path.exists() and grafts_path.read_bytes().strip():
        raise RuntimeError('non-empty Git grafts file; history cannot be verified safely')

    shallow = git(root, 'rev-parse', '--is-shallow-repository').strip()
    if shallow != b'false':
        raise RuntimeError('shallow Git checkout; fetch complete history before checking')

    ref = '--all' if all_refs else 'HEAD'
    commits = git(root, 'rev-list', ref).splitlines()
    if not commits:
        raise RuntimeError('Git history has no commits')

    path_args = ['log', '--full-history', '--format=', '--name-only', ref, '--']
    path_args.extend(f':(literal){name}' for name in sorted(LEGACY_FFMPEG_AAR_PATHS))
    path_output = git(root, *path_args)
    paths = set(path_output.decode('utf-8', errors='replace').splitlines())
    findings = [('legacy-ffmpeg-path', name) for name in sorted(paths & LEGACY_FFMPEG_AAR_PATHS)]

    known_blobs = LEGACY_FFMPEG_AAR_BLOBS if legacy_blobs is None else legacy_blobs
    object_ids = git(root, 'rev-list', '--objects', '--no-object-names', ref).splitlines()
    matched = sorted({oid.decode('ascii') for oid in object_ids if oid.decode('ascii') in known_blobs})
    findings.extend(('legacy-ffmpeg-blob', oid) for oid in matched)
    # Publication history uses GitHub privacy addresses; never print rejected addresses.
    metadata = git(root, 'log', '--format=%H%x00%ae%x00%ce', ref)
    for line in metadata.decode('utf-8', errors='replace').splitlines():
        commit, author, committer = line.split('\0')
        for role, email in (('author', author), ('committer', committer)):
            if email.lower() != 'noreply@github.com' and re.fullmatch(
                    r'[^@\s]+@users\.noreply\.github\.com', email, re.IGNORECASE) is None:
                findings.append((f'unreviewed-{role}-email', commit))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--all', action='store_true',
                        help='audit every ref; use for an isolated public mirror')
    args = parser.parse_args()
    try:
        findings = check_history(args.root, all_refs=args.all)
    except (OSError, RuntimeError) as error:
        print(f'Public-history check failed closed: {error}')
        return 2
    for category, label in findings:
        print(f'{category}: {label}')
    print(f'Public-history check: {"failed" if findings else "passed"} ({len(findings)} findings)')
    return 1 if findings else 0


if __name__ == '__main__':
    sys.exit(main())
