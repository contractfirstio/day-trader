package daytrader.data

import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistEntryProximityEvaluator
import daytrader.domain.WatchlistProximityEvaluation
import daytrader.gateway.BrokerGateway
import daytrader.gateway.GatewayConnectionState
import kotlinx.coroutines.delay

data class WatchlistScanProgress(
    val completed: Int,
    val total: Int,
    val symbol: String
)

data class WatchlistEntryScanResult(
    val entryId: String,
    val symbol: String,
    val price: Double?,
    val errorMessage: String?,
    val proximityHits: List<WatchlistProximityEvaluation>
)

data class WatchlistScanResult(
    val scannedAtEpochMs: Long,
    val entryResults: List<WatchlistEntryScanResult>
) {
    val nearHits: List<WatchlistEntryScanResult> =
        entryResults.filter { result -> result.proximityHits.any { it.isNear } }

    val failedCount: Int = entryResults.count { it.price == null }
}

class WatchlistPriceScanService(
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val batchDelayMs: Long = DEFAULT_BATCH_DELAY_MS
) {
    suspend fun scan(
        entries: List<WatchlistEntry>,
        gateway: BrokerGateway,
        onProgress: (WatchlistScanProgress) -> Unit = {}
    ): WatchlistScanResult {
        if (gateway.connectionState.value != GatewayConnectionState.Connected) {
            return WatchlistScanResult(
                scannedAtEpochMs = System.currentTimeMillis(),
                entryResults = entries.map { entry ->
                    WatchlistEntryScanResult(
                        entryId = entry.id,
                        symbol = entry.symbol,
                        price = null,
                        errorMessage = "Broker not connected",
                        proximityHits = emptyList()
                    )
                }
            )
        }

        val results = mutableListOf<WatchlistEntryScanResult>()
        entries.forEachIndexed { index, entry ->
            onProgress(WatchlistScanProgress(index + 1, entries.size, entry.symbol))
            val priceResult = gateway.fetchLatestDailyClose(entry.symbol, entry.instrument)
            val price = priceResult.getOrNull()
            val hits = price?.let { WatchlistEntryProximityEvaluator.evaluateEntry(entry, it) }.orEmpty()
            results.add(
                WatchlistEntryScanResult(
                    entryId = entry.id,
                    symbol = entry.symbol,
                    price = price,
                    errorMessage = priceResult.exceptionOrNull()?.message,
                    proximityHits = hits
                )
            )
            if ((index + 1) % batchSize == 0 && index + 1 < entries.size) {
                delay(batchDelayMs)
            }
        }
        return WatchlistScanResult(
            scannedAtEpochMs = System.currentTimeMillis(),
            entryResults = results
        )
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 3
        const val DEFAULT_BATCH_DELAY_MS = 2_000L
    }
}
