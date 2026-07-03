package daytrader.domain

import kotlinx.serialization.Serializable

/** One band of IB [reqMarketRule] — minimum price increment from [lowEdge] upward. */
@Serializable
data class InstrumentPriceIncrement(
    val lowEdge: Double,
    val increment: Double
)
