package daytrader.presentation.strategies

import daytrader.presentation.positions.SortDirection

object DeploymentListSorter {
    fun sortedRows(
        rows: List<StrategyDeploymentRowUi>,
        column: DeploymentListSortColumn?,
        direction: SortDirection,
    ): List<StrategyDeploymentRowUi> {
        if (column == null) return rows
        val comparator = comparator(column).thenBy { it.instrumentName.lowercase() }
        return if (direction == SortDirection.DESCENDING) {
            rows.sortedWith(comparator.reversed())
        } else {
            rows.sortedWith(comparator)
        }
    }

    private fun comparator(column: DeploymentListSortColumn): Comparator<StrategyDeploymentRowUi> =
        when (column) {
            DeploymentListSortColumn.WIN_RATE -> nullsLastCompareBy { it.winRatePercent }
            DeploymentListSortColumn.NO_TRADE_RATE -> nullsLastCompareBy { it.noTradeRatePercent }
            DeploymentListSortColumn.PNL -> compareBy { it.totalPnL }
        }

    private fun nullsLastCompareBy(
        selector: (StrategyDeploymentRowUi) -> Double?,
    ): Comparator<StrategyDeploymentRowUi> =
        Comparator { left, right ->
            val leftValue = selector(left)
            val rightValue = selector(right)
            when {
                leftValue == null && rightValue == null -> 0
                leftValue == null -> 1
                rightValue == null -> -1
                else -> leftValue.compareTo(rightValue)
            }
        }
}
