package daytrader.data

import daytrader.domain.StrategyType
object StrategyCatalog {
    fun displayName(type: StrategyType): String = when (type) {
        StrategyType.TOUCH_AND_TURN_SCALPER -> "Touch and Turn Scalper"
        StrategyType.QUICK_FLIP_SCALPER -> "Quick Flip Scalper"
    }

    fun description(type: StrategyType): String = when (type) {
        StrategyType.TOUCH_AND_TURN_SCALPER ->
            "Scalps reversals when price touches prior session high/low and turns."
        StrategyType.QUICK_FLIP_SCALPER ->
            "Rapid in-and-out trades on short-term momentum flips with tight stops."
    }

    fun defaultMaxDollars(type: StrategyType): Int = when (type) {
        StrategyType.TOUCH_AND_TURN_SCALPER -> 500
        StrategyType.QUICK_FLIP_SCALPER -> 250
    }

    /** R-multiple used to derive target when no explicit target price is set. */
    fun rewardMultiple(type: StrategyType): Double = when (type) {
        StrategyType.TOUCH_AND_TURN_SCALPER -> 2.0
        StrategyType.QUICK_FLIP_SCALPER -> 1.5
    }

    /**
     * Touch Turn only: minutes after RTH open (from the first 15m candle) to auto-stop and flatten.
     * Other strategies return null (no open-deadline auto-stop).
     */
    fun stopAfterMinOpen(type: StrategyType): Int? = when (type) {
        StrategyType.TOUCH_AND_TURN_SCALPER -> 90
        StrategyType.QUICK_FLIP_SCALPER -> null
    }

    /** Minutes before RTH close to log (and eventually send) market closes for open IB positions. */
    const val CLOSE_POSITIONS_BEFORE_MARKET_CLOSE_MIN = 5
}
