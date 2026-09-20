package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.FiveMinuteConfirmationStatus
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

class TouchTurnPipelineDetailUiMapperTest {
    @Test
    fun sessionDataCapture_includesAtrAndOpeningBar() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = "20260522  09:30:00"),
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            rangeThreshold = 2.5,
            adr14 = 10.0,
            milestones = daytrader.domain.TouchTurnMilestoneTimestamps(dataReadyAt = "2026-05-22T09:30:12")
        )
        val capture = TouchTurnPipelineDetailUiMapper.sessionDataCapture(session)
        assertTrue(capture.isReady)
        assertEquals(10.0, capture.atr14)
        assertEquals(2.5, capture.rangeThreshold)
        assertEquals("20260522  09:30:00", capture.candle?.time)
        assertEquals("2026-05-22T09:30:12", capture.dataReadyAt)
        assertEquals(25, capture.atrRatioPercent)
    }

    @Test
    fun sessionDataCapture_usesConfiguredAtrLiquidityRatioAndDailyAtr() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-07-27",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 26.88, high = 28.32, low = 26.84, close = 27.96, time = "20260727  09:30:00"),
            marketZoneId = "Asia/Hong_Kong",
            currencyCode = "HKD",
            dailyAtr14 = 1.3871428571428575,
            rangeThreshold = 1.3871428571428575 * 0.7,
            rules = daytrader.domain.TouchTurnRuleConfig.DEFAULT.copy(
                atrLiquidityRatio = 0.7,
                enables = daytrader.domain.TouchTurnRuleEnables.DEFAULT.copy(
                    liquidityRangeDailyAtr = true
                )
            )
        )
        val capture = TouchTurnPipelineDetailUiMapper.sessionDataCapture(session)
        assertEquals(1.3871428571428575, capture.atr14)
        assertEquals(70, capture.atrRatioPercent)
        assertTrue(abs(capture.observedRangeAtrRatio!! - (1.48 / 1.3871428571428575)) < 0.0001)
        assertEquals("106.7%", capture.formattedObservedRangeAtrPercent)
    }

    @Test
    fun openingBarDetail_includesAllPrices() {
        val barTime = "20260522  09:30:00"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!!
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = barTime),
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            adr14 = 10.0
        )
        val detail = TouchTurnPipelineDetailUiMapper.openingBarDetail(session, barEnd - 1)
        assertNotNull(detail)
        assertEquals(FirstCandleCloseStatus.FORMING, detail.closeStatus)
        assertNotNull(detail.timeUntilCloseLabel)
        assertEquals(6.0, detail.range)
    }

    @Test
    fun closeConfirmation_ordersPlaced_showsPassedAfterWindowExpired() {
        val barTime = "20260529  08:00:00"
        val zone = "Europe/London"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val now = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        val base = StrategyDeployment(
            id = "tt-detail",
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            status = DeploymentStatus.RUNNING,
            symbol = "JD",
            maxDollars = 500,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-29",
                status = TouchTurnCandleStatus.READY,
                candle = OhlcBar(open = 105.0, high = 110.0, low = 100.0, close = 104.0, time = barTime),
                marketZoneId = zone,
                rangeThreshold = 0.01,
                adr14 = 0.04
            )
        )
        val session = base.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = false,
            nowEpochMillis = barEnd + 4
        ).withOrdersPlacedForSession(null).touchTurnSession!!
        assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, session.decisionOutcome)
        assertEquals(TouchTurnCloseConfirmation.PASSED, session.closeConfirmation(now))
        val ui = TouchTurnPipelineDetailUiMapper.closeConfirmation(session, now)!!
        assertEquals(TouchTurnCloseConfirmation.PASSED, ui.confirmation)
        assertEquals(null, ui.remainingMillis)
    }

    @Test
    fun rulesEvaluation_liquidityMatchesOtherRuleChecks() {
        val barTime = "20260522  09:30:00"
        val now = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!! + 1
        val setup = TouchTurnBracketSetup(
            range = 6.0,
            rangeThreshold = 2.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.SHORT,
            entry = 105.0,
            stopLoss = 106.0,
            takeProfit = 102.0
        )
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = barTime),
            setup = setup,
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            dailyAtr14 = 10.0,
            rules = daytrader.domain.TouchTurnRuleConfig.DEFAULT.copy(
                enables = daytrader.domain.TouchTurnRuleEnables.DEFAULT.copy(
                    liquidityRangeDailyAtr = true
                )
            )
        )
        val evaluation = TouchTurnPipelineDetailUiMapper.rulesEvaluation(session, now)
        assertNotNull(evaluation)
        val liquidity = evaluation.checks.first { it.label == "15m opening bar range (daily ATR)" }
        assertEquals(true, liquidity.passed)
        assertEquals("OK · 60.0% of ATR (need ≥25%)", liquidity.detail)
        assertEquals(true, liquidity.enabled)
    }

    @Test
    fun fiveMinHammerBarDetail_includesHammerOhlcAndSweep() {
        val hammer = OhlcBar(open = 381.0, high = 382.5, low = 380.2, close = 382.43, time = "20260522  09:35:00")
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 385.0, high = 386.0, low = 380.0, close = 381.0, time = "20260522  09:30:00"),
            setup = TouchTurnBracketSetup(
                range = 6.0,
                rangeThreshold = 2.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.SHORT,
                entry = 382.43,
                stopLoss = 382.5,
                takeProfit = 381.0
            ),
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            fiveMinuteConfirmation = FiveMinuteConfirmationLogic.initialState(
                candle = OhlcBar(open = 385.0, high = 386.0, low = 380.0, close = 381.0, time = "20260522  09:30:00"),
                side = TouchTurnTradeSide.SHORT,
                nowEpochMillis = 1L
            ).copy(
                status = FiveMinuteConfirmationStatus.CONFIRMED,
                confirmedHammerBar = hammer
            )
        )
        val detail = TouchTurnPipelineDetailUiMapper.fiveMinHammerBarDetail(session)
        assertNotNull(detail)
        assertEquals(382.43, detail.close)
        assertEquals(386.0, detail.sweepPrice)
        assertEquals(TouchTurnTradeSide.SHORT, detail.tradeSide)
        assertTrue(detail.hammerConfirmed)
    }

    @Test
    fun fiveMinHammerBarDetail_nullUntilHammerConfirmed() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            fiveMinuteConfirmation = FiveMinuteConfirmationLogic.initialState(
                candle = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = "20260522  09:30:00"),
                side = TouchTurnTradeSide.LONG,
                nowEpochMillis = 1L
            )
        )
        assertEquals(null, TouchTurnPipelineDetailUiMapper.fiveMinHammerBarDetail(session))
    }

    @Test
    fun sessionCandlePriceChart_nullWhenOpeningBarMissing() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5
        )
        assertEquals(null, TouchTurnPipelineDetailUiMapper.sessionCandlePriceChart(session))
    }

    @Test
    fun sessionCandlePriceChart_includes15mBarWithoutFiveMinWhenConfirmationInactive() {
        val candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = "20260522  09:30:00")
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5
        )
        val chart = TouchTurnPipelineDetailUiMapper.sessionCandlePriceChart(session)!!
        assertEquals(candle, chart.candle)
        assertEquals(FirstCandleColor.GREEN, chart.candleColor)
        assertFalse(chart.showFiveMinuteOverlay)
        assertEquals(emptyList(), chart.fiveMinuteBars)
        assertEquals(null, chart.sweepPrice)
    }

    @Test
    fun sessionCandlePriceChart_includesFiveMinOverlayWhenConfirmationAwaiting() {
        val candle = OhlcBar(open = 385.0, high = 386.0, low = 380.0, close = 381.0, time = "20260522  09:30:00")
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            setup = TouchTurnBracketSetup(
                range = 6.0,
                rangeThreshold = 2.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.SHORT,
                entry = 382.0,
                stopLoss = 386.0,
                takeProfit = 381.0
            ),
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            rules = daytrader.domain.TouchTurnRuleConfig.DEFAULT.copy(
                enables = daytrader.domain.TouchTurnRuleEnables.DEFAULT.copy(
                    fiveMinuteConfirmation = true
                )
            ),
            fiveMinuteConfirmation = FiveMinuteConfirmationLogic.initialState(
                candle = candle,
                side = TouchTurnTradeSide.SHORT,
                nowEpochMillis = 1L
            )
        )
        val chart = TouchTurnPipelineDetailUiMapper.sessionCandlePriceChart(session)!!
        assertTrue(chart.showFiveMinuteOverlay)
        assertEquals(386.0, chart.sweepPrice)
        assertEquals(emptyList(), chart.fiveMinuteBars)
    }

    @Test
    fun sessionCandlePriceChart_includesEvaluatedFiveMinBars() {
        val candle = OhlcBar(open = 385.0, high = 386.0, low = 380.0, close = 381.0, time = "20260522  09:30:00")
        val fiveMinBar = OhlcBar(open = 381.0, high = 382.5, low = 380.2, close = 382.43, time = "20260522  09:35:00")
        val confirmation = FiveMinuteConfirmationLogic.initialState(
            candle = candle,
            side = TouchTurnTradeSide.SHORT,
            nowEpochMillis = 1L
        ).let { FiveMinuteConfirmationLogic.stateAfterBarEvaluated(it, fiveMinBar) }
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            setup = TouchTurnBracketSetup(
                range = 6.0,
                rangeThreshold = 2.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.SHORT,
                entry = 382.0,
                stopLoss = 386.0,
                takeProfit = 381.0
            ),
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            rules = daytrader.domain.TouchTurnRuleConfig.DEFAULT.copy(
                enables = daytrader.domain.TouchTurnRuleEnables.DEFAULT.copy(
                    fiveMinuteConfirmation = true
                )
            ),
            fiveMinuteConfirmation = confirmation
        )
        val chart = TouchTurnPipelineDetailUiMapper.sessionCandlePriceChart(session)!!
        assertEquals(listOf(fiveMinBar), chart.fiveMinuteBars)
        assertTrue(chart.showFiveMinuteOverlay)
    }

    @Test
    fun sessionCandlePriceChart_omitsLiveMarksWhenRequested() {
        val candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = "20260522  09:30:00")
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5
        )
        val liveChart = TouchTurnLiveOrderChartUiState(
            symbol = "AAPL",
            currencyCode = "USD",
            priceHistory = listOf(101.0, 102.0),
            currentPrice = 102.5,
            levels = emptyList()
        )
        val chart = TouchTurnPipelineDetailUiMapper.sessionCandlePriceChart(
            session = session,
            formingBarPriceChart = liveChart,
            includeLiveMarks = false
        )!!
        assertEquals(emptyList(), chart.livePriceHistory)
        assertEquals(null, chart.currentPrice)
        assertEquals(null, chart.quoteStrip)
    }

    @Test
    fun liquidityCalculation_showsPassWhenRangeExceedsThreshold() {
        val barTime = "20260522  09:30:00"
        val now = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!! + 1
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 100.0, high = 105.0, low = 99.0, close = 103.0, time = barTime),
            marketZoneId = "America/New_York",
            rangeThreshold = 2.5,
            adr14 = 10.0
        )
        val calc = TouchTurnPipelineDetailUiMapper.liquidityCalculation(session, now)
        assertNotNull(calc)
        assertEquals(LiquidityCandleEvaluation.LIQUIDITY, calc.evaluation)
        assertEquals(true, calc.passes)
        assertTrue(calc.canCompare)
        assertEquals(6.0, calc.barRange)
    }

    @Test
    fun liquidityCalculation_includesObservedRangeOfDailyAtrAndConfiguredGate() {
        val barTime = "20260727  09:30:00"
        val now = TouchTurnLogic.barEndEpochMillis(barTime, "Asia/Hong_Kong")!! + 1
        val dailyAtr = 1.3871428571428575
        val session = TouchTurnSessionContext(
            sessionDate = "2026-07-27",
            status = TouchTurnCandleStatus.READY,
            candle = OhlcBar(open = 26.88, high = 28.32, low = 26.84, close = 27.96, time = barTime),
            marketZoneId = "Asia/Hong_Kong",
            currencyCode = "HKD",
            dailyAtr14 = dailyAtr,
            rangeThreshold = dailyAtr * 0.7,
            rules = daytrader.domain.TouchTurnRuleConfig.DEFAULT.copy(
                atrLiquidityRatio = 0.7,
                enables = daytrader.domain.TouchTurnRuleEnables.DEFAULT.copy(
                    liquidityRangeDailyAtr = true
                )
            )
        )
        val calc = TouchTurnPipelineDetailUiMapper.liquidityCalculation(session, now)
        assertNotNull(calc)
        assertEquals(70, calc.atrRatioPercent)
        assertEquals(dailyAtr, calc.atr14)
        assertTrue(abs(calc.observedRangeAtrRatio!! - (1.48 / dailyAtr)) < 0.0001)
        assertEquals("106.7%", calc.formattedObservedRangeAtrPercent)
        assertEquals(true, calc.passes)
    }
}
