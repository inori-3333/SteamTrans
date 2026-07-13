package com.steamtrans.ledger.data

object LedgerCalculator {
    private data class Position(var quantity: Long = 0, var cost: Long = 0)

    fun calculate(items: List<ItemEntity>, views: List<EventView>): LedgerSnapshot {
        val positions = mutableMapOf<Long, Position>()
        var bought = 0L
        var sold = 0L
        var realizedPnl = 0L
        var error: String? = null

        eventLoop@ for (view in views.sortedWith(compareBy<EventView> { it.event.timestamp }.thenBy { it.event.id })) {
            val event = view.event
            val nextPositions = positions.mapValuesTo(mutableMapOf()) { (_, p) -> p.copy() }
            var nextBought = bought
            var nextSold = sold
            var nextRealizedPnl = realizedPnl
            try {
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
                            val p = nextPositions.getOrPut(line.itemId) { Position() }
                            p.quantity = Math.addExact(p.quantity, line.quantity)
                            val fee = if (index == 0) event.feeCents else 0
                            p.cost = Math.addExact(p.cost, Math.addExact(lineCost, fee))
                        }
                        nextBought = Math.addExact(bought, Math.addExact(gross, event.feeCents))
                    }
                    EventType.SELL -> {
                        val outputs = view.lines.filter { it.direction == LineDirection.OUT }
                        require(outputs.isNotEmpty() && outputs.size == view.lines.size) { "卖出流水格式无效" }
                        val requested = aggregateQuantities(outputs)
                        val insufficient = requested.entries.firstOrNull { (itemId, quantity) ->
                            (nextPositions[itemId]?.quantity ?: 0) < quantity
                        }
                        if (insufficient != null) {
                            error = "${items.firstOrNull { it.id == insufficient.key }?.name ?: "物品"} 在 ${event.timestamp} 的库存不足"
                            break@eventLoop
                        }
                        var gross = 0L
                        var removedCostTotal = 0L
                        for (line in outputs) {
                            require(line.unitPriceCents > 0) { "卖出单价必须大于 0" }
                            gross = Math.addExact(gross, Math.multiplyExact(line.quantity, line.unitPriceCents))
                            val p = nextPositions.getValue(line.itemId)
                            val removedCost = proportionalCost(p.cost, line.quantity, p.quantity)
                            p.quantity = Math.subtractExact(p.quantity, line.quantity)
                            p.cost = Math.subtractExact(p.cost, removedCost)
                            removedCostTotal = Math.addExact(removedCostTotal, removedCost)
                        }
                        require(event.feeCents <= gross) { "手续费不能高于卖出金额" }
                        val netIncome = Math.subtractExact(gross, event.feeCents)
                        nextSold = Math.addExact(sold, netIncome)
                        nextRealizedPnl = Math.addExact(realizedPnl, Math.subtractExact(netIncome, removedCostTotal))
                    }
                    EventType.CONVERT -> {
                        val outgoing = view.lines.filter { it.direction == LineDirection.OUT }
                        val incoming = view.lines.filter { it.direction == LineDirection.IN }
                        require(outgoing.isNotEmpty() && incoming.isNotEmpty()) { "转换必须同时包含转出物和产出物" }
                        require(view.lines.all { it.quantity > 0 && it.unitPriceCents == 0L }) { "转换流水格式无效" }
                        val requested = aggregateQuantities(outgoing)
                        val insufficient = requested.entries.firstOrNull { (itemId, quantity) ->
                            (nextPositions[itemId]?.quantity ?: 0) < quantity
                        }
                        if (insufficient != null) {
                            error = "${items.firstOrNull { it.id == insufficient.key }?.name ?: "物品"} 在转换时库存不足"
                            break@eventLoop
                        }
                        var costPool = 0L
                        for (line in outgoing) {
                            val p = nextPositions.getValue(line.itemId)
                            val removedCost = proportionalCost(p.cost, line.quantity, p.quantity)
                            p.quantity = Math.subtractExact(p.quantity, line.quantity)
                            p.cost = Math.subtractExact(p.cost, removedCost)
                            costPool = Math.addExact(costPool, removedCost)
                        }
                        val weight = incoming.fold(0L) { total, line -> Math.addExact(total, line.quantity) }
                        var allocated = 0L
                        incoming.forEachIndexed { index, line ->
                            val cost = if (index == incoming.lastIndex) {
                                Math.subtractExact(costPool, allocated)
                            } else {
                                proportionalCost(costPool, line.quantity, weight)
                            }
                            allocated = Math.addExact(allocated, cost)
                            val p = nextPositions.getOrPut(line.itemId) { Position() }
                            p.quantity = Math.addExact(p.quantity, line.quantity)
                            p.cost = Math.addExact(p.cost, cost)
                        }
                    }
                }
            } catch (cause: IllegalArgumentException) {
                error = cause.message ?: "流水数据无效"
                break
            } catch (_: ArithmeticException) {
                error = "流水数值过大，无法安全计算"
                break
            }
            positions.clear()
            positions.putAll(nextPositions)
            bought = nextBought
            sold = nextSold
            realizedPnl = nextRealizedPnl
        }

        val holdings = items.mapNotNull { item ->
            val p = positions[item.id] ?: return@mapNotNull null
            if (p.quantity <= 0) null else Holding(item, p.quantity, p.cost)
        }.sortedByDescending { it.costCents }
        return LedgerSnapshot(
            holdings = holdings,
            events = views.sortedWith(compareByDescending<EventView> { it.event.timestamp }.thenByDescending { it.event.id }),
            totalBoughtCents = bought,
            totalSoldCents = sold,
            totalRealizedPnlCents = realizedPnl,
            totalCostCents = holdings.sumOf { it.costCents },
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
}
