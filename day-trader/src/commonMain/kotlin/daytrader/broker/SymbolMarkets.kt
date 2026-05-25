package daytrader.broker

import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder

object SymbolMarkets {
    /** HK listings on IB: numeric symbol (e.g. `700`, `0700`, `9988`, `0700.HK`). */
    fun isHongKong(symbol: String): Boolean {
        val normalized = symbol.trim().uppercase().removeSuffix(".HK")
        return normalized.isNotEmpty() && normalized.all { it.isDigit() }
    }

    fun currencyCode(symbol: String): String = if (isHongKong(symbol)) "HKD" else "USD"

    fun zoneId(symbol: String): String = if (isHongKong(symbol)) "Asia/Hong_Kong" else "America/New_York"

    fun zoneIdForCurrency(currencyCode: String): String = when (currencyCode.uppercase()) {
        "HKD" -> "Asia/Hong_Kong"
        "EUR" -> "Europe/Berlin"
        else -> "America/New_York"
    }

    /** Normalize symbols for matching instance config to IB position/order symbols. */
    fun normalizeSymbol(symbol: String): String {
        val trimmed = symbol.trim().uppercase().removeSuffix(".HK")
        if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) {
            return trimmed.toLongOrNull()?.toString() ?: trimmed.trimStart('0').ifEmpty { trimmed }
        }
        return trimmed
    }

    fun symbolsMatch(instanceSymbol: String, brokerSymbol: String): Boolean {
        val a = normalizeSymbol(instanceSymbol)
        val b = normalizeSymbol(brokerSymbol)
        if (a == b) return true
        if (a.all { it.isDigit() } && b.all { it.isDigit() }) {
            return a.toLongOrNull() == b.toLongOrNull()
        }
        return false
    }

    fun hasOpenPosition(instanceSymbol: String, positions: List<AccountPosition>): Boolean =
        findOpenPosition(instanceSymbol, positions) != null

    fun findOpenPosition(instanceSymbol: String, positions: List<AccountPosition>): AccountPosition? =
        positions.firstOrNull { pos -> symbolsMatch(instanceSymbol, pos.symbol) && pos.quantity != 0 }

    fun openOrdersForSymbol(instanceSymbol: String, orders: List<WorkingOrder>): List<WorkingOrder> =
        orders.filter { order -> symbolsMatch(instanceSymbol, order.symbol) }

    fun hasOpenOrders(instanceSymbol: String, orders: List<WorkingOrder>): Boolean =
        openOrdersForSymbol(instanceSymbol, orders).isNotEmpty()
}
