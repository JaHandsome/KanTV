package com.kantv.app.catalog

import androidx.room.withTransaction
import com.kantv.app.data.ImportSummary
import com.kantv.app.data.M3uPlaylistImporter
import com.kantv.app.data.ReadyPlaylistImport
import com.kantv.app.playback.PlaybackRequest
import com.kantv.app.playback.HubRouteSelector
import com.kantv.app.playback.OfficialWebChannelCatalog
import com.kantv.app.playback.SupportedWebLivePage
import com.kantv.app.util.Hashing
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class CatalogRepository(
    private val database: CatalogDatabase,
    private val secretBox: SecretBox,
    private val importer: M3uPlaylistImporter = M3uPlaylistImporter(),
    /** 已应用的枢纽订阅视图；用以为枢纽频道补上 route / url_key / ClearKey 元数据。 */
    private val hubRoutes: HubRouteRegistry? = null,
    /** 服务端下发的别名表（契约 §1.1）；用于把第三方导入归因到服务端 channel_id。 */
    private val aliasTableProvider: (() -> ChannelAliasTable?)? = null,
) : HubCatalogSink {
    private val dao = database.catalogDao()

    suspend fun hasChannels(): Boolean = dao.hasChannels()

    fun observeChannels(): Flow<List<ChannelEntity>> = dao.observeChannels()

    /**
     * Keep official webpage channels available without replacing the user's M3U channels.
     *
     * The identity-safe variant dedupe always runs afterwards, so pre-existing duplicates converge on
     * every entry point and every source and the earliest position survive.
     */
    suspend fun ensureOfficialWebChannels(): Int {
        val added = installOfficialWebChannels()
        dedupeNameVariants()
        return added
    }

    private suspend fun installOfficialWebChannels(): Int = database.withTransaction {
        val current = dao.allChannels().toMutableList()
        val removed = current.filter(::isSuppressed).mapTo(mutableListOf()) { it.id }
        current.removeAll(::isSuppressed)
        val candidateUrls = OfficialWebChannelCatalog.pages.mapTo(hashSetOf()) { it.url }.apply {
            addAll(OfficialWebChannelCatalog.retiredOfficialUrls)
        }
        val installedUrls = OfficialWebChannelCatalog.installedPages.mapTo(hashSetOf()) { it.url }
        val idsBeforeGate = current.mapTo(linkedSetOf()) { it.id }
        val retained = current.mapNotNull { entity ->
            val sources = retainInstallableOfficialSources(
                decodeSources(entity.encryptedRequest), candidateUrls, installedUrls,
            )
            entity.copy(encryptedRequest = encodeSources(sources)).takeIf { sources.isNotEmpty() }
        }
        current.clear()
        current += retained
        removed += idsBeforeGate - current.mapTo(linkedSetOf()) { it.id }
        var nextPosition = (current.maxOfOrNull { it.position } ?: -1) + 1
        var added = 0
        val cursor = MergeCursor(current)
        OfficialWebChannelCatalog.installedPages.forEach { page ->
            val id = stableId(page)
            val prototype = webPageEntity(page, id, nextPosition)
            val existed = cursor.hasMergeTarget(prototype)
            val merged = mergeInto(cursor, prototype, listOf(webSource(page)), removed)
            if (!existed) {
                added++
                nextPosition++
            }
            cursor.replaceWith(merged)
        }
        if (removed.isNotEmpty()) dao.deleteChannelsByIds(removed.distinct())
        dao.insertChannels(current)
        added
    }

    /** Add a stable page URL without replacing the user's existing playlist. */
    suspend fun addWebLivePage(url: String): ChannelEntity {
        val page = requireNotNull(com.kantv.app.playback.SupportedWebLivePage.from(url))
        val channelId = stableId(page)
        return database.withTransaction {
            val current = dao.allChannels().toMutableList()
            val prototype = webPageEntity(page, channelId, (current.maxOfOrNull { it.position } ?: -1) + 1)
            val removed = mutableListOf<String>()
            val cursor = MergeCursor(current)
            val entity = mergeInto(cursor, prototype, listOf(webSource(page)), removed)
            cursor.replaceWith(entity)
            if (removed.isNotEmpty()) dao.deleteChannelsByIds(removed.distinct())
            dao.insertChannels(current)
            entity
        }
    }

    /** Subscription metadata never exposes its address; only this repository decrypts it for refresh. */
    suspend fun subscriptions(): List<PlaylistSubscriptionEntity> = dao.subscriptions()

    fun observeSubscriptions(): Flow<List<PlaylistSubscriptionEntity>> = dao.observeSubscriptions()

    suspend fun refreshUrl(id: String): String? = dao.subscription(id)?.let { secretBox.decrypt(it.encryptedUrl) }

    suspend fun recordSubscriptionAttempt(id: String, nowMs: Long = System.currentTimeMillis()) {
        database.withTransaction {
            dao.subscription(id)?.let { previous ->
                if (nowMs >= previous.lastAttemptAtMs) {
                    dao.saveSubscription(previous.copy(lastAttemptAtMs = nowMs, status = SUBSCRIPTION_REFRESHING))
                }
            }
        }
    }

    suspend fun recordSubscriptionFailure(
        id: String,
        status: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        database.withTransaction {
            dao.subscription(id)?.let { previous ->
                if (nowMs >= previous.lastAttemptAtMs) {
                    dao.saveSubscription(previous.copy(lastAttemptAtMs = nowMs, status = safeStatus(status)))
                }
            }
        }
    }

    /**
     * Seeds the APK-bundled playlist once, without ever recording a network subscription.
     * A historical personal playlist/subscription is intentionally treated as custom so an
     * upgrade cannot add a second, unexpected source to it.
     */
    suspend fun ensureBundledPlaylist(sourceText: String): CatalogImportResult {
        val preparation = importer.prepare(sourceText)
        val ready = preparation as? ReadyPlaylistImport
        val importableChannels = ready?.snapshot?.channels?.filterNot { channel ->
            ChannelIdentity.isSuppressed(channel.tvgId, channel.name, channel.tvgName)
        }.orEmpty()
        if (ready == null || preparation.summary.rejectedLines > 0 || importableChannels.isEmpty()) {
            return CatalogImportResult.Rejected(preparation.summary)
        }
        return database.withTransaction {
            val currentPlaylist = dao.playlist()
            val current = dao.allChannels()
            val shouldSeed = DefaultPlaylistSeedingPolicy.shouldSeed(
                savedMode = currentPlaylist?.mode,
                hasPlaylistRecord = currentPlaylist != null,
                hasSubscriptions = dao.subscriptions().isNotEmpty(),
                hasNonOfficialSources = current.any { entity ->
                    decodeSources(entity.encryptedRequest).any { !it.official }
                },
            )
            if (!shouldSeed) return@withTransaction CatalogImportResult.Preserved(preparation.summary)
            applySnapshot(
                current = current.toMutableList(),
                channels = importableChannels,
                owner = BUNDLED_DEFAULT_OWNER,
                removeOwners = emptySet(),
            )
            dao.savePlaylist(
                PlaylistEntity(
                    encryptedOrigin = secretBox.encrypt(BUNDLED_DEFAULT_ORIGIN),
                    encryptedEpgUrls = secretBox.encrypt(JSONArray(ready.snapshot.epgUrls).toString()),
                    updatedAtMs = System.currentTimeMillis(),
                    mode = DefaultPlaylistMode.BUNDLED,
                ),
            )
            CatalogImportResult.Imported(
                summary = preparation.summary,
                channelCount = dao.allChannels().size,
                sourceCount = dao.allChannels().sumOf { decodeSources(it.encryptedRequest).size },
            )
        }
    }

    /** A safe, presentation-ready value: BUNDLED, CUSTOM, or null for legacy/unknown state. */
    suspend fun defaultPlaylistMode(): String? =
        dao.playlist()?.mode?.takeIf(DefaultPlaylistMode::isSafe)

    /**
     * 应用服务端下发的枢纽订阅（契约 §3 投递通道的落库端）。
     *
     * 语义（与既有 M3U 刷新保持一致）：
     * - 只增删本来源（`hub-subscription`）与「内置兜底」来源；用户自己导入的来源不受影响。
     * - 服务端订阅一旦生效，内置 asset 清单即让位——它降级为「服务端不可达时的缓存」。
     * - 频道主键统一由 [HubChannelIdentity] 从 channel_id 派生，禁止以显示名作主键。
     * - `display` 直接采用服务端值，不再经过本地重命名。
     *
     * 注意：web-embed 线路不进入原生播放源（它由 WebView 容器承载），见 [HubRouteSelector]。
     */
    override suspend fun applyHubSubscription(
        subscription: HubSubscription,
        startedAtMs: Long,
    ): CatalogImportResult {
        val groupTitles = subscription.groups.associate { it.id to it.title }
        val importable = subscription.channels.filter { it.channelId.isNotBlank() }
        val outcome = database.withTransaction {
            val current = dao.allChannels().toMutableList()
            val idsBefore = current.mapTo(linkedSetOf()) { it.id }
            val removed = current.filter(::isSuppressed).mapTo(mutableListOf()) { it.id }
            current.removeAll(::isSuppressed)
            val ownersToRemove = setOf(HUB_SUBSCRIPTION_OWNER, BUNDLED_DEFAULT_OWNER)
            val retained = current.map { entity ->
                entity.copy(
                    encryptedRequest = encodeSources(
                        removeOwners(decodeSources(entity.encryptedRequest), ownersToRemove),
                    ),
                )
            }
            current.clear()
            current += retained
            var nextPosition = (current.maxOfOrNull { it.position } ?: -1) + 1
            var sourceCount = 0
            val cursor = MergeCursor(current)
            importable.forEach { hubChannel ->
                val incoming = hubChannel.routes
                    .filter { HubRouteSelector.supports(it.route, HubCaps.declared) }
                    .filter { it.route != HubRouteType.WEB_EMBED }
                    .map { route ->
                        CatalogSource(
                            route.url,
                            emptyMap(),
                            official = false,
                            owners = setOf(HUB_SUBSCRIPTION_OWNER),
                        )
                    }
                val prototype = ChannelEntity(
                    id = HubChannelIdentity.internalId(hubChannel.channelId),
                    position = nextPosition,
                    tvgId = hubChannel.channelId,
                    name = hubChannel.display,
                    groupTitle = hubChannel.groupId?.let { groupTitles[it] },
                    logoUrl = hubChannel.logo,
                    encryptedRequest = secretBox.encrypt("[]"),
                    identityKey = HubChannelIdentity.identityKey(hubChannel.channelId),
                )
                val existed = cursor.hasMergeTarget(prototype)
                val merged = mergeHubInto(cursor, prototype, incoming, removed)
                if (!existed) nextPosition++
                sourceCount += incoming.size
                cursor.replaceWith(merged)
            }
            // 兜底来源已被移除、且没有服务端线路的频道不再保留空壳。
            // 例外：本次订阅中的枢纽频道即使没有线路也要保留（服务端 coverage=missing），
            // 它是用户可见的频道条目，播放时由「服务端 fallback / 本地官网内嵌目录」兜底。
            val hubPlaceholderIds = importable.mapNotNull { HubChannelIdentity.internalId(it.channelId) }.toSet()
            current.removeAll { entity ->
                decodeSources(entity.encryptedRequest).isEmpty() && entity.id !in hubPlaceholderIds
            }
            val savedIds = current.mapTo(linkedSetOf()) { it.id }
            val deleteIds = (idsBefore - savedIds) + removed.filterNot(savedIds::contains)
            if (deleteIds.isNotEmpty()) dao.deleteChannelsByIds(deleteIds.distinct())
            dao.insertChannels(current)
            dedupeNameVariants()
            dao.savePlaylist(
                PlaylistEntity(
                    encryptedOrigin = secretBox.encrypt(HUB_ORIGIN),
                    encryptedEpgUrls = dao.playlist()?.encryptedEpgUrls ?: secretBox.encrypt("[]"),
                    updatedAtMs = startedAtMs,
                    // 枢纽是服务端默认来源，不是用户自建清单：保留既有 mode 语义。
                    mode = dao.playlist()?.mode,
                ),
            )
            CatalogImportResult.Imported(
                summary = ImportSummary(importable.size, 0, emptySet()),
                channelCount = current.size,
                sourceCount = sourceCount,
            )
        }
        return outcome
    }

    /**
     * 枢纽频道的合并：与 [mergeInto] 的唯一差别是**不重命名**服务端下发的 display。
     * 契约 §1 要求客户端以 channel_id 为主键、display 为服务端口径，因此这里不做本地改名。
     */
    private fun mergeHubInto(
        cursor: MergeCursor,
        prototype: ChannelEntity,
        incoming: List<CatalogSource>,
        removed: MutableList<String>,
    ): ChannelEntity {
        val matches = cursor.matchesOf(prototype)
        val primary = matches.minByOrNull { it.position }
        matches.filter { it.id != primary?.id }.forEach { removed += it.id }
        val allSources = matches.flatMap { decodeSources(it.encryptedRequest) }
        return prototype.copy(
            id = primary?.id ?: prototype.id,
            position = primary?.position ?: prototype.position,
            // 显示名与分组一律以本次服务端下发为准。
            name = prototype.name,
            groupTitle = prototype.groupTitle ?: primary?.groupTitle,
            logoUrl = prototype.logoUrl ?: primary?.logoUrl,
            encryptedRequest = encodeSources(CatalogSourceMerge.merge(allSources, incoming)),
        )
    }

    suspend fun import(
        sourceText: String,
        origin: String,
        refreshStartedAtMs: Long = System.currentTimeMillis(),
        userInitiated: Boolean = true,
    ): CatalogImportResult {
        val preparation = importer.prepare(sourceText)
        val subscriptionId = subscriptionIdFor(origin)
        val ready = preparation as? ReadyPlaylistImport
        val importableChannels = ready?.snapshot?.channels?.filterNot { channel ->
            ChannelIdentity.isSuppressed(channel.tvgId, channel.name, channel.tvgName)
        }.orEmpty()
        // A malformed, partial, empty, or wholly suppressed first import must not create a
        // subscription, replace bundled data, or change its persistent mode.
        if (ready == null || preparation.summary.rejectedLines > 0 || importableChannels.isEmpty()) {
            return database.withTransaction {
                val previous = subscriptionId?.let { dao.subscription(it) }
                // A response which began before a later refresh cannot change metadata either.
                if (previous != null && refreshStartedAtMs < previous.lastAttemptAtMs) {
                    CatalogImportResult.Preserved(preparation.summary)
                } else if (previous != null) {
                    val preserved = previous.lastSuccessAtMs != null
                    saveSubscriptionAttempt(
                        id = previous.id,
                        origin = origin,
                        previous = previous,
                        attemptedAtMs = refreshStartedAtMs,
                        status = if (preserved) SUBSCRIPTION_PRESERVED else SUBSCRIPTION_REJECTED,
                        successAtMs = previous.lastSuccessAtMs,
                        channelCount = previous.channelCount,
                    )
                    if (preserved) CatalogImportResult.Preserved(preparation.summary) else CatalogImportResult.Rejected(preparation.summary)
                } else CatalogImportResult.Rejected(preparation.summary)
            }
        }
        val groupedChannels = importableChannels.groupBy { channel ->
            attributedHubChannelId(channel)?.let { "hub:$it" }
                ?: ChannelIdentity.knownKey(channel.tvgId, channel.name, channel.tvgName)?.let { "known:$it" }
                ?: "id:${ChannelIdentity.storageKey(channel.tvgId, channel.name, channel.tvgName) ?: channel.id}"
        }

        val outcome = database.withTransaction {
            val previousSubscription = subscriptionId?.let { dao.subscription(it) }
            // A worker never creates a subscription incidentally; only an explicit successful
            // user import may do that.
            if (!userInitiated && subscriptionId != null && previousSubscription == null) {
                return@withTransaction null
            }
            if (previousSubscription != null && refreshStartedAtMs < previousSubscription.lastAttemptAtMs) {
                return@withTransaction null
            }
            val current = dao.allChannels().toMutableList()
            val previousPlaylist = dao.playlist()
            val idsBefore = current.mapTo(linkedSetOf()) { it.id }
            val removed = current.filter(::isSuppressed).mapTo(mutableListOf()) { it.id }
            current.removeAll(::isSuppressed)
            val ownersToRemove = buildSet {
                subscriptionId?.let(::add)
                if (userInitiated) add(BUNDLED_DEFAULT_OWNER)
            }
            if (ownersToRemove.isNotEmpty()) {
                val retained = current.map { entity ->
                    entity.copy(encryptedRequest = encodeSources(removeOwners(decodeSources(entity.encryptedRequest), ownersToRemove)))
                }
                current.clear()
                current += retained
            }
            var nextPosition = (current.maxOfOrNull { it.position } ?: -1) + 1
            val cursor = MergeCursor(current)
            groupedChannels.values.forEach { channels ->
                val channel = channels.first()
                val hubChannelId = attributedHubChannelId(channel)
                val identityKey = hubChannelId?.let(HubChannelIdentity::identityKey)
                    ?: ChannelIdentity.knownKey(channel.tvgId, channel.name, channel.tvgName)
                val channelId = hubChannelId?.let(HubChannelIdentity::internalId)
                    ?: ChannelIdentity.storageKey(channel.tvgId, channel.name, channel.tvgName)
                        ?.let { com.kantv.app.data.StableChannelId.create(it, null, "") }
                    ?: channel.id
                val prototype = ChannelEntity(
                    id = channelId,
                    position = nextPosition,
                    tvgId = channel.tvgId,
                    name = ChannelIdentity.displayName(channel.tvgId, channel.name, channel.tvgName),
                    groupTitle = channel.groupTitle,
                    logoUrl = channel.logoUrl,
                    encryptedRequest = secretBox.encrypt("[]"),
                    identityKey = identityKey,
                )
                val sources = channels.map {
                    CatalogSource(
                        it.request.url,
                        it.request.headers,
                        official = false,
                        owners = subscriptionId?.let(::setOf) ?: setOf(LOCAL_IMPORT_OWNER),
                    )
                }
                val existed = cursor.hasMergeTarget(prototype)
                val merged = mergeInto(cursor, prototype, sources, removed)
                if (!existed) nextPosition++
                cursor.replaceWith(merged)
            }
            // Keep now-empty shells until the incoming snapshot has merged.  Otherwise a channel
            // that remains in this subscription is recreated with a new position every refresh.
            current.removeAll { decodeSources(it.encryptedRequest).isEmpty() }
            val savedIds = current.mapTo(linkedSetOf()) { it.id }
            val deleteIds = (idsBefore - savedIds) + removed.filterNot(savedIds::contains)
            if (deleteIds.isNotEmpty()) dao.deleteChannelsByIds(deleteIds.distinct())
            dao.insertChannels(current)
            dedupeNameVariants()
            dao.savePlaylist(
                PlaylistEntity(
                    encryptedOrigin = secretBox.encrypt(origin),
                    encryptedEpgUrls = secretBox.encrypt(JSONArray(ready.snapshot.epgUrls).toString()),
                    updatedAtMs = System.currentTimeMillis(),
                    mode = if (userInitiated) DefaultPlaylistMode.CUSTOM else previousPlaylist?.mode,
                ),
            )
            if (subscriptionId != null) {
                saveSubscriptionAttempt(
                    id = subscriptionId,
                    origin = origin,
                    previous = previousSubscription,
                    attemptedAtMs = refreshStartedAtMs,
                    status = SUBSCRIPTION_OK,
                    successAtMs = refreshStartedAtMs,
                    channelCount = importableChannels.size,
                )
            }
            current.size to current.sumOf { decodeSources(it.encryptedRequest).size }
        }
        if (outcome == null) return CatalogImportResult.Preserved(preparation.summary)
        return CatalogImportResult.Imported(
            summary = preparation.summary,
            channelCount = outcome.first,
            sourceCount = outcome.second,
        )
    }

    /**
     * 按服务端别名表把导入条目归因到 channel_id（契约 §1.1）。
     * 候选顺序：tvg-id、显示名、tvg-name；别名表为空时恒为 null（行为与过去一致）。
     */
    private fun attributedHubChannelId(channel: com.kantv.app.data.PlaylistChannel): String? {
        val aliases = aliasTableProvider?.invoke() ?: return null
        if (aliases.isEmpty) return null
        return ChannelIdentity.resolveChannelId(channel.tvgId, aliases)
            ?: ChannelIdentity.resolveChannelId(channel.name, aliases)
            ?: ChannelIdentity.resolveChannelId(channel.tvgName, aliases)
    }

    private suspend fun applySnapshot(
        current: MutableList<ChannelEntity>,
        channels: List<com.kantv.app.data.PlaylistChannel>,
        owner: String,
        removeOwners: Set<String>,
    ) {
        val idsBefore = current.mapTo(linkedSetOf()) { it.id }
        val removed = current.filter(::isSuppressed).mapTo(mutableListOf()) { it.id }
        current.removeAll(::isSuppressed)
        if (removeOwners.isNotEmpty()) {
            val retained = current.map { entity ->
                entity.copy(encryptedRequest = encodeSources(removeOwners(decodeSources(entity.encryptedRequest), removeOwners)))
            }
            current.clear()
            current += retained
        }
        var nextPosition = (current.maxOfOrNull { it.position } ?: -1) + 1
        val cursor = MergeCursor(current)
        channels.groupBy { channel ->
            ChannelIdentity.knownKey(channel.tvgId, channel.name, channel.tvgName)?.let { "known:$it" }
                ?: "id:${ChannelIdentity.storageKey(channel.tvgId, channel.name, channel.tvgName) ?: channel.id}"
        }.values.forEach { grouped ->
            val channel = grouped.first()
            val prototype = ChannelEntity(
                id = ChannelIdentity.storageKey(channel.tvgId, channel.name, channel.tvgName)
                    ?.let { com.kantv.app.data.StableChannelId.create(it, null, "") } ?: channel.id,
                position = nextPosition,
                tvgId = channel.tvgId,
                name = ChannelIdentity.displayName(channel.tvgId, channel.name, channel.tvgName),
                groupTitle = channel.groupTitle,
                logoUrl = channel.logoUrl,
                encryptedRequest = secretBox.encrypt("[]"),
                identityKey = ChannelIdentity.knownKey(channel.tvgId, channel.name, channel.tvgName),
            )
            val existed = cursor.hasMergeTarget(prototype)
            val sources = grouped.map {
                CatalogSource(it.request.url, it.request.headers, official = false, owners = setOf(owner))
            }
            val merged = mergeInto(cursor, prototype, sources, removed)
            if (!existed) nextPosition++
            cursor.replaceWith(merged)
        }
        current.removeAll { decodeSources(it.encryptedRequest).isEmpty() }
        val savedIds = current.mapTo(linkedSetOf()) { it.id }
        val deleteIds = (idsBefore - savedIds) + removed.filterNot(savedIds::contains)
        if (deleteIds.isNotEmpty()) dao.deleteChannelsByIds(deleteIds.distinct())
        dao.insertChannels(current)
        dedupeNameVariants()
    }

    /** 名字变体兜底合并（别名表未覆盖的同义频道名，如 CCTV-1 / CCTV1综合）。返回是否发生了合并。 */
    suspend fun dedupeNameVariants(): Boolean = database.withTransaction {
        val channels = dao.allChannels()
        if (channels.size < 2) return@withTransaction false
        val inputs = channels.map { entity ->
            ChannelVariantInput(
                id = entity.id,
                position = entity.position,
                name = entity.name,
                tvgId = entity.tvgId,
                logoUrl = entity.logoUrl,
                groupTitle = entity.groupTitle,
                sources = decodeSources(entity.encryptedRequest),
                // A stored hub identity is authoritative; raw M3U rows carry none. The merger uses
                // this to keep same-name rows with different tvg-id or hub identity separate.
                identityKey = entity.identityKey,
            )
        }
        // Prefer the server alias table. For old catalogues whose alias cache predates a hub row,
        // bridge only a closed, locally-known identity that maps to exactly one authoritative hub
        // identity in this same catalogue. Unknown or ambiguous names stay separate.
        val aliases = aliasTableProvider?.invoke()
        val knownHubIdentities = KnownHubIdentityBridge.uniqueMappings(inputs)
        val plans = ChannelVariantMerger.plan(inputs) { input ->
            input.identityKey?.takeIf { HubChannelIdentity.isAuthoritative(it) }
                ?: (ChannelIdentity.resolveChannelId(input.tvgId, aliases)
                    ?: ChannelIdentity.resolveChannelId(input.name, aliases))
                    ?.let(HubChannelIdentity::identityKey)
                ?: ChannelIdentity.knownKey(input.tvgId, input.name)?.let(knownHubIdentities::get)
                ?: ChannelVariantMerger.defaultIdentity(input)
        }
        if (plans.isEmpty()) return@withTransaction false
        dao.deleteChannelsByIds(plans.flatMap { it.removeIds }.distinct())
        dao.insertChannels(
            plans.map { merge ->
                val kept = merge.keep
                ChannelEntity(
                    id = kept.id,
                    position = kept.position,
                    tvgId = kept.tvgId,
                    name = kept.name,
                    groupTitle = kept.groupTitle,
                    logoUrl = kept.logoUrl,
                    // Official playable sources first; the rest keeps its merged relative order.
                    encryptedRequest = encodeSources(CatalogSourceOrdering.stable(kept.sources)),
                    // The merged identity is authoritative when either side had one, so a kept row
                    // must never lose the hub identity of a removed duplicate.
                    identityKey = kept.identityKey
                        ?: channels.firstOrNull { it.id == kept.id }?.identityKey,
                )
            },
        )
        true
    }

    suspend fun playbackRequests(channelId: String): List<PlaybackRequest> {
        val channel = dao.channel(channelId) ?: return emptyList()
        val compatibility = com.kantv.app.playback.PlaybackCompatibilityPolicy.forChannel(
            channel.identityKey ?: ChannelIdentity.knownKey(channel.tvgId, channel.name),
            channel.id,
        )
        val stored = decodeSources(channel.encryptedRequest)
        // 枢纽频道的线路元数据（route / url_key / ClearKey）来自服务端订阅，
        // 不以 URL 推断 route；顺序即服务端 score 降序。
        val hubPlans = hubRoutes?.plansForInternal(channel.id).orEmpty()
        val hubRequests = hubPlans.map { plan ->
            PlaybackRequest(
                channelId = channel.id,
                uri = plan.uri,
                mimeType = plan.mimeType,
                requestHeaders = emptyMap(),
                preferredKernel = compatibility.preferredKernel,
                hubRoute = plan.route.wireName,
                // F1：只透传服务端下发的 url_key；不可归因时不构造回流记录（此处即为 null）。
                hubUrlKey = plan.urlKey,
                // 只来自枢纽元数据；普通 M3U 源保持 SourceQuality.NONE。
                quality = plan.qualityRank,
                drm = com.kantv.app.playback.ClearKeyPlaybackSupport.specFor(plan)?.let { spec ->
                    com.kantv.app.playback.DrmRequest(spec.uuid, spec.keyResponseJson)
                },
            )
        }
        // Trusted quality from hub metadata only; health-based candidate ordering happens at play
        // time in the coordinator. The server's score order is the stable tie-break.
        val orderedHubRequests = hubRequests.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<PlaybackRequest>> { it.value.quality }
                    .thenBy { it.index },
            )
            .map { it.value }
        val hubUris = hubPlans.mapTo(hashSetOf()) { it.uri }
        val remaining = stored.filterNot { it.url in hubUris }.map { source ->
        val url = source.url
        PlaybackRequest(
            channelId = channel.id,
            uri = url,
            mimeType = inferMimeType(url),
            requestHeaders = source.headers,
            preferredKernel = compatibility.preferredKernel,
        )
        }
        // 契约 §3 兜底顺序的第 ①、② 步以「最后一条请求」的形式附加：
        // - 有原生线路时，官方页面会被 PlaybackRouteClassifier 从 Native 路线中剔除，不产生影响；
        // - 没有原生线路时（coverage=missing），它成为唯一请求，直接走 WebView 容器。
        // 「原生线路全部失败」的情形由 PlaybackCoordinator 的 hubFallbackRoute 钩子接管。
        val fallbackRequest = hubRoutes?.fallbackRouteForInternal(channel.id)?.page?.url?.let { fallbackUrl ->
            PlaybackRequest(
                channelId = channel.id,
                uri = fallbackUrl,
                mimeType = null,
                requestHeaders = emptyMap(),
                preferredKernel = compatibility.preferredKernel,
            )
        }
        return CatalogSourceOrdering.officialWebFirst(
            orderedHubRequests + remaining + listOfNotNull(fallbackRequest),
        )
    }

    /** Number of selectable, de-duplicated sources; neither URLs nor headers are exposed. */
    suspend fun sourceCount(channelId: String): Int =
        dao.channel(channelId)?.let { decodeSources(it.encryptedRequest).size } ?: 0

    private fun inferMimeType(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".ts") -> "video/mp2t"
            else -> null
        }
    }

    private fun stableId(page: SupportedWebLivePage): String =
        com.kantv.app.data.StableChannelId.create(page.tvgId, null, "")

    private fun webPageEntity(page: SupportedWebLivePage, channelId: String, position: Int): ChannelEntity {
        val request = JSONObject().put("url", page.url).put("headers", JSONObject())
        return ChannelEntity(
            id = channelId,
            position = position,
            tvgId = page.tvgId,
            name = ChannelIdentity.displayName(page.tvgId, page.name),
            groupTitle = page.groupTitle,
            logoUrl = null,
            encryptedRequest = secretBox.encrypt(JSONArray().put(request).toString()),
            identityKey = ChannelIdentity.knownKey(page.tvgId, page.name),
        )
    }

    private fun webSource(page: SupportedWebLivePage) =
        CatalogSource(page.url, emptyMap(), official = true, legacyUnowned = false)

    /**
     * 一次目录重建内的合并键缓存。`matches` 的判定等价于合并键相等（见 [ChannelIdentity.mergeKeyOf]），
     * 把「每个待并入频道 × 全表扫描 × 名称归一化（含正则）」的 O(N²) 降为每实体一次求键。
     * 键按对象引用缓存：合并产出的新实体是新对象，不会命中旧键。
     */
    private class MergeCursor(val current: MutableList<ChannelEntity>) {
        private val keys = java.util.IdentityHashMap<ChannelEntity, String>()

        fun keyOf(entity: ChannelEntity): String = keys.getOrPut(entity) {
            ChannelIdentity.mergeKeyOf(
                entity.id,
                entity.identityKey ?: ChannelIdentity.knownKey(entity.tvgId, entity.name),
            )
        }

        fun hasMergeTarget(prototype: ChannelEntity): Boolean {
            val key = keyOf(prototype)
            return current.any { keyOf(it) == key }
        }

        fun matchesOf(prototype: ChannelEntity): List<ChannelEntity> {
            val key = keyOf(prototype)
            return current.filter { keyOf(it) == key }
        }

        fun replaceWith(entity: ChannelEntity) {
            val key = keyOf(entity)
            current.removeAll { keyOf(it) == key }
            current += entity
        }
    }

    private fun mergeInto(
        cursor: MergeCursor,
        prototype: ChannelEntity,
        incoming: List<CatalogSource>,
        removed: MutableList<String>,
    ): ChannelEntity {
        val matches = cursor.matchesOf(prototype)
        val primary = matches.minByOrNull { it.position }
        matches.filter { it.id != primary?.id }.forEach { removed += it.id }
        val allSources = matches.flatMap { decodeSources(it.encryptedRequest) }
        val id = primary?.id ?: prototype.id
        return prototype.copy(
            id = id,
            position = primary?.position ?: prototype.position,
            tvgId = primary?.tvgId ?: prototype.tvgId,
            name = ChannelIdentity.displayName(
                primary?.tvgId ?: prototype.tvgId,
                primary?.name ?: prototype.name,
            ),
            groupTitle = primary?.groupTitle ?: prototype.groupTitle,
            logoUrl = primary?.logoUrl ?: prototype.logoUrl,
            identityKey = primary?.identityKey ?: prototype.identityKey,
            encryptedRequest = encodeSources(CatalogSourceMerge.merge(allSources, incoming)),
        )
    }

    private fun isSuppressed(channel: ChannelEntity): Boolean =
        ChannelIdentity.isSuppressed(channel.tvgId, channel.name, channel.identityKey)

    private fun decodeSources(encryptedRequest: String): List<CatalogSource> {
        val decrypted = secretBox.decrypt(encryptedRequest)
        val objects = if (decrypted.trimStart().startsWith("[")) {
            val array = JSONArray(decrypted)
            List(array.length()) { array.getJSONObject(it) }
        } else listOf(JSONObject(decrypted)) // v0.1 compatibility
        return objects.map { json ->
            val headersObject = json.optJSONObject("headers") ?: JSONObject()
            val headers = headersObject.keys().asSequence().associateWith { headersObject.optString(it) }
            val url = json.getString("url")
            val legacyOfficial = OfficialWebChannelCatalog.pages.any { it.url == url } ||
                url in OfficialWebChannelCatalog.retiredOfficialUrls
            val owners = json.optJSONArray("owners")?.let { array ->
                buildSet { for (index in 0 until array.length()) add(array.optString(index)) }
            } ?: emptySet()
            CatalogSource(
                url,
                headers,
                json.optBoolean("official", legacyOfficial),
                owners,
                legacyUnowned = json.optBoolean("legacyUnowned", !json.has("owners")),
            )
        }.let { CatalogSourceMerge.merge(emptyList(), it) }
    }

    private fun encodeSources(sources: List<CatalogSource>): String {
        val requests = JSONArray()
        sources.forEach { source ->
            requests.put(
                JSONObject().put("url", source.url).put("headers", JSONObject(source.headers)).put("official", source.official)
                    .also { json ->
                        if (source.owners.isNotEmpty()) json.put("owners", JSONArray(source.owners.sorted()))
                        if (source.legacyUnowned) json.put("legacyUnowned", true)
                    },
            )
        }
        return secretBox.encrypt(requests.toString())
    }

    private fun removeOwners(sources: List<CatalogSource>, owners: Set<String>): List<CatalogSource> =
        owners.fold(sources) { remaining, owner -> CatalogSourceMerge.removeOwner(remaining, owner) }

    private fun subscriptionIdFor(origin: String): String? = runCatching { URI(origin).scheme?.lowercase() }
        .getOrNull()?.takeIf { it == "http" || it == "https" }?.let {
            Hashing.sha256Hex(origin.toByteArray(Charsets.UTF_8))
        }

    private suspend fun saveSubscriptionAttempt(
        id: String,
        origin: String,
        previous: PlaylistSubscriptionEntity?,
        attemptedAtMs: Long,
        status: String,
        successAtMs: Long?,
        channelCount: Int,
    ) {
        dao.saveSubscription(
            PlaylistSubscriptionEntity(
                id = id,
                encryptedUrl = secretBox.encrypt(origin),
                lastAttemptAtMs = attemptedAtMs,
                lastSuccessAtMs = successAtMs,
                status = status,
                channelCount = channelCount,
                createdAtMs = previous?.createdAtMs ?: attemptedAtMs,
            ),
        )
    }

    private fun safeStatus(status: String): String = when {
        status in FIXED_SUBSCRIPTION_FAILURES -> status
        Regex("HTTP_[1-5][0-9]{2}").matches(status) -> status
        else -> SUBSCRIPTION_FAILED
    }

    private companion object {
        const val SUBSCRIPTION_OK = "SUCCESS"
        const val SUBSCRIPTION_REFRESHING = "UPDATING"
        const val SUBSCRIPTION_REJECTED = "REJECTED_SNAPSHOT"
        const val SUBSCRIPTION_PRESERVED = "PRESERVED_SNAPSHOT"
        const val SUBSCRIPTION_FAILED = "FAILED"
        // New local imports are explicitly owned so a later subscription replacement cannot
        // mistake an identical URL for that subscription's sole source.
        const val LOCAL_IMPORT_OWNER = "local-import"
        // Provenance-only owner for sources delivered by the hub subscription. Never an address.
        const val HUB_SUBSCRIPTION_OWNER = "hub-subscription"
        const val HUB_ORIGIN = "hub://subscription"
        // This is provenance only. It is not a subscription identifier and never contains the
        // address from which the release asset was originally obtained.
        const val BUNDLED_DEFAULT_OWNER = "apk-bundled-default"
        const val BUNDLED_DEFAULT_ORIGIN = "bundled://apk-default"
        val FIXED_SUBSCRIPTION_FAILURES = setOf(
            "SUBSCRIPTION_MISSING", "IMPORT_REJECTED", "IMPORT_PRESERVED", "PLAYLIST_TOO_LARGE",
            "PLAYLIST_INVALID", "URL_INVALID", "NETWORK_TIMEOUT", "NETWORK_UNAVAILABLE", "NETWORK_IO", "NETWORK_BLOCKED",
        )
    }
}

internal fun retainInstallableOfficialSources(
    sources: List<CatalogSource>,
    candidateUrls: Set<String>,
    installedUrls: Set<String>,
): List<CatalogSource> = sources.filterNot { source ->
    source.official && source.url in candidateUrls && source.url !in installedUrls
}

sealed interface CatalogImportResult {
    data class Imported(
        val summary: ImportSummary,
        val channelCount: Int,
        val sourceCount: Int,
    ) : CatalogImportResult
    data class Rejected(val summary: ImportSummary) : CatalogImportResult
    data class Preserved(val summary: ImportSummary) : CatalogImportResult
}

object CatalogReplacementGuard {
    fun shouldPreserveExisting(existingCount: Int, incomingCount: Int, rejectedLines: Int): Boolean =
        existingCount >= 5 && rejectedLines > 0 && incomingCount * 2 < existingCount
}

/** Values persisted in playlist.mode. Do not expose arbitrary database content to the UI. */
object DefaultPlaylistMode {
    const val BUNDLED = "BUNDLED"
    const val CUSTOM = "CUSTOM"

    fun isSafe(value: String?): Boolean = value == BUNDLED || value == CUSTOM
}

/** Pure migration/first-run policy, kept separate so legacy decisions are directly testable. */
object DefaultPlaylistSeedingPolicy {
    fun shouldSeed(
        savedMode: String?,
        hasPlaylistRecord: Boolean,
        hasSubscriptions: Boolean,
        hasNonOfficialSources: Boolean,
    ): Boolean = when (savedMode) {
        DefaultPlaylistMode.BUNDLED, DefaultPlaylistMode.CUSTOM -> false
        // Unknown future values and v3-era state are intentionally conservative. A playlist row,
        // subscription, or non-official source is evidence of user customisation.
        null -> !hasPlaylistRecord && !hasSubscriptions && !hasNonOfficialSources
        else -> false
    }
}
