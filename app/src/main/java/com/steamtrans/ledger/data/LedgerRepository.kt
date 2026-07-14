package com.steamtrans.ledger.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LedgerRepository(private val db: LedgerDatabase) {
    private val dao = db.dao()

    val items: Flow<List<ItemEntity>> = dao.observeItems()
    val platformProfiles: Flow<List<PlatformProfileEntity>> = dao.observePlatformProfiles()
    val portfolioSnapshots: Flow<List<PortfolioSnapshotEntity>> = dao.observePortfolioSnapshots()
    val marketQuotes: Flow<List<MarketQuoteEntity>> = dao.observeMarketQuotes()
    val snapshot: Flow<LedgerSnapshot> = dao.observeLedgerChanges().map {
        db.withTransaction { calculateCurrent() }
    }

    suspend fun addItem(
        name: String,
        game: String,
        type: ItemType,
        trackingMode: TrackingMode = type.defaultTracking
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insertItem(
            normalizeItem(
                ItemEntity(
                    name = name,
                    game = game,
                    type = type,
                    trackingMode = trackingMode,
                    trackingReviewed = true,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
    }

    suspend fun addEvent(draft: EventDraft) = db.withTransaction {
        validateAndInsert(draft)
        recordPortfolioSnapshot("event")
    }

    suspend fun updateEvent(id: Long, draft: EventDraft) = db.withTransaction {
        validateDraft(draft)
        val records = loadViews()
        val original = records.firstOrNull { it.event.id == id } ?: error("流水不存在或已被删除")
        val oldLineIds = original.lines.map { it.id }.toSet()
        val downstreamAllocations = dao.getAllocations().filter {
            it.sourceLineId in oldLineIds && it.eventLineId !in oldLineIds
        }
        require(downstreamAllocations.isEmpty() || draft.lines.size == original.lines.size) {
            "该买入批次已被后续流水引用，不能改变物品行数量"
        }

        val candidate = candidateView(
            draft = draft,
            id = id,
            status = original.event.status,
            legacy = original.event.legacy,
            preferredLineIds = original.lines.map { it.id }
        )
        validateCandidate(records.filterNot { it.event.id == id } + candidate)

        val updatedEvent = candidate.event.copy(updatedAt = System.currentTimeMillis())
        dao.updateEvent(updatedEvent)
        dao.deleteLinesForEvent(id)

        val storedLines = candidate.lines.map { it.copy(eventId = id) }
        if (storedLines.all { it.id > 0 }) dao.insertLinesForRestore(storedLines)
        else dao.insertLines(storedLines.map { it.copy(id = 0) })

        if (downstreamAllocations.isNotEmpty()) dao.insertAllocationsForRestore(downstreamAllocations)
        insertDraftAllocations(id, draft)
        dao.touchItems(draft.lines.map { it.itemId }.filter { it > 0 }.distinct(), System.currentTimeMillis())
        recordPortfolioSnapshot("edit")
    }

    suspend fun setEventVoided(id: Long, voided: Boolean) = db.withTransaction {
        val records = loadViews()
        val target = records.firstOrNull { it.event.id == id } ?: error("流水不存在或已被删除")
        val status = if (voided) EventStatus.VOIDED else EventStatus.ACTIVE
        val candidate = records.map { view ->
            if (view.event.id == id) view.copy(event = view.event.copy(status = status, updatedAt = System.currentTimeMillis()))
            else view
        }
        validateCandidate(candidate)
        dao.updateEvent(target.event.copy(status = status, updatedAt = System.currentTimeMillis()))
        recordPortfolioSnapshot(if (voided) "void" else "restore")
    }

    suspend fun addAccountAdjustment(account: AccountType, newBalanceCents: Long, note: String) = db.withTransaction {
        val current = calculateCurrent().balance(account)
        val delta = Math.subtractExact(newBalanceCents, current)
        require(delta != 0L) { "余额没有变化" }
        validateAndInsert(
            EventDraft(
                type = EventType.ACCOUNT_ADJUSTMENT,
                platform = if (account == AccountType.STEAM_WALLET_CNY) "Steam" else "人民币资金池",
                note = note.trim(),
                lines = emptyList(),
                accountType = account,
                accountDeltaCents = delta
            )
        )
        recordPortfolioSnapshot("account")
    }

    suspend fun completeLegacyMigration(
        platformAccounts: Map<String, AccountType>,
        walletBalanceCents: Long,
        fiatBalanceCents: Long?
    ) = db.withTransaction {
        val now = System.currentTimeMillis()
        platformAccounts.forEach { (platform, account) -> dao.updateLegacyPlatformAccount(platform, account, now) }
        val targets = buildMap {
            put(AccountType.STEAM_WALLET_CNY, walletBalanceCents)
            if (fiatBalanceCents != null) put(AccountType.FIAT_CNY, fiatBalanceCents)
        }
        targets.forEach { (account, target) ->
            val current = calculateCurrent().balance(account)
            val delta = Math.subtractExact(target, current)
            if (delta != 0L) {
                validateAndInsert(
                    EventDraft(
                        type = EventType.ACCOUNT_ADJUSTMENT,
                        timestamp = now,
                        platform = if (account == AccountType.STEAM_WALLET_CNY) "Steam" else "人民币资金池",
                        note = "v2 迁移期初校准",
                        lines = emptyList(),
                        accountType = account,
                        accountDeltaCents = delta
                    )
                )
            }
        }
        recordPortfolioSnapshot("migration")
    }

    /** 新物品与首笔买入在同一事务中完成。 */
    suspend fun addItemWithInitialBuy(item: ItemEntity, draft: EventDraft) = db.withTransaction {
        require(draft.type == EventType.BUY && draft.lines.size == 1 && draft.lines.single().direction == LineDirection.IN) {
            "首次入账必须是单物品买入"
        }
        val normalized = normalizeItem(item).copy(
            trackingReviewed = true,
            createdAt = item.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val itemId = dao.findItem(normalized.name, normalized.game, normalized.type)?.id
            ?: dao.insertItem(normalized)
        validateAndInsert(draft.copy(lines = listOf(draft.lines.single().copy(itemId = itemId))))
        recordPortfolioSnapshot("buy")
    }

    suspend fun addItemWithConversionOutput(item: ItemEntity, draft: EventDraft) = db.withTransaction {
        val outputs = draft.lines.filter { it.direction == LineDirection.OUT }
        val unresolvedInputs = draft.lines.filter { it.direction == LineDirection.IN && it.itemId == 0L }
        require(draft.type == EventType.CONVERT && outputs.size == 1 && unresolvedInputs.size == 1 && draft.lines.size == 2) {
            "转换必须包含一个转出物和一个产出物"
        }
        val normalized = normalizeItem(item).copy(
            trackingReviewed = true,
            createdAt = item.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val itemId = dao.findItem(normalized.name, normalized.game, normalized.type)?.id
            ?: dao.insertItem(normalized)
        val resolved = draft.copy(
            feeCents = 0,
            lines = draft.lines.map { line -> if (line.itemId == 0L) line.copy(itemId = itemId) else line }
        )
        validateAndInsert(resolved)
        recordPortfolioSnapshot("convert")
    }

    suspend fun reviewTracking(itemId: Long, mode: TrackingMode) = db.withTransaction {
        val item = dao.getItems().firstOrNull { it.id == itemId } ?: error("物品不存在")
        dao.updateItem(item.copy(trackingMode = mode, trackingReviewed = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateItem(item: ItemEntity) = db.withTransaction {
        dao.updateItem(normalizeItem(item).copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun bindMarket(itemId: Long, binding: MarketBindingDraft) = db.withTransaction {
        val item = dao.getItems().firstOrNull { it.id == itemId } ?: error("物品不存在")
        dao.updateItem(
            item.copy(
                tradeable = binding.tradeable,
                marketAppId = binding.appId,
                marketHashName = binding.marketHashName.trim(),
                imageUrl = binding.imageUrl,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun unbindMarket(itemId: Long) = db.withTransaction {
        val item = dao.getItems().firstOrNull { it.id == itemId } ?: error("物品不存在")
        dao.updateItem(
            item.copy(
                tradeable = false,
                marketAppId = null,
                marketHashName = null,
                imageUrl = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveQuote(
        itemId: Long,
        grossCents: Long,
        estimatedNetCents: Long,
        source: PriceSource,
        volume: String = ""
    ) = db.withTransaction {
        require(grossCents >= 0 && estimatedNetCents >= 0) { "价格不能为负数" }
        dao.insertQuote(
            MarketQuoteEntity(
                itemId = itemId,
                grossPriceCents = grossCents,
                estimatedNetPriceCents = estimatedNetCents,
                source = source,
                timestamp = System.currentTimeMillis(),
                volume = volume
            )
        )
        recordPortfolioSnapshot("quote")
    }

    suspend fun updatePlatformProfile(profile: PlatformProfileEntity) {
        require(profile.name.trim().isNotBlank()) { "平台名称不能为空" }
        require(profile.buyFeeRateBps in 0..10_000 && profile.sellFeeRateBps in 0..10_000) { "费率必须在 0% 到 100% 之间" }
        require(profile.buyFixedFeeCents >= 0 && profile.sellFixedFeeCents >= 0) { "固定费用不能为负数" }
        dao.insertPlatformProfiles(listOf(profile.copy(name = profile.name.trim())))
    }

    suspend fun clearAll() = db.withTransaction {
        dao.deleteEvents()
        dao.deleteQuotes()
        dao.deletePortfolioSnapshots()
        dao.deleteItems()
    }

    suspend fun clearMarketData() = db.withTransaction {
        dao.deleteQuotes()
        dao.deletePortfolioSnapshots()
    }

    suspend fun removePortfolioSnapshot(id: Long) {
        require(id > 0) { "趋势点无效" }
        require(dao.deletePortfolioSnapshot(id) == 1) { "趋势点不存在或已被移除" }
    }

    suspend fun currentSnapshot(): LedgerSnapshot = db.withTransaction { calculateCurrent() }
    suspend fun allItems(): List<ItemEntity> = dao.getItems()
    suspend fun allEvents(): List<EventView> = db.withTransaction { loadViews() }
    suspend fun allProfiles(): List<PlatformProfileEntity> = dao.getPlatformProfiles()
    suspend fun allQuotes(): List<MarketQuoteEntity> = dao.getAllQuotes()
    suspend fun allPortfolioSnapshots(): List<PortfolioSnapshotEntity> = dao.getAllPortfolioSnapshots()

    suspend fun exportData(): LedgerExportData = db.withTransaction {
        val views = loadViews()
        LedgerExportData(
            items = dao.getItems(),
            events = views.map { it.event },
            lines = views.flatMap { it.lines },
            allocations = dao.getAllocations(),
            platformProfiles = dao.getPlatformProfiles(),
            quotes = dao.getAllQuotes(),
            portfolioSnapshots = dao.getAllPortfolioSnapshots()
        )
    }

    suspend fun restoreData(data: LedgerExportData) = db.withTransaction {
        dao.deleteEvents()
        dao.deleteQuotes()
        dao.deletePortfolioSnapshots()
        dao.deleteItems()
        dao.deletePlatformProfiles()
        dao.insertPlatformProfiles(data.platformProfiles)
        dao.insertItems(data.items)
        dao.insertEvents(data.events)
        dao.insertLinesForRestore(data.lines)
        dao.insertAllocationsForRestore(data.allocations)
        dao.insertQuotes(data.quotes)
        dao.insertPortfolioSnapshots(data.portfolioSnapshots)
        val result = calculateCurrent()
        require(result.error == null) { result.error ?: "恢复后的账本无效" }
    }

    private suspend fun validateAndInsert(draft: EventDraft): Long {
        validateDraft(draft)
        val current = loadViews()
        val candidate = candidateView(draft)
        validateCandidate(current + candidate)

        val eventId = dao.insertEvent(candidate.event.copy(id = 0, legacy = false, updatedAt = System.currentTimeMillis()))
        val lineIds = dao.insertLines(candidate.lines.map { it.copy(id = 0, eventId = eventId) })
        if (draft.allocations.isNotEmpty()) {
            val allocations = draft.allocations.map { allocation ->
                LotAllocationEntity(
                    eventLineId = lineIds[allocation.lineIndex],
                    sourceLineId = allocation.sourceLineId,
                    quantity = allocation.quantity,
                    unitOrdinal = allocation.unitOrdinal
                )
            }
            dao.insertAllocations(allocations)
        }
        dao.touchItems(draft.lines.map { it.itemId }.filter { it > 0 }.distinct(), System.currentTimeMillis())
        return eventId
    }

    private suspend fun insertDraftAllocations(eventId: Long, draft: EventDraft) {
        if (draft.allocations.isEmpty()) return
        val record = dao.getEventRecords().first { it.event.id == eventId }
        dao.insertAllocations(
            draft.allocations.map { allocation ->
                LotAllocationEntity(
                    eventLineId = record.lines.sortedBy { it.id }[allocation.lineIndex].id,
                    sourceLineId = allocation.sourceLineId,
                    quantity = allocation.quantity,
                    unitOrdinal = allocation.unitOrdinal
                )
            }
        )
    }

    private suspend fun calculateCurrent(): LedgerSnapshot {
        val quotes = dao.getLatestQuotes().associateBy { it.itemId }
        return runCatching { LedgerCalculator.calculate(dao.getItems(), loadViews(), quotes) }.getOrElse { cause ->
            LedgerSnapshot(events = loadViews(), error = "账本数据异常：${cause.message ?: "未知错误"}")
        }
    }

    private suspend fun loadViews(): List<EventView> {
        val allocationsByLine = dao.getAllocations().groupBy { it.eventLineId }
        return dao.getEventRecords().map { record ->
            record.asView(record.lines.flatMap { allocationsByLine[it.id].orEmpty() })
        }
    }

    private suspend fun validateCandidate(views: List<EventView>) {
        val result = LedgerCalculator.calculate(dao.getItems(), views, dao.getLatestQuotes().associateBy { it.itemId })
        require(result.error == null) { result.error ?: "流水校验失败" }
    }

    private fun candidateView(
        draft: EventDraft,
        id: Long = Long.MAX_VALUE,
        status: EventStatus = EventStatus.ACTIVE,
        legacy: Boolean = false,
        preferredLineIds: List<Long> = emptyList()
    ): EventView {
        val event = LedgerEventEntity(
            id = id,
            type = draft.type,
            timestamp = draft.timestamp,
            platform = draft.platform.trim().ifBlank { if (draft.accountType == AccountType.STEAM_WALLET_CNY) "Steam" else "人民币" },
            feeCents = draft.feeCents,
            note = draft.note.trim(),
            status = status,
            accountType = draft.accountType,
            accountDeltaCents = draft.accountDeltaCents,
            feeRateBps = draft.feeRateBps,
            fixedFeeCents = draft.fixedFeeCents,
            legacy = legacy,
            updatedAt = System.currentTimeMillis()
        )
        val lines = draft.lines.mapIndexed { index, line ->
            EventLineEntity(
                id = preferredLineIds.getOrNull(index) ?: (Long.MAX_VALUE - index - 1),
                eventId = id,
                itemId = line.itemId,
                direction = line.direction,
                quantity = line.quantity,
                unitPriceCents = line.unitPriceCents
            )
        }
        val allocations = draft.allocations.map { allocation ->
            LotAllocationEntity(
                id = Long.MAX_VALUE - allocation.lineIndex,
                eventLineId = lines[allocation.lineIndex].id,
                sourceLineId = allocation.sourceLineId,
                quantity = allocation.quantity,
                unitOrdinal = allocation.unitOrdinal
            )
        }
        return EventView(event, lines, allocations)
    }

    private suspend fun recordPortfolioSnapshot(trigger: String) {
        val snapshot = calculateCurrent()
        if (snapshot.error != null) return
        dao.insertPortfolioSnapshot(
            PortfolioSnapshotEntity(
                timestamp = System.currentTimeMillis(),
                trigger = trigger,
                fiatBalanceCents = snapshot.fiatBalanceCents,
                walletBalanceCents = snapshot.walletBalanceCents,
                fiatHoldingCostCents = snapshot.fiatHoldingCostCents,
                walletHoldingCostCents = snapshot.walletHoldingCostCents,
                marketGrossCents = snapshot.marketGrossValueCents,
                marketNetCents = snapshot.marketNetValueCents
            )
        )
    }

    private suspend fun validateDraft(draft: EventDraft) {
        require(draft.feeCents >= 0) { "手续费不能为负数" }
        if (draft.type == EventType.ACCOUNT_ADJUSTMENT) {
            require(draft.lines.isEmpty() && draft.accountDeltaCents != 0L) { "余额调整格式无效" }
            return
        }
        require(draft.lines.isNotEmpty()) { "流水不能为空" }
        require(draft.lines.all { it.itemId > 0 && it.quantity > 0 && it.unitPriceCents >= 0 }) {
            "物品、数量或金额无效"
        }
        val itemIds = dao.getItems().mapTo(hashSetOf()) { it.id }
        require(draft.lines.all { it.itemId in itemIds }) { "流水包含不存在的物品" }
        val gross = try {
            draft.lines.fold(0L) { total, line ->
                Math.addExact(total, Math.multiplyExact(line.quantity, line.unitPriceCents))
            }
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("数量或金额过大")
        }
        when (draft.type) {
            EventType.BUY -> require(draft.lines.all { it.direction == LineDirection.IN && it.unitPriceCents > 0 }) {
                "买入流水格式无效"
            }
            EventType.SELL -> {
                require(draft.lines.all { it.direction == LineDirection.OUT && it.unitPriceCents > 0 }) {
                    "卖出流水格式无效"
                }
                require(draft.feeCents <= gross) { "手续费不能高于卖出金额" }
            }
            EventType.CONVERT -> {
                require(draft.feeCents == 0L && draft.lines.all { it.unitPriceCents == 0L }) { "转换不能包含金额或手续费" }
                require(draft.lines.any { it.direction == LineDirection.OUT } && draft.lines.any { it.direction == LineDirection.IN }) {
                    "转换必须同时包含转出物和产出物"
                }
            }
            EventType.ACCOUNT_ADJUSTMENT -> Unit
        }
    }

    private fun normalizeItem(item: ItemEntity): ItemEntity {
        val normalized = item.copy(
            name = item.name.trim(),
            game = item.game.trim(),
            note = item.note.trim(),
            marketHashName = item.marketHashName?.trim()?.takeIf { it.isNotBlank() }
        )
        require(normalized.name.isNotBlank()) { "物品名称不能为空" }
        return normalized
    }
}

data class LedgerExportData(
    val items: List<ItemEntity>,
    val events: List<LedgerEventEntity>,
    val lines: List<EventLineEntity>,
    val allocations: List<LotAllocationEntity>,
    val platformProfiles: List<PlatformProfileEntity>,
    val quotes: List<MarketQuoteEntity>,
    val portfolioSnapshots: List<PortfolioSnapshotEntity>
)
