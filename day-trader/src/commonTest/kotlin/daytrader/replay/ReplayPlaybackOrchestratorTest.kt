package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.defaultStrategyDeployment
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.TouchTurnEvent
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ReplayPlaybackOrchestratorTest {
    @Test
    fun fastForwardOpeningBar_advancesClockPastBarEnd() = runBlocking {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val runtime = ReplayHybridRuntime(bundle, clock, scope)
        val repository = seededRepository(bundle.deploymentId, bundle.symbol)
        val engine = RecordingEngine()
        runtime.playbackOrchestrator.attach(engine, repository)

        runtime.playbackOrchestrator.fastForwardOpeningBar(
            instanceId = bundle.deploymentId,
            formingWallDurationMs = 0L
        )

        val session = repository.deployments.value.single().touchTurnSession!!
        val barEnd = TouchTurnLogic.barEndEpochMillis(session.openingBarTime!!, session.marketZoneId)!!
        val targetMs = barEnd + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + 1
        assertEquals(targetMs, clock.nowEpochMillis())
        assertTrue(engine.pollLiquidityCount >= 1)
        assertTrue(runtime.isOpeningBarQuotesReady(bundle.symbol))
    }

    @Test
    fun ensureQuotesFlowing_doesNotPrePublishCapturedQuotes() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val registry = ReplayCaptureRegistry(bundle)
        val gateway = ReplayMarketDataGateway(registry)
        val feeder = MultiSymbolQuoteFeeder(registry, quoteBus = null, gateway, clock, scope)
        val orchestrator = ReplayPlaybackOrchestrator(clock, feeder, scope)
        val symbolFeeder = feeder.feederForSymbol(bundle.symbol)!!
        assertTrue(symbolFeeder.totalQuoteCount > 0)

        orchestrator.ensureQuotesFlowing(bundle.symbol)

        assertEquals(0, symbolFeeder.publishedQuoteCount)
    }

    @Test
    fun enableDrip_publishesOneQuotePerInterval() = runBlocking {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val registry = ReplayCaptureRegistry(bundle)
        val gateway = ReplayMarketDataGateway(registry)
        val feeder = MultiSymbolQuoteFeeder(registry, quoteBus = null, gateway, clock, scope)
        val symbolFeeder = feeder.feederForSymbol(bundle.symbol)!!

        feeder.ensureStreaming(bundle.symbol)
        feeder.enableDrip(bundle.symbol)

        delay(1L)
        assertEquals(1, symbolFeeder.publishedQuoteCount)

        delay(ReplayPlaybackConfig.DEFAULT_QUOTE_INTERVAL_MS + 5L)
        assertEquals(2, symbolFeeder.publishedQuoteCount)

        feeder.releaseStreaming(bundle.symbol)
    }

    private fun seededRepository(deploymentId: String, symbol: String): StrategyDeploymentRepository {
        val repository = InMemoryStrategyDeploymentRepository()
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = symbol,
            maxDollars = 500,
            marketZoneId = "America/New_York",
            status = DeploymentStatus.RUNNING
        ).copy(id = deploymentId)
        repository.add(deployment)
        val withSession = deployment.copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-04",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = "20260604  09:30:00",
                marketZoneId = "America/New_York"
            )
        )
        repository.update(deploymentId) { withSession }
        return repository
    }

    private class RecordingEngine : TouchTurnEnginePort {
        var pollLiquidityCount = 0
        override fun dispatch(command: TouchTurnCommand) {
            if (command is TouchTurnCommand.PollLiquidity) pollLiquidityCount++
        }
        override val events: Flow<TouchTurnEvent> = emptyFlow()
        override fun start() = Unit
    }
}
