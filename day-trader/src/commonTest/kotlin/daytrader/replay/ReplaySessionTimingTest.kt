package daytrader.replay

import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnLogic
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.onSessionStarted
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReplaySessionTimingTest {
    @Test
    fun alignClockToSessionOpen_anchorsToRthOpenOnSessionDate() {
        val clock = ReplayClock(initialEpochMs = 9_999_999_999L)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "TSCO",
            maxDollars = 500,
            marketZoneId = "Europe/London"
        )
        val open = ReplaySessionTiming.alignClockToSessionOpen(clock, deployment, "2026-06-10")!!
        val expected = TouchTurnLogic.marketOpenEpochMillis("2026-06-10", "Europe/London", null)!!
        assertEquals(expected, open)
        assertEquals(expected, clock.nowEpochMillis())
    }
}

class BeginTouchTurnSessionRulesTest {
    @Test
    fun beginTouchTurnSession_usesDeploymentRulesNotStaleSessionRules() {
        val staleRules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        val deploymentRules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = false)
        )
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "TSCO",
            maxDollars = 500
        ).copy(
            touchTurnRules = deploymentRules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-09",
                status = TouchTurnCandleStatus.READY,
                rules = staleRules
            )
        )
        val started = deployment
            .onSessionStarted("2026-06-10")
            .beginTouchTurnSession("2026-06-10")
        assertFalse(started.touchTurnSession!!.rules.enables.openDeadline)
    }
}

class ReplaySeedDeploymentTest {
    @Test
    fun seedDeploymentIfNeeded_copiesTouchTurnRulesFromGroundTruth() {
        val sourceRules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                liquidityRangeDailyAtr = true,
                openDeadline = true
            )
        )
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val bundleWithRules = bundle.copy(
            groundTruth = bundle.groundTruth!!.copy(
                runRecord = bundle.groundTruth!!.runRecord.copy(rules = sourceRules)
            )
        )
        val repository = InMemoryStrategyDeploymentRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ReplayHybridRuntime(bundleWithRules, ReplayClock(0L), scope)
        val controller = ReplaySessionController(
            runtime = runtime,
            repository = repository,
            engine = runtime.createEngine(repository),
            scope = scope
        )
        controller.seedDeploymentIfNeeded()
        val seeded = repository.deployments.value.single { it.id == bundleWithRules.deploymentId }
        assertEquals(sourceRules, seeded.touchTurnRules)
    }

    @Test
    fun seedDeploymentIfNeeded_doesNotOverwriteExistingDeployment() {
        val existingRules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val repository = InMemoryStrategyDeploymentRepository(
            initial = listOf(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = bundle.symbol,
                    maxDollars = 500
                ).copy(id = bundle.deploymentId, touchTurnRules = existingRules)
            )
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ReplayHybridRuntime(bundle, ReplayClock(0L), scope)
        val controller = ReplaySessionController(
            runtime = runtime,
            repository = repository,
            engine = runtime.createEngine(repository),
            scope = scope
        )
        controller.seedDeploymentIfNeeded()
        assertEquals(existingRules, repository.deployments.value.single().touchTurnRules)
    }
}
