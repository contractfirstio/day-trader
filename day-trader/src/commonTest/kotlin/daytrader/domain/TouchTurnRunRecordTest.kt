package daytrader.domain

import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.presentation.strategies.TouchTurnPipelineDetailUiMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TouchTurnRunRecordTest {
    @Test
    fun resolveOutcome_dataFailed() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.FAILED,
            errorMessage = "ADR unavailable",
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED
        )
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED, resolveTouchTurnSessionOutcome(session))
    }

    @Test
    fun resolveOutcome_notLiquidity() {
        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 5.0,
            isLiquidityCandle = false,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 105.0
        )
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            setup = setup,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        )
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY, resolveTouchTurnSessionOutcome(session))
    }

    @Test
    fun onSessionStopped_persistsTouchTurnRunRecord() {
        val candle = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = "20260522  09:30:00")
        val setup = TouchTurnBracketSetup(
            range = 11.0,
            rangeThreshold = 5.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.SHORT,
            entry = 110.0,
            stopLoss = 113.0,
            takeProfit = 103.0
        )
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).onSessionStarted("2026-05-22", touchTurnStartedBy = TouchTurnSessionStartedBy.AUTO_MARKET_OPEN)
            .copy(
                touchTurnSession = TouchTurnSessionContext(
                    sessionDate = "2026-05-22",
                    status = TouchTurnCandleStatus.READY,
                    candle = candle,
                    setup = setup,
                    adr14 = 40.0,
                    currencyCode = "HKD",
                    marketZoneId = "Asia/Hong_Kong",
                    ordersPlacedForSession = true,
                    decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                    plannedQuantity = 4,
                    plannedBracket = TouchTurnPlannedBracket(
                        side = TouchTurnTradeSide.SHORT,
                        entry = 110.0,
                        stopLoss = 113.0,
                        takeProfit = 103.0
                    )
                )
            )

        val stopped = instance.onSessionStopped(
            stopParams = SessionStopParams(
                stopTrigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN,
                brokerId = BrokerId.EMULATOR,
                brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
                brokerUnrealizedPnLAtStop = 12.5
            )
        )

        val record = stopped.sessionHistory.single().touchTurnRunRecord
        requireNotNull(record)
        assertEquals(500, record.runContext.maxDollars)
        assertEquals(TouchTurnSessionStartedBy.AUTO_MARKET_OPEN, record.runContext.startedBy)
        assertEquals(BrokerId.EMULATOR, record.runContext.brokerId)
        assertEquals(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA, record.runContext.brokerKind)
        assertEquals(candle, record.marketInputs.openingBar)
        assertEquals(40.0, record.marketInputs.adr14)
        assertEquals("HKD", record.marketInputs.currencyCode)
        assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, record.decision.outcome)
        assertEquals(4, record.decision.plannedQuantity)
        assertEquals(TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN, record.stopEvent.stopTrigger)
        assertEquals(12.5, record.stopEvent.brokerUnrealizedPnLAtStop)
    }

    @Test
    fun touchTurnAnalysisSession_restoresOpeningBarFromClosedRun() {
        val candle = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = "20260522  09:30:00")
        val setup = TouchTurnBracketSetup(
            range = 11.0,
            rangeThreshold = 5.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.SHORT,
            entry = 110.0,
            stopLoss = 113.0,
            takeProfit = 103.0
        )
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).onSessionStarted("2026-05-22")
            .copy(
                touchTurnSession = TouchTurnSessionContext(
                    sessionDate = "2026-05-22",
                    status = TouchTurnCandleStatus.READY,
                    candle = candle,
                    setup = setup,
                    adr14 = 40.0,
                    ordersPlacedForSession = true,
                    decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
                )
            )
        val stopped = instance.onSessionStopped(
            stopParams = SessionStopParams(
                stopTrigger = TouchTurnSessionStopTrigger.MANUAL,
                brokerId = BrokerId.EMULATOR
            )
        )

        assertNull(stopped.touchTurnSession)
        val analysis = stopped.touchTurnAnalysisSession()
        requireNotNull(analysis)
        assertEquals(candle, analysis.candle)
        assertEquals(40.0, analysis.adr14)
        assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, analysis.decisionOutcome)
        assertEquals(true, analysis.ordersPlacedForSession)
    }

    @Test
    fun touchTurnAnalysisSession_restoresDisabledRuleEnablesFromClosedRun() {
        val barTime = "20260522  09:30:00"
        val candle = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = barTime)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                liquidityRange = false,
                volumeExhaustion = false
            )
        )
        val setup = TouchTurnLogic.computeBracketSetup(candle, rangeThreshold = 5.0, rules)
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).copy(touchTurnRules = rules).onSessionStarted("2026-05-22")
            .copy(
                touchTurnSession = TouchTurnSessionContext(
                    sessionDate = "2026-05-22",
                    status = TouchTurnCandleStatus.READY,
                    candle = candle,
                    setup = setup,
                    adr14 = 40.0,
                    atr14 = 20.0,
                    rangeThreshold = 5.0,
                    rules = rules,
                    ordersPlacedForSession = true,
                    decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
                )
            )
        val stopped = instance.onSessionStopped(
            stopParams = SessionStopParams(
                stopTrigger = TouchTurnSessionStopTrigger.MANUAL,
                brokerId = BrokerId.EMULATOR
            )
        )
        val analysis = stopped.touchTurnAnalysisSession()
        requireNotNull(analysis)
        assertEquals(rules, analysis.rules)

        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!! + 1
        val evaluation = TouchTurnPipelineDetailUiMapper.rulesEvaluation(analysis, barEnd)
        requireNotNull(evaluation)
        val liquidity = evaluation.checks.first { it.label == "Liquidity range" }
        val volume = evaluation.checks.first { it.label == "Volume" }
        assertFalse(liquidity.enabled)
        assertEquals("Disabled", liquidity.detail)
        assertNull(liquidity.passed)
        assertFalse(volume.enabled)
        assertEquals("Disabled", volume.detail)
        assertNull(volume.passed)
    }

    @Test
    fun onSessionStopped_withoutStopParams_skipsRunRecord() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).onSessionStarted("2026-05-22").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY
            )
        )

        val stopped = instance.onSessionStopped()
        assertNull(stopped.sessionHistory.single().touchTurnRunRecord)
    }
}
