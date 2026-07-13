package com.steamtrans.ledger

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.EventDraft
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.data.LedgerDatabase
import com.steamtrans.ledger.data.LedgerRepository
import com.steamtrans.ledger.data.LedgerSnapshot
import com.steamtrans.ledger.data.MarketBindingDraft
import com.steamtrans.ledger.data.MarketQuoteEntity
import com.steamtrans.ledger.data.PlatformProfileEntity
import com.steamtrans.ledger.data.PortfolioSnapshotEntity
import com.steamtrans.ledger.data.PriceSource
import com.steamtrans.ledger.data.TrackingMode
import com.steamtrans.ledger.data.market.CommunityMarketGateway
import com.steamtrans.ledger.data.market.SteamMarketSearchResult
import com.steamtrans.ledger.data.market.estimateNetPrice
import com.steamtrans.ledger.domain.backup.BackupPreview
import com.steamtrans.ledger.domain.backup.BackupService
import com.steamtrans.ledger.domain.ledger.RoomLedgerCommandService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class MarketRefreshState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val lastUpdatedAt: Long? = null
)

data class MarketSearchState(
    val running: Boolean = false,
    val query: String = "",
    val results: List<SteamMarketSearchResult> = emptyList(),
    val error: String? = null
)

data class PendingRestore(val raw: String, val preview: BackupPreview)

@Serializable
data class ConversionRecipe(
    val name: String,
    val inputItemId: Long,
    val inputQuantity: Long,
    val outputItemId: Long,
    val outputQuantity: Long
)

private val Context.ledgerPreferences by preferencesDataStore("ledger_preferences")
private val MigrationCompletedKey = booleanPreferencesKey("v2_migration_completed")
private val ConversionRecipesKey = stringPreferencesKey("conversion_recipes")
private val PreferencesJson = Json { ignoreUnknownKeys = true }

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LedgerRepository(LedgerDatabase.get(application))
    private val commandService = RoomLedgerCommandService(repository)
    private val marketGateway = CommunityMarketGateway()
    private val backupService = BackupService(application, repository)

    val items: StateFlow<List<ItemEntity>> = repository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val snapshot: StateFlow<LedgerSnapshot> = repository.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerSnapshot())
    val platformProfiles: StateFlow<List<PlatformProfileEntity>> = repository.platformProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val portfolioSnapshots: StateFlow<List<PortfolioSnapshotEntity>> = repository.portfolioSnapshots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val marketQuotes: StateFlow<List<MarketQuoteEntity>> = repository.marketQuotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val migrationCompleted: StateFlow<Boolean> = application.ledgerPreferences.data
        .map { it[MigrationCompletedKey] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val conversionRecipes: StateFlow<List<ConversionRecipe>> = application.ledgerPreferences.data
        .map { preferences ->
            preferences[ConversionRecipesKey]
                ?.let { encoded -> runCatching { PreferencesJson.decodeFromString<List<ConversionRecipe>>(encoded) }.getOrNull() }
                .orEmpty()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()
    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress: StateFlow<Boolean> = _operationInProgress.asStateFlow()
    private val _marketRefresh = MutableStateFlow(MarketRefreshState())
    val marketRefresh: StateFlow<MarketRefreshState> = _marketRefresh.asStateFlow()
    private val _marketSearch = MutableStateFlow(MarketSearchState())
    val marketSearch: StateFlow<MarketSearchState> = _marketSearch.asStateFlow()
    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    fun clearOperationError() { _operationError.value = null }
    fun clearOperationMessage() { _operationMessage.value = null }

    fun addItem(name: String, game: String, type: ItemType, tracking: TrackingMode, after: (Long) -> Unit = {}) =
        viewModelScope.launch {
            runCatching { repository.addItem(name, game, type, tracking) }
                .onSuccess(after).onFailure { showError(it, "保存失败") }
        }

    fun addEvent(draft: EventDraft, done: () -> Unit = {}) =
        launchOperation("保存失败", done) { commandService.add(draft) }

    fun updateEvent(id: Long, draft: EventDraft, done: () -> Unit = {}) =
        launchOperation("修改失败", done) { commandService.edit(id, draft) }

    fun addItemWithInitialBuy(item: ItemEntity, draft: EventDraft, done: () -> Unit = {}) =
        launchOperation("保存失败", done) { commandService.addItemWithInitialBuy(item, draft) }

    fun addItemWithConversionOutput(item: ItemEntity, draft: EventDraft, done: () -> Unit = {}) =
        launchOperation("转换保存失败", done) { commandService.addItemWithConversionOutput(item, draft) }

    fun setEventVoided(id: Long, voided: Boolean) =
        launchOperation(if (voided) "作废失败" else "恢复失败") {
            if (voided) commandService.void(id) else commandService.restore(id)
        }

    fun adjustAccount(account: AccountType, newBalanceCents: Long, note: String, done: () -> Unit = {}) =
        launchOperation("余额调整失败", done) { commandService.adjustAccount(account, newBalanceCents, note) }

    fun reviewTracking(itemId: Long, mode: TrackingMode) =
        launchOperation("确认持仓方式失败") { repository.reviewTracking(itemId, mode) }

    fun completeLegacyMigration(
        platformAccounts: Map<String, AccountType>,
        walletBalanceCents: Long,
        fiatBalanceCents: Long?,
        done: () -> Unit = {}
    ) = launchOperation("迁移校准失败", done) {
        commandService.completeLegacyMigration(platformAccounts, walletBalanceCents, fiatBalanceCents)
        getApplication<Application>().ledgerPreferences.edit { it[MigrationCompletedKey] = true }
    }

    fun updateItem(item: ItemEntity) = launchOperation("更新物品失败") { repository.updateItem(item) }

    fun bindMarket(itemId: Long, result: SteamMarketSearchResult, done: () -> Unit = {}) =
        launchOperation("绑定行情失败", done) {
            repository.bindMarket(itemId, MarketBindingDraft(result.appId, result.marketHashName, result.imageUrl))
        }

    fun unbindMarket(itemId: Long) = launchOperation("解除绑定失败") { repository.unbindMarket(itemId) }

    fun setManualQuote(itemId: Long, grossCents: Long, done: () -> Unit = {}) =
        launchOperation("保存手动行情失败", done) {
            val steam = platformProfiles.value.firstOrNull { it.name.equals("Steam", true) }
            repository.saveQuote(itemId, grossCents, estimateNetPrice(grossCents, steam), PriceSource.MANUAL)
        }

    fun updatePlatformProfile(profile: PlatformProfileEntity, done: () -> Unit = {}) =
        launchOperation("保存平台档案失败", done) { repository.updatePlatformProfile(profile) }

    fun saveConversionRecipe(recipe: ConversionRecipe) = viewModelScope.launch {
        runCatching {
            require(recipe.name.trim().isNotBlank()) { "配方名称不能为空" }
            require(recipe.inputItemId > 0 && recipe.outputItemId > 0) { "配方物品无效" }
            require(recipe.inputQuantity > 0 && recipe.outputQuantity > 0) { "配方数量必须大于 0" }
            getApplication<Application>().ledgerPreferences.edit { preferences ->
                val current = preferences[ConversionRecipesKey]
                    ?.let { encoded -> runCatching { PreferencesJson.decodeFromString<List<ConversionRecipe>>(encoded) }.getOrNull() }
                    .orEmpty()
                val normalized = recipe.copy(name = recipe.name.trim())
                val updated = (current.filterNot { it.name.equals(normalized.name, ignoreCase = true) } + normalized).takeLast(20)
                preferences[ConversionRecipesKey] = PreferencesJson.encodeToString(updated)
            }
            _operationMessage.value = "转换配方已保存"
        }.onFailure { showError(it, "保存转换配方失败") }
    }

    fun clearAll(done: () -> Unit = {}) = launchOperation("清空失败", done) { repository.clearAll() }
    fun clearMarketData(done: () -> Unit = {}) = launchOperation("清空行情失败", done) { repository.clearMarketData() }

    fun searchMarket(query: String, appId: Int?) {
        if (_marketSearch.value.running) return
        viewModelScope.launch {
            _marketSearch.value = MarketSearchState(running = true, query = query)
            try {
                _marketSearch.value = MarketSearchState(query = query, results = marketGateway.search(query, appId))
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                _marketSearch.value = MarketSearchState(query = query, error = cause.message ?: "搜索失败")
            }
        }
    }

    fun clearMarketSearch() { _marketSearch.value = MarketSearchState() }

    fun refreshCurrentHoldings() {
        if (_marketRefresh.value.running) return
        viewModelScope.launch {
            val bound = snapshot.value.holdings.filter {
                it.quantity > 0 && it.item.tradeable && it.item.marketAppId != null && !it.item.marketHashName.isNullOrBlank()
            }
            if (bound.isEmpty()) {
                _operationMessage.value = "当前持仓没有已绑定的可交易物品"
                return@launch
            }
            val steam = platformProfiles.value.firstOrNull { it.name.equals("Steam", true) }
            var succeeded = 0
            var failed = 0
            var lastFailure: String? = null
            _marketRefresh.value = MarketRefreshState(running = true, total = bound.size)
            bound.forEachIndexed { index, holding ->
                try {
                    val quote = marketGateway.quote(holding.item.marketAppId!!, holding.item.marketHashName!!)
                    repository.saveQuote(
                        holding.item.id,
                        quote.grossPriceCents,
                        estimateNetPrice(quote.grossPriceCents, steam),
                        PriceSource.STEAM_MARKET,
                        quote.volume
                    )
                    succeeded++
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    failed++
                    lastFailure = cause.message ?: "未知错误"
                }
                _marketRefresh.value = MarketRefreshState(
                    running = true,
                    completed = index + 1,
                    total = bound.size,
                    succeeded = succeeded,
                    failed = failed
                )
            }
            _marketRefresh.value = MarketRefreshState(
                completed = bound.size,
                total = bound.size,
                succeeded = succeeded,
                failed = failed,
                lastUpdatedAt = System.currentTimeMillis()
            )
            _operationMessage.value = buildString {
                append("行情更新完成：成功 $succeeded，失败 $failed")
                if (failed > 0 && !lastFailure.isNullOrBlank()) append("；最近错误：$lastFailure")
            }
        }
    }

    fun exportBackup(onReady: (String) -> Unit) = viewModelScope.launch {
        runCatching { backupService.exportJson() }
            .onSuccess(onReady).onFailure { showError(it, "生成备份失败") }
    }

    fun exportCsv(onReady: (ByteArray) -> Unit) = viewModelScope.launch {
        runCatching { backupService.exportCsvZip() }
            .onSuccess(onReady).onFailure { showError(it, "生成 CSV 失败") }
    }

    fun inspectBackup(raw: String) {
        runCatching { backupService.preview(raw) }
            .onSuccess { _pendingRestore.value = PendingRestore(raw, it) }
            .onFailure { showError(it, "备份校验失败") }
    }

    fun cancelRestore() { _pendingRestore.value = null }

    fun confirmRestore(done: () -> Unit = {}) {
        val pending = _pendingRestore.value ?: return
        launchOperation("恢复失败", {
            _pendingRestore.value = null
            _operationMessage.value = "恢复完成；已在应用目录保留恢复前应急备份"
            done()
        }) { backupService.restoreJson(pending.raw) }
    }

    private fun launchOperation(
        fallbackMessage: String,
        done: () -> Unit = {},
        operation: suspend () -> Unit
    ) {
        if (!_operationInProgress.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                operation()
                done()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                showError(cause, fallbackMessage)
            } finally {
                _operationInProgress.value = false
            }
        }
    }

    private fun showError(cause: Throwable, fallbackMessage: String) {
        _operationError.value = cause.message ?: fallbackMessage
    }
}
