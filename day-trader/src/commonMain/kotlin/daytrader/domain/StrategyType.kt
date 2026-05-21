package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
enum class StrategyType {
    TOUCH_AND_TURN_SCALPER,
    QUICK_FLIP_SCALPER
}
