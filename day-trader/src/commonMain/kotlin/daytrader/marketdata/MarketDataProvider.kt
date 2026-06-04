package daytrader.marketdata

import daytrader.domain.InstrumentIdentity
import daytrader.domain.TouchTurnSignalContext
import daytrader.gateway.LiveQuote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Uniform market-data surface for Touch Turn signal logic (IB, hybrid, emulator).
 * The signal engine depends on this interface — not broker-specific adapters.
 */
interface MarketDataProvider {
    val quotes: StateFlow<Map<String, LiveQuote>>

    suspend fun fetchTouchTurnSignalContext(
        symbol: String,
        instrument: InstrumentIdentity? = null,
        isClosedBarRefetch: Boolean = false
    ): Result<TouchTurnSignalContext>

    /** Live incremental volume for post-entry buffer monitoring. */
    fun observeVolumeTicks(symbol: String): Flow<VolumeTick>

    fun ensureStreaming(symbol: String, instrument: InstrumentIdentity? = null)

    fun releaseStreaming(symbol: String, instrument: InstrumentIdentity? = null)
}
