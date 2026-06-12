package daytrader.domain

import kotlin.math.abs

/** IB adjustable-stop parameters for Touch Turn bracket stop legs (Option A). */
data class TouchTurnAdjustableStopParams(
    val triggerPrice: Double,
    /** Nominal trail distance sent to IB with [adjustableTrailingUnit] = amount (0). */
    val trailAmount: Double
)

object TouchTurnAdjustableStop {
    /**
     * Returns a human-readable rejection reason, or null when [triggerFraction] and [trailFraction]
     * are compatible with [takeProfitToStopLossRatio] (entry-to-TP : entry-to-stop reward:risk).
     *
     * Ensures the first trailing stop at arm time is not worse than the initial fixed stop:
     * `triggerFraction × RR ≥ trailFraction − 1`.
     */
    fun validateFractions(
        triggerFraction: Double,
        trailFraction: Double,
        takeProfitToStopLossRatio: Double
    ): String? {
        if (triggerFraction < 0.0 || triggerFraction > 1.0) {
            return "Trail arm must be between 0 and 1 (fraction of entry-to-take-profit)."
        }
        if (trailFraction <= 0.0) {
            return "Trail distance must be greater than 0."
        }
        if (takeProfitToStopLossRatio <= 0.0) {
            return "Take profit : stop loss ratio must be greater than 0."
        }
        val minTriggerFraction = (trailFraction - 1.0) / takeProfitToStopLossRatio
        if (triggerFraction + 1e-9 < minTriggerFraction) {
            return "Trail arm is too early for this trail distance: when trailing activates, " +
                "the stop would sit beyond the initial fixed stop. Increase trail arm or reduce trail distance."
        }
        return null
    }

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
        val takeProfitToStopLossRatio = abs(entryToTp) / abs(entryToStop)
        if (validateFractions(triggerFraction, trailFraction, takeProfitToStopLossRatio) != null) return null
        val triggerPrice = entry + triggerFraction * entryToTp
        val trailAmount = trailFraction * abs(entryToStop)
        if (trailAmount <= 0.0) return null
        return TouchTurnAdjustableStopParams(triggerPrice = triggerPrice, trailAmount = trailAmount)
    }
}
