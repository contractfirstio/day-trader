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

}
