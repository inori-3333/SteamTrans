package com.steamtrans.ledger.data

import java.math.BigInteger

object LedgerCalculator {
    private data class LegacyPosition(
        var quantity: Long = 0,
        var cost: CostVector = CostVector(),
        var sourceLineId: Long = 0,
        var sourceEventId: Long = 0,
        var acquiredAt: Long = 0
    )

    private data class MutableLot(
        val itemId: Long,
        val sourceLineId: Long,
        val sourceEventId: Long,
        val acquiredAt: Long,
        var quantity: Long,
        var cost: CostVector,
        val unitOrdinal: Int? = null,
        val legacyCarry: Boolean = false
    )

    fun calculate(
        items: List<ItemEntity>,
        views: List<EventView>,
        latestQuotes: Map<Long, MarketQuoteEntity> = emptyMap()
    ): LedgerSnapshot {
        val itemById = items.associateBy { it.id }
        val legacyPositions = mutableMapOf<Long, LegacyPosition>()
        val lots = mutableMapOf<Long, MutableList<MutableLot>>()
        val crossAccountOutcomes = mutableListOf<CrossAccountOutcome>()
        var legacyFlushed = false
        var fiatBalance = 0L
        var walletBalance = 0L
        var bought = 0L
        var sold = 0L
        var fiatPnl = 0L
        var walletPnl = 0L
        var error: String? = null

        fun addBalance(account: AccountType, delta: Long) {
            when (account) {
                AccountType.FIAT_CNY -> fiatBalance = Math.addExact(fiatBalance, delta)
                AccountType.STEAM_WALLET_CNY -> walletBalance = Math.addExact(walletBalance, delta)
            }
        }

        fun addPnl(account: AccountType, delta: Long) {
            when (account) {
                AccountType.FIAT_CNY -> fiatPnl = Math.addExact(fiatPnl, delta)
                AccountType.STEAM_WALLET_CNY -> walletPnl = Math.addExact(walletPnl, delta)
            }
        }

        fun recordSaleOutcome(eventId: Long, cost: CostVector, account: AccountType, proceeds: Long) {
            if (cost.only(account)) {
                addPnl(account, Math.subtractExact(proceeds, cost.amount(account)))
            } else {
                crossAccountOutcomes += CrossAccountOutcome(eventId, cost, account, proceeds)
            }
        }

        fun addLot(
            item: ItemEntity,
            sourceLineId: Long,
            sourceEventId: Long,
            acquiredAt: Long,
            quantity: Long,
            cost: CostVector,
            legacyCarry: Boolean = false
        ) {
            require(quantity > 0) { "数量必须大于 0" }
            val target = lots.getOrPut(item.id) { mutableListOf() }
            if (item.trackingMode == TrackingMode.INDIVIDUAL) {
                require(quantity <= 100_000) { "逐件物品数量过大，请改为按量持仓" }
                var remainingQuantity = quantity
                var remainingCost = cost
                var ordinal = 1
                while (remainingQuantity > 0) {
                    val unitCost = if (remainingQuantity == 1L) remainingCost
                    else proportionalCost(remainingCost, 1, remainingQuantity)
                    target += MutableLot(
                        itemId = item.id,
                        sourceLineId = sourceLineId,
                        sourceEventId = sourceEventId,
                        acquiredAt = acquiredAt,
                        quantity = 1,
                        cost = unitCost,
                        unitOrdinal = ordinal,
                        legacyCarry = legacyCarry
                    )
                    remainingCost -= unitCost
                    remainingQuantity--
                    ordinal++
                }
            } else {
                target += MutableLot(
                    itemId = item.id,
                    sourceLineId = sourceLineId,
                    sourceEventId = sourceEventId,
                    acquiredAt = acquiredAt,
                    quantity = quantity,
                    cost = cost,
                    legacyCarry = legacyCarry
                )
            }
        }

        fun flushLegacy() {
            if (legacyFlushed) return
            legacyPositions.forEach { (itemId, position) ->
                if (position.quantity > 0) {
                    val item = itemById[itemId] ?: error("流水包含不存在的物品")
                    addLot(
                        item = item,
                        sourceLineId = position.sourceLineId.takeIf { it > 0 } ?: error("旧持仓缺少承接流水行"),
                        sourceEventId = position.sourceEventId,
                        acquiredAt = position.acquiredAt,
                        quantity = position.quantity,
                        cost = position.cost,
                        legacyCarry = true
                    )
                }
            }
            legacyPositions.clear()
            legacyFlushed = true
        }

        fun removeFromLots(line: EventLineEntity, allocations: List<LotAllocationEntity>): CostVector {
            val itemLots = lots[line.itemId].orEmpty()
            require(itemLots.sumOf { it.quantity } >= line.quantity) { "${itemById[line.itemId]?.name ?: "物品"} 库存不足" }
            var remaining = line.quantity
            var removed = CostVector()

            fun take(lot: MutableLot, quantity: Long) {
                require(quantity > 0 && quantity <= lot.quantity) { "所选批次数量无效" }
                val cost = proportionalCost(lot.cost, quantity, lot.quantity)
                lot.quantity = Math.subtractExact(lot.quantity, quantity)
                lot.cost -= cost
                removed += cost
                remaining = Math.subtractExact(remaining, quantity)
            }

            if (allocations.isNotEmpty()) {
                require(allocations.sumOf { it.quantity } == line.quantity) { "手动批次数量与卖出数量不一致" }
                allocations.forEach { allocation ->
                    val lot = itemLots.firstOrNull {
                        it.sourceLineId == allocation.sourceLineId &&
                            (allocation.unitOrdinal == null || it.unitOrdinal == allocation.unitOrdinal)
                    } ?: error("所选买入批次已不存在")
                    take(lot, allocation.quantity)
                }
            } else {
                itemLots.sortedWith(
                    compareBy<MutableLot> { it.acquiredAt }
                        .thenBy { it.sourceEventId }
                        .thenBy { it.sourceLineId }
                        .thenBy { it.unitOrdinal ?: 0 }
                ).forEach { lot ->
                    if (remaining > 0 && lot.quantity > 0) take(lot, minOf(remaining, lot.quantity))
                }
            }
            require(remaining == 0L) { "库存批次不足" }
            lots[line.itemId]?.removeAll { it.quantity == 0L }
            return removed
        }

        fun processLegacy(view: EventView) {
            val event = view.event
            require(event.feeCents >= 0) { "手续费不能为负数" }
            when (event.type) {
                EventType.BUY -> {
                    val inputs = view.lines.filter { it.direction == LineDirection.IN }
                    require(inputs.isNotEmpty() && inputs.size == view.lines.size) { "买入流水格式无效" }
                    var gross = 0L
                    inputs.forEachIndexed { index, line ->
                        require(line.quantity > 0 && line.unitPriceCents > 0) { "买入数量和单价必须大于 0" }
                        val lineCost = Math.multiplyExact(line.quantity, line.unitPriceCents)
                        gross = Math.addExact(gross, lineCost)
                        val position = legacyPositions.getOrPut(line.itemId) { LegacyPosition() }
                        position.quantity = Math.addExact(position.quantity, line.quantity)
                        val fee = if (index == 0) event.feeCents else 0
                        position.cost += CostVector.of(event.accountType, Math.addExact(lineCost, fee))
                        position.sourceLineId = line.id
                        position.sourceEventId = event.id
                        position.acquiredAt = event.timestamp
                    }
                    val spent = Math.addExact(gross, event.feeCents)
                    bought = Math.addExact(bought, spent)
                    addBalance(event.accountType, -spent)
                }

                EventType.SELL -> {
                    val outputs = view.lines.filter { it.direction == LineDirection.OUT }
                    require(outputs.isNotEmpty() && outputs.size == view.lines.size) { "卖出流水格式无效" }
                    val requested = aggregateQuantities(outputs)
                    val insufficient = requested.entries.firstOrNull { (itemId, quantity) ->
                        (legacyPositions[itemId]?.quantity ?: 0) < quantity
                    }
                    require(insufficient == null) {
                        "${itemById[insufficient?.key]?.name ?: "物品"} 库存不足"
                    }
                    var gross = 0L
                    var removedCost = CostVector()
                    outputs.forEach { line ->
                        require(line.unitPriceCents > 0) { "卖出单价必须大于 0" }
                        gross = Math.addExact(gross, Math.multiplyExact(line.quantity, line.unitPriceCents))
                        val position = legacyPositions.getValue(line.itemId)
                        val lineCost = proportionalCost(position.cost, line.quantity, position.quantity)
                        position.quantity = Math.subtractExact(position.quantity, line.quantity)
                        position.cost -= lineCost
                        removedCost += lineCost
                    }
                    require(event.feeCents <= gross) { "手续费不能高于卖出金额" }
                    val net = Math.subtractExact(gross, event.feeCents)
                    sold = Math.addExact(sold, net)
                    addBalance(event.accountType, net)
                    recordSaleOutcome(event.id, removedCost, event.accountType, net)
                }

                EventType.CONVERT -> {
                    val outgoing = view.lines.filter { it.direction == LineDirection.OUT }
                    val incoming = view.lines.filter { it.direction == LineDirection.IN }
                    require(outgoing.isNotEmpty() && incoming.isNotEmpty()) { "转换必须同时包含转出物和产出物" }
                    val requested = aggregateQuantities(outgoing)
                    requested.forEach { (itemId, quantity) ->
                        require((legacyPositions[itemId]?.quantity ?: 0) >= quantity) {
                            "${itemById[itemId]?.name ?: "物品"} 在转换时库存不足"
                        }
                    }
                    var costPool = CostVector()
                    outgoing.forEach { line ->
                        val position = legacyPositions.getValue(line.itemId)
                        val lineCost = proportionalCost(position.cost, line.quantity, position.quantity)
                        position.quantity = Math.subtractExact(position.quantity, line.quantity)
                        position.cost -= lineCost
                        costPool += lineCost
                    }
                    allocateVector(costPool, incoming.map { it.quantity }).zip(incoming).forEach { (cost, line) ->
                        val position = legacyPositions.getOrPut(line.itemId) { LegacyPosition() }
                        position.quantity = Math.addExact(position.quantity, line.quantity)
                        position.cost += cost
                        position.sourceLineId = line.id
                        position.sourceEventId = event.id
                        position.acquiredAt = event.timestamp
                    }
                }

                EventType.ACCOUNT_ADJUSTMENT -> addBalance(event.accountType, event.accountDeltaCents)
            }
        }

        fun processV2(view: EventView) {
            val event = view.event
            require(event.feeCents >= 0) { "手续费不能为负数" }
            when (event.type) {
                EventType.BUY -> {
                    val lines = view.lines.filter { it.direction == LineDirection.IN }
                    require(lines.isNotEmpty() && lines.size == view.lines.size) { "买入流水格式无效" }
                    val grossByLine = lines.map { line ->
                        require(line.quantity > 0 && line.unitPriceCents > 0) { "买入数量和单价必须大于 0" }
                        Math.multiplyExact(line.quantity, line.unitPriceCents)
                    }
                    val gross = grossByLine.fold(0L, Math::addExact)
                    val fees = allocateLong(event.feeCents, grossByLine)
                    lines.forEachIndexed { index, line ->
                        val item = itemById[line.itemId] ?: error("流水包含不存在的物品")
                        addLot(
                            item,
                            line.id,
                            event.id,
                            event.timestamp,
                            line.quantity,
                            CostVector.of(event.accountType, Math.addExact(grossByLine[index], fees[index]))
                        )
                    }
                    val spent = Math.addExact(gross, event.feeCents)
                    bought = Math.addExact(bought, spent)
                    addBalance(event.accountType, -spent)
                }

                EventType.SELL -> {
                    val lines = view.lines.filter { it.direction == LineDirection.OUT }
                    require(lines.isNotEmpty() && lines.size == view.lines.size) { "卖出流水格式无效" }
                    val grossByLine = lines.map { line ->
                        require(line.quantity > 0 && line.unitPriceCents > 0) { "卖出数量和单价必须大于 0" }
                        Math.multiplyExact(line.quantity, line.unitPriceCents)
                    }
                    val gross = grossByLine.fold(0L, Math::addExact)
                    require(event.feeCents <= gross) { "手续费不能高于卖出金额" }
                    val fees = allocateLong(event.feeCents, grossByLine)
                    lines.forEachIndexed { index, line ->
                        val allocations = view.allocations.filter { it.eventLineId == line.id }
                        val cost = removeFromLots(line, allocations)
                        val net = Math.subtractExact(grossByLine[index], fees[index])
                        recordSaleOutcome(event.id, cost, event.accountType, net)
                    }
                    val net = Math.subtractExact(gross, event.feeCents)
                    sold = Math.addExact(sold, net)
                    addBalance(event.accountType, net)
                }

                EventType.CONVERT -> {
                    val outgoing = view.lines.filter { it.direction == LineDirection.OUT }
                    val incoming = view.lines.filter { it.direction == LineDirection.IN }
                    require(outgoing.isNotEmpty() && incoming.isNotEmpty()) { "转换必须同时包含转出物和产出物" }
                    require(view.lines.all { it.quantity > 0 && it.unitPriceCents == 0L }) { "转换流水格式无效" }
                    var costPool = CostVector()
                    outgoing.forEach { line ->
                        costPool += removeFromLots(line, view.allocations.filter { it.eventLineId == line.id })
                    }
                    allocateVector(costPool, incoming.map { it.quantity }).zip(incoming).forEach { (cost, line) ->
                        val item = itemById[line.itemId] ?: error("流水包含不存在的物品")
                        addLot(item, line.id, event.id, event.timestamp, line.quantity, cost)
                    }
                }

                EventType.ACCOUNT_ADJUSTMENT -> addBalance(event.accountType, event.accountDeltaCents)
            }
        }

        for (view in views.sortedWith(compareBy<EventView> { it.event.timestamp }.thenBy { it.event.id })) {
            if (view.event.status == EventStatus.VOIDED) continue
            val legacyBefore = legacyPositions.mapValues { (_, value) -> value.copy() }
            val lotsBefore = lots.mapValues { (_, value) -> value.map { it.copy() }.toMutableList() }
            val legacyFlushedBefore = legacyFlushed
            val fiatBalanceBefore = fiatBalance
            val walletBalanceBefore = walletBalance
            val boughtBefore = bought
            val soldBefore = sold
            val fiatPnlBefore = fiatPnl
            val walletPnlBefore = walletPnl
            val crossOutcomeSizeBefore = crossAccountOutcomes.size
            fun rollbackEvent() {
                legacyPositions.clear()
                legacyPositions.putAll(legacyBefore)
                lots.clear()
                lots.putAll(lotsBefore)
                legacyFlushed = legacyFlushedBefore
                fiatBalance = fiatBalanceBefore
                walletBalance = walletBalanceBefore
                bought = boughtBefore
                sold = soldBefore
                fiatPnl = fiatPnlBefore
                walletPnl = walletPnlBefore
                while (crossAccountOutcomes.size > crossOutcomeSizeBefore) crossAccountOutcomes.removeAt(crossAccountOutcomes.lastIndex)
            }
            try {
                if (view.event.legacy) {
                    require(!legacyFlushed) { "旧版流水不能排在新版流水之后" }
                    processLegacy(view)
                } else {
                    flushLegacy()
                    processV2(view)
                }
            } catch (cause: IllegalArgumentException) {
                rollbackEvent()
                error = cause.message ?: "流水数据无效"
                break
            } catch (cause: IllegalStateException) {
                rollbackEvent()
                error = cause.message ?: "流水状态无效"
                break
            } catch (_: ArithmeticException) {
                rollbackEvent()
                error = "流水数值过大，无法安全计算"
                break
            }
        }

        runCatching { flushLegacy() }.onFailure { if (error == null) error = it.message ?: "旧数据承接失败" }

        val holdings = items.mapNotNull { item ->
            val itemLots = lots[item.id].orEmpty().filter { it.quantity > 0 }
            if (itemLots.isEmpty()) return@mapNotNull null
            val quantity = itemLots.fold(0L) { total, lot -> Math.addExact(total, lot.quantity) }
            val cost = itemLots.fold(CostVector()) { total, lot -> total + lot.cost }
            Holding(
                item = item,
                quantity = quantity,
                cost = cost,
                lots = itemLots.map { lot ->
                    HoldingLot(
                        sourceLineId = lot.sourceLineId,
                        sourceEventId = lot.sourceEventId,
                        acquiredAt = lot.acquiredAt,
                        remainingQuantity = lot.quantity,
                        cost = lot.cost,
                        unitOrdinal = lot.unitOrdinal,
                        legacyCarry = lot.legacyCarry
                    )
                },
                quote = latestQuotes[item.id]
            )
        }.sortedWith(
            compareByDescending<Holding> { it.marketNetValueCents ?: -1L }
                .thenByDescending { it.costCents }
        )

        val totalCost = holdings.fold(0L) { total, holding -> Math.addExact(total, holding.costCents) }
        val fiatCost = holdings.fold(0L) { total, holding -> Math.addExact(total, holding.cost.fiatCents) }
        val walletCost = holdings.fold(0L) { total, holding -> Math.addExact(total, holding.cost.walletCents) }
        val grossMarket = holdings.mapNotNull { it.marketGrossValueCents }.fold(0L, Math::addExact)
        val netMarket = holdings.mapNotNull { it.marketNetValueCents }.fold(0L, Math::addExact)

        return LedgerSnapshot(
            holdings = holdings,
            events = views.sortedWith(compareByDescending<EventView> { it.event.timestamp }.thenByDescending { it.event.id }),
            totalBoughtCents = bought,
            totalSoldCents = sold,
            totalRealizedPnlCents = Math.addExact(fiatPnl, walletPnl),
            totalCostCents = totalCost,
            fiatBalanceCents = fiatBalance,
            walletBalanceCents = walletBalance,
            fiatRealizedPnlCents = fiatPnl,
            walletRealizedPnlCents = walletPnl,
            fiatHoldingCostCents = fiatCost,
            walletHoldingCostCents = walletCost,
            marketGrossValueCents = grossMarket,
            marketNetValueCents = netMarket,
            unpricedHoldingCount = holdings.count { it.quote == null },
            crossAccountOutcomes = crossAccountOutcomes,
            error = error
        )
    }

    private fun aggregateQuantities(lines: List<EventLineEntity>): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        for (line in lines) {
            require(line.quantity > 0) { "数量必须大于 0" }
            result[line.itemId] = Math.addExact(result[line.itemId] ?: 0, line.quantity)
        }
        return result
    }

    private fun allocateLong(total: Long, weights: List<Long>): List<Long> {
        require(total >= 0 && weights.isNotEmpty() && weights.all { it >= 0 })
        if (total == 0L) return List(weights.size) { 0 }
        val weightTotal = weights.fold(0L, Math::addExact)
        require(weightTotal > 0) { "无法分摊费用" }
        var allocated = 0L
        return weights.mapIndexed { index, weight ->
            val value = if (index == weights.lastIndex) Math.subtractExact(total, allocated)
            else BigInteger.valueOf(total)
                .multiply(BigInteger.valueOf(weight))
                .divide(BigInteger.valueOf(weightTotal))
                .toLong()
            allocated = Math.addExact(allocated, value)
            value
        }
    }

    private fun allocateVector(total: CostVector, weights: List<Long>): List<CostVector> {
        val fiat = allocateLong(total.fiatCents, weights)
        val wallet = allocateLong(total.walletCents, weights)
        return weights.indices.map { CostVector(fiat[it], wallet[it]) }
    }
}
