package daytrader.e2e.support

import daytrader.domain.LiquidityBucketLogic
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.withOrdersPlacedForSession
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.platform.currentSessionDateIso

object E2ELiquidityAllocatorHelper {
    fun allocatorEligibleDeployment(
        symbol: String = E2ETestFixtures.SYMBOL,
        deploymentId: String = E2ETestFixtures.DEPLOYMENT_ID,
    ): StrategyDeployment {
        val plan = E2EBracketHelper.liquidityPlan(symbol = symbol)
        return E2ETestFixtures.runningDeployment(symbol = symbol)
            .copy(id = deploymentId)
            .withOrdersPlacedForSession(plan = plan)
    }

    fun bracketOpenOrders(symbol: String = E2ETestFixtures.SYMBOL): List<WorkingOrder> {
        val entryId = 1_000
        val tpId = 1_001
        val stopId = 1_002
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
}
