package daytrader.presentation.watchlist

import daytrader.broker.SymbolMarkets
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistTradePlan
import daytrader.gateway.WorkingOrder
import daytrader.presentation.strategies.TouchTurnLiveOrderLevels
import daytrader.presentation.strategies.TouchTurnOrderLevelUi

object WatchlistChartLevels {
    fun forEntry(
        symbol: String,
        entry: WatchlistEntry?,
        openOrders: List<WorkingOrder>,
        bracketDraft: WatchlistBracketOrderUi? = null,
        planEditors: List<WatchlistPlanEditorUi> = emptyList()
    ): List<TouchTurnOrderLevelUi> {
        val symbolOrders = openOrders.filter { SymbolMarkets.symbolsMatch(symbol, it.symbol) }
        val plannedBracket = plannedBracketForEntry(entry, bracketDraft, planEditors)
        return TouchTurnLiveOrderLevels.chartLevels(
            openOrders = symbolOrders,
            plannedBracket = plannedBracket,
            bracketSetup = null
        )
    }

    fun activePlacedPlan(
        entry: WatchlistEntry?,
        bracketDraft: WatchlistBracketOrderUi? = null
    ): WatchlistTradePlan? {
        if (bracketDraft != null) {
            entry?.tradePlans?.find { it.id == bracketDraft.planId }?.let { return it }
        }
        return entry?.tradePlans
            ?.filter { plan ->
                plan.hasPlacedOrder &&
                    plan.entryPrice != null &&
                    plan.stopPrice != null &&
                    plan.targetPrice != null
            }
            ?.maxByOrNull { it.orderPlacedAtEpochMs ?: 0L }
    }

    fun plannedBracketForEntry(
        entry: WatchlistEntry?,
        bracketDraft: WatchlistBracketOrderUi? = null,
        planEditors: List<WatchlistPlanEditorUi> = emptyList()
    ): TouchTurnPlannedBracket? {
        bracketDraft?.toPlannedBracket()?.let { return it }
        val placedPlan = activePlacedPlan(entry, bracketDraft) ?: return null
        planEditors.find { it.planId == placedPlan.id }?.toPlannedBracket(placedPlan.side)?.let { return it }
        return placedPlan.toPlannedBracket()
    }

    private fun WatchlistBracketOrderUi.toPlannedBracket(): TouchTurnPlannedBracket? {
        val entryPrice = entryPriceText.toDoubleOrNull() ?: return null
        val stopPrice = stopPriceText.toDoubleOrNull() ?: return null
        val targetPrice = targetPriceText.toDoubleOrNull() ?: return null
        return TouchTurnPlannedBracket(
            side = side.toTouchTurnTradeSide(),
            entry = entryPrice,
            stopLoss = stopPrice,
            takeProfit = targetPrice
        )
    }

    private fun WatchlistPlanEditorUi.toPlannedBracket(side: TradeSide): TouchTurnPlannedBracket? {
        val entryPrice = entryPriceText.toDoubleOrNull() ?: return null
        val stopPrice = stopPriceText.toDoubleOrNull() ?: return null
        val targetPrice = targetPriceText.toDoubleOrNull() ?: return null
        return TouchTurnPlannedBracket(
            side = side.toTouchTurnTradeSide(),
            entry = entryPrice,
            stopLoss = stopPrice,
            takeProfit = targetPrice
        )
    }

    private fun WatchlistTradePlan.toPlannedBracket(): TouchTurnPlannedBracket? {
        val entryPrice = entryPrice ?: return null
        val stopPrice = stopPrice ?: return null
        val targetPrice = targetPrice ?: return null
        return TouchTurnPlannedBracket(
            side = side.toTouchTurnTradeSide(),
            entry = entryPrice,
            stopLoss = stopPrice,
            takeProfit = targetPrice
        )
    }

    private fun TradeSide.toTouchTurnTradeSide(): TouchTurnTradeSide = when (this) {
        TradeSide.LONG -> TouchTurnTradeSide.LONG
        TradeSide.SHORT -> TouchTurnTradeSide.SHORT
    }
}
