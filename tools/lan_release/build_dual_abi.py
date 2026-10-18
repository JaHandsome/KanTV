#!/usr/bin/env python3
"""一条命令构建同版本的两个单 ABI 正式 APK（arm64-v8a + armeabi-v7a）。

为什么需要它
------------
``app/build.gradle.kts`` 提供受校验的 ``-Pkantv.abi=arm64-v8a|armeabi-v7a`` 单 ABI 入口，
但一次 Gradle 调用只产出一个固定路径的 APK（``app-release.apk``）。本脚本对每个 ABI 各跑
一次，并在第二次构建覆盖输出前**立即**把产物按 ABI 命名搬到 ``--out-dir``，因此：

* 绝不产出双 ABI 巨包——每个产物落盘前都用 ``lib/<abi>/`` 中实际条目核对；
* 两个产物版本相同（同一 ``versionCode`` / ``versionName``，由 Gradle 保证）；
* 得到 ``app-release-arm64-v8a.apk`` 与 ``app-release-armeabi-v7a.apk`` 两个稳定名字，
  可直接交给 ``lan_release.py pack-dual``。

签名、更新端点、HUB token 等仍由构建期环境变量注入（与手动 ``assembleRelease`` 完全一致），
本脚本不读取、不打印任何凭据。

用法::

    export GRADLE_USER_HOME=...        # 隔离的 Gradle home（可选）
    export KANTV_RELEASE_KEYSTORE_* ... # 正式签名（由调用者提供）
    python3 tools/lan_release/build_dual_abi.py
    # -> app/build/outputs/dual-abi/app-release-{arm64-v8a,armeabi-v7a}.apk

只跑单个 ABI（本地调试）::

    python3 tools/lan_release/build_dual_abi.py --abi armeabi-v7a

输入产物固定为 release 变体的 ``app/build/outputs/apk/release/app-release.apk``；需要改用别的
Gradle 任务时用 ``--task`` 覆盖，默认 ``:app:assembleRelease``。
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from typing import Dict, List, Optional, Sequence

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import lan_release  # noqa: E402  (import after sys.path tweak)

_REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_OUT_DIR = os.path.join("app", "build", "outputs", "dual-abi")
# 受校验的 -Pkantv.abi 单 ABI 构建固定写在这个 release 变体路径上。
RELEASE_APK_PATH = os.path.join("app", "build", "outputs", "apk", "release", "app-release.apk")
DEFAULT_GRADLE_TASK = ":app:assembleRelease"


class BuildError(Exception):
    """构建/搬移产物时的可读错误。"""


def stage_apk(source_apk: str, out_dir: str, abi: str) -> str:
    """把 ``source_apk`` 核对为恰好单 ``abi`` 后原子搬到 ``out_dir`` 的 ABI 命名文件。

    落盘名固定为 ``app-release-<abi>.apk``。核对失败（不是 zip / 双 ABI / 错 ABI）一律
    抛 :class:`BuildError`，绝不把可疑产物当成正式包继续发布。
    """

    if abi not in lan_release.SUPPORTED_ABIS:
        raise BuildError(f"不支持的 ABi={abi}；可选 {list(lan_release.SUPPORTED_ABIS)}")
    if not os.path.isfile(source_apk):
        raise BuildError(f"构建产物不存在: {source_apk}")

    abis = lan_release.read_apk_abis(source_apk)
    if abis is None:
        raise BuildError(f"无法读取 {source_apk} 的 lib/ 目录：不是有效 zip/APK")
    if abis != frozenset({abi}):
        raise BuildError(f"{source_apk} 实际 ABI={sorted(abis)}，期望单 {abi}（拒绝双 ABI 巨包或错通道）")

    os.makedirs(out_dir, exist_ok=True)
    destination = os.path.join(out_dir, f"app-release-{abi}.apk")
    try:
        lan_release.atomic_copy_file(source_apk, destination, replace=True)
    except OSError as error:
        raise BuildError(f"复制产物失败: {error}") from error
    return destination


def build_abi(gradle: str, task: str, abi: str, out_dir: str, repo_root: str) -> str:
    """跑一次单 ABI Gradle 构建并立即搬移产物。"""

    apk = os.path.join(repo_root, RELEASE_APK_PATH)
    if os.path.exists(apk):
        os.remove(apk)  # 防止构建失败时用上一次的旧产物冒充成功
    command = [gradle, task, f"-Pkantv.abi={abi}"]
    print(f"[build_dual_abi] {' '.join(command)}")
    result = subprocess.run(command, cwd=repo_root)
    if result.returncode != 0:
        raise BuildError(f"Gradle 构建失败（{abi}）：exit={result.returncode}")
    return stage_apk(apk, out_dir, abi)


def cross_check_identity(staged: Dict[str, str]) -> Optional[Dict[str, object]]:
    """两个产物都用 aapt2 读到元数据时，核对 applicationId/versionCode/versionName 一致。

    缺 aapt2 时返回 ``None`` 并提示；真正的强校验由 ``lan_release.py pack-dual`` 完成。
    """

    metadata: Dict[str, Dict[str, object]] = {}
    for abi, path in staged.items():
        found = lan_release.read_apk_metadata(path)
        if found is None:
            print(
                "[build_dual_abi] 注意：缺少 aapt2，跳过 applicationId/versionCode/versionName 一致性预检；"
                "pack-dual 仍会强制核对。",
                file=sys.stderr,
            )
            return None
        metadata[abi] = found
    values = {
        (str(item["applicationId"]), int(item["versionCode"]), str(item["versionName"]))
        for item in metadata.values()
    }
    if len(values) != 1:
        raise BuildError(f"两个产物的 applicationId/versionCode/versionName 不一致: {metadata}")
    print(f"[build_dual_abi] 身份预检通过: applicationId/versionCode/versionName = {values.pop()}")
    return metadata


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="build_dual_abi.py",
        description="同版本构建 arm64-v8a 与 armeabi-v7a 两个单 ABI 正式 APK。",
    )
    parser.add_argument("--task", default=None, help="覆盖 Gradle 任务（默认 :app:assembleRelease）")
    parser.add_argument("--gradle", default=os.path.join(_REPO_ROOT, "gradlew"), help="Gradle 可执行文件（默认仓库 gradlew）")
    parser.add_argument("--out-dir", default=DEFAULT_OUT_DIR, help="产物目录（默认 app/build/outputs/dual-abi）")
    parser.add_argument("--abi", choices=lan_release.SUPPORTED_ABIS, action="append", default=None, help="只构建指定 ABI（可重复；默认两个都构建）")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    abis: List[str] = list(dict.fromkeys(args.abi or lan_release.SUPPORTED_ABIS))
    task = args.task or DEFAULT_GRADLE_TASK
    out_dir = args.out_dir if os.path.isabs(args.out_dir) else os.path.join(_REPO_ROOT, args.out_dir)
    try:
        staged: Dict[str, str] = {}
        for abi in abis:
            staged[abi] = build_abi(args.gradle, task, abi, out_dir, _REPO_ROOT)
        if len(staged) == len(lan_release.SUPPORTED_ABIS):
            cross_check_identity(staged)
        print("构建完成，单 ABI 产物：")
        for abi, path in staged.items():
            print(f"  {abi}: {path}")
        if len(staged) == len(lan_release.SUPPORTED_ABIS):
            print("下一步：")
            print("  python3 tools/lan_release/lan_release.py pack-dual \\")
            print(f"    --arm64-apk {staged['arm64-v8a']} --v7a-apk {staged['armeabi-v7a']} --release-dir <发布目录>")
    except BuildError as error:
        print(f"BUILD: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
