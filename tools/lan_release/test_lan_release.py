#!/usr/bin/env python3
"""``tools/lan_release/lan_release.py`` 的单元测试。

运行方式（二者皆可，均离线）：

    python3 tools/lan_release/test_lan_release.py
    python3 -m unittest discover -s tools/lan_release

测试纪律
--------
* **不依赖真实 keystore**：默认路径用临时目录里构造的「假 APK + 伪造清单」，并把外部
  工具（``apksigner``/``keytool``/``aapt2``）函数替换为确定性桩，完全离线。
* 仅当仓库里存在 ``app/build/outputs/apk/debug/app-debug.apk`` 时，额外跑一组**真实**的
  端到端校验（用该 debug 包的既有签名，而非 release keystore）；不存在或缺少外部工具时
  自动 ``skip``。
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import lan_release  # noqa: E402  (import after sys.path tweak)
import build_dual_abi  # noqa: E402  (import after sys.path tweak)

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
_DEBUG_APK = os.path.join(_REPO_ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")

_FAKE_CERT = "a" * 64
_FAKE_CERT_ALT = "b" * 64


def _render_pairs(pairs) -> str:
    """把 (key, value) 列表渲染成极简 JSON 文本（用于注入重复/未知/缺失字段）。"""

    parts = []
    for key, value in pairs:
        parts.append(f"{json.dumps(key, ensure_ascii=False)}:{json.dumps(value, ensure_ascii=False)}")
    return "{" + ",".join(parts) + "}"


class _Harness(unittest.TestCase):
    """公共辅助：构造假 APK 与清单。"""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = self._tmp.name

    def make_fake_apk(self, name: str = "kantv-release.apk", payload: bytes = b"not-a-real-apk") -> str:
        path = os.path.join(self.dir, name)
        with open(path, "wb") as handle:
            handle.write(payload)
        return path

    def make_zip_apk(self, name: str, *abis: str) -> str:
        """构造带 ``lib/<abi>/`` 的真实 zip，用于 ABI 核对路径。"""

        path = os.path.join(self.dir, name)
        with zipfile.ZipFile(path, "w") as archive:
            for abi in abis:
                archive.writestr(f"lib/{abi}/libkantv.so", b"\x7fELF")
            archive.writestr("classes.dex", b"dex")
        return path

    def manifest_for(self, apk_path: str, **overrides):
        manifest = {
            "applicationId": "com.kantv.app",
            "versionCode": 9,
            "versionName": "0.1.8",
            "apkPath": os.path.basename(apk_path),
            "size": os.path.getsize(apk_path),
            "sha256": lan_release.sha256_file(apk_path),
            "signingCertSha256": _FAKE_CERT,
            "publishedAt": "2026-09-11T12:00:00Z",
        }
        manifest.update(overrides)
        return manifest

    def write_manifest(self, manifest=None, raw_text: str = None) -> str:
        path = os.path.join(self.dir, "latest.json")
        text = raw_text if raw_text is not None else lan_release.render_manifest_json(manifest)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path


# ---------------------------------------------------------------------------
# 1) 清单解析 / 取值校验（纯函数，完全离线）
# ---------------------------------------------------------------------------


class ManifestParsingTests(_Harness):
    def _valid_pairs(self):
        return [
            ("applicationId", "com.kantv.app"),
            ("versionCode", 9),
            ("versionName", "0.1.8"),
            ("apkPath", "kantv-release.apk"),
            ("size", 1024),
            ("sha256", "0" * 64),
            ("signingCertSha256", "1" * 64),
            ("publishedAt", "2026-09-11T12:00:00Z"),
        ]

    def test_valid_manifest_parses_in_fixed_order(self):
        parsed = lan_release.parse_manifest_text(_render_pairs(self._valid_pairs()))
        self.assertEqual(list(parsed.keys()), list(lan_release.MANIFEST_FIELDS))
        self.assertEqual(parsed["versionCode"], 9)

    def test_unknown_field_is_rejected(self):
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(self._valid_pairs() + [("extraField", "x")]))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_UNKNOWN_FIELD)

    def test_duplicate_field_is_rejected(self):
        pairs = self._valid_pairs() + [("applicationId", "com.kantv.app")]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_missing_field_is_rejected(self):
        pairs = [p for p in self._valid_pairs() if p[0] != "publishedAt"]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_bad_published_at_format_is_rejected(self):
        pairs = [("publishedAt", "2026-09-11 12:00:00") if k == "publishedAt" else (k, v) for k, v in self._valid_pairs()]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_impossible_published_at_is_rejected(self):
        pairs = [("publishedAt", "2026-13-40T12:00:00Z") if k == "publishedAt" else (k, v) for k, v in self._valid_pairs()]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_apk_path_with_directory_is_rejected(self):
        pairs = [("apkPath", "sub/dir.apk") if k == "apkPath" else (k, v) for k, v in self._valid_pairs()]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_APK_PATH)

    def test_apk_path_with_dotdot_is_rejected(self):
        pairs = [("apkPath", "a..b.apk") if k == "apkPath" else (k, v) for k, v in self._valid_pairs()]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_APK_PATH)

    def test_non_scalar_value_is_rejected(self):
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(
                '{"applicationId":"com.kantv.app","versionCode":9,"versionName":"0.1.8",'
                '"apkPath":"a.apk","size":1,"sha256":"' + "0" * 64 + '","signingCertSha256":"' + "1" * 64 + '",'
                '"publishedAt":true}'
            )
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_sha256_with_uppercase_is_rejected(self):
        pairs = [("sha256", "A" * 64) if k == "sha256" else (k, v) for k, v in self._valid_pairs()]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_version_code_above_long_max_is_rejected(self):
        # 客户端用 Kotlin toLong() 会拒绝超范围值；工具必须同样 fail-closed。
        pairs = [
            ("versionCode", 99999999999999999999999999) if k == "versionCode" else (k, v)
            for k, v in self._valid_pairs()
        ]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_version_code_at_long_max_is_accepted(self):
        pairs = [
            ("versionCode", lan_release.MAX_VERSION_CODE) if k == "versionCode" else (k, v)
            for k, v in self._valid_pairs()
        ]
        parsed = lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(parsed["versionCode"], lan_release.MAX_VERSION_CODE)

    def test_version_code_zero_is_rejected(self):
        pairs = [("versionCode", 0) if k == "versionCode" else (k, v) for k, v in self._valid_pairs()]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_version_code_negative_is_rejected(self):
        pairs = [("versionCode", -5) if k == "versionCode" else (k, v) for k, v in self._valid_pairs()]
        with self.assertRaises(lan_release.ManifestError) as ctx:
            lan_release.parse_manifest_text(_render_pairs(pairs))
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)


# ---------------------------------------------------------------------------
# 2) verify：逐条拒绝路径（假 APK，离线）
# ---------------------------------------------------------------------------


class VerifyRejectionTests(_Harness):
    def _verify(self, **kwargs):
        return lan_release.run_verify(self.dir, **kwargs)

    def test_sha256_one_char_wrong_is_hash(self):
        apk = self.make_fake_apk()
        manifest = self.manifest_for(apk, sha256="f" + lan_release.sha256_file(apk)[1:])
        self.write_manifest(manifest)
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify()
        self.assertEqual(ctx.exception.code, lan_release.REASON_HASH)

    def test_size_off_by_one_is_size(self):
        apk = self.make_fake_apk()
        manifest = self.manifest_for(apk, size=os.path.getsize(apk) + 1)
        self.write_manifest(manifest)
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify()
        self.assertEqual(ctx.exception.code, lan_release.REASON_SIZE)

    def test_missing_apk_is_apk_missing(self):
        self.make_fake_apk()
        manifest = self.manifest_for(os.path.join(self.dir, "kantv-release.apk"), apkPath="missing.apk")
        self.write_manifest(manifest)
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify()
        self.assertEqual(ctx.exception.code, lan_release.REASON_APK_MISSING)

    def test_version_not_newer_rejected(self):
        apk = self.make_fake_apk()
        self.write_manifest(self.manifest_for(apk, versionCode=9))
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify(installed_version_code=9)
        self.assertEqual(ctx.exception.code, lan_release.REASON_NOT_NEWER)

    def test_application_id_mismatch_rejected(self):
        apk = self.make_fake_apk()
        self.write_manifest(self.manifest_for(apk))
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify(expect_application_id="com.kantv.app.acceptance")
        self.assertEqual(ctx.exception.code, lan_release.REASON_IDENTITY_MISMATCH)

    def test_signature_cert_mismatch_rejected(self):
        apk = self.make_fake_apk()
        self.write_manifest(self.manifest_for(apk))
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify(expect_signing_cert_sha256=_FAKE_CERT_ALT)
        self.assertEqual(ctx.exception.code, lan_release.REASON_CERT)

    def test_apk_too_large_rejected(self):
        apk = self.make_fake_apk()
        # 稀疏文件：只有元数据，不实际占用 256+MiB 磁盘。
        with open(apk, "wb") as handle:
            handle.truncate(lan_release.MAX_APK_BYTES + 1)
        # 清单 size 取上限（合法），实际文件超上限 → 文件级 APK_TOO_LARGE。
        manifest = self.manifest_for(apk, size=lan_release.MAX_APK_BYTES)
        self.write_manifest(manifest)
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify()
        self.assertEqual(ctx.exception.code, lan_release.REASON_APK_TOO_LARGE)

    def test_actual_file_larger_than_manifest_size(self):
        apk = self.make_fake_apk()
        with open(apk, "wb") as handle:
            handle.truncate(4096)
        manifest = self.manifest_for(apk, size=1024)
        self.write_manifest(manifest)
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify()
        self.assertEqual(ctx.exception.code, lan_release.REASON_SIZE)

    def test_version_code_above_long_max_rejected_by_verify(self):
        # QA 复现路径：清单 versionCode 超出 Android long。清单解析先于文件检查，
        # 因此即使 aapt2 缺失也必须在这里被拦掉（不再静默放行）。
        apk = self.make_fake_apk()
        self.write_manifest(self.manifest_for(apk, versionCode=99999999999999999999999999))
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._verify()
        self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)


# ---------------------------------------------------------------------------
# 3) verify：通过路径（离线桩 + 真实 APK）
# ---------------------------------------------------------------------------


class VerifyPassTests(_Harness):
    def test_pass_with_stubbed_cert_tool(self):
        apk = self.make_fake_apk()
        self.write_manifest(self.manifest_for(apk))
        with mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT),\
                mock.patch.object(lan_release, "read_apk_metadata", return_value=None):
            result = lan_release.run_verify(
                self.dir,
                expect_application_id="com.kantv.app",
                installed_version_code=8,
            )
        self.assertEqual(result["versionCode"], 9)
        self.assertEqual(list(result.keys()), list(lan_release.MANIFEST_FIELDS))

    def test_main_returns_zero_on_success(self):
        apk = self.make_fake_apk()
        self.write_manifest(self.manifest_for(apk))
        with mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT),\
                mock.patch.object(lan_release, "read_apk_metadata", return_value=None):
            self.assertEqual(lan_release.main(["verify", "--release-dir", self.dir]), 0)

    def test_main_returns_nonzero_and_prints_code(self):
        apk = self.make_fake_apk()
        self.write_manifest(self.manifest_for(apk, sha256="f" * 64))
        self.assertEqual(lan_release.main(["verify", "--release-dir", self.dir]), 1)

    @unittest.skipUnless(os.path.isfile(_DEBUG_APK), "缺少 app-debug.apk，跳过真实 APK 端到端校验")
    def test_real_debug_apk_end_to_end(self):
        try:
            cert = lan_release.extract_signing_cert_sha256(_DEBUG_APK)
            metadata = lan_release.read_apk_metadata(_DEBUG_APK)
        except lan_release.ToolchainError:
            self.skipTest("缺少 apksigner/keytool")
        if metadata is None:
            self.skipTest("缺少 aapt2，无法读取 APK 元数据")

        with tempfile.TemporaryDirectory() as release_dir:
            link = os.path.join(release_dir, os.path.basename(_DEBUG_APK))
            os.symlink(_DEBUG_APK, link)
            manifest = {
                "applicationId": str(metadata["applicationId"]),
                "versionCode": int(metadata["versionCode"]),
                "versionName": "0.1.8",
                "apkPath": os.path.basename(_DEBUG_APK),
                "size": os.path.getsize(_DEBUG_APK),
                "sha256": lan_release.sha256_file(_DEBUG_APK),
                "signingCertSha256": cert,
                "publishedAt": "2026-09-11T12:00:00Z",
            }
            with open(os.path.join(release_dir, "latest.json"), "w", encoding="utf-8") as handle:
                handle.write(lan_release.render_manifest_json(manifest))

            result = lan_release.run_verify(
                release_dir,
                expect_application_id=str(metadata["applicationId"]),
                installed_version_code=int(metadata["versionCode"]) - 1,
            )
            self.assertEqual(result["sha256"], manifest["sha256"])

            # 真实证书不符 → CERT
            wrong = ("0" * 64) if cert != "0" * 64 else ("1" * 64)
            with self.assertRaises(lan_release.ManifestError) as ctx:
                lan_release.run_verify(release_dir, expect_signing_cert_sha256=wrong)
            self.assertEqual(ctx.exception.code, lan_release.REASON_CERT)


# ---------------------------------------------------------------------------
# 4) pack（桩掉外部工具，离线）
# ---------------------------------------------------------------------------


class PackTests(_Harness):
    def _pack(self, apk, release_dir=None, **kwargs):
        return lan_release.run_pack(
            apk=apk,
            release_dir=release_dir or self.dir,
            application_id=kwargs.pop("application_id", "com.kantv.app"),
            version_name=kwargs.pop("version_name", "0.1.8"),
            version_code=kwargs.pop("version_code", 9),
            published_at=kwargs.pop("published_at", "2026-09-11T12:00:00Z"),
            **kwargs,
        )

    def test_pack_builds_manifest_copies_apk_and_verifies(self):
        apk = self.make_fake_apk()
        release_dir = os.path.join(self.dir, "release")
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=None),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            manifest = self._pack(apk, release_dir=release_dir)

        latest = os.path.join(release_dir, "latest.json")
        self.assertTrue(os.path.isfile(latest))
        with open(latest, "r", encoding="utf-8") as handle:
            text = handle.read()
        self.assertTrue(text.endswith("\n"))
        data = json.loads(text)
        self.assertEqual(list(data.keys()), list(lan_release.MANIFEST_FIELDS))
        self.assertEqual(data["applicationId"], "com.kantv.app")
        self.assertEqual(data["versionCode"], 9)
        self.assertEqual(data["apkPath"], "com.kantv.app.apk")
        self.assertTrue(os.path.isfile(os.path.join(release_dir, data["apkPath"])))
        self.assertEqual(manifest["sha256"], lan_release.sha256_file(apk))

        # 生成物可被自身 verify 接受。
        with mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT),\
                mock.patch.object(lan_release, "read_apk_metadata", return_value=None):
            lan_release.run_verify(release_dir, expect_application_id="com.kantv.app", installed_version_code=8)

    def test_pack_refuses_downgrade(self):
        apk = self.make_fake_apk()
        release_dir = os.path.join(self.dir, "release")
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=None),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            self._pack(apk, release_dir=release_dir, version_code=9)
            with self.assertRaises(lan_release.ManifestError) as ctx:
                self._pack(apk, release_dir=release_dir, version_code=9)
            self.assertEqual(ctx.exception.code, lan_release.REASON_NOT_NEWER)
            with self.assertRaises(lan_release.ManifestError):
                self._pack(apk, release_dir=release_dir, version_code=8)

    def test_pack_missing_apk_is_rejected(self):
        with self.assertRaises(lan_release.ManifestError) as ctx:
            self._pack(os.path.join(self.dir, "nope.apk"))
        self.assertEqual(ctx.exception.code, lan_release.REASON_APK_MISSING)

    def test_pack_requires_explicit_id_when_no_tooling(self):
        apk = self.make_fake_apk()
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=None),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            with self.assertRaises(lan_release.ManifestError) as ctx:
                lan_release.run_pack(apk=apk, release_dir=self.dir, version_name="0.1.8", version_code=9)
            self.assertEqual(ctx.exception.code, lan_release.REASON_MANIFEST_INVALID)

    def test_pack_errors_when_no_cert_tooling(self):
        apk = self.make_fake_apk()
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=None),\
                mock.patch.object(lan_release, "find_build_tool", return_value=None),\
                mock.patch.object(lan_release, "_find_keytool", return_value=None):
            with self.assertRaises(lan_release.ToolchainError):
                self._pack(apk)

    def test_pack_prefers_apk_metadata_over_args(self):
        apk = self.make_fake_apk()
        metadata = {"applicationId": "com.kantv.app", "versionCode": 42, "versionName": "9.9.9"}
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=metadata),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            manifest = self._pack(apk, application_id="com.other.app", version_code=None, version_name=None)
        self.assertEqual(manifest["applicationId"], "com.kantv.app")
        self.assertEqual(manifest["versionCode"], 42)
        self.assertEqual(manifest["versionName"], "9.9.9")
        self.assertEqual(manifest["apkPath"], "com.kantv.app.apk")

    def test_pack_honours_custom_apk_name_and_rejects_bad_one(self):
        apk = self.make_fake_apk()
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=None),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            manifest = self._pack(apk, apk_name="kantv-release.apk")
            self.assertEqual(manifest["apkPath"], "kantv-release.apk")
            with self.assertRaises(lan_release.ManifestError) as ctx:
                self._pack(apk, release_dir=os.path.join(self.dir, "r2"), apk_name="../evil.apk")
            self.assertEqual(ctx.exception.code, lan_release.REASON_APK_PATH)


class DualAbiTests(_Harness):
    def _pack_dual(self, release_dir=None):
        arm64 = self.make_zip_apk("arm64.apk", "arm64-v8a")
        v7a = self.make_zip_apk("v7a.apk", "armeabi-v7a")
        metadata = {"applicationId": "com.kantv.app", "versionCode": 24, "versionName": "0.2.11"}
        target = release_dir or os.path.join(self.dir, "release")
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=metadata),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            manifests = lan_release.run_pack_dual(
                arm64_apk=arm64,
                v7a_apk=v7a,
                release_dir=target,
                published_at="2026-09-14T12:00:00Z",
            )
        return target, manifests

    def test_pack_dual_writes_legacy_arm64_and_v7a_channel_then_verifies(self):
        release_dir, manifests = self._pack_dual()
        self.assertTrue(os.path.isfile(os.path.join(release_dir, "latest.json")))
        self.assertTrue(os.path.isfile(os.path.join(release_dir, "latest-armeabi-v7a.json")))
        self.assertEqual(24, manifests["arm64-v8a"]["versionCode"])
        self.assertEqual(24, manifests["armeabi-v7a"]["versionCode"])
        self.assertNotEqual(
            manifests["arm64-v8a"]["apkPath"],
            manifests["armeabi-v7a"]["apkPath"],
        )
        metadata = {"applicationId": "com.kantv.app", "versionCode": 24, "versionName": "0.2.11"}
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=metadata),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            checked = lan_release.run_verify_dual(
                release_dir,
                expect_application_id="com.kantv.app",
                installed_version_code=23,
                expect_signing_cert_sha256=_FAKE_CERT,
            )
        self.assertEqual(set(lan_release.SUPPORTED_ABIS), set(checked))

    def test_pack_dual_rejects_wrong_dual_or_mismatched_identity_and_downgrade(self):
        arm64 = self.make_zip_apk("arm64.apk", "arm64-v8a")
        wrong_v7a = self.make_zip_apk("wrong-v7a.apk", "arm64-v8a")
        metadata = {"applicationId": "com.kantv.app", "versionCode": 24, "versionName": "0.2.11"}
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=metadata),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            with self.assertRaises(lan_release.ManifestError) as ctx:
                lan_release.run_pack_dual(arm64, wrong_v7a, os.path.join(self.dir, "wrong"))
        self.assertEqual(lan_release.REASON_ABI, ctx.exception.code)

        release_dir, _ = self._pack_dual()
        with mock.patch.object(lan_release, "read_apk_metadata", return_value=metadata),\
                mock.patch.object(lan_release, "extract_signing_cert_sha256", return_value=_FAKE_CERT):
            with self.assertRaises(lan_release.ManifestError) as ctx:
                lan_release.run_pack_dual(
                    arm64,
                    self.make_zip_apk("v7a-second.apk", "armeabi-v7a"),
                    release_dir,
                )
        self.assertEqual(lan_release.REASON_NOT_NEWER, ctx.exception.code)

    def test_stage_apk_accepts_exact_single_abi_and_rejects_fat_apk(self):
        source = self.make_zip_apk("v7a.apk", "armeabi-v7a")
        staged = build_dual_abi.stage_apk(source, os.path.join(self.dir, "out"), "armeabi-v7a")
        self.assertEqual(frozenset({"armeabi-v7a"}), lan_release.read_apk_abis(staged))
        fat = self.make_zip_apk("fat.apk", "arm64-v8a", "armeabi-v7a")
        with self.assertRaises(build_dual_abi.BuildError):
            build_dual_abi.stage_apk(fat, os.path.join(self.dir, "out"), "arm64-v8a")


if __name__ == "__main__":
    unittest.main(verbosity=2)
