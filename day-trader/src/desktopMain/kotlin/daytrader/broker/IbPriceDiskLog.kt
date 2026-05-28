package daytrader.broker

import com.ib.client.TickType
import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.JsonFileStore
import daytrader.diagnostics.LogTimestamps
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.platform.AppFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only per-symbol IB tick log (JSONL), written on a background IO thread.
 *
 * Set `DAY_TRADER_IB_PRICE_DISK_LOGS=true` to enable. Files default to:
 * `{appData}/runs/{launchId}/{broker-scope}/ib-prices/{SYMBOL}.jsonl`
 *
 * Override directory with `DAY_TRADER_IB_PRICE_DISK_DIR` (absolute path; one file per symbol there).
 *
 * [tick] only enqueues; JSON encoding and disk append run asynchronously so IB callbacks are not blocked.
 */
internal object IbPriceDiskLog {
    private const val QUEUE_CAPACITY = 65_536

    private val json = Json { encodeDefaults = false }

    private val enabled: Boolean =
        System.getenv("DAY_TRADER_IB_PRICE_DISK_LOGS")?.equals("true", ignoreCase = true) == true

    private val directoryOverride: String? =
        System.getenv("DAY_TRADER_IB_PRICE_DISK_DIR")?.trim()?.takeIf { it.isNotEmpty() }

    private val announcedPaths = mutableSetOf<String>()
    private val droppedTicks = AtomicLong(0)
    private var lastDropLogAtMs = 0L

    private val queue: Channel<PendingTick>? =
        if (enabled) Channel(capacity = QUEUE_CAPACITY) else null

    private val writerScope: CoroutineScope? =
        if (enabled) CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)) else null

    init {
        val ch = queue
        val scope = writerScope
        if (ch != null && scope != null) {
            scope.launch {
                for (event in ch) {
                    writeTick(event)
                }
            }
        }
    }

    fun tick(
        symbol: String?,
        key: String,
        field: Int,
        price: Double,
        bid: Double?,
        ask: Double?,
        last: Double?
    ) {
        val ch = queue ?: return
        val result = ch.trySend(
            PendingTick(
                symbol = symbol,
                key = key,
                field = field,
                price = price,
                bid = bid,
                ask = ask,
                last = last
            )
        )
        if (result.isFailure) {
            val dropped = droppedTicks.incrementAndGet()
            logDroppedIfNeeded(dropped)
        }
    }

    private fun writeTick(event: PendingTick) {
        val fileKey = event.symbol?.let { SymbolMarkets.normalizeSymbol(it) }
            ?.takeIf { it.isNotBlank() }
            ?: AppDataFiles.safeFileNameComponent(event.key)
        val stamp = LogTimestamps.now()
        val line = json.encodeToString(
            IbPriceTickLine.serializer(),
            IbPriceTickLine(
                at = stamp.at,
                epochMs = stamp.epochMs,
                symbol = fileKey,
                field = event.field,
                fieldName = tickFieldName(event.field),
                price = event.price,
                bid = event.bid,
                ask = event.ask,
                last = event.last,
                marketDataKey = if (event.symbol == null) event.key else null
            )
        )
        val written = runCatching {
            if (directoryOverride != null) {
                appendToOverrideDir(fileKey, line)
            } else {
                val relative = AppDataFiles.ibPriceLogFileName(fileKey)
                JsonFileStore.appendIbPriceTickLine(relative, line)
                announceOnce(relative)
            }
        }
        if (written.isFailure && System.getenv("DAY_TRADER_IB_LOGS")?.equals("true", ignoreCase = true) == true) {
            TimestampedConsoleLog.line(
                "IB",
                "Price disk log failed symbol=$fileKey error=${written.exceptionOrNull()?.message}"
            )
        }
    }

    private fun appendToOverrideDir(fileKey: String, line: String) {
        val dir = Path.of(directoryOverride!!)
        Files.createDirectories(dir)
        val path = dir.resolve("${AppDataFiles.safeFileNameComponent(fileKey)}.jsonl")
        Files.writeString(
            path,
            "$line\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE
        )
        announceOnce(path.toString())
    }

    private fun announceOnce(pathKey: String) {
        synchronized(announcedPaths) {
            if (!announcedPaths.add(pathKey)) return
        }
        val fullPath = if (directoryOverride != null) {
            pathKey
        } else {
            runCatching { AppFileSystem.dataFilePath(pathKey) }.getOrElse { pathKey }
        }
        TimestampedConsoleLog.line("IB", "Price disk log -> $fullPath")
    }

    private fun logDroppedIfNeeded(totalDropped: Long) {
        if (System.getenv("DAY_TRADER_IB_LOGS")?.equals("true", ignoreCase = true) != true) return
        val now = System.currentTimeMillis()
        synchronized(IbPriceDiskLog) {
            if (now - lastDropLogAtMs < 5_000L) return
            lastDropLogAtMs = now
        }
        TimestampedConsoleLog.line("IB", "Price disk log queue full; dropped $totalDropped tick(s) so far")
    }

    private fun tickFieldName(field: Int): String = when (field) {
        TickType.BID.index(), TickType.DELAYED_BID.index() -> "BID"
        TickType.ASK.index(), TickType.DELAYED_ASK.index() -> "ASK"
        TickType.LAST.index(), TickType.DELAYED_LAST.index() -> "LAST"
        TickType.CLOSE.index(), TickType.DELAYED_CLOSE.index() -> "CLOSE"
        else -> "FIELD_$field"
    }
}

private data class PendingTick(
    val symbol: String?,
    val key: String,
    val field: Int,
    val price: Double,
    val bid: Double?,
    val ask: Double?,
    val last: Double?
)

@Serializable
private data class IbPriceTickLine(
    val at: String,
    val epochMs: Long,
    val symbol: String,
    val field: Int,
    val fieldName: String,
    val price: Double,
    val bid: Double? = null,
    val ask: Double? = null,
    val last: Double? = null,
    val marketDataKey: String? = null
)
