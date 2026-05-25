package daytrader.broker

import daytrader.domain.SessionTrade
import daytrader.domain.StrategyInstance
import daytrader.domain.inProgressRun
import daytrader.gateway.BrokerFill
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object SessionTradeMatcher {
    private val ibTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd  HH:mm:ss")
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun fillsForSession(
        symbol: String,
        startedAt: String,
        stoppedAt: String?,
        fills: List<BrokerFill>
    ): List<BrokerFill> {
        val start = parseTime(startedAt) ?: return emptyList()
        val end = stoppedAt?.let(::parseTime)
        return fills
            .filter { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
            .filter { fill ->
                val fillTime = parseTime(fill.time) ?: return@filter true
                !fillTime.isBefore(start) && (end == null || !fillTime.isAfter(end))
            }
            .sortedWith(compareBy({ it.time }, { it.execId }))
    }

    fun captureForRunStop(
        instance: StrategyInstance,
        fills: List<BrokerFill>,
        stoppedAt: String
    ): List<SessionTrade> {
        val run = instance.inProgressRun() ?: return emptyList()
        return toSessionTrades(
            fillsForSession(
                symbol = instance.symbol,
                startedAt = run.startedAt,
                stoppedAt = stoppedAt,
                fills = fills
            )
        )
    }

    fun toSessionTrades(fills: List<BrokerFill>): List<SessionTrade> =
        fills.map { fill ->
            SessionTrade(
                execId = fill.execId,
                orderId = fill.orderId,
                permId = fill.permId,
                parentOrderId = fill.parentOrderId,
                side = fill.side,
                quantity = fill.quantity,
                price = fill.price,
                time = fill.time,
                currency = fill.currency,
                commission = fill.commission,
                realizedPnL = fill.realizedPnL
            )
        }

    private fun parseTime(raw: String): LocalDateTime? {
        if (raw.isBlank()) return null
        return try {
            LocalDateTime.parse(raw, isoFormatter)
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(raw.trim(), ibTimeFormatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
