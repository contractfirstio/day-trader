package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.gateway.BrokerId
import daytrader.gateway.TouchTurnBracketAck
import daytrader.gateway.WorkingOrder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Execution-broker gateway diagnostics (not session-scoped).
 * Pair with session `application.jsonl` via `epochMs` + symbol.
 *
 * Disabled when `DAY_TRADER_EXECUTION_GATEWAY_LOG=false`.
 */
object ExecutionGatewayLog {
    private val json = Json { encodeDefaults = false }

    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_EXECUTION_GATEWAY_LOG")
            ?.equals("false", ignoreCase = true) != true

    fun openOrdersSnapshot(
        brokerId: BrokerId,
        orders: List<WorkingOrder>,
        previousCount: Int
    ) {
        if (!enabled) return
        val bySymbol = orders.groupBy { it.symbol }
        event(
            type = "open_orders_snapshot",
            brokerId = brokerId,
            details = buildMap {
                put("orderCount", orders.size.toString())
                put("previousCount", previousCount.toString())
                put("symbolCount", bySymbol.size.toString())
                put(
                    "symbols",
                    bySymbol.entries.joinToString(";") { (sym, list) ->
                        "$sym=${list.size}[${list.joinToString(",") { o -> "${o.orderId}:${o.status}" }}]"
                    }
                )
            }
        )
    }

    fun touchTurnBracketPlaced(brokerId: BrokerId, ack: TouchTurnBracketAck) {
        if (!enabled) return
        event(
            type = "touch_turn_bracket_placed",
            brokerId = brokerId,
            symbol = ack.symbol,
            details = buildMap {
                put("success", ack.result.isSuccess.toString())
                ack.result.exceptionOrNull()?.message?.let { put("error", it) }
                put("orderIds", ack.orderIds.joinToString(","))
                put("orderCount", ack.orderIds.size.toString())
            }
        )
    }

    fun sessionPositionClosePlaced(
        brokerId: BrokerId,
        symbol: String,
        orderId: Int,
        action: String,
        quantity: Int,
        purpose: String
    ) {
        if (!enabled) return
        event(
            type = "session_position_close_placed",
            brokerId = brokerId,
            symbol = symbol,
            details = mapOf(
                "orderId" to orderId.toString(),
                "action" to action,
                "quantity" to quantity.toString(),
                "orderType" to "MKT",
                "purpose" to purpose
            )
        )
    }

    fun sessionPositionCloseSkipped(
        brokerId: BrokerId,
        symbol: String,
        reason: String,
        purpose: String
    ) {
        if (!enabled) return
        event(
            type = "session_position_close_skipped",
            brokerId = brokerId,
            symbol = symbol,
            details = mapOf(
                "reason" to reason,
                "purpose" to purpose
            )
        )
    }

    fun sessionPositionCloseRejected(
        brokerId: BrokerId,
        symbol: String,
        orderId: Int,
        purpose: String,
        status: String? = null,
        errorCode: Int? = null,
        errorMessage: String? = null
    ) {
        if (!enabled) return
        event(
            type = "session_position_close_rejected",
            brokerId = brokerId,
            symbol = symbol,
            details = buildMap {
                put("orderId", orderId.toString())
                put("purpose", purpose)
                status?.let { put("status", it) }
                errorCode?.let { put("errorCode", it.toString()) }
                errorMessage?.let { put("errorMessage", it) }
            }
        )
    }

    fun sessionPositionCloseFilled(
        brokerId: BrokerId,
        symbol: String,
        orderId: Int,
        purpose: String,
        filledQuantity: Int,
        avgFillPrice: Double
    ) {
        if (!enabled) return
        event(
            type = "session_position_close_filled",
            brokerId = brokerId,
            symbol = symbol,
            details = mapOf(
                "orderId" to orderId.toString(),
                "purpose" to purpose,
                "filledQuantity" to filledQuantity.toString(),
                "avgFillPrice" to avgFillPrice.toString()
            )
        )
    }

    /** Test hook — captures events without writing to disk. */
    internal var testListener: ((type: String, symbol: String?, details: Map<String, String>) -> Unit)? = null

    private fun event(
        type: String,
        brokerId: BrokerId,
        symbol: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        val stamp = LogTimestamps.now()
        val line = json.encodeToString(
            ExecutionGatewayLine.serializer(),
            ExecutionGatewayLine(
                at = stamp.at,
                epochMs = stamp.epochMs,
                type = type,
                brokerId = brokerId.name,
                symbol = symbol,
                details = details
            )
        )
        testListener?.invoke(type, symbol, details)
        DiagnosticJsonlWriter.appendLine(AppDataFiles.executionGatewayLogFileName(), line)
    }
}

@Serializable
private data class ExecutionGatewayLine(
    val at: String,
    val epochMs: Long,
    val type: String,
    val brokerId: String,
    val symbol: String? = null,
    val details: Map<String, String> = emptyMap()
)
