package com.steamtrans.ledger.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LedgerRepository(private val db: LedgerDatabase) {
    private val dao = db.dao()
    val items: Flow<List<ItemEntity>> = dao.observeItems()
    val snapshot: Flow<LedgerSnapshot> = dao.observeLedgerChanges().map {
        db.withTransaction {
            val items = dao.getItems()
            val views = dao.getEventRecords().map { it.asView() }
            runCatching { LedgerCalculator.calculate(items, views) }.getOrElse { cause ->
                LedgerSnapshot(events = views, error = "账本数据异常：${cause.message ?: "未知错误"}")
            }
        }
    }

    suspend fun addItem(name: String, game: String, type: ItemType): Long =
        dao.insertItem(ItemEntity(name = name.trim(), game = game.trim(), type = type))

    suspend fun addEvent(draft: EventDraft) = db.withTransaction {
        validateAndInsert(draft)
    }

    /** 新物品与首笔买入在同一事务中完成，避免先建档、再重复录入交易。 */
    suspend fun addItemWithInitialBuy(item: ItemEntity, draft: EventDraft) = db.withTransaction {
        require(draft.type == EventType.BUY && draft.lines.size == 1 && draft.lines.single().direction == LineDirection.IN) {
            "首次入账必须是单物品买入"
        }
        val normalizedItem = normalizeItem(item)
        val itemId = dao.findItem(normalizedItem.name, normalizedItem.game, normalizedItem.type)?.id
            ?: dao.insertItem(normalizedItem)
        val resolvedDraft = draft.copy(lines = listOf(draft.lines.single().copy(itemId = itemId)))
        validateAndInsert(resolvedDraft)
    }

    suspend fun addItemWithConversionOutput(item: ItemEntity, draft: EventDraft) = db.withTransaction {
        val outputs = draft.lines.filter { it.direction == LineDirection.OUT }
        val inputs = draft.lines.filter { it.direction == LineDirection.IN && it.itemId == 0L }
        require(draft.type == EventType.CONVERT && outputs.size == 1 && inputs.size == 1 && draft.lines.size == 2) { "转换必须包含一个转出物和一个产出物" }
        require(outputs.single().itemId > 0) { "转出物无效" }
        val normalizedItem = normalizeItem(item)
        val itemId = dao.findItem(normalizedItem.name, normalizedItem.game, normalizedItem.type)?.id
            ?: dao.insertItem(normalizedItem)
        val resolvedDraft = draft.copy(
            feeCents = 0,
            lines = draft.lines.map { line -> if (line.itemId == 0L) line.copy(itemId = itemId) else line }
        )
        validateAndInsert(resolvedDraft)
    }

    suspend fun deleteEvent(id: Long) = db.withTransaction {
        val items = dao.getItems()
        val records = dao.getEventRecords()
        require(records.any { it.event.id == id }) { "流水不存在或已被删除" }
        val remaining = records.filterNot { it.event.id == id }.map { it.asView() }
        val result = LedgerCalculator.calculate(items, remaining)
        require(result.error == null) { "无法删除：${result.error}" }
        dao.deleteEvent(id)
    }
    suspend fun clearAll() = db.withTransaction { dao.deleteEvents(); dao.deleteItems() }

    private suspend fun validateAndInsert(draft: EventDraft) {
        validateDraft(draft)
        val items = dao.getItems()
        val itemIds = items.mapTo(hashSetOf()) { it.id }
        require(draft.lines.all { it.itemId in itemIds }) { "流水包含不存在的物品" }

        val candidateEvent = LedgerEventEntity(
            id = Long.MAX_VALUE,
            type = draft.type,
            timestamp = draft.timestamp,
            platform = draft.platform.trim().ifBlank { "Steam" },
            feeCents = draft.feeCents,
            note = draft.note.trim()
        )
        val candidateLines = draft.lines.mapIndexed { index, line ->
            EventLineEntity(
                id = Long.MAX_VALUE - index,
                eventId = candidateEvent.id,
                itemId = line.itemId,
                direction = line.direction,
                quantity = line.quantity,
                unitPriceCents = line.unitPriceCents
            )
        }
        val currentViews = dao.getEventRecords().map { it.asView() }
        val result = LedgerCalculator.calculate(items, currentViews + EventView(candidateEvent, candidateLines))
        require(result.error == null) { result.error ?: "流水校验失败" }

        val eventId = dao.insertEvent(candidateEvent.copy(id = 0))
        dao.insertLines(candidateLines.map { it.copy(id = 0, eventId = eventId) })
        dao.touchItems(draft.lines.map { it.itemId }.distinct())
    }

    private fun validateDraft(draft: EventDraft) {
        require(draft.lines.isNotEmpty()) { "流水不能为空" }
        require(draft.feeCents >= 0) { "手续费不能为负数" }
        require(draft.lines.all { it.itemId > 0 && it.quantity > 0 && it.unitPriceCents >= 0 }) {
            "物品、数量或金额无效"
        }
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
        }
        try {
            if (draft.type == EventType.BUY) Math.addExact(gross, draft.feeCents)
            else Math.subtractExact(gross, draft.feeCents)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("交易总额过大")
        }
    }

    private fun normalizeItem(item: ItemEntity): ItemEntity {
        val normalized = item.copy(name = item.name.trim(), game = item.game.trim(), note = item.note.trim())
        require(normalized.name.isNotBlank()) { "物品名称不能为空" }
        return normalized
    }
}
