package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.JsonFileStore
import daytrader.diagnostics.LogTimestamps
import daytrader.gateway.LiveQuote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Append-only quote log for the emulated exchange/book (synthetic ticks and ingested live feeds).
 * Not session-scoped — the emulator only knows symbols, not deployment/session ids.
 *
 * Files: `{broker-scope}/emulator/prices.jsonl` (paired with `emulator/engine.jsonl`)
 *
 * Disabled when `DAY_TRADER_EMULATOR_LOGS=false`.
 */
object EmulatorPriceLog {
    private val json = Json { encodeDefaults = false }

    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_EMULATOR_LOGS")
            ?.equals("false", ignoreCase = true) != true

    private var previous: Map<String, LiveQuote> = emptyMap()

    fun clearState() {
        previous = emptyMap()
    }

    fun recordSnapshot(
        incoming: Map<String, LiveQuote>,
        pricingSource: String
    ) {
        if (!enabled) return
        for ((symbol, quote) in incoming) {
            if (!SessionPriceLog.quoteHasData(quote)) continue
            val prior = previous[symbol]
            if (prior != null && SessionPriceLog.quotesEqual(prior, quote)) continue
            val stamp = LogTimestamps.now()
            val line = json.encodeToString(
                EmulatorPriceLine.serializer(),
                EmulatorPriceLine(
                    at = stamp.at,
                    epochMs = stamp.epochMs,
                    symbol = symbol,
                    bid = quote.bid,
                    ask = quote.ask,
                    last = quote.last,
                    pricingSource = pricingSource,
                    kind = "quote_snapshot"
                )
            )
            runCatching {
                JsonFileStore.appendEmulatorPriceLine(AppDataFiles.emulatorPricesLogFileName(), line)
            }
        }
        previous = incoming
    }
}

@Serializable
internal data class EmulatorPriceLine(
    val at: String,
    val epochMs: Long,
    val symbol: String,
    val bid: Double? = null,
    val ask: Double? = null,
    val last: Double? = null,
    val pricingSource: String,
    val kind: String = "quote_snapshot"
)
