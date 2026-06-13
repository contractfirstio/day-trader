package daytrader.domain

import kotlinx.serialization.Serializable

/** Live or replay mid/last mark during the opening 15m RTH bar. */
@Serializable
data class TouchTurnOpeningBarPriceSample(
    val epochMs: Long,
    val price: Double
)
