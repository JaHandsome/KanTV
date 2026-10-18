package com.kantv.app.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 服务端下发的一条别名（契约 §3 响应体的 `aliases[]`：`name -> channel_id`）。 */
data class ChannelAlias(val name: String, val channelId: String)

/** 服务端下发的一个频道身份（契约 §3 响应体的 `channels[].channel_id` / `display`）。 */
data class ChannelIdentityRecord(val channelId: String, val display: String? = null)

/**
 * 只读别名索引，**只能**由服务端下发的 `aliases` 与 `channels` 构建。
 *
 * 语义（契约 §1.1）：
 * - 别名名与 channel_id 必须一一对应；同名指向多个 channel_id 时视为歧义，该名返回 null
 *   （宁可归因失败，也不误归因）。
 * - `display` 同理：多个频道共用同一显示名时视为歧义。
 * - `channel_id` 自身永远映射到自己。
 *
 * 本类不做任何网络或存储 I/O，便于纯单测。
 */
class ChannelAliasTable private constructor(
    val aliases: List<ChannelAlias>,
    val channels: List<ChannelIdentityRecord>,
    private val aliasIndex: Map<String, String>,
    private val identityIndex: Map<String, String>,
) {
    val isEmpty: Boolean get() = aliasIndex.isEmpty() && identityIndex.isEmpty()

    val aliasCount: Int get() = aliasIndex.size

    val channelCount: Int get() = channels.size

    fun channelIdForAlias(name: String): String? = aliasIndex[name]

    fun channelIdForIdentity(name: String): String? = identityIndex[name]

    companion object {
        val EMPTY = ChannelAliasTable(emptyList(), emptyList(), emptyMap(), emptyMap())

        fun from(
            aliases: List<ChannelAlias>,
            channels: List<ChannelIdentityRecord>,
        ): ChannelAliasTable {
            val aliasIndex = unambiguousIndex(aliases.map { it.name to it.channelId })
            val identityIndex = HashMap<String, String>()
            channels.forEach { record ->
                if (record.channelId.isNotEmpty()) {
                    identityIndex[record.channelId] = record.channelId
                }
            }
            unambiguousIndex(
                channels.mapNotNull { record ->
                    record.display?.takeIf { it.isNotEmpty() }?.let { it to record.channelId }
                },
            ).forEach { (display, channelId) -> identityIndex[display] = channelId }
            return ChannelAliasTable(aliases, channels, aliasIndex, identityIndex)
        }

        /** 出现冲突（同名不同 id）时丢弃该键，返回 null 而不是任选一个。 */
        private fun unambiguousIndex(pairs: List<Pair<String, String>>): Map<String, String> =
            pairs.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.distinct().size == 1 }
                .mapValues { it.value.first() }
    }
}

/** 别名表的持久化端口；只保存服务端下发的原始条目，客户端不生成内容。 */
interface ChannelAliasPersistence {
    fun read(): String?
    fun write(value: String)
}

class InMemoryChannelAliasPersistence(private var value: String? = null) : ChannelAliasPersistence {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}

class SharedPreferencesChannelAliasPersistence(context: Context) : ChannelAliasPersistence {
    private val prefs = context.applicationContext.getSharedPreferences("hub_aliases", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "aliases_payload"
    }
}

/**
 * 别名表缓存：内存 + 持久化。写入只发生在服务端响应成功解析之后，
 * 因此本地不会出现「客户端自造」的别名表。
 */
class ChannelAliasCache(
    private val persistence: ChannelAliasPersistence = InMemoryChannelAliasPersistence(),
) {
    @Volatile private var cached: ChannelAliasTable? = null

    fun current(): ChannelAliasTable {
        cached?.let { return it }
        val decoded = ChannelAliasCodec.decode(persistence.read())
        cached = decoded
        return decoded
    }

    fun replace(table: ChannelAliasTable) {
        cached = table
        persistence.write(ChannelAliasCodec.encode(table))
    }
}

/** 进程内共享的别名表缓存；供 MainActivity 与刷新 Worker 读取同一份服务端别名。 */
object ChannelAliasStore {
    @Volatile private var shared: ChannelAliasCache? = null

    fun of(context: Context): ChannelAliasCache = shared ?: synchronized(this) {
        shared ?: ChannelAliasCache(SharedPreferencesChannelAliasPersistence(context)).also { shared = it }
    }
}

/** 别名表的 JSON 编解码；结构与服务端 payload 片段一致，便于直接落盘。 */
internal object ChannelAliasCodec {
    fun encode(table: ChannelAliasTable): String = JSONObject()
        .put(
            "aliases",
            JSONArray().also { array ->
                table.aliases.forEach { alias ->
                    array.put(JSONObject().put("name", alias.name).put("channel_id", alias.channelId))
                }
            },
        )
        .put(
            "channels",
            JSONArray().also { array ->
                table.channels.forEach { record ->
                    array.put(
                        JSONObject().put("channel_id", record.channelId).apply {
                            record.display?.let { put("display", it) }
                        },
                    )
                }
            },
        )
        .toString()

    fun decode(payload: String?): ChannelAliasTable {
        if (payload.isNullOrBlank()) return ChannelAliasTable.EMPTY
        return try {
            val root = JSONObject(payload)
            val aliases = root.optJSONArray("aliases")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = row.optString("name")
                    val channelId = row.optString("channel_id")
                    if (name.isEmpty() || channelId.isEmpty()) null else ChannelAlias(name, channelId)
                }
            }.orEmpty()
            val channels = root.optJSONArray("channels")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val row = array.optJSONObject(index) ?: return@mapNotNull null
                    val channelId = row.optString("channel_id")
                    if (channelId.isEmpty()) null
                    else ChannelIdentityRecord(channelId, row.optString("display").takeIf { it.isNotEmpty() })
                }
            }.orEmpty()
            ChannelAliasTable.from(aliases, channels)
        } catch (_: Exception) {
            ChannelAliasTable.EMPTY
        }
    }
}
