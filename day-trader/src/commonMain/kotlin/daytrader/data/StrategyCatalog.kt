package daytrader.data

import daytrader.domain.StrategyType

data class StrategyTypeDefaults(
    val defaultTimeframe: String,
    val positionSize: Int,
    val stopLossTicks: Int,
    val sessionWindow: String,
    val defaultRiskDollars: Int
)

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

    fun defaultsFor(type: StrategyType): StrategyTypeDefaults = when (type) {
        StrategyType.TOUCH_AND_TURN_SCALPER -> StrategyTypeDefaults(
            defaultTimeframe = "1m",
            positionSize = 100,
            stopLossTicks = 4,
            sessionWindow = "09:30 – 16:00 ET",
            defaultRiskDollars = 500
        )
        StrategyType.QUICK_FLIP_SCALPER -> StrategyTypeDefaults(
            defaultTimeframe = "1m",
            positionSize = 50,
            stopLossTicks = 2,
            sessionWindow = "09:45 – 15:45 ET",
            defaultRiskDollars = 250
        )
    }
}
