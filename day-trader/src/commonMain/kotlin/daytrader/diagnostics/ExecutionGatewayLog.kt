package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.JsonFileStore
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
        runCatching {
            JsonFileStore.appendExecutionGatewayLine(AppDataFiles.executionGatewayLogFileName(), line)
        }
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
