package daytrader.broker

import daytrader.domain.OhlcBar

/**
 * Fetches the first regular-hours 15-minute candle for the current session day.
 * Desktop implementation uses IB `reqHistoricalData` (1 D / 15 mins / TRADES).
 */
interface FirstFifteenMinuteCandleProvider {
    suspend fun fetch(symbol: String): Result<OhlcBar>
}
