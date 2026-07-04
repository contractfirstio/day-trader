package daytrader.marketdata

import daytrader.broker.SymbolMarkets
import daytrader.domain.OhlcBar
import daytrader.domain.InstrumentIdentity
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSignalContext
import daytrader.gateway.BrokerGateway
import daytrader.gateway.LiveQuote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * Adapts [BrokerGateway] historical and quote streams into [MarketDataProvider].
 */
class BrokerGatewayMarketDataProvider(
    private val gateway: BrokerGateway,
    private val ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null
) : MarketDataProvider {

    override val quotes: StateFlow<Map<String, LiveQuote>> = gateway.quotes

    override suspend fun fetchTouchTurnSignalContext(
        symbol: String,
        instrument: InstrumentIdentity?,
        isClosedBarRefetch: Boolean,
        marketZoneId: String?,
        allowMissingTodayOpeningBar: Boolean,
        rules: TouchTurnRuleConfig
    ): Result<TouchTurnSignalContext> =
        gateway.fetchTouchTurnSignalContext(
            symbol,
            instrument,
            isClosedBarRefetch,
            marketZoneId,
            allowMissingTodayOpeningBar,
            rules
        )

    override suspend fun fetchFiveMinuteBars(
        symbol: String,
        instrument: InstrumentIdentity?,
        afterBarOpenEpochMs: Long,
        marketZoneId: String
    ): Result<List<OhlcBar>> =
        gateway.fetchFiveMinuteBars(symbol, instrument, afterBarOpenEpochMs, marketZoneId)

    override fun observeVolumeTicks(symbol: String): Flow<VolumeTick> {
        val normalized = SymbolMarkets.normalizeSymbol(symbol)
        return quotes
            .mapNotNull { map -> map[normalized] }
            .distinctUntilChanged { old, new -> old.tickVolume == new.tickVolume }
            .map { quote ->
                VolumeTick(
                    symbol = normalized,
                    volumeDelta = quote.tickVolume ?: 0.0,
                    epochMillis = quote.quoteEpochMillis
                )
            }
    }

    override fun ensureStreaming(symbol: String, instrument: InstrumentIdentity?) {
        ensureLiveMarketData?.invoke(symbol, instrument)
    }

    override fun releaseStreaming(symbol: String, instrument: InstrumentIdentity?) {
        releaseLiveMarketData?.invoke(symbol, instrument)
    }
}
