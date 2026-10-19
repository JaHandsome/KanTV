#!/usr/bin/env python3
"""发布回归全部使用临时目录、假 APK 和本机进程代替 SSH，不读凭据。"""
import io
from pathlib import Path
import socket
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

import lan_release as release
import publish_dual as publish
from test_lan_release import _FAKE_CERT, _Harness
import build_dual_abi


class PublishTests(_Harness):
    def setUp(self):
        super().setUp()
        self.target = Path(self.dir) / 'nas'
        self.target.mkdir()
        self.staging = Path(self.dir) / 'candidate'
        self.staging.mkdir()
        self.write_dual(self.target, 53, b'stable')
        self.write_dual(self.staging, 54, b'candidate')
        self.original = self.snapshot()

    def write_dual(self, folder, version, payload):
        import zipfile
        for abi, name in zip(release.SUPPORTED_ABIS, publish.MANIFEST_NAMES):
            apk = folder / f'com.kantv.app-v{version}-{abi}.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr(f'lib/{abi}/libtest.so', payload)
            manifest = self.manifest_for(str(apk), versionCode=version, versionName=f'v{version}')
            (folder / name).write_text(release.render_manifest_json(manifest))

    def snapshot(self):
        return {p.name: p.read_bytes() for p in self.target.iterdir() if p.is_file() and p.name != '.release.lock'}

    def assert_preserved(self):
        for name, content in self.original.items():
            self.assertEqual(content, (self.target / name).read_bytes(), name)

    def apply(self, activate=False):
        return publish.apply_candidate(self.staging, self.target, 'com.kantv.app', _FAKE_CERT, activate)

    def archive(self):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w') as archive:
            for path in self.staging.iterdir():
                archive.add(path, arcname=path.name)
        return stream.getvalue()

    def receive(self, data, **kwargs):
        return publish.receive(io.BytesIO(data), self.target, 'com.kantv.app', _FAKE_CERT,
                               kwargs.pop('hostname', socket.gethostname()), **kwargs)

    def test_same_version_and_downgrade_preserve_all_existing_files(self):
        for version in (53, 52):
            with tempfile.TemporaryDirectory() as staging:
                self.write_dual(Path(staging), version, b'changed')
                with self.assertRaises(release.ManifestError) as error:
                    publish.apply_candidate(staging, self.target, 'com.kantv.app', _FAKE_CERT, True)
                self.assertEqual(release.REASON_NOT_NEWER, error.exception.code)
            self.assertEqual(self.original, self.snapshot())

    def test_existing_same_name_different_hash_preserves_every_existing_file(self):
        path = self.target / 'com.kantv.app-v54-armeabi-v7a.apk'
        path.write_bytes(b'previous-candidate-do-not-overwrite')
        self.original = self.snapshot()
        with self.assertRaises(release.ManifestError) as error:
            self.apply(True)
        self.assertEqual(release.REASON_HASH, error.exception.code)
        self.assertEqual(self.original, self.snapshot())

    def test_interrupted_upload_preserves_stable_and_no_temporary_files(self):
        with self.assertRaises((tarfile.TarError, release.ManifestError)):
            self.receive(self.archive()[:700])
        self.assertEqual(self.original, self.snapshot())
        self.assertFalse(any(self.target.glob('.upload-*')))

    def test_tampered_upload_preserves_stable(self):
        (self.staging / 'com.kantv.app-v54-arm64-v8a.apk').write_bytes(b'bad-transfer')
        with self.assertRaises(release.ManifestError):
            self.receive(self.archive())
        self.assertEqual(self.original, self.snapshot())

    def test_high_version_prepares_candidate_without_switching_and_can_retry(self):
        self.receive(self.archive())
        self.assert_preserved()
        candidates = list((self.target / 'candidates').iterdir())
        self.assertEqual(1, len(candidates))
        for name in publish.MANIFEST_NAMES:
            self.assertEqual(self.original[name], (candidates[0] / ('rollback-' + name)).read_bytes())
            manifest = release.load_manifest_file(str(candidates[0] / name))
            self.assertEqual(set(release.MANIFEST_FIELDS), set(manifest))
            self.assertEqual(54, manifest['versionCode'])
        self.apply()  # identical already-uploaded APKs are safe to reuse
        self.assert_preserved()

    def test_explicit_activation_switches_both_and_preserves_old_apks(self):
        import os
        for name, mode in zip(publish.MANIFEST_NAMES, (0o644, 0o640)):
            (self.target / name).chmod(mode)
        expected_modes = dict(zip(publish.MANIFEST_NAMES, (0o644, 0o640)))
        replacement_modes = {}
        original_replace = os.replace
        def inspect_mode_before_replace(source, destination):
            destination = Path(destination)
            if destination.parent == self.target and destination.name in expected_modes:
                replacement_modes[destination.name] = Path(source).stat().st_mode & 0o777
            return original_replace(source, destination)
        with mock.patch.object(publish.os, 'replace', side_effect=inspect_mode_before_replace):
            self.receive(self.archive(), activate=True)
        self.assertEqual(expected_modes, replacement_modes)
        for name, mode in zip(publish.MANIFEST_NAMES, (0o644, 0o640)):
            self.assertEqual(54, release.load_manifest_file(str(self.target / name))['versionCode'])
            self.assertEqual(mode, (self.target / name).stat().st_mode & 0o777)
        for name, content in self.original.items():
            if name.endswith('.apk'):
                self.assertEqual(content, (self.target / name).read_bytes())

    def test_uppercase_certificate_pin_accepts_the_same_certificate(self):
        publish.apply_candidate(self.staging, self.target, 'com.kantv.app', _FAKE_CERT.upper())
        self.assert_preserved()

    def test_second_manifest_switch_failure_restores_both_exactly(self):
        original_write = publish._atomic_write_manifest
        for name, mode in zip(publish.MANIFEST_NAMES, (0o644, 0o640)):
            (self.target / name).chmod(mode)
        def fail_legacy_once(path, text, mode):
            if Path(path) == self.target / 'latest.json' and '"versionCode": 54' in text:
                raise OSError('injected second manifest replacement failure')
            return original_write(path, text, mode)
        with mock.patch.object(publish, '_atomic_write_manifest', side_effect=fail_legacy_once):
            with self.assertRaises(OSError):
                self.apply(True)
        self.assert_preserved()
        for name, mode in zip(publish.MANIFEST_NAMES, (0o644, 0o640)):
            self.assertEqual(mode, (self.target / name).stat().st_mode & 0o777)
        self.assertTrue(list((self.target / 'candidates').glob('*/rollback-latest.json')))

    def test_wrong_nas_identity_rejected_before_upload(self):
        with self.assertRaises(release.ManifestError):
            self.receive(self.archive(), hostname='not-this-host.invalid')
        self.assertEqual(self.original, self.snapshot())

    def test_missing_or_damaged_rollback_apk_blocks_publish(self):
        old_apk = self.target / 'com.kantv.app-v53-arm64-v8a.apk'
        old_apk.write_bytes(b'damaged')
        self.original = self.snapshot()
        with self.assertRaises(release.ManifestError):
            self.apply(True)
        self.assertEqual(self.original, self.snapshot())

    def test_remote_application_and_certificate_pins_are_required(self):
        for app, cert in [('com.other.app', _FAKE_CERT), ('com.kantv.app', 'b' * 64)]:
            with self.assertRaises(release.ManifestError):
                publish.apply_candidate(self.staging, self.target, app, cert, True)
            self.assertEqual(self.original, self.snapshot())

    def test_version_compares_both_remote_channels(self):
        manifest = release.load_manifest_file(str(self.target / publish.MANIFEST_NAMES[1]))
        manifest['versionCode'] = 55
        (self.target / publish.MANIFEST_NAMES[1]).write_text(release.render_manifest_json(manifest))
        self.original = self.snapshot()
        with self.assertRaises(release.ManifestError) as error:
            self.apply()
        self.assertEqual(release.REASON_NOT_NEWER, error.exception.code)
        self.assertEqual(self.original, self.snapshot())

    def test_ssh_adapter_executes_real_bootstrap_and_stream_without_ssh(self):
        popen = subprocess.Popen
        calls = []
        def fake_ssh(args, **kwargs):
            import shlex
            calls.append(args)
            return popen(shlex.split(args[-1]), **kwargs)
        with mock.patch.object(publish.subprocess, 'Popen', side_effect=fake_ssh), \
                mock.patch.object(release, 'extract_signing_cert_sha256', return_value=_FAKE_CERT), \
                mock.patch.object(release, 'read_apk_metadata', return_value={
                    'applicationId': 'com.kantv.app', 'versionCode': 54, 'versionName': 'v54'}):
            publish.publish(str(self.staging), 'fake-host.invalid', str(self.target),
                            'com.kantv.app', _FAKE_CERT, socket.gethostname())
        self.assertEqual('ssh', calls[0][0])
        self.assert_preserved()

    def test_mutable_build_output_can_be_rebuilt(self):
        source = self.staging / 'com.kantv.app-v54-arm64-v8a.apk'
        out = Path(self.dir) / 'build'
        dest = build_dual_abi.stage_apk(str(source), str(out), 'arm64-v8a')
        self.write_dual(self.staging, 54, b'rebuilt')
        build_dual_abi.stage_apk(str(source), str(out), 'arm64-v8a')
        self.assertEqual(source.read_bytes(), Path(dest).read_bytes())

    def test_candidate_directory_symlink_cannot_redirect_writes(self):
        external = Path(self.dir) / 'unrelated'
        external.mkdir()
        (self.target / 'candidates').symlink_to(external, target_is_directory=True)
        with self.assertRaises(release.ManifestError):
            self.apply()
        self.assertEqual([], list(external.iterdir()))
        self.assert_preserved()

    def test_atomic_copy_never_replaces_existing_apk(self):
        old = self.target / 'com.kantv.app-v53-arm64-v8a.apk'
        candidate = self.staging / 'com.kantv.app-v54-arm64-v8a.apk'
        with self.assertRaises(release.ManifestError):
            release.atomic_copy_file(str(candidate), str(old))
        self.assert_preserved()


if __name__ == '__main__':
    unittest.main(verbosity=2)
