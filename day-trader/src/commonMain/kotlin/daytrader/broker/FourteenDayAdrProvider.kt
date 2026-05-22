package daytrader.broker

/**
 * Fetches completed daily bars from IB and computes 14-day average daily range (ADR).
 */
interface FourteenDayAdrProvider {
    suspend fun fetchFourteenDayAdr(symbol: String): Result<Double>
}
