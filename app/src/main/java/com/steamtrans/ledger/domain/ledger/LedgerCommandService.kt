package com.steamtrans.ledger.domain.ledger

import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.EventDraft
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.LedgerRepository
import com.steamtrans.ledger.data.LedgerSnapshot

/**
 * 账务写入的唯一领域入口。底层仓储保证每条命令在同一 Room 事务内完成候选重放、
 * 库存/批次校验和最终写入，调用方不会观察到半完成状态。
 */
interface LedgerCommandService {
    suspend fun add(draft: EventDraft)
    suspend fun edit(eventId: Long, draft: EventDraft)
    suspend fun void(eventId: Long)
    suspend fun restore(eventId: Long)
    suspend fun adjustAccount(account: AccountType, targetBalanceCents: Long, note: String)
    suspend fun addItemWithInitialBuy(item: ItemEntity, draft: EventDraft)
    suspend fun addItemWithConversionOutput(item: ItemEntity, draft: EventDraft)
    suspend fun addItemsWithConversionOutputs(items: List<ItemEntity>, draft: EventDraft)
    suspend fun completeLegacyMigration(
        platformAccounts: Map<String, AccountType>,
        walletBalanceCents: Long,
        fiatBalanceCents: Long?
    )
    suspend fun replay(): LedgerSnapshot
}

class RoomLedgerCommandService(
    private val repository: LedgerRepository
) : LedgerCommandService {
    override suspend fun add(draft: EventDraft) {
        repository.addEvent(draft)
    }

    override suspend fun edit(eventId: Long, draft: EventDraft) {
        repository.updateEvent(eventId, draft)
    }

    override suspend fun void(eventId: Long) {
        repository.setEventVoided(eventId, true)
    }

    override suspend fun restore(eventId: Long) {
        repository.setEventVoided(eventId, false)
    }

    override suspend fun adjustAccount(
        account: AccountType,
        targetBalanceCents: Long,
        note: String
    ) {
        repository.addAccountAdjustment(account, targetBalanceCents, note)
    }

    override suspend fun addItemWithInitialBuy(item: ItemEntity, draft: EventDraft) {
        repository.addItemWithInitialBuy(item, draft)
    }

    override suspend fun addItemWithConversionOutput(item: ItemEntity, draft: EventDraft) {
        repository.addItemWithConversionOutput(item, draft)
    }

    override suspend fun addItemsWithConversionOutputs(items: List<ItemEntity>, draft: EventDraft) {
        repository.addItemsWithConversionOutputs(items, draft)
    }

    override suspend fun completeLegacyMigration(
        platformAccounts: Map<String, AccountType>,
        walletBalanceCents: Long,
        fiatBalanceCents: Long?
    ) {
        repository.completeLegacyMigration(platformAccounts, walletBalanceCents, fiatBalanceCents)
    }

    override suspend fun replay(): LedgerSnapshot = repository.currentSnapshot().also { snapshot ->
        require(snapshot.error == null) { snapshot.error ?: "账本重放失败" }
    }
}
