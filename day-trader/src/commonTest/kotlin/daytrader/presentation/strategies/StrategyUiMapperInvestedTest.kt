package daytrader.presentation.strategies

import daytrader.domain.ActiveExecution
import daytrader.domain.DeploymentStatus
import daytrader.domain.ExecutionState
import daytrader.domain.SessionStatus
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.gateway.AccountPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrategyUiMapperInvestedTest {
    private val sessionDate = "2026-06-14"

    @Test
    fun toRowUi_openPosition_investedFromLiveEntryNotional() {
        val instance = deployment(
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 25,
                entryPrice = 40.0,
                stopPrice = 38.0,
                targetPrice = 44.0,
            ),
        )
        val row = StrategyUiMapper.toRowUi(
            instance = instance,
            sessionDate = sessionDate,
            brokerUnrealizedPnL = 12.0,
            brokerPosition = AccountPosition(
                account = "DU1",
                symbol = "TSLA",
                companyName = "Tesla",
                quantity = 25,
                avgPrice = 40.0,
                marketPrice = 40.48,
                priorClose = 39.0,
                totalUnrealizedPnL = 12.0,
                currency = "USD",
            ),
        )
        assertEquals(1_000.0, row.investedNotional)
    }

    @Test
    fun toRowUi_openPosition_prefersSessionEntryFillNotional() {
        val instance = deployment(
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 100,
                entryPrice = 11.0,
                stopPrice = 10.0,
                targetPrice = 12.0,
            ),
            sessions = listOf(
                StrategySession(
                    id = "live",
                    date = sessionDate,
                    startedAt = "${sessionDate}T09:30:00",
                    stoppedAt = "",
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 1000,
                    status = SessionStatus.IN_PROGRESS,
                    positionOpened = true,
                    sessionTrades = listOf(
                        SessionTrade(
                            execId = "e1",
                            orderId = 1,
                            permId = 1L,
                            parentOrderId = 0,
                            side = "BUY",
                            quantity = 40,
                            price = 10.0,
                            time = "${sessionDate}T09:35:00",
                            currency = "USD",
                            realizedPnL = 0.0,
                        ),
                        SessionTrade(
                            execId = "e2",
                            orderId = 1,
                            permId = 1L,
                            parentOrderId = 0,
                            side = "BUY",
                            quantity = 60,
                            price = 12.0,
                            time = "${sessionDate}T09:36:00",
                            currency = "USD",
                            realizedPnL = 0.0,
                        ),
                    ),
                ),
            ),
        )
        val row = StrategyUiMapper.toRowUi(
            instance = instance,
            sessionDate = sessionDate,
            brokerUnrealizedPnL = 5.0,
            brokerPosition = AccountPosition(
                account = "DU1",
                symbol = "TSLA",
                companyName = "Tesla",
                quantity = 100,
                avgPrice = 11.2,
                marketPrice = 11.25,
                priorClose = 11.0,
                totalUnrealizedPnL = 5.0,
                currency = "USD",
            ),
        )
        assertEquals(1_120.0, row.investedNotional)
    }

    @Test
    fun toRowUi_emulatorOpenPosition_flatLive_investedFromBrokerAvgCost() {
        val instance = deployment(live = ActiveExecution.flat())
        val row = StrategyUiMapper.toRowUi(
            instance = instance,
            sessionDate = sessionDate,
            brokerUnrealizedPnL = 12.0,
            brokerPosition = AccountPosition(
                account = "DU1",
                symbol = "TSLA",
                companyName = "Tesla",
                quantity = 25,
                avgPrice = 40.0,
                marketPrice = 40.48,
                priorClose = 39.0,
                totalUnrealizedPnL = 12.0,
                currency = "USD",
            ),
        )
        assertEquals(1_000.0, row.investedNotional)
    }

    @Test
    fun toRowUi_noOpenPosition_omitsInvested() {
        val row = StrategyUiMapper.toRowUi(
            instance = deployment(live = ActiveExecution.flat()),
            sessionDate = sessionDate,
        )
        assertNull(row.investedNotional)
    }

    private fun deployment(
        live: ActiveExecution,
        sessions: List<StrategySession> = emptyList(),
    ) = StrategyDeployment(
        id = "d1",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = DeploymentStatus.RUNNING,
        symbol = "TSLA",
        maxDollars = 1000,
        live = live,
        sessionHistory = sessions,
    )
}
