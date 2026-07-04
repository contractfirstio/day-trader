package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnTradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmulatorBracketPlanAdjusterTest {

    @Test
    fun widenExits_spreadsStopAndTakeProfitFromEntry() {
        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 2.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500)!!
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
        val tp = widened.orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }
        val stop = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(113.5, tp.price, 0.001)
        assertEquals(93.25, stop.price, 0.001)
    }

    @Test
    fun widenExits_trailingDisabled_leavesTrailFieldsNull() {
        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 2.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(adjustableTrailingStop = false)
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, rules = rules)!!
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
        val stop = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(93.25, stop.price, 0.001)
        assertNull(stop.trailTriggerPrice)
    }

    @Test
    fun widenExits_customTrailingFractions_recomputedOnWidenedBracket() {
        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 2.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopTriggerFractionOfEntryToTp = 0.25
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, rules = rules)!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(102.5, stop.trailTriggerPrice!!, 0.001)
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
        val widenedStop = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(103.375, widenedStop.trailTriggerPrice!!, 0.001)
        assertEquals(100.0, widenedStop.trailArmStopPrice!!, 0.001)
    }

    @Test
    fun widenExits_customArmCushion_recomputedOnWidenedBracket() {
        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 2.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopArmFractionOfEntryToStop = 0.05
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, rules = rules)!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(99.75, stop.trailArmStopPrice!!, 0.001)
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
        val widenedStop = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(99.6625, widenedStop.trailArmStopPrice!!, 0.001)
    }

    @Test
    fun widenExits_preservesConfiguredRatio_forHammerConfirmationPlan() {
        val hammer = OhlcBar(
            open = 382.381277696,
            high = 382.689645056,
            low = 382.365859328,
            close = 382.4275328
        )
        val fifteenMinuteSetup = TouchTurnBracketSetup(
            range = 4.0,
            rangeThreshold = 0.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.SHORT,
            entry = 384.0,
            stopLoss = 385.0,
            takeProfit = 382.0
        )
        val plan = TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = "0700",
            fifteenMinuteSetup = fifteenMinuteSetup,
            hammerBar = hammer,
            maxDollars = 500
        )!!
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
        val entry = widened.orders.first { it.role == TouchTurnOrderRole.ENTRY }.price
        val tp = widened.orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price
        val stop = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price
        val reward = entry - tp
        val risk = stop - entry
        assertTrue(reward > 0.0 && risk > 0.0)
        assertEquals(
            TouchTurnRuleConfig.DEFAULT.takeProfitToStopLossRatio,
            reward / risk,
            absoluteTolerance = 1e-9
        )
    }
}
