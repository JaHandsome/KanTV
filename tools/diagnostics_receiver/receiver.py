"""最小化、仅写入的 NAS 播放诊断接收器。"""

from __future__ import annotations

import argparse
import errno
import hashlib
import hmac
import io
import json
import logging
import os
import re
import stat
import tempfile
import threading
import time
import zipfile
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from schema import SchemaError, validate_report


LOGGER = logging.getLogger(__name__)

MAX_REQUEST_BYTES = 512 * 1024
MAX_REPORTS = 2000
MAX_STORAGE_BYTES = 512 * 1024 * 1024
MAX_RELEASE_MANIFEST_BYTES = 16 * 1024
MAX_APK_BYTES = 256 * 1024 * 1024
RELEASE_FIELDS = {
    "applicationId",
    "versionCode",
    "versionName",
    "apkPath",
    "size",
    "sha256",
    "signingCertSha256",
    "publishedAt",
}
APK_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,126}\.apk")
APP_ID = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
SHA256 = re.compile(r"[0-9a-f]{64}")
PUBLISHED_AT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
SINGLE_RANGE = re.compile(r"bytes=(\d+)-(\d*)")
# ABI 通道：没有该头 = legacy arm64；只有白名单值才路由到对应清单。
ABI_HEADER = "X-KanTV-ABI"
LEGACY_ABI = "arm64-v8a"
SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a")
# 仅允许结构化字段路径：body/report，后接小写开头的标识符段或数字下标段。
# schema 生成的字段名均为 lowerCamelCase，因此小写开头可同时挡掉大写频道名等注入键。
FAILURE_PATH = re.compile(r"(?:body|report)(?:\.[a-z][A-Za-z0-9_]*|\[\d{1,3}\])*")
MAX_FAILURE_PATH_CHARS = 200
REPORT_NAME = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.json")


@dataclass(frozen=True)
class ReceiverConfig:
    token: str
    storage_dir: Path
    max_reports: int = MAX_REPORTS
    max_storage_bytes: int = MAX_STORAGE_BYTES
    release_dir: Path | None = None
    release_token: str | None = None
    max_apk_bytes: int = MAX_APK_BYTES


def load_token(environ: dict[str, str] | None = None) -> str:
    environment = os.environ if environ is None else environ
    token = environment.get("DIAGNOSTICS_RECEIVER_TOKEN")
    token_file = environment.get("DIAGNOSTICS_RECEIVER_TOKEN_FILE")
    if token is None and token_file:
        token = Path(token_file).read_text(encoding="utf-8").rstrip("\r\n")
    if not token or len(token) > 4096:
        raise ValueError("必须通过 DIAGNOSTICS_RECEIVER_TOKEN 或 DIAGNOSTICS_RECEIVER_TOKEN_FILE 配置有效写入令牌")
    return token


def load_release_token(environ: dict[str, str] | None = None) -> str | None:
    environment = os.environ if environ is None else environ
    token = environment.get("UPDATE_RECEIVER_TOKEN")
    token_file = environment.get("UPDATE_RECEIVER_TOKEN_FILE")
    if token is None and token_file:
        token = Path(token_file).read_text(encoding="utf-8").rstrip("\r\n")
    if token is not None and (not token or len(token) > 4096):
        raise ValueError("UPDATE_RECEIVER_TOKEN 或 UPDATE_RECEIVER_TOKEN_FILE 必须提供有效的独立更新令牌")
    return token


def _prepare_storage(path: Path) -> None:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink() or not path.is_dir():
        raise ValueError("存储路径必须是非符号链接目录")
    os.chmod(path, 0o700)


def _prepare_release_dir(path: Path) -> None:
    """Validate the administrator-owned read-only release mount without modifying it."""
    info = os.lstat(path)
    if path.is_symlink() or not stat.S_ISDIR(info.st_mode):
        raise ValueError("发布路径必须是非符号链接目录")


class ReleaseError(ValueError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ReleaseError("duplicate release field")
        result[key] = value
    return result


def _manifest_name(abi: str) -> str:
    """把 ABI 通道映射到发布目录内的清单文件名（legacy arm64 沿用 latest.json）。"""

    return "latest.json" if abi == LEGACY_ABI else f"latest-{abi}.json"


def _apk_abis(apk_path: Path) -> frozenset[str] | None:
    """只读 APK 的 ``lib/<abi>/`` 中央目录，返回 ABI 集合。

    ``None`` 表示无法判定（不是 zip / IO 失败）；空集合表示不含原生库。正式更新包
    必须能明确证明只包含通道对应的单一 ABI，因此两者都会由调用方 fail-closed 拒绝。
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


def _load_release(config: ReceiverConfig, abi: str = LEGACY_ABI) -> tuple[dict[str, object], Path]:
    if config.release_dir is None:
        raise FileNotFoundError("release endpoint disabled")
    manifest_path = config.release_dir / _manifest_name(abi)
    info = os.lstat(manifest_path)
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) or info.st_size > MAX_RELEASE_MANIFEST_BYTES:
        raise ReleaseError("invalid release manifest file")
    try:
        manifest = json.loads(
            manifest_path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, OSError, ReleaseError) as error:
        raise ReleaseError("invalid release manifest") from error
    if not isinstance(manifest, dict) or set(manifest) != RELEASE_FIELDS:
        raise ReleaseError("invalid release fields")
    application_id = manifest["applicationId"]
    version_code = manifest["versionCode"]
    version_name = manifest["versionName"]
    apk_name = manifest["apkPath"]
    size = manifest["size"]
    sha256 = manifest["sha256"]
    signing_cert = manifest["signingCertSha256"]
    published_at = manifest["publishedAt"]
    if not isinstance(application_id, str) or APP_ID.fullmatch(application_id) is None:
        raise ReleaseError("invalid application id")
    if isinstance(version_code, bool) or not isinstance(version_code, int) or version_code <= 0:
        raise ReleaseError("invalid version code")
    if not isinstance(version_name, str) or not 1 <= len(version_name) <= 100 or any(ord(c) < 0x20 for c in version_name):
        raise ReleaseError("invalid version name")
    if not isinstance(apk_name, str) or APK_NAME.fullmatch(apk_name) is None or ".." in apk_name:
        raise ReleaseError("invalid apk path")
    if isinstance(size, bool) or not isinstance(size, int) or size <= 0 or size > config.max_apk_bytes:
        raise ReleaseError("invalid apk size")
    if not isinstance(sha256, str) or SHA256.fullmatch(sha256) is None:
        raise ReleaseError("invalid apk sha256")
    if not isinstance(signing_cert, str) or SHA256.fullmatch(signing_cert) is None:
        raise ReleaseError("invalid signing certificate sha256")
    if not isinstance(published_at, str) or PUBLISHED_AT.fullmatch(published_at) is None:
        raise ReleaseError("invalid published time")
    apk_path = config.release_dir / apk_name
    apk_info = os.lstat(apk_path)
    if not stat.S_ISREG(apk_info.st_mode) or stat.S_ISLNK(apk_info.st_mode) or apk_info.st_size != size:
        raise ReleaseError("apk file does not match manifest")
    # 通道一致性：必须能证明 APK 恰好只有该通道 ABI（拒绝坏包、无原生库、双 ABI 巨包和错通道）。
    abis = _apk_abis(apk_path)
    if abis != frozenset({abi}):
        raise ReleaseError("apk abi does not match channel")
    return manifest, apk_path


def _storage_entries(path: Path) -> list[tuple[Path, os.stat_result]]:
    entries = []
    for entry in path.iterdir():
        try:
            info = os.lstat(entry)
        except FileNotFoundError:
            continue
        if entry.suffix == ".json" and stat.S_ISREG(info.st_mode):
            entries.append((entry, info))
    return entries


def _storage_usage(path: Path) -> tuple[int, int]:
    entries = _storage_entries(path)
    return len(entries), sum(info.st_size for _, info in entries)


def _check_directory(path: Path) -> None:
    if not stat.S_ISDIR(os.lstat(path).st_mode):
        raise OSError(errno.ELOOP, "unsafe storage directory")


def _archive_index(path: Path) -> dict[str, list[Path]]:
    """只在启动时索引一层归档中的 UUID 报告，不读取归档元数据。"""
    index: dict[str, list[Path]] = {}
    for archive in path.iterdir():
        if not archive.name.startswith("archive-"):
            continue
        _check_directory(archive)
        for entry in archive.iterdir():
            if REPORT_NAME.fullmatch(entry.name):
                if not stat.S_ISREG(os.lstat(entry).st_mode):
                    raise OSError(errno.ELOOP, "unsafe archived report")
                index.setdefault(entry.name, []).append(entry)
    return index


def _matches_report(path: Path, canonical: bytes) -> bool:
    if not stat.S_ISREG(os.lstat(path).st_mode):
        return False
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            return False
        return source.read(len(canonical) + 1) == canonical


def _sync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _archive_reports(
    storage: Path,
    entries: list[tuple[Path, os.stat_result]],
    index: dict[str, list[Path]],
) -> None:
    # 一次移动整批活跃报告；只有容量事件才 hash。归档不释放磁盘空间。
    started = time.monotonic()
    archive = Path(tempfile.mkdtemp(prefix="archive-", dir=storage))
    _check_directory(archive)
    manifest = []
    for source, info in entries:
        descriptor = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as report_file:
            actual = os.fstat(report_file.fileno())
            if not stat.S_ISREG(actual.st_mode) or actual.st_size != info.st_size:
                raise OSError(errno.EIO, "report changed during archive")
            digest = hashlib.file_digest(report_file, "sha256").hexdigest()
        manifest.append({"name": source.name, "size": info.st_size, "sha256": digest})
    # 清单先落盘；中断时按清单检查活跃区和归档区，不删除或覆盖任一份证据。
    with (archive / "manifest.json").open("xb") as manifest_file:
        os.fchmod(manifest_file.fileno(), 0o600)
        manifest_file.write(json.dumps({"reports": manifest}, separators=(",", ":")).encode("utf-8"))
        manifest_file.flush()
        os.fsync(manifest_file.fileno())
    _sync_directory(archive)
    _sync_directory(storage)
    try:
        for source, _info in entries:
            _check_directory(archive)
            destination = archive / source.name
            if os.path.lexists(destination):
                raise OSError(errno.EEXIST, "archive destination exists")
            os.rename(source, destination)
            # 每个成功 rename 后立即更新；后续移动失败也不能丢失重传幂等记录。
            if REPORT_NAME.fullmatch(source.name):
                index.setdefault(source.name, []).append(destination)
    finally:
        # 部分移动失败也尝试持久化两端，任何同步失败都保留证据并拒收。
        try:
            _sync_directory(archive)
        finally:
            _sync_directory(storage)
    LOGGER.info(
        "reports_archived count=%d bytes=%d duration_ms=%d",
        len(entries), sum(info.st_size for _, info in entries), int((time.monotonic() - started) * 1000),
    )


def _failure_path(error: SchemaError) -> str:
    """从 SchemaError 中只提取结构化失败路径，无法安全提取时返回 unknown。

    SchemaError 文本形如 ``"<path>: <message>"``。path 由 schema 内部拼接，
    但可能混入调用方可控的键名，甚至完整敏感文本，因此候选路径必须整体匹配
    严格白名单结构（仅限 ``body``/``report`` 前缀、小写开头的 lowerCamelCase
    标识符段与数字下标），否则一律降级为 ``unknown``，绝不回传异常原文或字段值。
    """
    try:
        message = str(error)
    except Exception:
        return "unknown"
    if not isinstance(message, str):
        return "unknown"
    candidate = message.split(": ", 1)[0]
    if not candidate or len(candidate) > MAX_FAILURE_PATH_CHARS:
        return "unknown"
    if FAILURE_PATH.fullmatch(candidate) is None:
        return "unknown"
    return candidate


class _DeadlineReader(io.RawIOBase):
    """接收器独立打包；每次底层读前收紧 headers/body 共用总期限。"""

    def __init__(self, raw, sock, deadline, timeout):
        self.raw = raw
        self.sock = sock
        self.deadline = deadline
        self.timeout = timeout

    def readable(self):
        return True

    def readinto(self, buffer):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('request read deadline exceeded')
        self.sock.settimeout(min(self.timeout, remaining))
        return self.raw.readinto(buffer)

    def close(self):
        try:
            self.raw.close()
        finally:
            super().close()


class _BoundedHTTPServer(ThreadingHTTPServer):
    max_requests = 32
    request_timeout = 15.0

    def __init__(self, *args, **kwargs):
        self._request_slots = threading.BoundedSemaphore(self.max_requests)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._request_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


def create_server(host: str, port: int, config: ReceiverConfig) -> ThreadingHTTPServer:
    """创建未启动的服务，便于测试用临时目录和临时端口运行。"""
    _prepare_storage(config.storage_dir)
    if config.release_dir is not None:
        _prepare_release_dir(config.release_dir)
        if not config.release_token:
            raise ValueError("启用发布目录时必须配置独立更新令牌")
    lock = threading.Lock()
    archived_reports = _archive_index(config.storage_dir)

    class DiagnosticsHandler(BaseHTTPRequestHandler):
        server_version = "DiagnosticsReceiver"
        sys_version = ""

        def setup(self):
            super().setup()
            deadline = time.monotonic() + self.server.request_timeout
            self.rfile.close()
            self.rfile = io.BufferedReader(_DeadlineReader(
                self.connection.makefile('rb', 0), self.connection,
                deadline, self.server.request_timeout))

        def log_message(self, _format: str, *_args: object) -> None:
            # 请求内容和 Authorization 均可能含敏感信息，因此不记录访问日志。
            return

        def _send_json(self, status: HTTPStatus, body: dict[str, object]) -> None:
            encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)

        def _send_ok(self) -> None:
            encoded = b"ok\n"
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)

        def _authorized(self, token: str | None) -> bool:
            if not token:
                return False
            authorization = self.headers.get("Authorization")
            expected = f"Bearer {token}"
            return authorization is not None and hmac.compare_digest(authorization.encode("utf-8"), expected.encode("utf-8"))

        def _send_release_manifest(self, abi: str) -> None:
            try:
                manifest, _ = _load_release(config, abi)
            except FileNotFoundError:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "release_not_configured"})
                return
            except (OSError, ReleaseError):
                self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "release_unavailable"})
                return
            self._send_json(HTTPStatus.OK, manifest)

        def _send_apk(self, requested_name: str, abi: str) -> None:
            try:
                manifest, apk_path = _load_release(config, abi)
            except FileNotFoundError:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "release_not_configured"})
                return
            except (OSError, ReleaseError):
                self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "release_unavailable"})
                return
            if requested_name != manifest["apkPath"]:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            size = int(manifest["size"])
            start, end = 0, size - 1
            range_header = self.headers.get("Range")
            status = HTTPStatus.OK
            if range_header is not None:
                match = SINGLE_RANGE.fullmatch(range_header)
                if match is None:
                    self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    return
                start = int(match.group(1))
                end = int(match.group(2)) if match.group(2) else size - 1
                if start >= size or end < start or end >= size:
                    self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    return
                status = HTTPStatus.PARTIAL_CONTENT
            length = end - start + 1
            self.send_response(status)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "no-store")
            if status == HTTPStatus.PARTIAL_CONTENT:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            try:
                with apk_path.open("rb") as apk:
                    apk.seek(start)
                    remaining = length
                    while remaining:
                        chunk = apk.read(min(64 * 1024, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                return

        def _requested_abi(self) -> str | None:
            """解析 X-KanTV-ABI：缺失=legacy arm64；未知值返回 None（调用方回 400）。"""

            header = self.headers.get(ABI_HEADER)
            if header is None:
                return LEGACY_ABI
            header = header.strip()
            return header if header in SUPPORTED_ABIS else None

        def do_GET(self) -> None:
            if self.path == "/health":
                self._send_ok()
                return
            prefix = "/v1/releases/apk/"
            if self.path == "/v1/releases/latest" or self.path.startswith(prefix):
                if not self._authorized(config.release_token):
                    self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                abi = self._requested_abi()
                if abi is None:
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "unsupported_abi"})
                    return
                if self.path == "/v1/releases/latest":
                    self._send_release_manifest(abi)
                else:
                    self._send_apk(self.path[len(prefix):], abi)
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

        def do_POST(self) -> None:
            if self.path != "/v1/reports":
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            if not self._authorized(config.token):
                self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            if self.headers.get("Transfer-Encoding"):
                self._send_json(HTTPStatus.LENGTH_REQUIRED, {"error": "chunked_not_allowed"})
                return
            content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if content_type != "application/json":
                self._send_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "application_json_required"})
                return
            lengths = self.headers.get_all("Content-Length") or []
            raw_length = lengths[0] if len(lengths) == 1 else None
            if raw_length is None or len(raw_length) > 10 or not raw_length.isascii() or not raw_length.isdecimal():
                self._send_json(HTTPStatus.LENGTH_REQUIRED, {"error": "valid_content_length_required"})
                return
            length = int(raw_length)
            if length > MAX_REQUEST_BYTES:
                self._send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "request_too_large"})
                return
            try:
                body = self.rfile.read(length)
            except TimeoutError:
                self.close_connection = True
                self._send_json(HTTPStatus.REQUEST_TIMEOUT, {"error": "request_timeout"})
                return
            if len(body) != length:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "incomplete_body"})
                return
            try:
                report = validate_report(body)
            except SchemaError as error:
                # 只记录经白名单清洗的结构化失败路径，绝不记录异常原文、字段值、请求体或鉴权头。
                LOGGER.warning("invalid_report path=%s", _failure_path(error))
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_report"})
                return
            destination = config.storage_dir / f"{report.report_id}.json"
            with lock:
                try:
                    _check_directory(config.storage_dir)
                    existing = list(archived_reports.get(destination.name, []))
                    if os.path.lexists(destination):
                        existing.append(destination)
                    if existing:
                        for previous in existing:
                            _check_directory(previous.parent)
                        if all(_matches_report(previous, report.canonical_bytes) for previous in existing):
                            # 前一请求可能 rename 已完成、目录同步却失败；成功重传不能跳过持久化。
                            for directory in {config.storage_dir, *(previous.parent for previous in existing)}:
                                _sync_directory(directory)
                            self._send_json(HTTPStatus.OK, {"reportId": report.report_id, "stored": False})
                        else:
                            self._send_json(HTTPStatus.CONFLICT, {"error": "report_id_conflict"})
                        return
                    if config.max_reports < 1 or len(report.canonical_bytes) > config.max_storage_bytes:
                        LOGGER.warning("report_storage_limit_reached")
                        self._send_json(HTTPStatus.INSUFFICIENT_STORAGE, {"error": "storage_limit_reached"})
                        return
                    entries = _storage_entries(config.storage_dir)
                    usage = sum(info.st_size for _, info in entries)
                    if len(entries) >= config.max_reports or usage + len(report.canonical_bytes) > config.max_storage_bytes:
                        _archive_reports(config.storage_dir, entries, archived_reports)
                    descriptor, temporary_name = tempfile.mkstemp(prefix=".incoming-", suffix=".tmp", dir=config.storage_dir)
                    try:
                        with os.fdopen(descriptor, "wb") as temporary:
                            os.fchmod(temporary.fileno(), 0o600)
                            temporary.write(report.canonical_bytes)
                            temporary.flush()
                            os.fsync(temporary.fileno())
                        os.replace(temporary_name, destination)
                        _sync_directory(config.storage_dir)
                    finally:
                        if os.path.exists(temporary_name):
                            os.unlink(temporary_name)
                except OSError as error:
                    LOGGER.warning("report_storage_failed errno=%s", error.errno)
                    self._send_json(HTTPStatus.INSUFFICIENT_STORAGE, {"error": "storage_unavailable"})
                    return
            self._send_json(HTTPStatus.CREATED, {"reportId": report.report_id, "stored": True})

        def do_PUT(self) -> None:
            self._send_json(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "method_not_allowed"})

        def do_DELETE(self) -> None:
            self._send_json(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "method_not_allowed"})

        def do_HEAD(self) -> None:
            self._send_json(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "method_not_allowed"})

    return _BoundedHTTPServer((host, port), DiagnosticsHandler)


def main() -> None:
    parser = argparse.ArgumentParser(description="KANTV 诊断报告接收器")
    parser.add_argument("--host", default=os.environ.get("DIAGNOSTICS_RECEIVER_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("DIAGNOSTICS_RECEIVER_PORT", "8091")))
    parser.add_argument("--storage-dir", type=Path, default=Path(os.environ.get("DIAGNOSTICS_RECEIVER_STORAGE_DIR", "/data/reports")))
    parser.add_argument("--release-dir", type=Path, default=Path(os.environ["DIAGNOSTICS_RECEIVER_RELEASE_DIR"]) if os.environ.get("DIAGNOSTICS_RECEIVER_RELEASE_DIR") else None)
    options = parser.parse_args()
    config = ReceiverConfig(
        token=load_token(),
        storage_dir=options.storage_dir,
        release_dir=options.release_dir,
        release_token=load_release_token(),
    )
    server = create_server(options.host, options.port, config)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.info("诊断接收器已启动于 %s:%d", options.host, options.port)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
