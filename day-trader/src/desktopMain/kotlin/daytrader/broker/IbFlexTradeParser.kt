package daytrader.broker

import daytrader.gateway.BrokerFill
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

internal object IbFlexTradeParser {
    fun parseTrades(xml: String): List<BrokerFill> {
        if (xml.isBlank()) return emptyList()
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        val statements = document.getElementsByTagName("FlexStatement")
        if (statements.length > 0) {
            return buildList {
                for (statementIndex in 0 until statements.length) {
                    val statement = statements.item(statementIndex) as? Element ?: continue
                    val statementDate = statement.singleDayTradeDate()
                    val trades = statement.getElementsByTagName("Trade")
                    for (tradeIndex in 0 until trades.length) {
                        val trade = trades.item(tradeIndex) as? Element ?: continue
                        toBrokerFill(trade, statementDate)?.let(::add)
                    }
                }
            }
        }
        val nodes = document.getElementsByTagName("Trade")
        return buildList(nodes.length) {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as? Element ?: continue
                toBrokerFill(node, statementDate = null)?.let(::add)
            }
        }
    }

    private fun toBrokerFill(trade: Element, statementDate: String?): BrokerFill? {
        val tradeId = trade.attribute("tradeID") ?: return null
        val symbol = trade.attribute("symbol") ?: return null
        val quantityRaw = trade.attribute("quantity")?.toDoubleOrNull() ?: return null
        val price = trade.attribute("tradePrice")?.toDoubleOrNull()
            ?: trade.attribute("price")?.toDoubleOrNull()
            ?: return null
        if (quantityRaw == 0.0 || price <= 0.0) return null
        val quantity = kotlin.math.abs(quantityRaw).toInt()
        if (quantity <= 0) return null
        val side = when (trade.attribute("buySell")?.uppercase()) {
            "BUY" -> "BOT"
            "SELL" -> "SLD"
            else -> if (quantityRaw < 0) "SLD" else "BOT"
        }
        return BrokerFill(
            execId = "flex-$tradeId",
            orderId = trade.attribute("ibOrderID")?.toIntOrNull() ?: 0,
            permId = trade.attribute("ibOrderID")?.toLongOrNull() ?: 0L,
            parentOrderId = 0,
            symbol = symbol,
            side = side,
            quantity = quantity,
            price = price,
            time = formatTradeTime(
                tradeDate = trade.attribute("tradeDate")
                    ?: trade.attribute("reportDate")
                    ?: statementDate,
                tradeTime = trade.attribute("tradeTime")
                    ?: trade.attribute("orderTime"),
                dateTime = trade.attribute("dateTime"),
            ),
            currency = trade.attribute("currency").orEmpty().ifBlank { "USD" },
            commission = trade.attribute("ibCommission")?.toDoubleOrNull()?.let { kotlin.math.abs(it) },
            realizedPnL = trade.attribute("fifoPnlRealized")?.toDoubleOrNull()
                ?: trade.attribute("mtmPnl")?.toDoubleOrNull(),
        )
    }

    private fun formatTradeTime(
        tradeDate: String?,
        tradeTime: String?,
        dateTime: String?,
    ): String {
        val combined = dateTime?.trim().orEmpty()
        if (combined.isNotBlank()) {
            val normalized = combined.replace('T', ' ').replace("-", "")
            val date = normalized.take(8)
            if (date.length == 8 && date.all { it.isDigit() }) {
                return formatIsoDate(date)
            }
        }
        val dateDigits = tradeDate?.trim().orEmpty().replace("-", "")
        if (dateDigits.length != 8 || !dateDigits.all { it.isDigit() }) return ""
        val time = tradeTime?.trim().orEmpty().replace(":", "")
        if (time.isBlank()) {
            return formatIsoDate(dateDigits)
        }
        val paddedTime = time.padEnd(6, '0').take(6)
        return "${formatIsoDate(dateDigits)} ${paddedTime.take(2)}:${paddedTime.substring(2, 4)}:${paddedTime.substring(4, 6)}"
    }

    private fun formatIsoDate(yyyyMmDd: String): String =
        "${yyyyMmDd.substring(0, 4)}-${yyyyMmDd.substring(4, 6)}-${yyyyMmDd.substring(6, 8)}"

    /** Only use statement dates when the report covers a single day — never stamp a range onto every trade. */
    private fun Element.singleDayTradeDate(): String? {
        val from = attribute("fromDate")?.replace("-", "")
        val to = attribute("toDate")?.replace("-", "")
        if (from.isNullOrBlank() || to.isNullOrBlank() || from != to) return null
        return from
    }

    private fun Element.attribute(name: String): String? =
        getAttribute(name)?.trim()?.takeIf { it.isNotEmpty() }
}
