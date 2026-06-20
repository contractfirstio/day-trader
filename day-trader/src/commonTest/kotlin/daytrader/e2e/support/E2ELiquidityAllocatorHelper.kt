package daytrader.e2e.support

import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.currentConfigurationFingerprint
import daytrader.domain.withOrdersPlacedForSession
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.platform.currentSessionDateIso

object E2ELiquidityAllocatorHelper {
    fun allocatorEligibleDeployment(
        symbol: String = E2ETestFixtures.SYMBOL,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
        winDays: Int = 0,
        lossDays: Int = 0,
    ): StrategyDeployment {
        val plan = E2EBracketHelper.liquidityPlan(symbol = symbol)
        var deployment = E2ETestFixtures.runningDeployment(symbol = symbol)
            .copy(id = deploymentId)
        if (winDays > 0 || lossDays > 0) {
            deployment = deployment.copy(
                sessionHistory = winLossSessionHistory(deployment, winDays, lossDays),
            )
        }
        return deployment.withOrdersPlacedForSession(plan = plan)
    }

    fun winLossSessionHistory(
        deployment: StrategyDeployment,
        winDays: Int,
        lossDays: Int,
    ): List<StrategySession> {
        val fingerprint = deployment.currentConfigurationFingerprint()
        val sessions = mutableListOf<StrategySession>()
        var dayOffset = 1
        repeat(winDays) { index ->
            sessions += closedTradedSession(
                id = "win-$index",
                date = historySessionDate(dayOffset++),
                pnl = 10.0,
                configurationFingerprint = fingerprint,
            )
        }
        repeat(lossDays) { index ->
            sessions += closedTradedSession(
                id = "loss-$index",
                date = historySessionDate(dayOffset++),
                pnl = -5.0,
                configurationFingerprint = fingerprint,
            )
        }
        return sessions
    }

    fun bracketOpenOrders(
        symbol: String = E2ETestFixtures.SYMBOL,
        orderIdBase: Int = 1_000,
    ): List<WorkingOrder> {
        val entryId = orderIdBase
        val tpId = orderIdBase + 1
        val stopId = orderIdBase + 2
        return listOf(
            WorkingOrder(
                orderId = entryId,
                parentOrderId = 0,
                symbol = symbol.uppercase(),
                action = "BUY",
                quantity = 5,
                filled = 0,
                remaining = 5,
                orderType = "LMT",
                limitPrice = 100.0,
                stopPrice = null,
                status = "Submitted",
                currency = "USD",
            ),
            WorkingOrder(
                orderId = tpId,
                parentOrderId = entryId,
                symbol = symbol.uppercase(),
                action = "SELL",
                quantity = 5,
                filled = 0,
                remaining = 5,
                orderType = "LMT",
                limitPrice = 101.0,
                stopPrice = null,
                status = "Submitted",
                currency = "USD",
            ),
            WorkingOrder(
                orderId = stopId,
                parentOrderId = entryId,
                symbol = symbol.uppercase(),
                action = "SELL",
                quantity = 5,
                filled = 0,
                remaining = 5,
                orderType = "STP",
                limitPrice = null,
                stopPrice = 99.0,
                status = "Submitted",
                currency = "USD",
            ),
        )
    }

    fun touchableQuote(symbol: String = E2ETestFixtures.SYMBOL): LiveQuote = LiveQuote(
        symbol = symbol.uppercase(),
        bid = 100.0,
        ask = 100.05,
        last = 100.02,
        quoteEpochMillis = System.currentTimeMillis(),
    )

    fun creditUsdBucket(
        repository: InMemoryLiquidityBucketRepository,
        amount: Int = 1_000,
        sessionDate: String = currentSessionDateIso(),
    ) {
        repository.update { state ->
            LiquidityBucketLogic.creditSession(
                state = state,
                currencyCode = "USD",
                sessionDate = sessionDate,
                sessionId = "credit-${sessionDate}",
                deploymentId = "credit-dep",
                symbol = E2ETestFixtures.SYMBOL,
                amount = amount,
                outcome = TouchTurnSessionOutcome.NO_TRADE_DOJI,
                creditedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun closedTradedSession(
        id: String,
        date: String,
        pnl: Double,
        configurationFingerprint: String,
    ): StrategySession = StrategySession(
        id = id,
        date = date,
        startedAt = "${date}T09:30:00",
        stoppedAt = "${date}T10:00:00",
        pnl = pnl,
        trades = 1,
        maxAtRisk = 500,
        status = SessionStatus.CLOSED,
        positionOpened = true,
        configurationFingerprint = configurationFingerprint,
    )

    private fun historySessionDate(dayOffset: Int): String {
        val day = dayOffset.coerceIn(1, 28)
        return "2026-05-${day.toString().padStart(2, '0')}"
    }
}
