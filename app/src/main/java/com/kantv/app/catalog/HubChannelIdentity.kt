package com.kantv.app.catalog

import com.kantv.app.data.StableChannelId

/**
 * 枢纽频道的本地主键与身份键。
 *
 * 契约 §1：客户端内部一律以服务端 `channel_id` 为主键，**禁止以显示名作为主键**。
 * 这里把这条规则收敛到唯一一处，避免目录写入与播放路由查询各算一套。
 *
 * - [internalId]：Room 主键。由 channel_id 单向派生，稳定且不含任何 URL/凭据。
 * - [identityKey]：合并身份键。使用 `hub:<channel_id>` 前缀，与既有 M3U 频道身份空间隔离。
 */
object HubChannelIdentity {
    const val PREFIX = "hub:"

    fun internalId(channelId: String): String = StableChannelId.create(channelId, null, "")

    fun identityKey(channelId: String): String = PREFIX + channelId

    /** 从身份键取回服务端 channel_id；非枢纽频道返回 null。 */
    fun serverChannelId(identityKey: String?): String? =
        identityKey?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }

    /**
     * 合并判定的权威身份判据：只有服务端枢纽身份算权威。
     * 本地按名字推导的 `known:` / `id:` 身份不算权威，避免把两条同名但 tvg-id 不同的源视为同一频道。
     */
    fun isAuthoritative(identityKey: String?): Boolean = serverChannelId(identityKey) != null
}
