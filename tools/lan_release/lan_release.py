#!/usr/bin/env python3
"""KanTV 局域网更新发布工具链（``pack`` / ``verify``）。

这是面向「把新构建推到已装在电视上的看电视」的**发布侧**工具：它把正式签名的
APK 打包成家用 NAS 更新服务所需的 ``latest.json`` + 单个 APK，并在发布前按客户端
规则逐项自检，避免把一个必然会被电视拒绝的清单挂上去。

设计约束（与客户端逐条对齐，不要自行发明格式）
------------------------------------------------
字段顺序与取值规则对齐自：

* ``app/src/main/java/com/kantv/app/update/LanUpdateModels.kt``
  （``LanReleaseManifestParser`` / ``LanUpdateEndpointPolicy`` / ``LanUpdateArtifactPolicy``）
* ``app/src/main/java/com/kantv/app/update/LanUpdateClient.kt``

个别校验**比客户端更严**（方向安全，只会更早拦掉明显错误，绝不放行客户端会拒绝的清单）：
``publishedAt`` 客户端只做正则匹配，本工具额外做日历有效性校验；``versionCode`` 本工具显式限制在
Android ``long`` 范围 ``1..2**63-1``（客户端 ``toLong()`` 同样会拒绝超范围值）。

清单响应只允许这 8 个标量字段，且**不允许未知字段或重复字段**，顺序固定为：

    applicationId, versionCode, versionName, apkPath, size,
    sha256, signingCertSha256, publishedAt

双 ABI（64 位 + 32 位）发布约定
--------------------------------
* 每个通道只发布**单 ABI** APK，绝不发布双 ABI 巨包；两个 APK 必须同
  ``applicationId`` / ``versionCode`` / ``versionName`` / 签名证书。
* **legacy 通道** ``latest.json`` = arm64-v8a，格式与上面 8 字段完全一致；旧 arm64
  客户端继续从 ``/v1/releases/latest`` 升级，行为不变。
* **32 位通道** ``latest-armeabi-v7a.json`` = armeabi-v7a；新客户端带
  ``X-KanTV-ABI: armeabi-v7a`` 请求同一个路径，NAS 据此返回该清单。
* ``pack-dual`` 一次写入两个通道：先落不可变的分版本命名 APK，再原子替换 v7a 清单，
  最后原子替换 legacy ``latest.json``；旧 APK / 旧清单全部保留，可回滚。
* 清单本身**不含 abi 字段**（旧客户端只认 8 字段）；ABI 是「通道」的属性，由
  ``verify-dual`` 逐通道核验 APK 内 ``lib/<abi>/`` 恰好只有该通道 ABI。

工具本身的硬性纪律
------------------
* 只用 Python 3.11+ 标准库；签名证书提取可选调用外部 ``apksigner`` / ``keytool``，
  APK 元数据读取可选调用 ``aapt2``。外部工具都不可用时**报错退出并给出手工填法**，
  绝不伪造证书或哈希。
* 不联网、不打印任何 token、不把 APK 内容写到标准输出。

默认 APK 文件名规则（未显式给出 ``--apk-name`` 时）
--------------------------------------------------
* 不带 ``--abi`` 的旧式单通道 ``pack``：``<applicationId>.apk``，例如
  ``com.kantv.app.apk``（完全保持历史行为）。
* 指定通道或 ``pack-dual``：``<applicationId>-v<versionCode>-<abi>.apk``，例如
  ``com.kantv.app-v24-arm64-v8a.apk``。名字里带 versionCode 使旧产物永不被覆盖。

部署约定
--------
* 更新端点由自己的部署参数指定，路径为 ``/v1/releases/latest``。
* 发布目录由调用者指定，服务容器只读挂载 APK 与清单。
* 升级必须保持既有 applicationId 和签名身份，递增 versionCode，并保留回滚产物。
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from typing import Dict, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# 常量：与客户端保持字节级一致
# ---------------------------------------------------------------------------

#: 清单字段的固定顺序（也是 ``latest.json`` 的键顺序）。两个 ABI 通道共用这 8 个字段；
#: 旧 arm64 客户端要求恰好 8 个，因此**绝不新增 abi 字段**。
MANIFEST_FIELDS: Tuple[str, ...] = (
    "applicationId",
    "versionCode",
    "versionName",
    "apkPath",
    "size",
    "sha256",
    "signingCertSha256",
    "publishedAt",
)
MANIFEST_FIELD_SET = frozenset(MANIFEST_FIELDS)

#: 正式发布的单 ABI 词汇与通道清单文件名。legacy 通道沿用 ``latest.json``。
SUPPORTED_ABIS: Tuple[str, ...] = ("arm64-v8a", "armeabi-v7a")
LEGACY_ABI = "arm64-v8a"
LEGACY_MANIFEST_NAME = "latest.json"
ABI_HEADER = "X-KanTV-ABI"


def manifest_name_for_abi(abi: str) -> str:
    """把发布通道 ABI 映射到 NAS 发布目录内的清单文件名。"""

    if abi == LEGACY_ABI:
        return LEGACY_MANIFEST_NAME
    return f"latest-{abi}.json"

#: 单个 APK 上限：256 MiB（``LanUpdateClient.MAX_APK_BYTES``）。
MAX_APK_BYTES = 256 * 1024 * 1024

#: ``versionCode`` 的合法上界：Android ``long`` 最大值 ``2**63-1``。
#: 客户端用 Kotlin ``signature.toLong()`` 语义解析，超出该范围会被客户端拒绝；
#: Python 整数无上限，因此工具必须**显式 fail-closed** 拦掉超范围值，避免"应当拒绝却静默通过"。
MAX_VERSION_CODE = 2 ** 63 - 1

#: 「看电视」正式包名，作为 ``--application-id`` 的默认建议值（仅用于提示/默认）。
DEFAULT_APPLICATION_ID = "com.kantv.app"

_APPLICATION_ID_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
_APK_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,126}\.apk")
_DIGEST_RE = re.compile(r"[0-9a-f]{64}")
_PUBLISHED_AT_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
_PUBLISHED_AT_STRPTIME = "%Y-%m-%dT%H:%M:%SZ"

#: 与客户端词汇一致的原因码（``verify`` 失败时只打印其中一个）。
REASON_MANIFEST_INVALID = "MANIFEST_INVALID"
REASON_MANIFEST_UNKNOWN_FIELD = "MANIFEST_UNKNOWN_FIELD"
REASON_APK_PATH = "APK_PATH"
REASON_APK_MISSING = "APK_MISSING"
REASON_APK_TOO_LARGE = "APK_TOO_LARGE"
REASON_SIZE = "SIZE"
REASON_HASH = "HASH"
REASON_CERT = "CERT"
REASON_ABI = "ABI"
REASON_IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
REASON_NOT_NEWER = "NOT_NEWER"


class ManifestError(Exception):
    """携带**单一原因码**的发布清单错误。"""

    def __init__(self, code: str, detail: str = "") -> None:
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {detail}" if detail else code)


class ToolchainError(Exception):
    """缺少必需的外部工具（apksigner / keytool / aapt2）时抛出。"""


# ---------------------------------------------------------------------------
# 严格的标量 JSON 解析（镜像 Kotlin 的 ScalarJsonObjectParser）
# ---------------------------------------------------------------------------


class _StrictManifestScanner:
    """只接受 `{ "k": <string|integer>, ... }` 的极简 JSON 扫描器。

    与客户端一致：拒绝重复字段、未知字段、非标量值、控制字符、前导 0 的整数、尾随数据。
    """

    def __init__(self, text: str) -> None:
        self._text = text
        self._pos = 0

    def parse(self) -> List[Tuple[str, object]]:
        self._skip_ws()
        self._expect("{")
        self._skip_ws()
        pairs: List[Tuple[str, object]] = []
        seen: set[str] = set()
        if self._peek("}"):
            self._pos += 1
            self._skip_ws()
            self._finish()
            return pairs
        while True:
            key = self._string()
            if key in seen:
                raise ManifestError(REASON_MANIFEST_INVALID, f"重复字段: {key}")
            seen.add(key)
            self._skip_ws()
            self._expect(":")
            self._skip_ws()
            value: object = self._string() if self._peek('"') else self._integer()
            pairs.append((key, value))
            self._skip_ws()
            if self._peek(","):
                self._pos += 1
                self._skip_ws()
            elif self._peek("}"):
                self._pos += 1
                self._skip_ws()
                self._finish()
                return pairs
            else:
                raise ManifestError(REASON_MANIFEST_INVALID, "期望 ',' 或 '}'")

    def _string(self) -> str:
        self._expect('"')
        out: List[str] = []
        while self._pos < len(self._text):
            ch = self._text[self._pos]
            self._pos += 1
            if ch == '"':
                return "".join(out)
            if ch == "\\":
                if self._pos >= len(self._text):
                    raise ManifestError(REASON_MANIFEST_INVALID, "转义不完整")
                esc = self._text[self._pos]
                self._pos += 1
                simple = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t"}
                if esc in simple:
                    out.append(simple[esc])
                elif esc == "u":
                    if self._pos + 4 > len(self._text):
                        raise ManifestError(REASON_MANIFEST_INVALID, "\\u 转义不完整")
                    code = self._text[self._pos:self._pos + 4]
                    try:
                        out.append(chr(int(code, 16)))
                    except ValueError:
                        raise ManifestError(REASON_MANIFEST_INVALID, "\\u 转义非法")
                    self._pos += 4
                else:
                    raise ManifestError(REASON_MANIFEST_INVALID, f"非法转义: \\{esc}")
            elif ch < " ":
                raise ManifestError(REASON_MANIFEST_INVALID, "字符串含控制字符")
            else:
                out.append(ch)
        raise ManifestError(REASON_MANIFEST_INVALID, "字符串未闭合")

    def _integer(self) -> int:
        start = self._pos
        if self._peek("-"):
            self._pos += 1
        digit_start = self._pos
        while self._pos < len(self._text) and self._text[self._pos] in "0123456789":
            self._pos += 1
        digits = self._text[digit_start:self._pos]
        if not digits or (len(digits) > 1 and digits[0] == "0"):
            raise ManifestError(REASON_MANIFEST_INVALID, "整数格式非法")
        try:
            return int(self._text[start:self._pos])
        except ValueError:
            raise ManifestError(REASON_MANIFEST_INVALID, "整数格式非法")

    def _skip_ws(self) -> None:
        while self._pos < len(self._text) and self._text[self._pos] in " \t\r\n":
            self._pos += 1

    def _expect(self, ch: str) -> None:
        if not self._peek(ch):
            raise ManifestError(REASON_MANIFEST_INVALID, f"期望 '{ch}'")
        self._pos += 1

    def _peek(self, ch: str) -> bool:
        return self._pos < len(self._text) and self._text[self._pos] == ch

    def _finish(self) -> None:
        if self._pos != len(self._text):
            raise ManifestError(REASON_MANIFEST_INVALID, "存在尾随数据")


# ---------------------------------------------------------------------------
# 清单校验（值层面）与解析（文本层面）
# ---------------------------------------------------------------------------


def validate_manifest_values(values: Dict[str, object]) -> Dict[str, object]:
    """按客户端规则校验 8 个字段的值，返回顺序固定的新字典。

    前提：``values`` 的键集合已等于 ``MANIFEST_FIELD_SET``。任何不合规即抛
    :class:`ManifestError`（``apkPath`` 相关一律归为 ``APK_PATH``）。

    其中 ``versionCode`` 限制在 Android ``long`` 范围 ``1..2**63-1``，``publishedAt`` 额外做
    日历有效性校验——这两项**比客户端更严**（方向安全，见模块 docstring）。
    """

    application_id = values.get("applicationId")
    if not isinstance(application_id, str) or not _APPLICATION_ID_RE.fullmatch(application_id):
        raise ManifestError(REASON_MANIFEST_INVALID, "applicationId 格式非法")

    version_code = values.get("versionCode")
    if (
        not isinstance(version_code, int)
        or isinstance(version_code, bool)
        or version_code < 1
        or version_code > MAX_VERSION_CODE
    ):
        raise ManifestError(
            REASON_MANIFEST_INVALID,
            f"versionCode 必须在 1..{MAX_VERSION_CODE}（Android long）范围内",
        )

    version_name = values.get("versionName")
    if not isinstance(version_name, str) or not version_name or len(version_name) > 100 or any(c < " " for c in version_name):
        raise ManifestError(REASON_MANIFEST_INVALID, "versionName 非法（空、超长或含控制字符）")

    apk_path = values.get("apkPath")
    if not isinstance(apk_path, str) or not _APK_NAME_RE.fullmatch(apk_path) or ".." in apk_path:
        raise ManifestError(REASON_APK_PATH, "apkPath 必须是无目录的 ASCII 文件名且不含 '..'")

    size = values.get("size")
    if not isinstance(size, int) or isinstance(size, bool) or size <= 0 or size > MAX_APK_BYTES:
        raise ManifestError(REASON_MANIFEST_INVALID, "size 超出 1..256MiB")

    digest = values.get("sha256")
    if not isinstance(digest, str) or not _DIGEST_RE.fullmatch(digest):
        raise ManifestError(REASON_MANIFEST_INVALID, "sha256 必须是 64 位小写 hex")

    cert = values.get("signingCertSha256")
    if not isinstance(cert, str) or not _DIGEST_RE.fullmatch(cert):
        raise ManifestError(REASON_MANIFEST_INVALID, "signingCertSha256 必须是 64 位小写 hex")

    published_at = values.get("publishedAt")
    if not isinstance(published_at, str) or not _PUBLISHED_AT_RE.fullmatch(published_at):
        raise ManifestError(REASON_MANIFEST_INVALID, "publishedAt 必须形如 2026-09-11T12:00:00Z")
    _verify_published_at_is_real(published_at)

    return {name: values[name] for name in MANIFEST_FIELDS}


def _verify_published_at_is_real(published_at: str) -> None:
    """日历有效性校验：**比客户端更严**，方向安全。

    客户端 ``LanReleaseManifestParser`` 只对 ``publishedAt`` 做正则匹配，因此
    ``2026-13-40T12:00:00Z`` 这类"格式对但日期不存在"的值**客户端会接受**；本工具额外
    确认它是真实存在的 UTC 时刻（拒绝 13 月 / 32 日等），只会更早拦掉明显错误，
    绝不放行客户端会拒绝的清单。
    """

    try:
        _dt.datetime.strptime(published_at, _PUBLISHED_AT_STRPTIME)
    except ValueError:
        raise ManifestError(REASON_MANIFEST_INVALID, "publishedAt 不是真实存在的 UTC 时刻")


def parse_manifest_text(text: str) -> Dict[str, object]:
    """解析并校验清单文本，返回顺序固定的字段字典。"""

    if len(text.encode("utf-8")) > 16 * 1024:
        raise ManifestError(REASON_MANIFEST_INVALID, "清单过大（>16KiB）")
    pairs = _StrictManifestScanner(text).parse()
    present = {key for key, _ in pairs}
    unknown = present - MANIFEST_FIELD_SET
    if unknown:
        raise ManifestError(REASON_MANIFEST_UNKNOWN_FIELD, f"未知字段: {sorted(unknown)}")
    missing = MANIFEST_FIELD_SET - present
    if missing:
        raise ManifestError(REASON_MANIFEST_INVALID, f"缺失字段: {sorted(missing)}")
    return validate_manifest_values(dict(pairs))


def load_manifest_file(path: str) -> Dict[str, object]:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
    except OSError as exc:
        raise ManifestError(REASON_MANIFEST_INVALID, f"无法读取清单: {exc}")
    return parse_manifest_text(text)


def render_manifest_json(manifest: Dict[str, object]) -> str:
    """把清单渲染为固定键序、UTF-8、末尾换行的 JSON 文本。"""

    ordered = {name: manifest[name] for name in MANIFEST_FIELDS}
    return json.dumps(ordered, ensure_ascii=False, indent=2) + "\n"


# ---------------------------------------------------------------------------
# 外部工具封装（apksigner / keytool / aapt2）
# ---------------------------------------------------------------------------


def _run(command: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def _android_sdk_roots() -> List[str]:
    roots: List[str] = []
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value and value not in roots:
            roots.append(value)
    return roots


def find_build_tool(name: str) -> Optional[str]:
    """在 `$ANDROID_HOME`/`$ANDROID_SDK_ROOT` 的 build-tools 里找最新的工具，回退到 PATH。"""

    candidates: List[str] = []
    for root in _android_sdk_roots():
        build_tools = os.path.join(root, "build-tools")
        if not os.path.isdir(build_tools):
            continue
        versions = []
        for entry in os.listdir(build_tools):
            if not os.path.isdir(os.path.join(build_tools, entry)):
                continue
            versions.append(entry)
        # 按「数字段」排序，让 37.0.0 > 9.0.0（避免字符串排序把 "9" 排在 "37" 之后）。
        versions.sort(key=lambda v: [int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", v)])
        for version in reversed(versions):
            for suffix in ("", ".bat", ".exe"):
                candidate = os.path.join(build_tools, version, name + suffix)
                if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                    candidates.append(candidate)
                    break
    if candidates:
        return candidates[0]
    return shutil.which(name)


def _find_keytool() -> Optional[str]:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "keytool")
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return shutil.which("keytool")


def extract_signing_cert_sha256(apk_path: str) -> str:
    """提取 APK 内签名证书的 SHA-256（64 位小写 hex）。

    实现顺序：优先 ``apksigner verify --print-certs``；找不到再退回
    ``keytool -printcert -jarfile``。

    两种失败情形分开报错，绝不伪造证书：

    * **工具缺失**（apksigner 与 keytool 都不可用）→ 提示安装工具或手工填值；
    * **工具可用但读不出证书**（APK 未签名 / 签名不可读）→ 提示该真实原因。

    两种情形都抛 :class:`ToolchainError`，对外原因码统一为 ``CERT``。
    """

    apksigner = find_build_tool("apksigner")
    keytool = _find_keytool()
    if apksigner is None and keytool is None:
        raise ToolchainError(
            "无法提取 APK 签名证书：既没有可用的 apksigner，也没有可用的 keytool；"
            "请安装 Android build-tools 或 JDK，或手工在 latest.json 的 signingCertSha256 填入真实值后重试"
        )

    if apksigner:
        result = _run([apksigner, "verify", "--print-certs", apk_path])
        match = re.search(r"certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})", result.stdout + result.stderr)
        if match:
            return match.group(1).lower()

    if keytool:
        result = _run([keytool, "-printcert", "-jarfile", apk_path])
        match = re.search(r"SHA256:\s*((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})", result.stdout + result.stderr)
        if match:
            return match.group(1).replace(":", "").lower()

    raise ToolchainError(
        "APK 未签名或签名不可读（证书提取失败）：apksigner/keytool 可用但未能读出签名证书；"
        "请确认该 APK 已签名，或手工核对 latest.json 的 signingCertSha256（绝不伪造证书）"
    )


def read_apk_metadata(apk_path: str) -> Optional[Dict[str, object]]:
    """用 ``aapt2 dump badging`` 读取 APK 的 applicationId / versionCode / versionName。

    不可用时返回 ``None``（调用方决定是报错还是回退到手工参数）。
    """

    aapt2 = find_build_tool("aapt2")
    if not aapt2:
        return None
    result = _run([aapt2, "dump", "badging", apk_path])
    if result.returncode != 0 or not result.stdout:
        return None
    first_line = result.stdout.splitlines()[0] if result.stdout.splitlines() else ""
    if not first_line.startswith("package:"):
        for line in result.stdout.splitlines():
            if line.startswith("package:"):
                first_line = line
                break
    name = re.search(r"name='([^']*)'", first_line)
    version_code = re.search(r"versionCode='([^']*)'", first_line)
    version_name = re.search(r"versionName='([^']*)'", first_line)
    if not name or not version_code or not version_name:
        return None
    if not version_code.group(1).isdigit():
        return None
    return {
        "applicationId": name.group(1),
        "versionCode": int(version_code.group(1)),
        "versionName": version_name.group(1),
    }


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


# ---------------------------------------------------------------------------
# ABI 核对（只读 APK 的 lib/ 中央目录，不解析 AndroidManifest）
# ---------------------------------------------------------------------------


def read_apk_abis(apk_path: str) -> Optional[frozenset]:
    """读出 APK 内 ``lib/<abi>/`` 覆盖的 ABI 集合。

    返回 ``None`` 表示**无法读取**（不是 zip / IO 失败）；返回空集合表示 APK 不含原生库。
    两种情况在调用方按严格程度决定是拒绝还是提示跳过。
    """

    try:
        with zipfile.ZipFile(apk_path) as archive:
            abis = set()
            for name in archive.namelist():
                if not name.startswith("lib/"):
                    continue
                segments = name.split("/")
                if len(segments) >= 3 and segments[1]:
                    abis.add(segments[1])
            return frozenset(abis)
    except (zipfile.BadZipFile, OSError):
        return None


def _check_apk_abi(apk_path: str, expected_abi: str, *, strict: bool) -> None:
    """确认 APK 恰好只打包 ``expected_abi`` 一个 ABI（拒绝双 ABI 巨包与错通道）。"""

    abis = read_apk_abis(apk_path)
    if abis is None or not abis:
        detail = "无法读取 APK 的 lib/ 目录（不是 zip）" if abis is None else "APK 不含任何原生库"
        if strict:
            raise ManifestError(REASON_ABI, f"{detail}，无法确认单 ABI: {apk_path}")
        print(f"注意：{detail}，已跳过 ABI 核对（未显式指定 --abi）。", file=sys.stderr)
        return
    if abis != frozenset({expected_abi}):
        raise ManifestError(
            REASON_ABI,
            f"APK 实际 ABI={sorted(abis)} 与通道 {expected_abi} 不符；正式包必须单 ABI",
        )


# ---------------------------------------------------------------------------
# 原子写入：清单用 temp+fsync+os.replace，旧产物永不覆盖
# ---------------------------------------------------------------------------


def _fsync_directory(directory: str) -> None:
    try:
        descriptor = os.open(directory, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        os.close(descriptor)


def atomic_write_text(path: str, text: str) -> None:
    """同目录写临时文件后 ``os.replace``，读者要么看到旧内容要么看到完整新内容。"""

    directory = os.path.dirname(path) or "."
    descriptor, temporary = tempfile.mkstemp(prefix=".release-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        _fsync_directory(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def atomic_copy_file(source: str, destination: str, *, replace: bool = False) -> None:
    """复制到唯一临时文件后硬链接发布；同名异内容拒绝，绝不替换旧 APK。"""

    directory = os.path.dirname(destination) or "."
    os.makedirs(directory, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".release-", suffix=".apk.tmp", dir=directory)
    try:
        with os.fdopen(descriptor, "wb") as output, open(source, "rb") as input_handle:
            shutil.copyfileobj(input_handle, output, 1024 * 1024)
            output.flush()
            os.fsync(output.fileno())
        if replace:  # 仅供可重复构建的工作输出；发布产物始终使用默认 no-clobber。
            os.replace(temporary, destination)
        else:
            try:
                os.link(temporary, destination)
            except FileExistsError:
                if os.path.islink(destination) or sha256_file(destination) != sha256_file(temporary):
                    raise ManifestError(REASON_HASH, "同名 APK 已存在且内容不同；拒绝覆盖")
        _fsync_directory(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


# ---------------------------------------------------------------------------
# verify：按客户端 1–5 条逐项校验发布目录
# ---------------------------------------------------------------------------


def run_verify(
    release_dir: str,
    expect_application_id: Optional[str] = None,
    installed_version_code: Optional[int] = None,
    expect_signing_cert_sha256: Optional[str] = None,
    abi: Optional[str] = None,
) -> Dict[str, object]:
    """校验 ``release_dir`` 下某个 ABI 通道的清单 + 它引用的 APK。

    ``abi=None`` 保持历史行为：只读 legacy ``latest.json``，不做 ABI 核对。
    显式传入 ``abi``（``arm64-v8a`` / ``armeabi-v7a``）时读取该通道清单，并强制
    该 APK 恰好只含这一个 ABI —— 双 ABI 巨包或错通道都会以 ``ABI`` 原因码拒绝。

    任一项失败立即抛 :class:`ManifestError`（携带单一原因码）；全部通过返回清单字典。

    校验顺序刻意与客户端一致：先做清单结构/格式，再做「清单 vs 期望身份」，最后做
    文件级 size / hash / 证书 / ABI / 包内身份校验。
    """

    if abi is not None and abi not in SUPPORTED_ABIS:
        raise ManifestError(REASON_ABI, f"不支持的 --abi={abi}；可选 {list(SUPPORTED_ABIS)}")

    manifest_path = os.path.join(release_dir, manifest_name_for_abi(abi or LEGACY_ABI))
    if not os.path.isfile(manifest_path):
        raise ManifestError(REASON_MANIFEST_INVALID, f"缺少清单文件: {manifest_path}")
    manifest = load_manifest_file(manifest_path)

    # ---- 清单 vs 期望身份（相当于客户端 checkOffer，先于下载/安装）----
    if expect_application_id is not None and manifest["applicationId"] != expect_application_id:
        raise ManifestError(
            REASON_IDENTITY_MISMATCH,
            f"清单 applicationId={manifest['applicationId']} 与期望 {expect_application_id} 不符",
        )
    if installed_version_code is not None and int(manifest["versionCode"]) <= int(installed_version_code):
        raise ManifestError(
            REASON_NOT_NEWER,
            f"清单 versionCode={manifest['versionCode']} 未大于已装 {installed_version_code}",
        )
    if expect_signing_cert_sha256 is not None and manifest["signingCertSha256"] != expect_signing_cert_sha256.lower():
        raise ManifestError(REASON_CERT, "清单 signingCertSha256 与期望证书不符")

    # ---- 文件级校验（相当于客户端 verifyDownloaded）----
    apk_path = os.path.join(release_dir, str(manifest["apkPath"]))
    if not os.path.isfile(apk_path):
        raise ManifestError(REASON_APK_MISSING, f"清单引用的 APK 不存在: {apk_path}")

    actual_size = os.path.getsize(apk_path)
    if actual_size > MAX_APK_BYTES:
        raise ManifestError(REASON_APK_TOO_LARGE, f"APK 超过 256 MiB: {actual_size}")
    if actual_size != manifest["size"]:
        raise ManifestError(REASON_SIZE, f"实际大小 {actual_size} != 清单 size {manifest['size']}")

    actual_sha256 = sha256_file(apk_path)
    if actual_sha256 != manifest["sha256"]:
        raise ManifestError(REASON_HASH, "实际 sha256 与清单不符")

    actual_cert = extract_signing_cert_sha256(apk_path)
    if actual_cert != manifest["signingCertSha256"]:
        raise ManifestError(REASON_CERT, "APK 内签名证书与清单 signingCertSha256 不符")

    # 只有显式指定通道时才强制单 ABI（legacy 默认保持旧行为，兼容历史调试包）。
    if abi is not None:
        _check_apk_abi(apk_path, abi, strict=True)

    metadata = read_apk_metadata(apk_path)
    if metadata is not None:
        if metadata["applicationId"] != manifest["applicationId"]:
            raise ManifestError(
                REASON_IDENTITY_MISMATCH,
                f"APK 包名 {metadata['applicationId']} 与清单 {manifest['applicationId']} 不符",
            )
        if int(metadata["versionCode"]) != int(manifest["versionCode"]):
            raise ManifestError(
                REASON_IDENTITY_MISMATCH,
                f"APK versionCode {metadata['versionCode']} 与清单 {manifest['versionCode']} 不符",
            )
    else:
        print("注意：缺少 aapt2，已跳过 APK 包内 applicationId/versionCode 与清单的一致性核对。", file=sys.stderr)

    return manifest


def _print_verify_ok(manifest: Dict[str, object], abi: str) -> None:
    print(f"发布目录校验通过（通道 {abi} / {manifest_name_for_abi(abi)}）：")
    print(f"  applicationId     : {manifest['applicationId']}")
    print(f"  versionCode       : {manifest['versionCode']}")
    print(f"  versionName       : {manifest['versionName']}")
    print(f"  apkPath           : {manifest['apkPath']}")
    print(f"  size              : {manifest['size']}")
    print(f"  sha256            : {str(manifest['sha256'])[:12]}…")
    print(f"  signingCertSha256 : {str(manifest['signingCertSha256'])[:12]}…")
    print(f"  publishedAt       : {manifest['publishedAt']}")
    print("OK")


def run_verify_dual(
    release_dir: str,
    expect_application_id: Optional[str] = None,
    installed_version_code: Optional[int] = None,
    expect_signing_cert_sha256: Optional[str] = None,
) -> Dict[str, Dict[str, object]]:
    """逐通道校验双 ABI 发布目录，并交叉确认两个通道身份完全一致。"""

    manifests: Dict[str, Dict[str, object]] = {}
    for abi in SUPPORTED_ABIS:
        manifests[abi] = run_verify(
            release_dir,
            expect_application_id=expect_application_id,
            installed_version_code=installed_version_code,
            expect_signing_cert_sha256=expect_signing_cert_sha256,
            abi=abi,
        )
    arm64 = manifests[LEGACY_ABI]
    v7a = manifests["armeabi-v7a"]
    for field in ("applicationId", "versionCode", "versionName", "signingCertSha256"):
        if arm64[field] != v7a[field]:
            raise ManifestError(
                REASON_IDENTITY_MISMATCH,
                f"两个 ABI 通道的 {field} 不一致：{arm64[field]!r} != {v7a[field]!r}",
            )
    if arm64["apkPath"] == v7a["apkPath"]:
        raise ManifestError(REASON_IDENTITY_MISMATCH, "两个 ABI 通道引用了同一个 APK 文件名")
    return manifests


# ---------------------------------------------------------------------------
# pack：从签名 APK 生成 latest.json + 复制 APK
# ---------------------------------------------------------------------------


def _default_apk_name(application_id: str) -> str:
    return f"{application_id}.apk"


def _versioned_apk_name(application_id: str, version_code: int, abi: str) -> str:
    """随 versionCode 递增的文件名，保证旧产物永不被覆盖、可随时回滚。"""

    return f"{application_id}-v{version_code}-{abi}.apk"


def _default_published_at() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime(_PUBLISHED_AT_STRPTIME)


def run_pack(
    apk: str,
    release_dir: str,
    application_id: Optional[str] = None,
    version_name: Optional[str] = None,
    published_at: Optional[str] = None,
    version_code: Optional[int] = None,
    apk_name: Optional[str] = None,
    abi: Optional[str] = None,
) -> Dict[str, object]:
    """把 ``apk`` 打包进 ``release_dir``（生成对应通道清单并原子复制 APK）。

    关键点：

    * ``applicationId`` / ``versionCode`` / ``versionName`` 优先从 APK 读取（``aapt2``），
      读不到时用显式参数；两者都没有则报错退出，绝不猜测。
    * ``signingCertSha256`` 必须从 APK 提取；工具不可用时**报错退出并给出手工填法**。
    * ``abi=None`` 保持历史行为：写 legacy ``latest.json``、默认文件名 ``<applicationId>.apk``。
      传入 ``--abi`` 时写该通道清单（v7a 为 ``latest-armeabi-v7a.json``），并强制 APK 单 ABI。
    * 防降级：发布目录内任一已知通道清单的 ``versionCode`` ≥ 新清单时拒绝覆盖。
    """

    if not os.path.isfile(apk):
        raise ManifestError(REASON_APK_MISSING, f"APK 不存在或不是普通文件: {apk}")
    file_size = os.path.getsize(apk)
    if file_size > MAX_APK_BYTES:
        raise ManifestError(REASON_APK_TOO_LARGE, f"APK 超过 256 MiB: {file_size}")

    metadata = read_apk_metadata(apk)

    # applicationId：优先 APK，读不到才用参数。
    if metadata is not None:
        resolved_application_id = str(metadata["applicationId"])
        if application_id is not None and application_id != resolved_application_id:
            print(
                f"注意：--application-id {application_id} 与 APK 实际包名 {resolved_application_id} 不符，"
                f"以 APK 为准。",
                file=sys.stderr,
            )
    elif application_id is not None:
        resolved_application_id = application_id
    else:
        raise ManifestError(
            REASON_MANIFEST_INVALID,
            "无法从 APK 读取 applicationId（缺少 aapt2），请显式传入 --application-id",
        )

    # versionCode：优先 APK，读不到才用参数（必须显式给出）。
    if metadata is not None:
        resolved_version_code = int(metadata["versionCode"])
        if version_code is not None and int(version_code) != resolved_version_code:
            print(
                f"注意：--version-code {version_code} 与 APK 实际 versionCode {resolved_version_code} 不符，"
                f"以 APK 为准。",
                file=sys.stderr,
            )
    elif version_code is not None:
        resolved_version_code = int(version_code)
    else:
        raise ManifestError(
            REASON_MANIFEST_INVALID,
            "无法从 APK 读取 versionCode（缺少 aapt2），请显式传入 --version-code",
        )

    # versionName：优先参数，其次 APK。
    if version_name is not None:
        resolved_version_name = version_name
    elif metadata is not None:
        resolved_version_name = str(metadata["versionName"])
    else:
        raise ManifestError(
            REASON_MANIFEST_INVALID,
            "无法从 APK 读取 versionName（缺少 aapt2），请显式传入 --version-name",
        )

    if abi is not None and abi not in SUPPORTED_ABIS:
        raise ManifestError(REASON_ABI, f"不支持的 --abi={abi}；可选 {list(SUPPORTED_ABIS)}")
    channel_abi = abi or LEGACY_ABI
    _check_apk_abi(apk, channel_abi, strict=abi is not None)

    resolved_published_at = published_at if published_at is not None else _default_published_at()
    if apk_name is not None:
        resolved_apk_name = apk_name
    elif abi is not None:
        # 显式通道必须用分版本命名，避免与 legacy <applicationId>.apk 互相覆盖。
        resolved_apk_name = _versioned_apk_name(resolved_application_id, resolved_version_code, channel_abi)
    else:
        resolved_apk_name = _default_apk_name(resolved_application_id)

    manifest_fields: Dict[str, object] = {
        "applicationId": resolved_application_id,
        "versionCode": resolved_version_code,
        "versionName": resolved_version_name,
        "apkPath": resolved_apk_name,
        "size": file_size,
        "sha256": sha256_file(apk),
        "signingCertSha256": extract_signing_cert_sha256(apk),
        "publishedAt": resolved_published_at,
    }

    # 先自检，确保我们写出的清单一定能被客户端解析（否则等于制造故障）。
    manifest = validate_manifest_values({name: manifest_fields[name] for name in MANIFEST_FIELDS})

    os.makedirs(release_dir, exist_ok=True)

    # 防降级：任一已知通道的现有清单都不允许被同版或降级覆盖。
    existing_version_code = _highest_existing_version_code(release_dir)
    if existing_version_code is not None and existing_version_code >= int(manifest["versionCode"]):
        raise ManifestError(
            REASON_NOT_NEWER,
            f"发布目录已有 versionCode={existing_version_code}，不小于新清单的 {manifest['versionCode']}；拒绝覆盖",
        )

    manifest_path = os.path.join(release_dir, manifest_name_for_abi(channel_abi))
    destination_apk = os.path.join(release_dir, str(manifest["apkPath"]))
    if os.path.realpath(apk) != os.path.realpath(destination_apk):
        atomic_copy_file(apk, destination_apk)
    atomic_write_text(manifest_path, render_manifest_json(manifest))

    _print_pack_ok(manifest, release_dir, destination_apk, channel_abi)
    return manifest


def _read_existing_version_code(latest_json_path: str) -> Optional[int]:
    try:
        with open(latest_json_path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        return None
    value = data.get("versionCode") if isinstance(data, dict) else None
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    return None


def _highest_existing_version_code(release_dir: str) -> Optional[int]:
    """返回发布目录内所有已知 ABI 通道清单的最大 versionCode。

    无法解析的既有清单直接报错拒绝，绝不静默覆盖破坏现场。
    """

    highest: Optional[int] = None
    for abi in SUPPORTED_ABIS:
        path = os.path.join(release_dir, manifest_name_for_abi(abi))
        if not os.path.isfile(path):
            continue
        code = _read_existing_version_code(path)
        if code is None:
            raise ManifestError(
                REASON_MANIFEST_INVALID,
                f"已存在无法解析的 {path}；为避免破坏现场，拒绝覆盖（请先人工处理）",
            )
        highest = code if highest is None else max(highest, code)
    return highest


def run_pack_dual(
    arm64_apk: str,
    v7a_apk: str,
    release_dir: str,
    application_id: Optional[str] = None,
    version_name: Optional[str] = None,
    published_at: Optional[str] = None,
    version_code: Optional[int] = None,
) -> Dict[str, Dict[str, object]]:
    """一次发布两个单 ABI 通道（arm64-v8a + armeabi-v7a）。

    不变量：
    * 两个 APK **同** applicationId / versionCode / versionName / 签名证书；
    * 每个 APK 恰好只含自己通道的那一个 ABI（拒绝双 ABI 巨包）；
    * APK 用 ``<applicationId>-v<versionCode>-<abi>.apk`` 分版本命名，旧产物保留；
    * 先原子写 v7a 清单，最后原子写 legacy ``latest.json``：旧客户端读到的 ``latest.json``
      任何时刻都是完整的旧版或完整的新版，且其引用的 APK 已先落盘。
    """

    inputs = {LEGACY_ABI: arm64_apk, "armeabi-v7a": v7a_apk}

    sizes: Dict[str, int] = {}
    for abi, path in inputs.items():
        if not os.path.isfile(path):
            raise ManifestError(REASON_APK_MISSING, f"{abi} APK 不存在或不是普通文件: {path}")
        size = os.path.getsize(path)
        if size > MAX_APK_BYTES:
            raise ManifestError(REASON_APK_TOO_LARGE, f"{abi} APK 超过 256 MiB: {size}")
        sizes[abi] = size
        _check_apk_abi(path, abi, strict=True)

    # 身份解析：优先 aapt2 读包；缺工具时必须显式给全 --application-id/--version-code/--version-name。
    resolved: Dict[str, Tuple[str, int, str]] = {}
    for abi, path in inputs.items():
        metadata = read_apk_metadata(path)
        if metadata is not None:
            resolved[abi] = (str(metadata["applicationId"]), int(metadata["versionCode"]), str(metadata["versionName"]))
        elif application_id is not None and version_code is not None and version_name is not None:
            resolved[abi] = (application_id, int(version_code), version_name)
        else:
            raise ManifestError(
                REASON_MANIFEST_INVALID,
                f"缺少 aapt2 且未显式给全 identity，无法解析 {abi} APK 的 applicationId/versionCode/versionName",
            )

    if len(set(resolved.values())) != 1:
        raise ManifestError(REASON_IDENTITY_MISMATCH, f"两个 APK 的 applicationId/versionCode/versionName 不一致: {resolved}")

    resolved_application_id, resolved_version_code, resolved_version_name = resolved[LEGACY_ABI]
    if application_id is not None and application_id != resolved_application_id:
        print(f"注意：--application-id {application_id} 与 APK 实际包名 {resolved_application_id} 不符，以 APK 为准。", file=sys.stderr)
    if version_code is not None and int(version_code) != resolved_version_code:
        print(f"注意：--version-code {version_code} 与 APK 实际 versionCode {resolved_version_code} 不符，以 APK 为准。", file=sys.stderr)
    if version_name is not None and version_name != resolved_version_name:
        print(f"注意：--version-name {version_name} 与 APK 实际 versionName {resolved_version_name} 不符，以 APK 为准。", file=sys.stderr)

    certs = {abi: extract_signing_cert_sha256(path) for abi, path in inputs.items()}
    if len(set(certs.values())) != 1:
        raise ManifestError(REASON_CERT, f"两个 APK 签名证书不一致（必须同一证书）: {certs}")
    resolved_cert = certs[LEGACY_ABI]

    resolved_published_at = published_at if published_at is not None else _default_published_at()
    names = {
        abi: _versioned_apk_name(resolved_application_id, resolved_version_code, abi)
        for abi in SUPPORTED_ABIS
    }

    manifests: Dict[str, Dict[str, object]] = {}
    for abi in SUPPORTED_ABIS:
        fields: Dict[str, object] = {
            "applicationId": resolved_application_id,
            "versionCode": resolved_version_code,
            "versionName": resolved_version_name,
            "apkPath": names[abi],
            "size": sizes[abi],
            "sha256": sha256_file(inputs[abi]),
            "signingCertSha256": resolved_cert,
            "publishedAt": resolved_published_at,
        }
        manifests[abi] = validate_manifest_values({name: fields[name] for name in MANIFEST_FIELDS})

    os.makedirs(release_dir, exist_ok=True)
    existing_version_code = _highest_existing_version_code(release_dir)
    if existing_version_code is not None and existing_version_code >= int(manifests[LEGACY_ABI]["versionCode"]):
        raise ManifestError(
            REASON_NOT_NEWER,
            f"发布目录已有 versionCode={existing_version_code}，不小于新清单的 {manifests[LEGACY_ABI]['versionCode']}；拒绝覆盖",
        )

    # 1) 先把不可变的 APK 全部落盘；2) 再切清单，legacy latest.json 最后切。
    destinations: Dict[str, str] = {}
    for abi in SUPPORTED_ABIS:
        destination = os.path.join(release_dir, names[abi])
        if os.path.realpath(inputs[abi]) != os.path.realpath(destination):
            atomic_copy_file(inputs[abi], destination)
        destinations[abi] = destination
    for abi in reversed(SUPPORTED_ABIS):  # armeabi-v7a 先、arm64-v8a(legacy latest.json) 最后
        atomic_write_text(
            os.path.join(release_dir, manifest_name_for_abi(abi)),
            render_manifest_json(manifests[abi]),
        )

    _print_pack_dual_ok(manifests, release_dir, destinations)
    return manifests


def _print_pack_ok(
    manifest: Dict[str, object],
    release_dir: str,
    destination_apk: str,
    abi: str,
) -> None:
    print(f"打包完成（通道 {abi}）：")
    print(f"  清单      : {os.path.join(release_dir, manifest_name_for_abi(abi))}")
    print(f"  APK       : {destination_apk}")
    print(f"  versionCode: {manifest['versionCode']}  versionName: {manifest['versionName']}")
    print(f"  size       : {manifest['size']}  sha256: {str(manifest['sha256'])[:12]}…")
    print("下一步：")
    print("  1) 使用自己已核实的发布目录与更新端点，保留既有版本与回滚清单。")
    print("  2) 更新令牌由既有工具注入，不写入命令历史、日志或仓库。")
    print("  3) 双通道发布前自检：python3 tools/lan_release/lan_release.py verify-dual --release-dir <该目录>")
    print("  4) 候选真实设备验收通过后，再按 README 的显式激活流程切换稳定入口。")


def _print_pack_dual_ok(
    manifests: Dict[str, Dict[str, object]],
    release_dir: str,
    destinations: Dict[str, str],
) -> None:
    print("双 ABI 打包完成（同一 versionCode，两个单 ABI 正式包）：")
    for abi in SUPPORTED_ABIS:
        manifest = manifests[abi]
        print(f"  {abi}:")
        print(f"    清单 : {os.path.join(release_dir, manifest_name_for_abi(abi))}")
        print(f"    APK  : {destinations[abi]}")
        print(f"    size : {manifest['size']}  sha256: {str(manifest['sha256'])[:12]}…")
    manifest = manifests[LEGACY_ABI]
    print(f"  versionCode: {manifest['versionCode']}  versionName: {manifest['versionName']}")
    print("  旧产物与旧清单都已保留，可回滚。")
    print("下一步：")
    print("  1) 使用 README 的候选发布流程保存不可变候选，默认不切换稳定入口。")
    print("  2) 发布前自检：python3 tools/lan_release/lan_release.py verify-dual --release-dir <该目录>")
    print("  3) 更新端点由自己的部署参数指定；32 位客户端带 X-KanTV-ABI 头取 v7a 清单。")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"不是整数: {value}")
    if parsed <= 0:
        raise argparse.ArgumentTypeError("必须为正整数")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="lan_release.py",
        description=(
            "KanTV 局域网更新发布工具链：从签名 APK 生成单 ABI 通道清单"
            "（pack / pack-dual），并在发布前按客户端规则自检（verify / verify-dual）。"
        ),
    )
    sub = parser.add_subparsers(dest="command", required=True)

    pack = sub.add_parser("pack", help="从签名 APK 生成通道清单并把 APK 复制到发布目录")
    pack.add_argument("--apk", required=True, help="已正式签名的 APK 路径")
    pack.add_argument("--release-dir", required=True, help="发布目录（容器以 :ro 挂载）")
    pack.add_argument("--application-id", default=None, help=f"包名（默认从 APK 读取；建议值 {DEFAULT_APPLICATION_ID}）")
    pack.add_argument("--version-name", default=None, help="版本名（默认从 APK 读取，例如 0.2.11）")
    pack.add_argument("--version-code", type=_positive_int, default=None, help="versionCode（默认从 APK 读取）")
    pack.add_argument("--published-at", default=None, help="发布时间（严格 YYYY-MM-DDTHH:MM:SSZ，默认当前 UTC）")
    pack.add_argument("--apk-name", default=None, help="发布目录内的 APK 文件名（不指定 --abi 时默认 <applicationId>.apk）")
    pack.add_argument("--abi", choices=SUPPORTED_ABIS, default=None, help="发布通道 ABI；指定后写该通道清单并强制 APK 单 ABI")

    pack_dual = sub.add_parser("pack-dual", help="一次发布 arm64-v8a + armeabi-v7a 两个单 ABI 通道（同版本、同签名）")
    pack_dual.add_argument("--arm64-apk", required=True, help="arm64-v8a 已签名 APK 路径")
    pack_dual.add_argument("--v7a-apk", required=True, help="armeabi-v7a 已签名 APK 路径")
    pack_dual.add_argument("--release-dir", required=True, help="发布目录（容器以 :ro 挂载）")
    pack_dual.add_argument("--application-id", default=None, help=f"包名（默认从 APK 读取；建议值 {DEFAULT_APPLICATION_ID}）")
    pack_dual.add_argument("--version-name", default=None, help="版本名（默认从 APK 读取）")
    pack_dual.add_argument("--version-code", type=_positive_int, default=None, help="versionCode（默认从 APK 读取）")
    pack_dual.add_argument("--published-at", default=None, help="发布时间（严格 YYYY-MM-DDTHH:MM:SSZ，默认当前 UTC，两通道共用）")

    verify = sub.add_parser("verify", help="按客户端规则校验发布目录（失败时打印单一原因码并以非 0 退出）")
    verify.add_argument("--release-dir", required=True, help="包含通道清单与其引用 APK 的发布目录")
    verify.add_argument("--expect-application-id", default=None, help="期望的目标包名（通常等于已装应用包名）")
    verify.add_argument("--installed-version-code", type=_positive_int, default=None, help="已装应用的 versionCode（用于递增校验）")
    verify.add_argument("--expect-signing-cert-sha256", default=None, help="期望的签名证书 SHA-256（通常等于已装应用证书）")
    verify.add_argument("--abi", choices=SUPPORTED_ABIS, default=None, help="校验该 ABI 通道清单并强制 APK 单 ABI（默认只校验 legacy latest.json）")

    verify_dual = sub.add_parser("verify-dual", help="逐通道校验双 ABI 发布目录并交叉确认两通道身份一致")
    verify_dual.add_argument("--release-dir", required=True, help="包含两个通道清单与其引用 APK 的发布目录")
    verify_dual.add_argument("--expect-application-id", default=None, help="期望的目标包名（通常等于已装应用包名）")
    verify_dual.add_argument("--installed-version-code", type=_positive_int, default=None, help="已装应用的 versionCode（用于递增校验）")
    verify_dual.add_argument("--expect-signing-cert-sha256", default=None, help="期望的签名证书 SHA-256（通常等于已装应用证书）")

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "pack":
            run_pack(
                apk=args.apk,
                release_dir=args.release_dir,
                application_id=args.application_id,
                version_name=args.version_name,
                published_at=args.published_at,
                version_code=args.version_code,
                apk_name=args.apk_name,
                abi=args.abi,
            )
        elif args.command == "pack-dual":
            run_pack_dual(
                arm64_apk=args.arm64_apk,
                v7a_apk=args.v7a_apk,
                release_dir=args.release_dir,
                application_id=args.application_id,
                version_name=args.version_name,
                published_at=args.published_at,
                version_code=args.version_code,
            )
        elif args.command == "verify-dual":
            manifests = run_verify_dual(
                release_dir=args.release_dir,
                expect_application_id=args.expect_application_id,
                installed_version_code=args.installed_version_code,
                expect_signing_cert_sha256=args.expect_signing_cert_sha256,
            )
            for abi in SUPPORTED_ABIS:
                _print_verify_ok(manifests[abi], abi)
        else:
            manifest = run_verify(
                release_dir=args.release_dir,
                expect_application_id=args.expect_application_id,
                installed_version_code=args.installed_version_code,
                expect_signing_cert_sha256=args.expect_signing_cert_sha256,
                abi=args.abi,
            )
            _print_verify_ok(manifest, args.abi or LEGACY_ABI)
    except ToolchainError as exc:
        print(f"{REASON_CERT}: {exc}", file=sys.stderr)
        return 1
    except ManifestError as exc:
        print(f"{exc.code}: {exc.detail}" if exc.detail else exc.code, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
