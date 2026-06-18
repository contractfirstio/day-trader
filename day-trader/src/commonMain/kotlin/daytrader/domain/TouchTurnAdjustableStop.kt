package daytrader.domain

import kotlin.math.abs
import kotlin.math.max

/** IB adjustable-stop parameters for Touch Turn bracket stop legs (Option A). */
data class TouchTurnAdjustableStopParams(
    val triggerPrice: Double,
    /** Stop price when trailing arms — between entry and initial stop per [armFractionOfEntryToStop]. */
    val armStopPrice: Double
)

object TouchTurnAdjustableStop {
    /**
     * Opening-bar range proxy when only bracket prices are available (e.g. watchlist manual brackets).
     * Uses the larger of entry-to-target and entry-to-stop distances.
     */
    fun inferBarRange(entry: Double, stopLoss: Double, takeProfit: Double): Double =
        max(abs(takeProfit - entry), abs(entry - stopLoss)).coerceAtLeast(1e-9)

    fun computeArmStopPrice(
        entry: Double,
        stopLoss: Double,
        armFractionOfEntryToStop: Double
    ): Double {
        val entryToStop = abs(entry - stopLoss)
        val offset = armFractionOfEntryToStop * entryToStop
        return if (entry > stopLoss) entry - offset else entry + offset
    }

    fun validate(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        triggerFraction: Double,
        armFractionOfEntryToStop: Double = 0.0
    ): String? {
        if (triggerFraction < 0.0 || triggerFraction > 1.0) {
            return "Trail arm must be between 0 and 1 (fraction of entry-to-take-profit)."
        }
        if (armFractionOfEntryToStop < 0.0 || armFractionOfEntryToStop > 1.0) {
            return "Trail arm position must be between 0 and 1 (fraction of entry-to-stop)."
        }
        val entryToTp = takeProfit - entry
        if (abs(entryToTp) < 1e-9) {
            return "Entry and take-profit must differ."
        }
        if (entryToTp > 0.0 && entry + 1e-9 < stopLoss) {
            return "When trailing activates, entry must be on the favorable side of the initial fixed stop " +
                "(long: entry above stop; short: entry below stop)."
        }
        if (entryToTp < 0.0 && entry - 1e-9 > stopLoss) {
            return "When trailing activates, entry must be on the favorable side of the initial fixed stop " +
                "(long: entry above stop; short: entry below stop)."
        }
        return null
    }

    fun compute(
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        triggerFraction: Double = TouchTurnDefaults.TRAILING_STOP_TRIGGER_FRACTION_OF_ENTRY_TO_TP,
        armFractionOfEntryToStop: Double = TouchTurnDefaults.TRAILING_STOP_ARM_FRACTION_OF_ENTRY_TO_STOP
    ): TouchTurnAdjustableStopParams? {
        if (validate(
                entry = entry,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
                triggerFraction = triggerFraction,
                armFractionOfEntryToStop = armFractionOfEntryToStop
            ) != null
        ) {
            return null
        }
        val entryToTp = takeProfit - entry
        val triggerPrice = entry + triggerFraction * entryToTp
        return TouchTurnAdjustableStopParams(
            triggerPrice = triggerPrice,
            armStopPrice = computeArmStopPrice(entry, stopLoss, armFractionOfEntryToStop)
        )
    }
}
