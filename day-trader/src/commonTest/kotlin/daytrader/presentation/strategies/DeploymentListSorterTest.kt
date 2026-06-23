package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.presentation.positions.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentListSorterTest {
    private fun row(
        id: String,
        instrumentName: String,
        winRatePercent: Double? = null,
        noTradeRatePercent: Double? = null,
        totalPnL: Double = 0.0,
    ) = StrategyDeploymentRowUi(
        id = id,
        name = instrumentName,
        instrumentName = instrumentName,
        status = DeploymentStatus.STOPPED,
        cardAccent = DeploymentCardAccent.STOPPED_IDLE,
        statusChipLabel = "Stopped",
        formattedTotalPnL = "$totalPnL",
        isPositiveTotalPnL = totalPnL >= 0,
        totalPnL = totalPnL,
        winRatePercent = winRatePercent,
        formattedWinRate = winRatePercent?.let { "${it.toInt()}%" } ?: "—",
        noTradeRatePercent = noTradeRatePercent,
        formattedNoTradeRate = noTradeRatePercent?.let { "${it.toInt()}%" } ?: "—",
        currencyCode = "USD",
    )

    @Test
    fun sortedRows_noColumn_preservesInputOrder() {
        val rows = listOf(
            row("b", "Beta", totalPnL = 10.0),
            row("a", "Alpha", totalPnL = 20.0),
        )

        val sorted = DeploymentListSorter.sortedRows(
            rows = rows,
            column = null,
            direction = SortDirection.DESCENDING,
        )

        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }

    @Test
    fun sortedRows_pnlDescending_ordersHighestFirst() {
        val rows = listOf(
            row("low", "Low", totalPnL = -5.0),
            row("high", "High", totalPnL = 25.0),
            row("mid", "Mid", totalPnL = 10.0),
        )

        val sorted = DeploymentListSorter.sortedRows(
            rows = rows,
            column = DeploymentListSortColumn.PNL,
            direction = SortDirection.DESCENDING,
        )

        assertEquals(listOf("high", "mid", "low"), sorted.map { it.id })
    }

    @Test
    fun sortedRows_winRateAscending_putsMissingValuesLast() {
        val rows = listOf(
            row("none", "None"),
            row("low", "Low", winRatePercent = 25.0),
            row("high", "High", winRatePercent = 75.0),
        )

        val sorted = DeploymentListSorter.sortedRows(
            rows = rows,
            column = DeploymentListSortColumn.WIN_RATE,
            direction = SortDirection.ASCENDING,
        )

        assertEquals(listOf("low", "high", "none"), sorted.map { it.id })
    }

    @Test
    fun sortedRows_noTradeRateDescending_ordersHighestFirst() {
        val rows = listOf(
            row("low", "Low", noTradeRatePercent = 10.0),
            row("high", "High", noTradeRatePercent = 80.0),
            row("mid", "Mid", noTradeRatePercent = 40.0),
        )

        val sorted = DeploymentListSorter.sortedRows(
            rows = rows,
            column = DeploymentListSortColumn.NO_TRADE_RATE,
            direction = SortDirection.DESCENDING,
        )

        assertEquals(listOf("high", "mid", "low"), sorted.map { it.id })
    }
}
