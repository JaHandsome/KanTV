#!/usr/bin/env python3
"""Prepare signed, unpaired APKs from one clean commit; never upload or activate them."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SIGNING = (
    "KANTV_RELEASE_KEYSTORE_FILE", "KANTV_RELEASE_KEYSTORE_PASSWORD",
    "KANTV_RELEASE_KEY_ALIAS", "KANTV_RELEASE_KEY_PASSWORD",
)
EMPTY_DEFAULTS = (
    "DEV_PLAYLIST_URL", "DIAGNOSTIC_DEFAULT_ENDPOINT", "DIAGNOSTIC_DEFAULT_TOKEN",
    "UPDATE_DEFAULT_ENDPOINT", "UPDATE_DEFAULT_TOKEN", "HUB_DEFAULT_ENDPOINT", "HUB_DEFAULT_TOKEN",
)


def public_environment(environment: dict[str, str], revision: str, epoch_ms: int) -> dict[str, str]:
    """Household pairing must not propagate from the invoking shell to a public build."""
    missing = [name for name in SIGNING if not environment.get(name)]
    if missing:
        raise ValueError("Missing signing environment: " + ", ".join(missing))
    result = {key: value for key, value in environment.items() if not key.startswith("KANTV_")}
    result.update({key: environment[key] for key in SIGNING})
    result["KANTV_BUILD_GIT_REVISION"] = revision
    result["KANTV_BUILD_EPOCH_MS"] = str(epoch_ms)
    return result


def check_build_config(text: str) -> None:
    for name in EMPTY_DEFAULTS:
        if not re.search(rf'\b{re.escape(name)}\s*=\s*""\s*;', text):
            raise ValueError("Nonempty or missing public default: " + name)
    if not re.search(r"\bKANTV_DIAGNOSTICS\s*=\s*false\s*;", text):
        raise ValueError("Release diagnostics must be disabled")


def sha256(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def prepare(output: Path, expected_certificate: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{64}", expected_certificate):
        raise ValueError("Expected signing certificate must be a lowercase SHA256")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    environment = public_environment(dict(os.environ), revision, int(time.time() * 1000))
    keystore = Path(environment["KANTV_RELEASE_KEYSTORE_FILE"]).resolve()
    if not keystore.is_file():
        raise ValueError("Signing keystore is unavailable")
    environment["KANTV_RELEASE_KEYSTORE_FILE"] = str(keystore)
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT):
        raise ValueError("Commit the reviewed public changes before preparing a release")
    output = output.resolve()
    output.relative_to(ROOT)
    if subprocess.run(["git", "check-ignore", "--quiet", "--no-index", str(output)], cwd=ROOT).returncode != 0:
        raise ValueError("Output must be in an ignored directory inside this checkout")
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    output.chmod(0o700)
    status_path = output / "PREPARATION.json"
    status = {"source_commit": revision, "status": "RUNNING", "uploaded": False, "activated": False}
    status_path.write_text(json.dumps(status, indent=2) + "\n")

    def run(label: str, command: list[str], cwd: Path = ROOT) -> None:
        with (output / (label + ".log")).open("wb") as log:
            completed = subprocess.run(command, cwd=cwd, env=environment, stdout=log, stderr=subprocess.STDOUT)
        if completed.returncode:
            raise RuntimeError(f"{label} failed (exit {completed.returncode}); inspect its private log")

    try:
        run("public-tree", [sys.executable, "tools/check_public_tree.py"])
        run("public-history", [sys.executable, "tools/check_public_history.py"])
        archive = output / "kantv-source.tar.gz"
        subprocess.run(["git", "archive", "--format=tar.gz", "--output", str(archive), revision], cwd=ROOT, check=True)
        source = output / "source"
        source.mkdir()
        with tarfile.open(archive) as handle:
            handle.extractall(source, filter="data")
        forbidden = (".private", ".signing", "local.properties", "server/data", "server/config/settings.json",
                     "server/config/hub_client_token.txt", "app/src/main/assets/bundled-default")
        if any((source / name).exists() for name in forbidden):
            raise ValueError("Public source archive contains a private input")
        run("build-dual", [sys.executable, "tools/lan_release/build_dual_abi.py", "--out-dir", str(output / "build")], source)
        inventory = output / "runtime-inventory.private.json"
        run("runtime-inventory", ["./gradlew", "-I", "tools/apk_distribution/runtime_inventory.gradle",
                                  ":app:apkRuntimeInventory", "-Dkantv.inventory.output=" + str(inventory),
                                  "--console=plain", "--no-daemon"], source)
        run("runtime-notices", [sys.executable, "tools/apk_distribution/prepare_notices.py",
                                "--inventory", str(inventory), "--verify-inventory"], source)
        config = source / "app/build/generated/source/buildConfig/release/com/kantv/app/BuildConfig.java"
        check_build_config(config.read_text())
        run("lint-release", ["./gradlew", ":app:lintRelease", "--console=plain", "--no-daemon"], source)
        staging = output / "staging"
        run("pack-dual", [sys.executable, "tools/lan_release/lan_release.py", "pack-dual",
                          "--arm64-apk", str(output / "build/app-release-arm64-v8a.apk"),
                          "--v7a-apk", str(output / "build/app-release-armeabi-v7a.apk"), "--release-dir", str(staging)], source)
        run("verify-dual", [sys.executable, "tools/lan_release/lan_release.py", "verify-dual", "--release-dir", str(staging),
                            "--expect-application-id", "com.kantv.app", "--expect-signing-cert-sha256", expected_certificate], source)
        artifacts = {}
        sys.path.insert(0, str(ROOT / "tools/lan_release"))
        from lan_release import find_build_tool
        signer = find_build_tool("apksigner")
        if signer is None:
            raise ValueError("apksigner is required for final signature verification")
        for apk in sorted(staging.glob("*.apk")):
            run("signature-" + apk.stem, [signer, "verify", "--verbose", "--print-certs", str(apk)])
            with zipfile.ZipFile(apk) as handle:
                if any(name.startswith("assets/bundled-default/") for name in handle.namelist()):
                    raise ValueError("APK contains a private default playlist")
            artifacts[apk.name] = {"sha256": sha256(apk), "size": apk.stat().st_size}
        if len(artifacts) != 2:
            raise ValueError("Expected two verified APKs")
        status.update(status="SIGNED_BUILD_VERIFIED", source_archive_sha256=sha256(archive), artifacts=artifacts,
                      household_defaults="empty", diagnostics=False,
                      distribution_materials="must be prepared and checked for these exact APKs",
                      real_device_acceptance="not established by this script")
        (output / "COMPLETE").write_text("SIGNED_BUILD_VERIFIED\n")
    except Exception:
        status["status"] = "FAILED"
        raise
    finally:
        status_path.write_text(json.dumps(status, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New ignored directory in this checkout")
    parser.add_argument("--expect-signing-cert-sha256", required=True)
    args = parser.parse_args()
    try:
        prepare(args.output, args.expect_signing_cert_sha256)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        # Do not stringify arbitrary environment or signing-tool output.
        print(f"Release preparation stopped: {error}", file=sys.stderr)
        return 1
    print("Signed public candidate prepared; no upload or stable-entry activation performed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
