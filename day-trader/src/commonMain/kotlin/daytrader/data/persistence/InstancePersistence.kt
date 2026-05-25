package daytrader.data.persistence

import daytrader.domain.ActiveExecution
import daytrader.domain.ExecutionState
import daytrader.domain.InstanceStatus
import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyRun
import daytrader.domain.TradeSide

object InstancePersistence {
    fun toDomain(record: InstanceRecord): StrategyInstance =
        StrategyInstance(
            id = record.id,
            strategyType = record.strategy,
            status = parseInstanceStatus(record.status),
            symbol = record.configuration.symbol,
            maxDollars = record.configuration.maxAtRisk,
            autoStartOnMarketOpen = record.configuration.autoStartOnMarketOpen,
            lastAutoStartSessionDate = record.configuration.lastAutoStartSessionDate,
            live = toLiveDomain(record.live),
            performance = record.performance.map { toPerformanceDomain(record.id, it) },
            touchTurnSession = TouchTurnPersistence.toDomain(record.touchTurnSession)
        )

    fun toRecord(instance: StrategyInstance): InstanceRecord =
        InstanceRecord(
            id = instance.id,
            strategy = instance.strategyType,
            status = instanceStatusLabel(instance.status),
            configuration = ConfigurationRecord(
                symbol = instance.symbol,
                maxAtRisk = instance.maxDollars,
                autoStartOnMarketOpen = instance.autoStartOnMarketOpen,
                lastAutoStartSessionDate = instance.lastAutoStartSessionDate
            ),
            live = toLiveRecord(instance.live),
            performance = instance.performance.map(::toPerformanceRecord),
            touchTurnSession = TouchTurnPersistence.toRecord(instance.touchTurnSession)
        )

    private fun toPerformanceDomain(instanceId: String, record: PerformanceDayRecord): StrategyRun =
        StrategyRun(
            id = record.id.ifBlank { "run-$instanceId-${record.date}" },
            date = record.date,
            startedAt = record.startedAt,
            stoppedAt = record.stoppedAt,
            pnl = record.pnl,
            trades = record.trades,
            maxAtRisk = record.maxAtRisk,
            status = parseRunStatus(record.status),
            hadLiquidityCandle = record.hadLiquidityCandle,
            ordersPlacedForCandle = record.ordersPlacedForCandle,
            positionOpened = record.positionOpened,
            sessionTrades = record.sessionTrades.map(::toSessionTradeDomain)
        )

    private fun toPerformanceRecord(day: StrategyRun): PerformanceDayRecord =
        PerformanceDayRecord(
            id = day.id,
            date = day.date,
            startedAt = day.startedAt,
            stoppedAt = day.stoppedAt,
            pnl = day.pnl,
            trades = day.trades,
            maxAtRisk = day.maxAtRisk,
            status = runStatusLabel(day.status),
            hadLiquidityCandle = day.hadLiquidityCandle,
            ordersPlacedForCandle = day.ordersPlacedForCandle,
            positionOpened = day.positionOpened,
            sessionTrades = day.sessionTrades.map(::toSessionTradeRecord)
        )

    private fun toSessionTradeDomain(record: SessionTradeRecord): SessionTrade =
        SessionTrade(
            execId = record.execId,
            orderId = record.orderId,
            permId = record.permId,
            parentOrderId = record.parentOrderId,
            side = record.side,
            quantity = record.quantity,
            price = record.price,
            time = record.time,
            currency = record.currency,
            commission = record.commission,
            realizedPnL = record.realizedPnL
        )

    private fun toSessionTradeRecord(trade: SessionTrade): SessionTradeRecord =
        SessionTradeRecord(
            execId = trade.execId,
            orderId = trade.orderId,
            permId = trade.permId,
            parentOrderId = trade.parentOrderId,
            side = trade.side,
            quantity = trade.quantity,
            price = trade.price,
            time = trade.time,
            currency = trade.currency,
            commission = trade.commission,
            realizedPnL = trade.realizedPnL
        )

    private fun toLiveDomain(record: LiveRecord): ActiveExecution =
        ActiveExecution(
            state = parseExecutionState(record.state),
            side = parseTradeSide(record.side),
            quantity = record.quantity,
            entryPrice = record.entry,
            stopPrice = record.stop,
            targetPrice = record.target,
            marketPrice = record.market,
            orderStatus = record.orderStatus,
            updatedAt = record.updatedAt
        )

    private fun toLiveRecord(live: ActiveExecution): LiveRecord =
        LiveRecord(
            state = executionStateLabel(live.state),
            side = tradeSideLabel(live.side),
            quantity = live.quantity,
            entry = live.entryPrice,
            stop = live.stopPrice,
            target = live.targetPrice,
            market = live.marketPrice,
            orderStatus = live.orderStatus,
            updatedAt = live.updatedAt
        )

    private fun parseInstanceStatus(value: String): InstanceStatus =
        when (value.lowercase()) {
            "running" -> InstanceStatus.RUNNING
            "stopped" -> InstanceStatus.STOPPED
            "error" -> InstanceStatus.ERROR
            else -> runCatching { InstanceStatus.valueOf(value.uppercase()) }
                .getOrDefault(InstanceStatus.STOPPED)
        }

    private fun instanceStatusLabel(status: InstanceStatus): String = when (status) {
        InstanceStatus.RUNNING -> "running"
        InstanceStatus.STOPPED -> "stopped"
        InstanceStatus.ERROR -> "error"
    }

    private fun parseRunStatus(value: String): RunStatus =
        when (value.lowercase()) {
            "in_progress", "in progress" -> RunStatus.IN_PROGRESS
            "closed" -> RunStatus.CLOSED
            else -> runCatching { RunStatus.valueOf(value.uppercase()) }
                .getOrDefault(RunStatus.CLOSED)
        }

    private fun runStatusLabel(status: RunStatus): String = when (status) {
        RunStatus.IN_PROGRESS -> "in_progress"
        RunStatus.CLOSED -> "closed"
    }

    private fun parseExecutionState(value: String): ExecutionState =
        when (value.lowercase()) {
            "flat" -> ExecutionState.FLAT
            "working" -> ExecutionState.WORKING
            "filled" -> ExecutionState.FILLED
            else -> runCatching { ExecutionState.valueOf(value.uppercase()) }
                .getOrDefault(ExecutionState.FLAT)
        }

    private fun executionStateLabel(state: ExecutionState): String = when (state) {
        ExecutionState.FLAT -> "flat"
        ExecutionState.WORKING -> "working"
        ExecutionState.FILLED -> "filled"
    }

    private fun parseTradeSide(value: String): TradeSide =
        when (value.lowercase()) {
            "long" -> TradeSide.LONG
            "short" -> TradeSide.SHORT
            else -> runCatching { TradeSide.valueOf(value.uppercase()) }
                .getOrDefault(TradeSide.LONG)
        }

    private fun tradeSideLabel(side: TradeSide): String = when (side) {
        TradeSide.LONG -> "long"
        TradeSide.SHORT -> "short"
    }
}
