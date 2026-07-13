package com.steamtrans.ledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.steamtrans.ledger.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = LedgerRepository(LedgerDatabase.get(application))
    val items: StateFlow<List<ItemEntity>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val snapshot: StateFlow<LedgerSnapshot> = repo.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerSnapshot())
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()
    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress: StateFlow<Boolean> = _operationInProgress.asStateFlow()

    fun clearOperationError() { _operationError.value = null }

    fun addItem(name: String, game: String, type: ItemType, after: (Long) -> Unit = {}) = viewModelScope.launch {
        runCatching { repo.addItem(name, game, type) }.onSuccess(after).onFailure { showError(it, "保存失败") }
    }
    fun addEvent(draft: EventDraft, done: () -> Unit) = launchOperation("保存失败", done) { repo.addEvent(draft) }
    fun addItemWithInitialBuy(item: ItemEntity, draft: EventDraft, done: () -> Unit) =
        launchOperation("保存失败", done) { repo.addItemWithInitialBuy(item, draft) }
    fun addItemWithConversionOutput(item: ItemEntity, draft: EventDraft, done: () -> Unit) =
        launchOperation("转换保存失败", done) { repo.addItemWithConversionOutput(item, draft) }
    fun deleteEvent(id: Long) = launchOperation("删除失败") { repo.deleteEvent(id) }
    fun clearAll() = launchOperation("清空失败") { repo.clearAll() }

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
