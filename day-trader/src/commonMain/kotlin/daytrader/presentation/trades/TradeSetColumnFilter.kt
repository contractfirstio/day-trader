package daytrader.presentation.trades

/**
 * Spreadsheet-style set filter: all values selected means no filter; a proper subset restricts rows.
 * An empty selection shows no rows.
 */
data class TradeSetColumnFilter(
    val selected: Set<String>,
    val available: Set<String>,
) {
    val isActive: Boolean
        get() = available.isNotEmpty() && selected.size < available.size

    fun matches(value: String): Boolean = when {
        available.isEmpty() -> true
        selected.size == available.size -> true
        else -> value in selected
    }

    fun toggle(value: String): TradeSetColumnFilter {
        if (value !in available) return this
        val next = if (value in selected) selected - value else selected + value
        return copy(selected = next)
    }

    fun setSelectAll(selectAll: Boolean): TradeSetColumnFilter =
        copy(selected = if (selectAll) available else emptySet())

    fun isAllSelected(): Boolean = available.isNotEmpty() && selected.size == available.size

    fun isValueSelected(value: String): Boolean = value in selected

    fun reconcile(availableValues: Set<String>): TradeSetColumnFilter {
        if (availableValues.isEmpty()) {
            return TradeSetColumnFilter(emptySet(), emptySet())
        }
        val previous = this
        val hadNoFilter = !previous.isActive || previous.selected.size == previous.available.size
        val nextSelected = when {
            hadNoFilter -> availableValues
            else -> previous.selected.intersect(availableValues)
        }
        return TradeSetColumnFilter(
            selected = if (nextSelected.isEmpty() && !hadNoFilter) emptySet() else nextSelected,
            available = availableValues
        )
    }

    companion object {
        fun forValues(values: Collection<String>): TradeSetColumnFilter {
            val available = values.filter { it.isNotBlank() }.toSet()
            return TradeSetColumnFilter(selected = available, available = available)
        }
    }
}

enum class TradeFilterColumn {
    DATE,
    SYMBOL,
    SIDE,
    MARKET,
}

data class TradeColumnFilters(
    val dates: TradeSetColumnFilter = TradeSetColumnFilter(emptySet(), emptySet()),
    val symbols: TradeSetColumnFilter = TradeSetColumnFilter(emptySet(), emptySet()),
    val sides: TradeSetColumnFilter = TradeSetColumnFilter(emptySet(), emptySet()),
    val markets: TradeSetColumnFilter = TradeSetColumnFilter(emptySet(), emptySet()),
) {
    fun reconcileFrom(fills: List<daytrader.gateway.BrokerFill>, previous: TradeColumnFilters): TradeColumnFilters {
        val dateAvailable = fills.mapNotNull { TradeUiMapper.tradeDateKey(it.time) }.toSet()
        val symbolAvailable = fills.map { it.symbol }
            .filter { it.isNotBlank() }
            .toSet()
        val sideAvailable = fills.map { TradeUiMapper.sideLabel(it.side) }.toSet()
        val marketAvailable = fills.map { TradeMarketResolver.zoneId(it) }.toSet()
        return TradeColumnFilters(
            dates = previous.dates.reconcile(dateAvailable),
            symbols = previous.symbols.reconcile(symbolAvailable),
            sides = previous.sides.reconcile(sideAvailable),
            markets = previous.markets.reconcile(marketAvailable),
        )
    }

    fun apply(fill: daytrader.gateway.BrokerFill): Boolean {
        val tradeDate = TradeUiMapper.tradeDateKey(fill.time).orEmpty()
        return dates.matches(tradeDate) &&
            symbols.matches(fill.symbol) &&
            sides.matches(TradeUiMapper.sideLabel(fill.side)) &&
            markets.matches(TradeMarketResolver.zoneId(fill))
    }
}
