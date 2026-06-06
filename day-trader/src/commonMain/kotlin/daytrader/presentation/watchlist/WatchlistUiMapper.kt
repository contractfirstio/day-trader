package daytrader.presentation.watchlist

import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.TradeSide
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistEntryProximityEvaluator
import daytrader.domain.WatchlistLabel
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistPlanOutcome
import daytrader.domain.WatchlistProximityStatus
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.WatchlistTradePlanCalculator
import daytrader.presentation.Formatters
import daytrader.presentation.markets.marketLabelForZone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WatchlistUiMapper {
    private val lastPriceAtFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss")

    fun toRowUi(
        entry: WatchlistEntry,
        watchlist: Watchlist,
        nearEntrySummary: String? = null
    ): WatchlistRowUi {
        val status = WatchlistEntryProximityEvaluator.entryStatus(entry, entry.lastScannedPrice)
        return WatchlistRowUi(
            entryId = entry.id,
            companyName = entry.companyName?.takeIf { it.isNotBlank() } ?: entry.symbol,
            symbol = entry.symbol,
            marketLabel = marketLabelForZone(entry.marketZoneId),
            formattedLast = Formatters.price(entry.lastScannedPrice?.takeIf { it > 0.0 }),
            lastPriceAtLabel = entry.lastScannedAtEpochMs?.let(::formatLastPriceAt),
            proximityStatusLabel = proximityStatusLabel(status),
            isNearEntry = status == WatchlistProximityStatus.NEAR,
            notesPreview = entry.notes?.trim()?.takeIf { it.isNotBlank() },
            planSummary = planSummary(entry),
            nearEntrySummary = nearEntrySummary,
            groups = toLabelUi(WatchlistLabels.resolveLabels(entry.labelIds, watchlist.labels))
        )
    }

    fun toEditorUi(entry: WatchlistEntry, watchlist: Watchlist): WatchlistTradePlansEditorUi {
        val assigned = WatchlistLabels.resolveLabels(entry.labelIds, watchlist.labels)
        return WatchlistTradePlansEditorUi(
            entryId = entry.id,
            symbol = entry.symbol,
            companyName = entry.companyName?.takeIf { it.isNotBlank() } ?: entry.symbol,
            formattedLast = Formatters.price(entry.lastScannedPrice?.takeIf { it > 0.0 }),
            currencyCode = entry.currencyCode,
            assignedLabels = toLabelUi(assigned),
            availableLabels = toLabelUi(WatchlistLabels.availableLabels(watchlist.labels, entry.labelIds)),
            assignedLabelIds = entry.labelIds,
            plans = entry.tradePlans.map { plan -> toPlanEditorUi(plan, entry.currencyCode) }
        )
    }

    fun toLabelUi(labels: List<WatchlistLabel>): List<WatchlistLabelUi> =
        labels.map { WatchlistLabelUi(id = it.id, name = it.name) }

    fun toDomainLabels(pending: List<WatchlistLabelUi>): List<WatchlistLabel> =
        pending.map { WatchlistLabel(id = it.id, name = it.name, createdAtEpochMs = System.currentTimeMillis()) }

    fun toPlanEditorUi(plan: WatchlistTradePlan, currencyCode: String): WatchlistPlanEditorUi {
        val draft = planFromEditorFields(
            plan = plan,
            side = plan.side,
            entryPriceText = plan.entryPrice?.let(::formatInputNumber).orEmpty(),
            stopPriceText = plan.stopPrice?.let(::formatInputNumber).orEmpty(),
            targetPriceText = plan.targetPrice?.let(::formatInputNumber).orEmpty(),
            investmentAmountText = plan.investmentAmount?.let(::formatInputNumber).orEmpty(),
            sizingMode = plan.sizingMode,
            proximityAlertEnabled = plan.proximityAlertEnabled,
            proximityThresholdMode = plan.proximityThresholdMode,
            proximityThresholdValueText = plan.proximityThresholdValue?.let(::formatInputNumber).orEmpty()
        )
        return WatchlistPlanEditorUi(
            planId = plan.id,
            label = plan.label,
            side = plan.side,
            entryPriceText = plan.entryPrice?.let(::formatInputNumber).orEmpty(),
            stopPriceText = plan.stopPrice?.let(::formatInputNumber).orEmpty(),
            targetPriceText = plan.targetPrice?.let(::formatInputNumber).orEmpty(),
            investmentAmountText = plan.investmentAmount?.let(::formatInputNumber).orEmpty(),
            sizingMode = plan.sizingMode,
            proximityAlertEnabled = plan.proximityAlertEnabled,
            proximityThresholdMode = plan.proximityThresholdMode,
            proximityThresholdValueText = plan.proximityThresholdValue?.let(::formatInputNumber).orEmpty(),
            outcome = outcomeUi(WatchlistTradePlanCalculator.compute(draft), currencyCode)
        )
    }

    fun recomputeEditorPlan(
        editor: WatchlistPlanEditorUi,
        base: WatchlistTradePlan,
        currencyCode: String
    ): WatchlistPlanEditorUi {
        val draft = planFromEditorFields(
            plan = base.copy(label = editor.label),
            side = editor.side,
            entryPriceText = editor.entryPriceText,
            stopPriceText = editor.stopPriceText,
            targetPriceText = editor.targetPriceText,
            investmentAmountText = editor.investmentAmountText,
            sizingMode = editor.sizingMode,
            proximityAlertEnabled = editor.proximityAlertEnabled,
            proximityThresholdMode = editor.proximityThresholdMode,
            proximityThresholdValueText = editor.proximityThresholdValueText
        )
        return editor.copy(outcome = outcomeUi(WatchlistTradePlanCalculator.compute(draft), currencyCode))
    }

    fun planFromEditorFields(
        plan: WatchlistTradePlan,
        side: TradeSide,
        entryPriceText: String,
        stopPriceText: String,
        targetPriceText: String,
        investmentAmountText: String,
        sizingMode: PlanSizingMode,
        proximityAlertEnabled: Boolean,
        proximityThresholdMode: ProximityThresholdMode,
        proximityThresholdValueText: String
    ): WatchlistTradePlan =
        plan.copy(
            side = side,
            entryPrice = entryPriceText.toDoubleOrNull(),
            stopPrice = stopPriceText.toDoubleOrNull(),
            targetPrice = targetPriceText.toDoubleOrNull(),
            investmentAmount = investmentAmountText.toDoubleOrNull(),
            sizingMode = sizingMode,
            proximityAlertEnabled = proximityAlertEnabled,
            proximityThresholdMode = proximityThresholdMode,
            proximityThresholdValue = proximityThresholdValueText.toDoubleOrNull()
        )

    fun outcomeUi(outcome: WatchlistPlanOutcome, currencyCode: String): WatchlistPlanOutcomeUi? {
        if (outcome.errors.isNotEmpty()) {
            return WatchlistPlanOutcomeUi(errors = outcome.errors)
        }
        if (!outcome.isComplete) return null
        return WatchlistPlanOutcomeUi(
            quantityLabel = "${outcome.quantity} shares",
            notionalLabel = Formatters.money(outcome.notionalAtEntry ?: 0.0, currencyCode),
            lossAtStopLabel = Formatters.money(outcome.lossAtStop ?: 0.0, currencyCode, showSign = true),
            profitAtTargetLabel = Formatters.money(outcome.profitAtTarget ?: 0.0, currencyCode, showSign = true),
            rMultipleLabel = outcome.rMultiple?.let { "%.1fR".format(it) },
            returnAtTargetLabel = outcome.returnAtTargetPct?.let { "%+.1f%%".format(it) },
            returnAtStopLabel = outcome.returnAtStopPct?.let { "%+.1f%%".format(it) },
            errors = emptyList()
        )
    }

    private fun planSummary(entry: WatchlistEntry): String? {
        val complete = entry.tradePlans
            .map { plan -> plan to WatchlistTradePlanCalculator.compute(plan) }
            .firstOrNull { (_, outcome) -> outcome.isComplete }
            ?: return null
        val (plan, outcome) = complete
        val profit = Formatters.money(outcome.profitAtTarget ?: 0.0, entry.currencyCode, showSign = true)
        val loss = Formatters.money(outcome.lossAtStop ?: 0.0, entry.currencyCode, showSign = true)
        return "${plan.label}: $profit / $loss"
    }

    private fun proximityStatusLabel(status: WatchlistProximityStatus): String = when (status) {
        WatchlistProximityStatus.NOT_SCANNED -> "Not scanned"
        WatchlistProximityStatus.CLEAR -> "Clear"
        WatchlistProximityStatus.NEAR -> "Near entry"
        WatchlistProximityStatus.NO_DATA -> "No data"
    }

    private fun formatLastPriceAt(epochMs: Long): String =
        lastPriceAtFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun formatInputNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
