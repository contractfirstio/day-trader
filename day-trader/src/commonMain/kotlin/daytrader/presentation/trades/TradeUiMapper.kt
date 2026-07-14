package daytrader.presentation.trades

import daytrader.gateway.BrokerFill
import daytrader.presentation.Formatters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object TradeUiMapper {
    private val displayDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val ibTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd  HH:mm:ss")
    private val ibDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val isoFractionFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][' ']HH:mm:ss[.][SSSSSSSSS][SSSSSS][SSS][SS][S]")

    fun toRowUi(fill: BrokerFill): TradeRowUi {
        val sideLabel = sideLabel(fill.side)
        val realized = fill.realizedPnL
        val currency = TradeMarketResolver.settlementCurrency(fill)
        return TradeRowUi(
            execId = fill.execId,
            formattedTime = formatTradeDate(fill.time),
            symbol = fill.symbol,
            marketLabel = TradeMarketResolver.shortLabel(fill),
            sideLabel = sideLabel,
            isBuySide = fill.side.equals("BOT", ignoreCase = true),
            quantityLabel = fill.quantity.toString(),
            formattedPrice = Formatters.moneyPlain(fill.price, currency),
            formattedCommission = fill.commission?.let {
                Formatters.money(-it, currency, showSign = true)
            },
            formattedRealizedPnL = realized?.let {
                Formatters.money(it, currency, showSign = true)
            },
            isPositiveRealizedPnL = realized?.let { it >= 0 }
        )
    }

    fun parseFillTime(raw: String): LocalDateTime? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        parseDateTime(trimmed)?.let { return it }
        return parseDateOnly(trimmed)?.atStartOfDay()
    }

    fun parseFillDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        parseDateOnly(trimmed)?.let { return it }
        return parseDateTime(trimmed)?.toLocalDate()
    }

    fun sideLabel(side: String): String = when (side.uppercase()) {
        "BOT" -> "Buy"
        "SLD" -> "Sell"
        else -> side.ifBlank { "—" }
    }

    fun tradeDateKey(raw: String): String? = parseFillDate(raw)?.format(isoDateFormatter)

    fun tradeDateLabel(isoDate: String): String {
        if (isoDate.isBlank()) return "—"
        return parseFillDate(isoDate)?.format(displayDateFormatter) ?: isoDate
    }

    private fun formatTradeDate(raw: String): String {
        if (raw.isBlank()) return "—"
        parseFillDate(raw)?.let { return it.format(displayDateFormatter) }
        return raw
    }

    private fun parseDateOnly(trimmed: String): LocalDate? =
        try {
            LocalDate.parse(trimmed, isoDateFormatter)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(trimmed.take(8), ibDateFormatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private fun parseDateTime(trimmed: String): LocalDateTime? =
        try {
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
