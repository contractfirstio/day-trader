package daytrader.presentation.watchlist

import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.TradeSide
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistEntryProximityEvaluator
import daytrader.domain.WatchlistLabel
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistPlanDiaryEntry
import daytrader.domain.WatchlistPlanDiaryNotifications
import daytrader.domain.WatchlistPlanOutcome
import daytrader.domain.WatchlistProximityStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.WatchlistStrategyLinks
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.WatchlistTradePlanCalculator
import daytrader.data.StrategyCatalog
import daytrader.presentation.Formatters
import daytrader.presentation.markets.marketLabelForZone
import daytrader.platform.currentSessionDateIso
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WatchlistUiMapper {
    private val lastPriceAtFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss")
    private val diaryCreatedAtFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")

    fun toRowUi(
        entry: WatchlistEntry,
        watchlist: Watchlist,
        deployments: List<StrategyDeployment> = emptyList(),
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
            groups = toLabelUi(WatchlistLabels.resolveLabels(entry.labelIds, watchlist.labels)),
            strategies = toStrategyUi(WatchlistStrategyLinks.resolve(entry.strategyDeploymentIds, deployments))
        )
    }

    fun toEditorUi(
        entry: WatchlistEntry,
        watchlist: Watchlist,
        deployments: List<StrategyDeployment> = emptyList()
    ): WatchlistTradePlansEditorUi {
        val assigned = WatchlistLabels.resolveLabels(entry.labelIds, watchlist.labels)
        val assignedStrategyIds = entry.strategyDeploymentIds
        return WatchlistTradePlansEditorUi(
            entryId = entry.id,
            symbol = entry.symbol,
            companyName = entry.companyName?.takeIf { it.isNotBlank() } ?: entry.symbol,
            formattedLast = Formatters.price(entry.lastScannedPrice?.takeIf { it > 0.0 }),
            scannedPrice = entry.lastScannedPrice,
            currencyCode = entry.currencyCode,
            assignedLabels = toLabelUi(assigned),
            availableLabels = toLabelUi(WatchlistLabels.availableLabels(watchlist.labels, entry.labelIds)),
            assignedLabelIds = entry.labelIds,
            assignedStrategies = toStrategyUi(WatchlistStrategyLinks.resolve(assignedStrategyIds, deployments)),
            availableStrategies = toStrategyUi(
                WatchlistStrategyLinks.available(deployments, assignedStrategyIds)
            ),
            assignedStrategyDeploymentIds = assignedStrategyIds,
            plans = entry.tradePlans.map { plan ->
                toPlanEditorUi(plan, entry.currencyCode, entry.lastScannedPrice)
            }
        )
    }

    fun toStrategyUi(deployments: List<StrategyDeployment>): List<WatchlistStrategyUi> =
        deployments.map { deployment ->
            WatchlistStrategyUi(
                deploymentId = deployment.id,
                strategyType = deployment.strategyType,
                label = StrategyCatalog.displayName(deployment.strategyType)
            )
        }

    fun toLabelUi(labels: List<WatchlistLabel>): List<WatchlistLabelUi> =
        labels.map { WatchlistLabelUi(id = it.id, name = it.name) }

    fun toDomainLabels(pending: List<WatchlistLabelUi>): List<WatchlistLabel> =
        pending.map { WatchlistLabel(id = it.id, name = it.name, createdAtEpochMs = System.currentTimeMillis()) }

    fun toPlanEditorUi(
        plan: WatchlistTradePlan,
        currencyCode: String,
        scannedPrice: Double? = null,
        todayIso: String = currentSessionDateIso()
    ): WatchlistPlanEditorUi {
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
            outcome = outcomeUi(WatchlistTradePlanCalculator.compute(draft), currencyCode),
            isNearEntry = isPlanNearEntry(draft, scannedPrice),
            orderPlacedLabel = orderPlacedLabel(plan),
            diaryEntryCount = plan.diaryEntries.size,
            pendingDiaryReminderCount = WatchlistPlanDiaryNotifications.pendingReminderCount(plan, todayIso)
        )
    }

    fun toDiaryEditorUi(
        entry: WatchlistEntry,
        plan: WatchlistTradePlan,
        focusedEntryId: String? = null,
        composingEntry: Boolean = false,
        editingEntryId: String? = null,
        draftBody: String = "",
        draftNotifyOnDate: String = "",
        draftNotifyEnabled: Boolean = false,
        todayIso: String = currentSessionDateIso()
    ): WatchlistPlanDiaryEditorUi =
        WatchlistPlanDiaryEditorUi(
            entryId = entry.id,
            planId = plan.id,
            symbol = entry.symbol,
            companyName = entry.companyName?.takeIf { it.isNotBlank() } ?: entry.symbol,
            planLabel = plan.label,
            entries = plan.diaryEntries
                .sortedByDescending { it.createdAtEpochMs }
                .map { toDiaryEntryUi(it, todayIso) },
            focusedEntryId = focusedEntryId,
            composingEntry = composingEntry,
            editingEntryId = editingEntryId,
            draftBody = draftBody,
            draftNotifyOnDate = draftNotifyOnDate,
            draftNotifyEnabled = draftNotifyEnabled
        )

    fun toDiaryEntryUi(entry: WatchlistPlanDiaryEntry, todayIso: String = currentSessionDateIso()): WatchlistPlanDiaryEntryUi =
        WatchlistPlanDiaryEntryUi(
            id = entry.id,
            body = entry.body,
            formattedCreatedAt = diaryCreatedAtFormatter.format(
                Instant.ofEpochMilli(entry.createdAtEpochMs).atZone(ZoneId.systemDefault())
            ),
            notifyOnDateLabel = entry.notifyOnDate?.let { "Reminder from $it" },
            reminderActive = WatchlistPlanDiaryNotifications.isDue(entry, todayIso)
        )

    fun toDiaryNotificationUi(
        notification: WatchlistPlanDiaryNotifications.DueNotification
    ): WatchlistDiaryNotificationUi =
        WatchlistDiaryNotificationUi(
            entryId = notification.entryId,
            planId = notification.planId,
            diaryEntryId = notification.diaryEntry.id,
            symbol = notification.symbol,
            companyName = notification.companyName,
            planLabel = notification.planLabel,
            bodyPreview = notification.diaryEntry.body.lineSequence().firstOrNull().orEmpty(),
            notifyOnDateLabel = notification.diaryEntry.notifyOnDate.orEmpty()
        )

    fun recomputeEditorPlan(
        editor: WatchlistPlanEditorUi,
        base: WatchlistTradePlan,
        currencyCode: String,
        scannedPrice: Double? = null
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
        return editor.copy(
            outcome = outcomeUi(WatchlistTradePlanCalculator.compute(draft), currencyCode),
            isNearEntry = isPlanNearEntry(draft, scannedPrice),
            orderPlacedLabel = orderPlacedLabel(base),
            diaryEntryCount = base.diaryEntries.size,
            pendingDiaryReminderCount = WatchlistPlanDiaryNotifications.pendingReminderCount(
                base,
                currentSessionDateIso()
            )
        )
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

    private fun isPlanNearEntry(plan: WatchlistTradePlan, scannedPrice: Double?): Boolean {
        if (plan.hasPlacedOrder) return false
        if (scannedPrice == null || scannedPrice <= 0.0) return false
        return WatchlistEntryProximityEvaluator.evaluatePlan(plan, scannedPrice)?.isNear == true
    }

    fun orderPlacedLabel(plan: WatchlistTradePlan): String? {
        if (!plan.hasPlacedOrder) return null
        val ids = plan.placedOrderIds.joinToString(", ")
        return if (ids.isBlank()) {
            "Order placed for this plan"
        } else {
            "Order placed (ids: $ids)"
        }
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
