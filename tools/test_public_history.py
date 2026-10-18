"""History guard tests use isolated synthetic repositories and opaque fixture blobs."""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from check_public_history import check_history


def run_git(root: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
    result = subprocess.run(['git', *args], cwd=root, input=input_bytes,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return result.stdout


def init_repo(root: Path) -> None:
    run_git(root, 'init', '-q')
    run_git(root, 'config', 'user.email', 'test@users.noreply.github.com')
    run_git(root, 'config', 'user.name', 'History Guard Test')


def commit_all(root: Path, message: str) -> None:
    run_git(root, 'add', '--', '.')
    run_git(root, 'commit', '-qm', message)


class PublicHistoryTests(unittest.TestCase):
    def test_private_email_remains_rejected_after_a_clean_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repo(root)
            run_git(root, 'config', 'user.email', 'fixture-owner@example.invalid')
            (root / 'README.md').write_text('fixture\n')
            commit_all(root, 'private metadata fixture')
            old = run_git(root, 'rev-parse', 'HEAD').decode().strip()
            run_git(root, 'config', 'user.email', 'test@users.noreply.github.com')
            (root / 'README.md').write_text('clean metadata fixture\n')
            commit_all(root, 'clean')
            findings = check_history(root)
            self.assertIn(('unreviewed-author-email', old), findings)
            self.assertIn(('unreviewed-committer-email', old), findings)
            self.assertNotIn('fixture-owner@example.invalid', repr(findings))

    def test_safe_author_does_not_hide_private_committer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repo(root)
            (root / 'README.md').write_text('fixture\n')
            run_git(root, 'add', '--', 'README.md')
            run_git(root, '-c', 'user.email=fixture-owner@example.invalid', 'commit', '-qm',
                    'committer fixture', '--author=Test <test@users.noreply.github.com>')
            commit = run_git(root, 'rev-parse', 'HEAD').decode().strip()
            self.assertEqual([('unreviewed-committer-email', commit)], check_history(root))

    def test_clean_repository_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repo(root)
            (root / 'README.md').write_text('clean fixture\n')
            commit_all(root, 'clean')
            self.assertEqual([], check_history(root))

    def test_deleted_legacy_path_is_rejected_from_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repo(root)
            old_path = root / 'app/libs/media3-decoder-ffmpeg-1.11.0-mp3.aar'
            old_path.parent.mkdir(parents=True)
            old_path.write_bytes(b'synthetic fixture only')
            commit_all(root, 'legacy path fixture')
            old_path.unlink()
            commit_all(root, 'remove legacy path')
            self.assertIn(('legacy-ffmpeg-path', 'app/libs/media3-decoder-ffmpeg-1.11.0-mp3.aar'),
                          check_history(root))

    def test_nonempty_info_grafts_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repo(root)
            old_path = root / 'app/libs/media3-decoder-ffmpeg-1.11.0-mp3.aar'
            old_path.parent.mkdir(parents=True)
            old_path.write_bytes(b'synthetic fixture only')
            commit_all(root, 'legacy path fixture')
            old_path.unlink()
            commit_all(root, 'remove legacy path')
            head = run_git(root, 'rev-parse', 'HEAD').decode().strip()
            grafts_value = run_git(root, 'rev-parse', '--git-path', 'info/grafts').decode().strip()
            grafts_path = Path(grafts_value)
            if not grafts_path.is_absolute():
                grafts_path = root / grafts_path
            grafts_path.parent.mkdir(parents=True, exist_ok=True)
            grafts_path.write_text(f'{head}\n')
            with self.assertRaisesRegex(RuntimeError, 'grafts'):
                check_history(root)

    def test_renamed_legacy_content_is_rejected_by_injected_blob_id(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_repo(root)
            content = b'opaque synthetic legacy aar marker'
            blob = run_git(root, 'hash-object', '-w', '--stdin', input_bytes=content).decode().strip()
            (root / 'renamed-binary.dat').write_bytes(content)
            commit_all(root, 'renamed content fixture')
            self.assertIn(('legacy-ffmpeg-blob', blob), check_history(root, legacy_blobs={blob}))

    def test_shallow_repository_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, clone = base / 'source', base / 'clone'
            source.mkdir()
            init_repo(source)
            (source / 'README.md').write_text('first\n')
            commit_all(source, 'first')
            (source / 'README.md').write_text('second\n')
            commit_all(source, 'second')
            subprocess.run(['git', 'clone', '-q', '--depth', '1', source.as_uri(), str(clone)], check=True)
            with self.assertRaisesRegex(RuntimeError, 'shallow'):
                check_history(clone)


if __name__ == '__main__':
    unittest.main()
