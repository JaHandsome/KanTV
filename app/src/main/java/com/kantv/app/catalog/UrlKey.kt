package com.kantv.app.catalog

import com.kantv.app.util.Hashing

/**
 * 线路身份 url_key —— HUB CONTRACT v1.1 §2 的客户端实现。
 *
 * ```
 * url_key = sha1( strip_signed_params( normalize_url( url ), signed_params ) )   # 小写十六进制，40 字符
 * ```
 *
 * 重要纪律（v1.1 §2.5）：
 * - **枢纽链路上不得自行计算 url_key**：`routes[].url_key` 由服务端保证非空，客户端只消费
 *   （见 [HubSubscriptionParser]）。本对象只用于**非枢纽来源**（例如用户自建的 M3U）。
 * - **不得对 query 排序**（§2.4 明确撤回 v1 的排序要求）：`normalize_url` 与服务端
 *   `url_hash` 唯一索引键共用，排序会让同一物理源被重复入库。
 *
 * 与 v1 的差别（本轮修复）：签名参数名单改为**服务端下发**（`signed_params`，两级：全局 + host 作用域）、
 * 参数名改为**精确匹配**（不再是后缀匹配）、authority **整体小写**（含 userinfo 与 port）、
 * 并补上「零命中时逐字节原样返回」这一关键不变量。
 */
object UrlKey {
    /** §2.1 第 4 步的形状：`^(https?)://([^/]+)(.*)$`（忽略大小写；`[^/]+` 要求至少一个字符）。 */
    private val HTTP_URL = Regex("^(https?)://([^/]+)(.*)$", RegexOption.IGNORE_CASE)

    /**
     * §2.2 取 host 用的正则：**区分大小写**，作用在**原始 url** 上。
     * 匹配失败时按 `url[:30]` 处理（镜像服务端行为）。
     */
    private val ORIGINAL_HOST = Regex("https?://([^/]+)")

    /** 契约 §2 的完整公式。`signedParams` 为 null / 空表示名单不可用。 */
    fun urlKey(url: String, signedParams: HubSignedParams?): String =
        sha1Hex(stripSignedParams(url, signedParams))

    /** 小写十六进制 SHA-1，固定 40 字符。 */
    fun sha1Hex(value: String): String = Hashing.sha1Hex(value.toByteArray(Charsets.UTF_8))

    /**
     * §2.1 `normalize_url`：
     * 1. `strip()`；2. 空 → 空串；3. 截断到第一个 `#` 之前；
     * 4. 命中 `^(https?)://([^/]+)(.*)$` → `scheme小写 + "://" + 第2组整体小写 + 第3组原样`；
     * 5. 否则返回第 3 步结果原样。
     */
    fun normalizeUrl(url: String): String {
        val stripped = url.trim()
        if (stripped.isEmpty()) return ""
        val withoutFragment = stripped.substringBefore('#')
        val match = HTTP_URL.find(withoutFragment) ?: return withoutFragment
        return match.groupValues[1].lowercase() +
            "://" +
            match.groupValues[2].lowercase() +
            match.groupValues[3]
    }

    /**
     * §2.2 `signed_params` 解析：全局 ∪ host 作用域，结果去重后的**小写参数名集合**。
     *
     * host 取值：从**原始 url** 用区分大小写的 `https?://([^/]+)` 取第 1 组 → 小写 →
     * 按第一个 `:` 截断（去端口）；匹配失败时取 `url[:30]`。
     * 生效条件：`host == k` 或 `host.endswith("." + k)`。
     */
    fun resolveSignedParams(url: String, signedParams: HubSignedParams?): Set<String> {
        if (signedParams == null) return emptySet()
        val host = ORIGINAL_HOST.find(url)?.groupValues?.get(1)
            ?.lowercase()
            ?.substringBefore(':')
            ?: url.take(30)
        val names = LinkedHashSet<String>()
        signedParams.global.forEach { names += it.lowercase() }
        signedParams.byHost.forEach { (scope, values) ->
            if (host == scope || host.endsWith(".$scope")) {
                values.forEach { names += it.lowercase() }
            }
        }
        return names
    }

    /** 参数名是否在名单内（§2.3 第 5 步：**小写后精确命中**）。 */
    fun isSignedParam(name: String, names: Set<String>): Boolean = name.lowercase() in names

    /**
     * §2.3 `strip_signed_params`。
     *
     * 关键不变量：**`hit == false` 时返回 `u` 本身**（逐字节，含 `?` 与其中的空参数段）。
     * 只有 `hit == true` 且 `kept` 为空才返回不带 `?` 的 `base`。
     */
    fun stripSignedParams(url: String, signedParams: HubSignedParams?): String {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty() || !normalized.contains('?')) return normalized
        val separator = normalized.indexOf('?')
        val base = normalized.substring(0, separator)
        val query = normalized.substring(separator + 1)
        val loweredBase = base.lowercase()
        if (!loweredBase.startsWith("http://") && !loweredBase.startsWith("https://")) return normalized

        val names = resolveSignedParams(url, signedParams)
        if (names.isEmpty()) return normalized

        var hit = false
        val kept = mutableListOf<String>()
        query.split('&').forEach { segment ->
            // 空参数段：丢弃；但若最终 hit == false，会连同空段一起原样返回（见下面的不变量）。
            if (segment.isEmpty()) return@forEach
            val name = segment.substringBefore('=')
            if (isSignedParam(name, names)) {
                hit = true
                return@forEach
            }
            kept += segment
        }
        if (!hit) return normalized
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }
}
