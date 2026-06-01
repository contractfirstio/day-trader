package daytrader.broker

import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
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

    /**
     * Fills to persist when a session stops. Uses the same open-ended window as
     * [daytrader.data.DeploymentSessionStopEvaluator] (no [stoppedAt] upper bound) so a
     * take-profit fill in the same broker snapshot as auto-stop is not dropped.
     */
    fun captureForSessionStop(
        instance: StrategyDeployment,
        fills: List<BrokerFill>
    ): List<SessionTrade> {
        val run = instance.inProgressSession() ?: return emptyList()
        return toSessionTrades(
            fillsForSession(
                symbol = instance.symbol,
                startedAt = run.startedAt,
                stoppedAt = null,
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
        val trimmed = raw.trim()
        return try {
            LocalDateTime.parse(trimmed, isoFormatter)
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(trimmed, isoFractionFormatter)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(trimmed, ibTimeFormatter)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    private val isoFractionFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][' ']HH:mm:ss[.][SSSSSSSSS][SSSSSS][SSS][SS][S]")
}
