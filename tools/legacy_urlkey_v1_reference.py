#!/usr/bin/env python3
"""v1 url_key 算法模拟器：用于「修前/修后对照」的可复现度量。

只用于对照与取证，**不是**生产实现。它逐字复现 HUB CONTRACT v1 §2 的写法：

- `normalize_url`：小写 scheme 与 host（**authority 未整体小写**：userinfo/port 原样），
  query 参数**按 key 字典序排序**后重新拼接，丢弃 fragment。
- `strip_signed_params`：参数名**后缀匹配**（非子串），保留 `key` / `authid`，
  且 `kept` 为空时**无条件去掉 `?`**（丢掉尾随 `?` 与空参数段）。

用法：

    python3 tools/legacy_urlkey_v1_reference.py <vectors.json>

输出：总条数、与 v1 算法不一致的条数（即 v1.1 修复所覆盖的分歧面）。
"""

import hashlib
import json
import sys
from typing import List

V1_SUFFIXES = [
    "sig", "sign", "signature", "token", "expire", "expires",
    "ts", "timestamp", "wssecret", "wstime",
]
V1_PRESERVED = {"key", "authid"}


def v1_is_signed(name: str) -> bool:
    lowered = name.lower()
    if lowered in V1_PRESERVED:
        return False
    return any(lowered.endswith(suffix) for suffix in V1_SUFFIXES)


def v1_normalize(url: str) -> str:
    without_fragment = url.split("#", 1)[0]
    head, _, raw_query = without_fragment.partition("?")
    has_query = "?" in without_fragment
    # v1：只小写 host，port / userinfo 原样
    marker = "://"
    if marker in head:
        scheme, rest = head.split(marker, 1)
        if "/" in rest:
            authority, path = rest.split("/", 1)
            path = "/" + path
        else:
            authority, path = rest, ""
        at = authority.rfind("@")
        userinfo = "" if at < 0 else authority[: at + 1]
        host_port = authority if at < 0 else authority[at + 1 :]
        colon = host_port.rfind(":")
        if colon >= 0 and host_port[colon + 1 :].isdigit() and colon + 1 < len(host_port):
            host = host_port[:colon]
        else:
            host = host_port
        head = f"{scheme.lower()}{marker}{userinfo}{host.lower()}{host_port[len(host):]}{path}"
    if not has_query:
        return head
    pairs = [pair for pair in raw_query.split("&") if pair]
    pairs.sort(key=lambda pair: pair.split("=", 1)[0] if "=" in pair else pair)
    return head + "?" + "&".join(pairs)


def v1_strip(url: str) -> str:
    without_fragment = url.split("#", 1)[0]
    if "?" not in without_fragment:
        return without_fragment
    head, _, raw_query = without_fragment.partition("?")
    kept: List[str] = []
    for pair in raw_query.split("&"):
        if not pair:
            continue
        name = pair.split("=", 1)[0]
        if v1_is_signed(name):
            continue
        kept.append(pair)
    # v1 缺陷：kept 为空时无条件返回不带 '?' 的 head
    return head if not kept else head + "?" + "&".join(kept)


def v1_url_key(url: str) -> str:
    return hashlib.sha1(v1_strip(v1_normalize(url)).encode("utf-8")).hexdigest()


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: legacy_urlkey_v1_reference.py <vectors.json>", file=sys.stderr)
        return 2
    vectors = json.load(open(sys.argv[1], encoding="utf-8"))
    mismatched = [(row["url"], row["url_key"], v1_url_key(row["url"])) for row in vectors
                  if v1_url_key(row["url"]) != row["url_key"]]
    print(f"vectors={len(vectors)} v1_mismatched={len(mismatched)} "
          f"({len(mismatched) / max(1, len(vectors)) * 100:.1f}%)")
    for url, expected, actual in mismatched[:12]:
        print(f"  URL      {url}")
        print(f"  v1.1(srv){expected}")
        print(f"  v1       {actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
