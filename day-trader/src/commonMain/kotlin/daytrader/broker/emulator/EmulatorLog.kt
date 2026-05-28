package daytrader.broker.emulator

import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.JsonFileStore
import daytrader.diagnostics.LogTimestamps
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Structured emulator engine events (brackets, fills, session stop actions).
 * Written to `{broker-scope}/emulator/engine.jsonl` — not session-scoped.
 *
 * Disabled when `DAY_TRADER_EMULATOR_LOGS=false`.
 */
internal object EmulatorLog {
    private val json = Json { encodeDefaults = false }

    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_EMULATOR_LOGS")
            ?.equals("false", ignoreCase = true) != true

    private fun event(
        type: String,
        symbol: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        if (!enabled) return
        val stamp = LogTimestamps.now()
        val line = json.encodeToString(
            EmulatorEngineLine.serializer(),
            EmulatorEngineLine(
                at = stamp.at,
                epochMs = stamp.epochMs,
                type = type,
                symbol = symbol,
                details = details
            )
        )
        runCatching {
            JsonFileStore.appendEmulatorEngineLine(AppDataFiles.emulatorEngineLogFileName(), line)
        }
    }

    fun firstCandleColor(
        symbol: String,
        isGreen: Boolean,
        fetchIndex: Int,
        colorMode: EmulatorFirstCandleColorMode
    ) {
        val side = if (isGreen) "SHORT (green bar)" else "LONG (red bar)"
        val mode = when (colorMode) {
            EmulatorFirstCandleColorMode.AUTO ->
                if (fetchIndex > 0) "auto-alternate#$fetchIndex" else "auto"
            EmulatorFirstCandleColorMode.GREEN -> "forced-green"
            EmulatorFirstCandleColorMode.RED -> "forced-red"
        }
        event(
            type = "first_candle_color",
            symbol = symbol,
            details = mapOf(
                "side" to side,
                "colorMode" to mode,
                "fetchIndex" to fetchIndex.toString()
            )
        )
    }

    fun firstCandleScheduled(symbol: String, barTime: String, secondsUntilClose: Long) {
        event(
            type = "first_candle_scheduled",
            symbol = symbol,
            details = mapOf(
                "barTime" to barTime,
                "secondsUntilClose" to secondsUntilClose.toString()
            )
        )
    }

    fun bracketPlaced(
        symbol: String,
        orderIds: List<Int>,
        entryPrice: Double,
        initialMark: Double,
        walkFloor: Double,
        walkCeiling: Double,
        entryScenario: TouchTurnEntryScenario
    ) {
        event(
            type = "bracket_placed",
            symbol = symbol,
            details = buildMap {
                put("orderIds", orderIds.joinToString(","))
                put("entryPrice", entryPrice.toString())
                put("initialMark", initialMark.toString())
                put("walkFloor", walkFloor.toString())
                put("walkCeiling", walkCeiling.toString())
                put("entryScenario", entryScenario.name)
            }
        )
    }

    fun bracketExitWalkStarted(symbol: String, floor: Double, ceiling: Double) {
        event(
            type = "bracket_exit_walk_started",
            symbol = symbol,
            details = mapOf(
                "floor" to floor.toString(),
                "ceiling" to ceiling.toString()
            )
        )
    }

    fun orderFilled(
        symbol: String,
        orderId: Int,
        qty: Int,
        price: Double,
        positionQty: Int,
        execId: String? = null,
        parentOrderId: Int? = null,
        side: String? = null,
        realizedPnL: Double? = null
    ) {
        event(
            type = "order_filled",
            symbol = symbol,
            details = buildMap {
                put("orderId", orderId.toString())
                put("qty", qty.toString())
                put("price", price.toString())
                put("positionQty", positionQty.toString())
                execId?.let { put("execId", it) }
                parentOrderId?.let { put("parentOrderId", it.toString()) }
                side?.let { put("side", it) }
                realizedPnL?.let { put("realizedPnL", it.toString()) }
            }
        )
    }

    fun sessionOrdersCancelled(symbol: String, count: Int) {
        event(
            type = "session_orders_cancelled",
            symbol = symbol,
            details = mapOf("count" to count.toString())
        )
    }

    fun sessionPositionClosed(symbol: String, action: String, quantity: Int, price: Double) {
        event(
            type = "session_position_closed",
            symbol = symbol,
            details = mapOf(
                "action" to action,
                "quantity" to quantity.toString(),
                "price" to price.toString()
            )
        )
    }

    fun connectionState(state: String, pricingSource: String? = null) {
        event(
            type = "connection_state",
            details = buildMap {
                put("state", state)
                pricingSource?.let { put("pricingSource", it) }
            }
        )
    }

    fun bracketRejected(symbol: String, reason: String) {
        event(
            type = "bracket_rejected",
            symbol = symbol,
            details = mapOf("reason" to reason)
        )
    }

    fun historicalFetchFailed(kind: String, symbol: String, message: String) {
        event(
            type = "historical_fetch_failed",
            symbol = symbol,
            details = mapOf("kind" to kind, "message" to message)
        )
    }

    fun externalQuoteIgnored(symbol: String, reason: String) {
        event(
            type = "external_quote_ignored",
            symbol = symbol,
            details = mapOf("reason" to reason)
        )
    }

    fun externalFeedReady(symbol: String) {
        event(
            type = "external_feed_ready",
            symbol = symbol
        )
    }

    fun bracketChildrenActivated(symbol: String, parentOrderId: Int, childOrderIds: List<Int>) {
        event(
            type = "bracket_children_activated",
            symbol = symbol,
            details = mapOf(
                "parentOrderId" to parentOrderId.toString(),
                "childOrderIds" to childOrderIds.joinToString(",")
            )
        )
    }

    fun bracketSiblingCancelled(symbol: String, filledOrderId: Int, cancelledOrderIds: List<Int>) {
        event(
            type = "bracket_sibling_cancelled",
            symbol = symbol,
            details = mapOf(
                "filledOrderId" to filledOrderId.toString(),
                "cancelledOrderIds" to cancelledOrderIds.joinToString(",")
            )
        )
    }
}

@Serializable
private data class EmulatorEngineLine(
    val at: String,
    val epochMs: Long,
    val type: String,
    val symbol: String? = null,
    val details: Map<String, String> = emptyMap()
)
