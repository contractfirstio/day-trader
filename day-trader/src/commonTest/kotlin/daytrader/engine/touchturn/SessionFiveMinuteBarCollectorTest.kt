package daytrader.engine.touchturn

import daytrader.domain.DeploymentStatus
import daytrader.domain.OhlcBar
import daytrader.domain.SessionStopParams
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import daytrader.domain.onSessionStopped
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionFiveMinuteBarCollectorTest {
    @Test
    fun collector_appendsFiveMinuteBars_whenConfirmationDisabled() = runBlocking {
        val bars = listOf(
            OhlcBar(open = 100.0, high = 101.0, low = 99.5, close = 100.8, time = "20260522  09:30:00"),
            OhlcBar(open = 100.8, high = 102.0, low = 100.5, close = 101.5, time = "20260522  09:35:00"),
            OhlcBar(open = 101.5, high = 103.0, low = 101.0, close = 102.2, time = "20260522  09:40:00"),
        )
        val gateway = FakeBrokerGateway(brokerId = BrokerId.EMULATOR).apply {
            fiveMinuteBarsFetchResult = Result.success(bars)
        }
        val repository = InMemoryStrategyDeploymentRepository()
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 1_000
        ).onSessionStarted("2026-05-22", touchTurnStartedBy = TouchTurnSessionStartedBy.MANUAL)
            .copy(
                status = DeploymentStatus.RUNNING,
                touchTurnSession = TouchTurnSessionContext(
                    sessionDate = "2026-05-22",
                    status = TouchTurnCandleStatus.READY,
                    openingBarTime = "20260522  09:30:00",
                    candle = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = "20260522  09:30:00"),
                    marketZoneId = "America/New_York",
                    decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
                    fiveMinuteConfirmation = null
                )
            )
        repository.add(deployment)

        val collectorJob = launch {
            SessionFiveMinuteBarCollector(
                marketData = BrokerGatewayMarketDataProvider(gateway),
                repository = repository,
                nowEpochMillis = { System.currentTimeMillis() },
                delayMillis = { delay(it) },
                pollIntervalMs = { 20L }
            ).runUntilSessionEnds(deployment.id)
        }

        withTimeout(5_000) {
            while (repository.deployments.value.single().touchTurnSession?.sessionFiveMinuteBars.isNullOrEmpty()) {
                delay(20)
            }
        }
        val collected = repository.deployments.value.single().touchTurnSession!!.sessionFiveMinuteBars
        assertTrue(collected.size >= 2, "expected session 5m bars, got ${collected.size}")

        val stopped = repository.deployments.value.single().onSessionStopped(
            stopParams = SessionStopParams(
                stopTrigger = TouchTurnSessionStopTrigger.MANUAL,
                brokerId = BrokerId.EMULATOR,
                brokerKind = BrokerKind.EMULATOR,
                brokerUnrealizedPnLAtStop = null
            )
        )
        repository.update(deployment.id) { stopped.copy(status = DeploymentStatus.STOPPED) }
        collectorJob.cancel()

        val recordBars = stopped.sessionHistory.single().touchTurnRunRecord?.sessionFiveMinuteBars
        assertTrue(recordBars?.isNotEmpty() == true, "run record should freeze session 5m bars")
        assertTrue(stopped.touchTurnSession?.fiveMinuteConfirmation == null)
    }
}
