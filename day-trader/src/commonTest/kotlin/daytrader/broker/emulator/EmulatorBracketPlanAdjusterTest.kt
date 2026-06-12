package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
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
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)

        val entry = 100.0
        val tp = widened.orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price
        val sl = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price
        assertEquals(101.35, tp, 0.001)
        assertEquals(98.65, sl, 0.001)
        assertTrue(tp - sl > plan.orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price -
            plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price)
    }

    @Test
    fun towardTakeProfitDirection_longIsUp_shortIsDown() {
        val longPlan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            TouchTurnBracketSetup(
                range = 2.0,
                rangeThreshold = 0.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.GREEN,
                side = TouchTurnTradeSide.LONG,
                entry = 100.0,
                stopLoss = 99.0,
                takeProfit = 101.0
            ),
            maxDollars = 500
        )!!
        val shortPlan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            TouchTurnBracketSetup(
                range = 2.0,
                rangeThreshold = 0.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.GREEN,
                side = TouchTurnTradeSide.SHORT,
                entry = 100.0,
                stopLoss = 101.0,
                takeProfit = 99.0
            ),
            maxDollars = 500
        )!!
        assertEquals(1, EmulatorBracketPlanAdjuster.towardTakeProfitDirection(longPlan))
        assertEquals(-1, EmulatorBracketPlanAdjuster.towardTakeProfitDirection(shortPlan))
    }

    @Test
    fun widenExits_trailingDisabled_keepsStopWithoutTrail() {
        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(adjustableTrailingStop = false)
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, rules = rules)!!
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
        val stop = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(98.65, stop.price, 0.001)
        assertNull(stop.trailTriggerPrice)
        assertNull(stop.trailAmount)
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
            trailingStopTriggerFractionOfEntryToTp = 0.25,
            trailingStopTrailFractionOfEntryToStop = 0.75
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, rules = rules)!!
        val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
        val stop = widened.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(103.375, stop.trailTriggerPrice!!, 0.001)
        assertEquals(5.0625, stop.trailAmount!!, 0.001)
    }
}
