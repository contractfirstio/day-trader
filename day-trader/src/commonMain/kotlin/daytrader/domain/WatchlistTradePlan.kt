package daytrader.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

@Serializable
enum class WatchlistPlanKind {
    BRACKET
}

@Serializable
enum class PlanSizingMode {
    /** Size from total capital deployed at entry (investment / entry). */
    NOTIONAL,
    /** Size from max loss if stop hits (investment / |entry - stop|). */
    RISK_BUDGET
}

@Serializable
data class WatchlistTradePlan(
    val id: String,
    val label: String,
    val kind: WatchlistPlanKind = WatchlistPlanKind.BRACKET,
    val side: TradeSide = TradeSide.LONG,
    val entryPrice: Double? = null,
    val stopPrice: Double? = null,
    val targetPrice: Double? = null,
    val investmentAmount: Double? = null,
    val sizingMode: PlanSizingMode = PlanSizingMode.NOTIONAL,
    val proximityAlertEnabled: Boolean = false,
    val proximityThresholdMode: ProximityThresholdMode = ProximityThresholdMode.PERCENT,
    val proximityThresholdValue: Double? = null,
    /** Set when a bracket order was submitted for this plan from the watchlist. */
    val orderPlacedAtEpochMs: Long? = null,
    val placedOrderIds: List<Int> = emptyList(),
    val diaryEntries: List<WatchlistPlanDiaryEntry> = emptyList()
) {
    val hasPlacedOrder: Boolean get() = orderPlacedAtEpochMs != null

    fun withoutOrderPlacement(): WatchlistTradePlan =
        copy(orderPlacedAtEpochMs = null, placedOrderIds = emptyList())
}

data class WatchlistPlanOutcome(
    val quantity: Int? = null,
    val notionalAtEntry: Double? = null,
    val lossAtStop: Double? = null,
    val profitAtTarget: Double? = null,
    val rMultiple: Double? = null,
    val returnAtTargetPct: Double? = null,
    val returnAtStopPct: Double? = null,
    val errors: List<String> = emptyList()
) {
    val isComplete: Boolean get() = errors.isEmpty() && quantity != null
}

fun newWatchlistTradePlanId(): String =
    "wtp-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun defaultWatchlistTradePlans(): List<WatchlistTradePlan> = listOf(
    WatchlistTradePlan(id = newWatchlistTradePlanId(), label = "Plan A"),
    WatchlistTradePlan(id = newWatchlistTradePlanId(), label = "Plan B")
)

object WatchlistTradePlanCalculator {
    fun compute(plan: WatchlistTradePlan): WatchlistPlanOutcome {
        val errors = mutableListOf<String>()
        val entry = plan.entryPrice
        val stop = plan.stopPrice
        val target = plan.targetPrice
        val investment = plan.investmentAmount

        if (entry == null || entry <= 0.0) errors.add("Entry price required")
        if (stop == null || stop <= 0.0) errors.add("Stop price required")
        if (target == null || target <= 0.0) errors.add("Target price required")
        if (investment == null || investment <= 0.0) errors.add("Investment amount required")

        if (entry != null && stop != null && target != null && errors.isEmpty()) {
            validateBracketGeometry(plan.side, entry, stop, target, errors)
        }

        if (errors.isNotEmpty()) {
            return WatchlistPlanOutcome(errors = errors)
        }

        val safeEntry = entry!!
        val safeStop = stop!!
        val safeTarget = target!!
        val safeInvestment = investment!!

        val quantity = quantityFor(plan.sizingMode, safeInvestment, safeEntry, safeStop)
            ?: return WatchlistPlanOutcome(errors = listOf("Could not size position"))

        val notionalAtEntry = safeEntry * quantity
        val lossAtStop = pnlAtPrice(plan.side, safeEntry, safeStop, quantity)
        val profitAtTarget = pnlAtPrice(plan.side, safeEntry, safeTarget, quantity)
        val rMultiple = lossAtStop
            .takeIf { it != 0.0 }
            ?.let { abs(profitAtTarget / it) }

        return WatchlistPlanOutcome(
            quantity = quantity,
            notionalAtEntry = notionalAtEntry,
            lossAtStop = lossAtStop,
            profitAtTarget = profitAtTarget,
            rMultiple = rMultiple,
            returnAtTargetPct = percentReturn(profitAtTarget, notionalAtEntry),
            returnAtStopPct = percentReturn(lossAtStop, notionalAtEntry),
            errors = emptyList()
        )
    }

    fun hasMinimumInput(plan: WatchlistTradePlan): Boolean =
        plan.entryPrice != null &&
            plan.stopPrice != null &&
            plan.targetPrice != null &&
            plan.investmentAmount != null

    private fun validateBracketGeometry(
        side: TradeSide,
        entry: Double,
        stop: Double,
        target: Double,
        errors: MutableList<String>
    ) {
        when (side) {
            TradeSide.LONG -> {
                if (stop >= entry) errors.add("Long stop must be below entry")
                if (target <= entry) errors.add("Long target must be above entry")
            }
            TradeSide.SHORT -> {
                if (stop <= entry) errors.add("Short stop must be above entry")
                if (target >= entry) errors.add("Short target must be below entry")
            }
        }
    }

    private fun quantityFor(
        sizingMode: PlanSizingMode,
        investment: Double,
        entry: Double,
        stop: Double
    ): Int? = when (sizingMode) {
        PlanSizingMode.NOTIONAL -> max(1, (investment / entry).toInt())
        PlanSizingMode.RISK_BUDGET -> {
            val riskPerShare = abs(entry - stop)
            if (riskPerShare <= 0.0) null else max(1, (investment / riskPerShare).toInt())
        }
    }

    private fun pnlAtPrice(side: TradeSide, entry: Double, exit: Double, quantity: Int): Double =
        when (side) {
            TradeSide.LONG -> (exit - entry) * quantity
            TradeSide.SHORT -> (entry - exit) * quantity
        }

    private fun percentReturn(pnl: Double, notional: Double): Double? =
        notional.takeIf { it > 0.0 }?.let { (pnl / it) * 100.0 }
}
