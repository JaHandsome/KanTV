#!/usr/bin/env python3
"""按 HUB CONTRACT v1.1 §2 生成 url_key 测试向量（独立语言参考实现）。

用途
----
v1.1 §2.5 要求服务端提供 `GET /api/hub/v1/urlkey-vectors`（≥40 组）供客户端逐条断言。
在该端点上线前，本脚本以**独立语言、逐条对照 §2.1–2.3 条文**的方式生成同构向量，
用于锁定 v1 曾经写错、且线上数据尚未覆盖的那一类边界（签名参数路径）。

    python3 tools/generate_urlkey_vectors.py > app/src/test/resources/urlkey_vectors.json
    python3 tools/generate_urlkey_vectors.py --signed-params > app/src/test/resources/urlkey_signed_params.json

重要
----
- 生成的向量**必须脱敏**：只使用契约正文示例主机与 RFC 2606 保留域名，不得写入真实流地址。
- 它不是权威向量。权威向量到位后应改走 `-Dkantv.urlkey.vectors=<file>` 注入（见
  docs/HUB_CLIENT.md §3），仓库内文件仅作回归基线。
- 签名名单取自契约 §2.2 记录的服务端配置；真实运行时应从订阅 payload 的
  `signed_params` 读取（测试两者都支持）。
"""

import hashlib
import json
import re
import sys
from typing import Dict, List, Optional

# 契约 §2.2 记录的服务端配置（真实运行时应以 payload 的 signed_params 为准）。
SIGNED_PARAMS = {'global': ['expires', 'expire', 'expires_at', 'token', 'securitykey', 'sign', 'signature', 'msisdn'], 'by_host': {'scope-1.example.invalid': ['sig', 'ts', 'c', 'ext_start_limit', 'int_start_limit'], 'edgeware.tvb.com': ['sig', 'ts', 'c', 'ext_start_limit', 'int_start_limit']}}

HTTP_URL = re.compile(r"^(https?)://([^/]+)(.*)$", re.IGNORECASE)
ORIGINAL_HOST = re.compile(r"https?://([^/]+)")  # 区分大小写，作用于原始 url


def normalize_url(url: str) -> str:
    """§2.1：strip → 空则空串 → 截断到首个 '#' → 命中 http(s) 则 scheme 与 authority 整体小写。"""
    stripped = url.strip()
    if not stripped:
        return ""
    without_fragment = stripped.split("#", 1)[0]
    match = HTTP_URL.match(without_fragment)
    if not match:
        return without_fragment
    return match.group(1).lower() + "://" + match.group(2).lower() + match.group(3)


def resolve_signed_params(url: str, signed_params: Optional[Dict]) -> set:
    """§2.2：全局 ∪ host 作用域，结果去重后的小写参数名集合。"""
    if not signed_params:
        return set()
    match = ORIGINAL_HOST.search(url)  # 区分大小写；匹配失败取 url[:30]
    host = match.group(1).lower().split(":", 1)[0] if match else url[:30]
    names = {name.lower() for name in signed_params.get("global", [])}
    for scope, values in (signed_params.get("by_host") or {}).items():
        if host == scope or host.endswith("." + scope):
            names |= {name.lower() for name in values}
    return names


def strip_signed_params(url: str, signed_params: Optional[Dict]) -> str:
    """§2.3：零命中逐字节原样返回；命中且 kept 为空则去 '?'。"""
    normalized = normalize_url(url)
    if not normalized or "?" not in normalized:
        return normalized
    base, query = normalized.split("?", 1)
    if not (base.lower().startswith("http://") or base.lower().startswith("https://")):
        return normalized
    names = resolve_signed_params(url, signed_params)
    if not names:
        return normalized
    hit = False
    kept: List[str] = []
    for segment in query.split("&"):
        if segment == "":
            continue
        name = segment.split("=", 1)[0]
        if name.lower() in names:
            hit = True
            continue
        kept.append(segment)
    if not hit:
        return normalized
    return base if not kept else base + "?" + "&".join(kept)


def url_key(url: str, signed_params: Optional[Dict] = None) -> str:
    params = SIGNED_PARAMS if signed_params is None else signed_params
    return hashlib.sha1(strip_signed_params(url, params).encode("utf-8")).hexdigest()


# 全部为脱敏样例：主机取自契约正文示例或 RFC 2606 保留域名。
VECTORS: List[str] = [
    # --- 审查报告给出的一致性校验首例（v1 在此处即 FAILED） ---
    "http://a/b.m3u8?",
    "http://a/b.m3u8?sig=AAA&t=1",
    "http://a/b.m3u8?sig=S&token=T&expires=1&timestamp=2",
    "https://h/p.m3u8?sig=S&token=T&expires=1&timestamp=2",
    # --- 零命中：必须逐字节原样返回（v1 曾在此处丢 '?' 与空段） ---
    "http://a.invalid/b.m3u8",
    "http://a.invalid/b.m3u8?",
    "http://a.invalid/b.m3u8?&&x=1&",
    "http://a.invalid/b.m3u8?quality=1080p&std=1",
    "http://a.invalid/b.m3u8?tab=1#/live",
    # --- 精确匹配（不再后缀匹配）：v1 的后缀规则会误删这些 ---
    "http://a.invalid/b.m3u8?signal=1",
    "http://a.invalid/b.m3u8?tokenized=1",
    "http://a.invalid/b.m3u8?expire_hint=1",
    "http://a.invalid/b.m3u8?authid=0",
    "http://a.invalid/b.m3u8?key=KEYVALUE&playlive=1&authid=0",
    # --- 全局名单命中 ---
    "http://a.invalid/b.m3u8?token=T",
    "http://a.invalid/b.m3u8?sign=S",
    "http://a.invalid/b.m3u8?signature=S",
    "http://a.invalid/b.m3u8?expires=1",
    "http://a.invalid/b.m3u8?expire=1",
    "http://a.invalid/b.m3u8?expires_at=1",
    "http://a.invalid/b.m3u8?securitykey=K",
    "http://a.invalid/b.m3u8?msisdn=8613800000000",
    "http://a.invalid/b.m3u8?token=T&uid=7",
    "http://a.invalid/b.m3u8?uid=7&token=T&quality=1080p",
    "http://a.invalid/b.m3u8?TOKEN=T&uid=7",
    "http://a.invalid/b.m3u8?sig=S&uid=7",  # sig 不在全局名单 → 非 edgeware 主机保留
    "http://a.invalid/b.m3u8?ts=1&uid=7",  # ts 只在 edgeware 作用域
    # --- host 作用域：scope-1.example.invalid（含子域） ---
    "https://scope-1.example.invalid/live/ch/index.mpd?sig=S&ts=1&c=1&token=T",
    "https://scope-1.example.invalid/live/ch/index.mpd?sig=S",
    "https://scope-1.example.invalid/live/ch/index.mpd?ts=1&c=2&ext_start_limit=1&int_start_limit=2",
    "https://scope-1.example.invalid/live/ch/index.mpd?ext_start_limit=1&keep=2",
    "https://fixture.scope-1.example.invalid/live/ch/index.mpd?sig=S&ts=1",
    "https://SCOPE-1.EXAMPLE.INVALID/live/ch/index.mpd?sig=S&uid=1",
    "https://fixture-32.example.invalid/live/ch/index.mpd?sig=S&uid=1",
    "https://scope-1.example.invalid.evil.invalid/live/x.mpd?sig=S&uid=1",
    # host 正则区分大小写：大写 scheme 匹配失败 → host = url[:30]
    "HTTPS://scope-1.example.invalid/live/ch/index.mpd?sig=S&uid=1",
    # --- authority 整体小写（含 userinfo 与 port） ---
    "HTTP://User:Pass@EXAMPLE.INVALID:8080/Path/Case.M3U8?token=T",
    "http://Example.invalid:8080/x?uid=1",
    "http://[2001:DB8::1]:8080/x?ts=1",
    # --- 非 http(s) 与非 URL：原样返回 ---
    "rtsp://a.invalid/live?token=T",
    "ftp://a.invalid/x?token=T",
    "not a url at all?token=T",
    "  http://a.invalid/spaced.m3u8?  ",
    "   ",
    "",
    # --- fragment 先于 '?' 判定 ---
    "http://a.invalid/b.m3u8#frag?token=T",
    "http://a.invalid/b.m3u8?token=T#frag",
    # --- 重复/连续分隔符 ---
    "http://a.invalid/b.m3u8?token=T&&uid=1&",
    "http://a.invalid/b.m3u8?a=1&&b=2",
    "http://a.invalid/b.m3u8?a=1&a=2&token=T",
    # --- 空值 / 无值参数 ---
    "http://a.invalid/b.m3u8?token=",
    "http://a.invalid/b.m3u8?flag&token=T",
    # --- 顺序必须保留（§2.4：不得排序） ---
    "http://a.invalid/b.m3u8?z=2&a=1&token=T",
    "https://scope-1.example.invalid/x.mpd?c=3&sig=S&ts=1&int_start_limit=9",
]


def build_vectors(signed_params: Dict) -> List[Dict[str, str]]:
    return [{"url": url, "url_key": url_key(url, signed_params)} for url in VECTORS]


def main() -> int:
    arguments = sys.argv[1:]
    if arguments and arguments[0] == "--signed-params":
        json.dump(SIGNED_PARAMS, sys.stdout, indent=2, ensure_ascii=False)
        sys.stdout.write("\n")
        return 0
    json.dump(build_vectors(SIGNED_PARAMS), sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
