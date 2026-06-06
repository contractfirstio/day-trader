package daytrader.data.persistence

import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.TradeSide
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistLabel
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistPlanKind
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.defaultWatchlistTradePlans

object WatchlistPersistence {
    fun toDomain(record: WatchlistRecord): Watchlist {
        val labels = record.labels.map(::toLabelDomain).toMutableList()
        val entries = record.entries.map { entryRecord ->
            toEntryDomain(entryRecord, labels)
        }
        return Watchlist(
            id = record.id,
            name = record.name,
            entries = entries,
            labels = WatchlistLabels.sorted(labels),
            createdAtEpochMs = record.createdAtEpochMs
        )
    }

    fun toRecord(watchlist: Watchlist): WatchlistRecord =
        WatchlistRecord(
            id = watchlist.id,
            name = watchlist.name,
            entries = watchlist.entries.map(::toEntryRecord),
            labels = watchlist.labels.map(::toLabelRecord),
            createdAtEpochMs = watchlist.createdAtEpochMs
        )

    private fun toLabelDomain(record: WatchlistLabelRecord): WatchlistLabel =
        WatchlistLabel(
            id = record.id,
            name = record.name,
            createdAtEpochMs = record.createdAtEpochMs
        )

    private fun toLabelRecord(label: WatchlistLabel): WatchlistLabelRecord =
        WatchlistLabelRecord(
            id = label.id,
            name = label.name,
            createdAtEpochMs = label.createdAtEpochMs
        )

    private fun toEntryDomain(
        record: WatchlistEntryRecord,
        labelRegistry: MutableList<WatchlistLabel>
    ): WatchlistEntry {
        val labelIds = when {
            record.labelIds.isNotEmpty() -> record.labelIds
            record.tags.isNotEmpty() -> record.tags.mapNotNull { tag ->
                WatchlistLabels.ensureLabel(labelRegistry, tag, record.addedAtEpochMs).id
            }.distinct()
            else -> emptyList()
        }
        return WatchlistEntry(
            id = record.id,
            symbol = record.symbol,
            companyName = record.companyName,
            marketZoneId = record.marketZoneId,
            currencyCode = record.currencyCode,
            instrument = InstrumentIdentityPersistence.toDomain(record.instrument),
            addedAtEpochMs = record.addedAtEpochMs,
            notes = record.notes,
            labelIds = labelIds,
            tradePlans = record.tradePlans
                .map(::toPlanDomain)
                .ifEmpty { defaultWatchlistTradePlans() },
            lastScannedPrice = record.lastScannedPrice,
            lastScannedAtEpochMs = record.lastScannedAtEpochMs
        )
    }

    private fun toEntryRecord(entry: WatchlistEntry): WatchlistEntryRecord =
        WatchlistEntryRecord(
            id = entry.id,
            symbol = entry.symbol,
            companyName = entry.companyName,
            marketZoneId = entry.marketZoneId,
            currencyCode = entry.currencyCode,
            instrument = InstrumentIdentityPersistence.toRecord(entry.instrument),
            addedAtEpochMs = entry.addedAtEpochMs,
            notes = entry.notes,
            labelIds = entry.labelIds,
            tradePlans = entry.tradePlans.map(::toPlanRecord),
            lastScannedPrice = entry.lastScannedPrice,
            lastScannedAtEpochMs = entry.lastScannedAtEpochMs
        )

    private fun toPlanDomain(record: WatchlistTradePlanRecord): WatchlistTradePlan =
        WatchlistTradePlan(
            id = record.id,
            label = record.label,
            kind = parsePlanKind(record.kind),
            side = parseTradeSide(record.side),
            entryPrice = record.entryPrice,
            stopPrice = record.stopPrice,
            targetPrice = record.targetPrice,
            investmentAmount = record.investmentAmount,
            sizingMode = parseSizingMode(record.sizingMode),
            proximityAlertEnabled = record.proximityAlertEnabled,
            proximityThresholdMode = parseProximityThresholdMode(record.proximityThresholdMode),
            proximityThresholdValue = record.proximityThresholdValue
        )

    private fun toPlanRecord(plan: WatchlistTradePlan): WatchlistTradePlanRecord =
        WatchlistTradePlanRecord(
            id = plan.id,
            label = plan.label,
            kind = planKindLabel(plan.kind),
            side = plan.side.name.lowercase(),
            entryPrice = plan.entryPrice,
            stopPrice = plan.stopPrice,
            targetPrice = plan.targetPrice,
            investmentAmount = plan.investmentAmount,
            sizingMode = sizingModeLabel(plan.sizingMode),
            proximityAlertEnabled = plan.proximityAlertEnabled,
            proximityThresholdMode = proximityThresholdModeLabel(plan.proximityThresholdMode),
            proximityThresholdValue = plan.proximityThresholdValue
        )

    private fun parsePlanKind(value: String): WatchlistPlanKind =
        when (value.lowercase()) {
            "bracket" -> WatchlistPlanKind.BRACKET
            else -> WatchlistPlanKind.BRACKET
        }

    private fun planKindLabel(kind: WatchlistPlanKind): String = when (kind) {
        WatchlistPlanKind.BRACKET -> "bracket"
    }

    private fun parseTradeSide(value: String): TradeSide =
        runCatching { TradeSide.valueOf(value.uppercase()) }
            .getOrDefault(TradeSide.LONG)

    private fun parseSizingMode(value: String): PlanSizingMode =
        when (value.lowercase()) {
            "risk_budget", "risk-budget", "risk" -> PlanSizingMode.RISK_BUDGET
            else -> PlanSizingMode.NOTIONAL
        }

    private fun sizingModeLabel(mode: PlanSizingMode): String = when (mode) {
        PlanSizingMode.NOTIONAL -> "notional"
        PlanSizingMode.RISK_BUDGET -> "risk_budget"
    }

    private fun parseProximityThresholdMode(value: String): ProximityThresholdMode =
        when (value.lowercase()) {
            "absolute", "dollars", "usd" -> ProximityThresholdMode.ABSOLUTE
            else -> ProximityThresholdMode.PERCENT
        }

    private fun proximityThresholdModeLabel(mode: ProximityThresholdMode): String = when (mode) {
        ProximityThresholdMode.PERCENT -> "percent"
        ProximityThresholdMode.ABSOLUTE -> "absolute"
    }
}
