package daytrader.domain

import kotlin.math.abs

/** IB adjustable-stop parameters for Touch Turn bracket stop legs (Option A). */
data class TouchTurnAdjustableStopParams(
    val triggerPrice: Double,
    /** Nominal trail distance sent to IB with [adjustableTrailingUnit] = amount (0). */
    val trailAmount: Double
)

object TouchTurnAdjustableStop {
    fun compute(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        triggerFraction: Double = TouchTurnDefaults.TRAILING_STOP_TRIGGER_FRACTION_OF_ENTRY_TO_TP,
        trailFraction: Double = TouchTurnDefaults.TRAILING_STOP_TRAIL_FRACTION_OF_ENTRY_TO_STOP
    ): TouchTurnAdjustableStopParams? {
        val entryToTp = takeProfit - entry
        val entryToStop = stopLoss - entry
        if (abs(entryToTp) < 1e-9 || abs(entryToStop) < 1e-9) return null
        val triggerPrice = entry + triggerFraction * entryToTp
        val trailAmount = trailFraction * abs(entryToStop)
        if (trailAmount <= 0.0) return null
        return TouchTurnAdjustableStopParams(triggerPrice = triggerPrice, trailAmount = trailAmount)
    }
}
