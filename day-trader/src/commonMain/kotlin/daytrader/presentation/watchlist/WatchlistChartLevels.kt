package daytrader.presentation.watchlist

import daytrader.broker.SymbolMarkets
import daytrader.domain.TouchTurnAdjustableStop
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
        val entry = entryPriceText.toDoubleOrNull() ?: return null
        val stop = stopPriceText.toDoubleOrNull() ?: return null
        val target = targetPriceText.toDoubleOrNull() ?: return null
        return buildPlannedBracket(side.toTouchTurnTradeSide(), entry, stop, target)
    }

    private fun WatchlistPlanEditorUi.toPlannedBracket(side: TradeSide): TouchTurnPlannedBracket? {
        val entry = entryPriceText.toDoubleOrNull() ?: return null
        val stop = stopPriceText.toDoubleOrNull() ?: return null
        val target = targetPriceText.toDoubleOrNull() ?: return null
        return buildPlannedBracket(side.toTouchTurnTradeSide(), entry, stop, target)
    }

    private fun WatchlistTradePlan.toPlannedBracket(): TouchTurnPlannedBracket? {
        val entry = entryPrice ?: return null
        val stop = stopPrice ?: return null
        val target = targetPrice ?: return null
        return buildPlannedBracket(side.toTouchTurnTradeSide(), entry, stop, target)
    }

    private fun buildPlannedBracket(
        side: TouchTurnTradeSide,
        entry: Double,
        stopLoss: Double,
        takeProfit: Double
    ): TouchTurnPlannedBracket =
        TouchTurnPlannedBracket(
            side = side,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            trailTriggerPrice = TouchTurnAdjustableStop.compute(
                entry,
                stopLoss,
                takeProfit,
                barRange = TouchTurnAdjustableStop.inferBarRange(entry, stopLoss, takeProfit)
            )?.triggerPrice
        )

    private fun TradeSide.toTouchTurnTradeSide(): TouchTurnTradeSide = when (this) {
        TradeSide.LONG -> TouchTurnTradeSide.LONG
        TradeSide.SHORT -> TouchTurnTradeSide.SHORT
    }
}
