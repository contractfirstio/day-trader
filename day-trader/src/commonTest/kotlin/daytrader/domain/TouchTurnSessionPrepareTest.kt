package daytrader.domain

import daytrader.domain.TouchTurnPrepareDefaults.MAX_AGE_MS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnSessionPrepareTest {
    @Test
    fun isValidForStart_requiresPassSameDayListingAndFreshCache() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "VOD",
            maxDollars = 500,
            marketZoneId = RthMarketSessions.EUR.zoneId,
            currencyCode = "GBP"
        ).copy(
            instrument = InstrumentIdentity(symbol = "VOD", exchange = "SMART", primaryExch = "LSE", currency = "GBP")
        )
        val sessionDate = DeploymentMarket.sessionDateIso(deployment)
        val now = 1_700_000_000_000L
        val prepare = samplePrepare(deployment, sessionDate, now)
        assertTrue(
            TouchTurnSessionPrepare.isValidForStart(prepare, deployment, sessionDate, now)
        )
    }

    @Test
    fun isValidForStart_rejectsStaleOrWrongInstrument() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "VOD",
            maxDollars = 500,
            marketZoneId = RthMarketSessions.EUR.zoneId,
            currencyCode = "GBP"
        )
        val sessionDate = DeploymentMarket.sessionDateIso(deployment)
        val now = 1_700_000_000_000L
        val prepare = samplePrepare(deployment, sessionDate, now - MAX_AGE_MS - 1)
        assertFalse(
            TouchTurnSessionPrepare.isValidForStart(prepare, deployment, sessionDate, now)
        )
        val otherListing = deployment.copy(
            instrument = InstrumentIdentity(symbol = "VOD", exchange = "SMART", primaryExch = "NYSE", currency = "USD")
        )
        assertFalse(
            TouchTurnSessionPrepare.isValidForStart(prepare, otherListing, sessionDate, now)
        )
    }

    @Test
    fun isValidForStart_allowsWarnWhenOpeningBarPending_canReuseRequiresTodayBar() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "VOD",
            maxDollars = 500,
            marketZoneId = RthMarketSessions.EUR.zoneId,
            currencyCode = "GBP"
        )
        val sessionDate = DeploymentMarket.sessionDateIso(deployment)
        val now = 1_700_000_000_000L
        val prepare = samplePrepare(deployment, sessionDate, now).copy(
            signalContext = samplePrepare(deployment, sessionDate, now).signalContext.copy(
                todayOpeningBarPending = true
            ),
            overallStatus = TouchTurnPrepareOverallStatus.WARN.name,
            checks = listOf(
                TouchTurnPrepareCheck(
                    id = TouchTurnPrepareCheckId.HISTORICAL_BOOTSTRAP.name,
                    status = TouchTurnPrepareStatus.WARN.name,
                    label = "Historical bootstrap"
                )
            )
        )
        assertTrue(TouchTurnSessionPrepare.isValidForStart(prepare, deployment, sessionDate, now))
        assertFalse(
            TouchTurnSessionPrepare.canReuseBootstrapOnStart(prepare, deployment, sessionDate, now)
        )
    }

    private fun samplePrepare(
        deployment: StrategyDeployment,
        sessionDate: String,
        preparedAt: Long
    ): TouchTurnSessionPrepare {
        val instrument = DeploymentMarket.effectiveInstrument(deployment)
        val ctx = TouchTurnSignalContext(
            firstCandle = OhlcBar(
                open = 100.0,
                high = 101.0,
                low = 99.0,
                close = 100.5,
                time = "20260522  08:00:00",
                volume = 1_000.0
            ),
            atr14 = 2.5,
            volumeSma20 = 50_000.0
        )
        return TouchTurnSessionPrepare(
            sessionDateIso = sessionDate,
            preparedAtEpochMillis = preparedAt,
            instrumentKey = instrument.dedupeKey(),
            marketZoneId = DeploymentMarket.effectiveZoneId(deployment),
            currencyCode = DeploymentMarket.effectiveCurrencyCode(deployment),
            signalContext = ctx,
            checks = listOf(
                TouchTurnPrepareCheck(
                    id = TouchTurnPrepareCheckId.IB_CONNECTED.name,
                    status = TouchTurnPrepareStatus.PASS.name,
                    label = "IB connected"
                )
            ),
            overallStatus = TouchTurnPrepareOverallStatus.PASS.name
        )
    }
}
