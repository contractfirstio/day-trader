package daytrader.data.persistence

import daytrader.domain.ActiveExecution
import daytrader.domain.ExecutionState
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.MarketSource
import daytrader.domain.StrategyDeployment
import daytrader.domain.SessionTrade
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TradeSide

object DeploymentPersistence {
    fun toDomain(record: DeploymentRecord): StrategyDeployment =
        StrategyDeployment(
            id = record.id,
            strategyType = record.strategy,
            status = parseDeploymentStatus(record.status),
            symbol = record.configuration.symbol,
            marketZoneId = record.configuration.marketZoneId,
            currencyCode = record.configuration.currencyCode,
            marketSource = parseMarketSource(record.configuration.marketSource),
            companyName = record.configuration.companyName,
            instrument = InstrumentIdentityPersistence.toDomain(record.configuration.instrument),
            maxDollars = record.configuration.maxAtRisk,
            autoStartOnMarketOpen = record.configuration.autoStartOnMarketOpen,
            lastAutoStartSessionDate = record.configuration.lastAutoStartSessionDate,
            live = toLiveDomain(record.live),
            sessionHistory = record.sessionHistory.map { toSessionHistoryDomain(record.id, it) },
            touchTurnSession = TouchTurnPersistence.toDomain(record.touchTurnSession)
        )

    fun toRecord(instance: StrategyDeployment): DeploymentRecord =
        DeploymentRecord(
            id = instance.id,
            strategy = instance.strategyType,
            status = deploymentStatusLabel(instance.status),
            configuration = ConfigurationRecord(
                symbol = instance.symbol,
                maxAtRisk = instance.maxDollars,
                autoStartOnMarketOpen = instance.autoStartOnMarketOpen,
                lastAutoStartSessionDate = instance.lastAutoStartSessionDate,
                marketZoneId = instance.marketZoneId,
                currencyCode = instance.currencyCode,
                marketSource = marketSourceLabel(instance.marketSource),
                companyName = instance.companyName,
                instrument = InstrumentIdentityPersistence.toRecord(instance.instrument)
            ),
            live = toLiveRecord(instance.live),
            sessionHistory = instance.sessionHistory.map(::toSessionHistoryRecord),
            touchTurnSession = TouchTurnPersistence.toRecord(instance.touchTurnSession)
        )

    private fun toSessionHistoryDomain(instanceId: String, record: SessionHistoryRecord): StrategySession =
        StrategySession(
            id = record.id.ifBlank { "session-$instanceId-${record.date}" },
            date = record.date,
            startedAt = record.startedAt,
            stoppedAt = record.stoppedAt,
            pnl = record.pnl,
            trades = record.trades,
            maxAtRisk = record.maxAtRisk,
            status = parseSessionStatus(record.status),
            hadLiquidityCandle = record.hadLiquidityCandle,
            ordersPlacedForCandle = record.ordersPlacedForCandle,
            positionOpened = record.positionOpened,
            sessionTrades = record.sessionTrades.map(::toSessionTradeDomain),
            touchTurnMilestones = record.touchTurnMilestones?.let(TouchTurnPersistence::milestonesToDomain),
            touchTurnStartedBy = record.touchTurnStartedBy?.let { value ->
                runCatching { TouchTurnSessionStartedBy.valueOf(value.uppercase()) }.getOrNull()
            },
            touchTurnRunRecord = TouchTurnRunPersistence.toDomain(record.touchTurnRunRecord)
        )

    private fun toSessionHistoryRecord(day: StrategySession): SessionHistoryRecord =
        SessionHistoryRecord(
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
            sessionTrades = day.sessionTrades.map(::toSessionTradeRecord),
            touchTurnMilestones = day.touchTurnMilestones?.let(TouchTurnPersistence::milestonesToRecord),
            touchTurnStartedBy = day.touchTurnStartedBy?.name?.lowercase(),
            touchTurnRunRecord = TouchTurnRunPersistence.toRecord(day.touchTurnRunRecord)
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

    private fun parseDeploymentStatus(value: String): DeploymentStatus =
        when (value.lowercase()) {
            "running" -> DeploymentStatus.RUNNING
            "stopped" -> DeploymentStatus.STOPPED
            "error" -> DeploymentStatus.ERROR
            else -> runCatching { DeploymentStatus.valueOf(value.uppercase()) }
                .getOrDefault(DeploymentStatus.STOPPED)
        }

    private fun deploymentStatusLabel(status: DeploymentStatus): String = when (status) {
        DeploymentStatus.RUNNING -> "running"
        DeploymentStatus.STOPPED -> "stopped"
        DeploymentStatus.ERROR -> "error"
    }

    private fun parseSessionStatus(value: String): SessionStatus =
        when (value.lowercase()) {
            "in_progress", "in progress" -> SessionStatus.IN_PROGRESS
            "closed" -> SessionStatus.CLOSED
            else -> runCatching { SessionStatus.valueOf(value.uppercase()) }
                .getOrDefault(SessionStatus.CLOSED)
        }

    private fun runStatusLabel(status: SessionStatus): String = when (status) {
        SessionStatus.IN_PROGRESS -> "in_progress"
        SessionStatus.CLOSED -> "closed"
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

    private fun parseMarketSource(value: String?): MarketSource =
        when (value?.lowercase()) {
            "user" -> MarketSource.USER
            "ib" -> MarketSource.IB
            "symbol_inferred", "symbol inferred" -> MarketSource.SYMBOL_INFERRED
            "legacy_inferred", "legacy inferred", null, "" -> MarketSource.LEGACY_INFERRED
            else -> runCatching { MarketSource.valueOf(value.uppercase()) }
                .getOrDefault(MarketSource.LEGACY_INFERRED)
        }

    private fun marketSourceLabel(source: MarketSource): String = when (source) {
        MarketSource.USER -> "user"
        MarketSource.IB -> "ib"
        MarketSource.SYMBOL_INFERRED -> "symbol_inferred"
        MarketSource.LEGACY_INFERRED -> "legacy_inferred"
    }
}
