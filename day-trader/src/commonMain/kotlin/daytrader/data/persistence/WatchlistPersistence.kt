package daytrader.data.persistence

import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.ReversalScoreAlignmentBadge
import daytrader.domain.MacroTrendState
import daytrader.domain.TradeSide
import daytrader.domain.HomeMarketMacroBenchmark
import daytrader.domain.RthMarketSessions
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistHomeMarketRegime
import daytrader.domain.WatchlistLabel
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistPlanDiaryEntry
import daytrader.domain.WatchlistPlanKind
import daytrader.domain.TouchTurnOrderRole
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
            createdAtEpochMs = record.createdAtEpochMs,
            lastReversalScoreHomeMarketRegimes = resolveHomeMarketRegimes(record)
        )
    }

    fun toRecord(watchlist: Watchlist): WatchlistRecord {
        val usRegime = watchlist.lastReversalScoreHomeMarketRegimes.firstOrNull {
            it.benchmarkSymbol == HomeMarketMacroBenchmark.forMarketZoneId(RthMarketSessions.US.zoneId).symbol
        }
        return WatchlistRecord(
            id = watchlist.id,
            name = watchlist.name,
            entries = watchlist.entries.map(::toEntryRecord),
            labels = watchlist.labels.map(::toLabelRecord),
            createdAtEpochMs = watchlist.createdAtEpochMs,
            lastReversalScoreHomeMarketRegimes = watchlist.lastReversalScoreHomeMarketRegimes.map(::toHomeMarketRegimeRecord),
            lastReversalScoreMacroTrend = usRegime?.macroTrend?.name,
            lastReversalScoreSpyLastPrice = usRegime?.lastPrice,
            lastReversalScoreSpySma200 = usRegime?.sma200
        )
    }

    private fun resolveHomeMarketRegimes(record: WatchlistRecord): List<WatchlistHomeMarketRegime> {
        if (record.lastReversalScoreHomeMarketRegimes.isNotEmpty()) {
            return record.lastReversalScoreHomeMarketRegimes.map(::toHomeMarketRegimeDomain)
        }
        val legacyTrend = record.lastReversalScoreMacroTrend
            ?.let { runCatching { MacroTrendState.valueOf(it) }.getOrNull() }
            ?: return emptyList()
        val benchmark = HomeMarketMacroBenchmark.forMarketZoneId(RthMarketSessions.US.zoneId)
        return listOf(
            WatchlistHomeMarketRegime(
                marketZoneId = benchmark.marketZoneId,
                benchmarkSymbol = benchmark.symbol,
                benchmarkLabel = benchmark.label,
                macroTrend = legacyTrend,
                lastPrice = record.lastReversalScoreSpyLastPrice,
                sma200 = record.lastReversalScoreSpySma200
            )
        )
    }

    private fun toHomeMarketRegimeDomain(record: WatchlistHomeMarketRegimeRecord): WatchlistHomeMarketRegime =
        WatchlistHomeMarketRegime(
            marketZoneId = record.marketZoneId,
            benchmarkSymbol = record.benchmarkSymbol,
            benchmarkLabel = record.benchmarkLabel,
            macroTrend = record.macroTrend?.let { runCatching { MacroTrendState.valueOf(it) }.getOrNull() },
            lastPrice = record.lastPrice,
            sma200 = record.sma200
        )

    private fun toHomeMarketRegimeRecord(regime: WatchlistHomeMarketRegime): WatchlistHomeMarketRegimeRecord =
        WatchlistHomeMarketRegimeRecord(
            marketZoneId = regime.marketZoneId,
            benchmarkSymbol = regime.benchmarkSymbol,
            benchmarkLabel = regime.benchmarkLabel,
            macroTrend = regime.macroTrend?.name,
            lastPrice = regime.lastPrice,
            sma200 = regime.sma200
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
            strategyDeploymentIds = record.strategyDeploymentIds,
            tradePlans = record.tradePlans
                .map(::toPlanDomain)
                .ifEmpty { defaultWatchlistTradePlans() },
            lastScannedPrice = record.lastScannedPrice,
            lastScannedAtEpochMs = record.lastScannedAtEpochMs,
            reversalScore = record.reversalScore,
            reversalScoreAtEpochMs = record.reversalScoreAtEpochMs,
            reversalScoreAlignmentBadge = record.reversalScoreAlignmentBadge
                ?.let { runCatching { ReversalScoreAlignmentBadge.valueOf(it) }.getOrNull() },
            reversalScoreInsightText = record.reversalScoreInsightText,
            reversalScoreRecommendationText = record.reversalScoreRecommendationText
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
            strategyDeploymentIds = entry.strategyDeploymentIds,
            tradePlans = entry.tradePlans.map(::toPlanRecord),
            lastScannedPrice = entry.lastScannedPrice,
            lastScannedAtEpochMs = entry.lastScannedAtEpochMs,
            reversalScore = entry.reversalScore,
            reversalScoreAtEpochMs = entry.reversalScoreAtEpochMs,
            reversalScoreAlignmentBadge = entry.reversalScoreAlignmentBadge?.name,
            reversalScoreInsightText = entry.reversalScoreInsightText,
            reversalScoreRecommendationText = entry.reversalScoreRecommendationText
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
            proximityThresholdValue = record.proximityThresholdValue,
            orderPlacedAtEpochMs = record.orderPlacedAtEpochMs,
            placedOrderIds = record.placedOrderIds,
            executedBracketLegs = record.executedBracketLegs.mapNotNull(::parseExecutedBracketLeg),
            diaryEntries = record.diaryEntries.map(::toDiaryDomain)
        )

    private fun toDiaryDomain(record: WatchlistPlanDiaryEntryRecord): WatchlistPlanDiaryEntry =
        WatchlistPlanDiaryEntry(
            id = record.id,
            body = record.body,
            createdAtEpochMs = record.createdAtEpochMs,
            notifyOnDate = record.notifyOnDate,
            notificationDismissed = record.notificationDismissed
        )

    private fun toDiaryRecord(entry: WatchlistPlanDiaryEntry): WatchlistPlanDiaryEntryRecord =
        WatchlistPlanDiaryEntryRecord(
            id = entry.id,
            body = entry.body,
            createdAtEpochMs = entry.createdAtEpochMs,
            notifyOnDate = entry.notifyOnDate,
            notificationDismissed = entry.notificationDismissed
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
            proximityThresholdValue = plan.proximityThresholdValue,
            orderPlacedAtEpochMs = plan.orderPlacedAtEpochMs,
            placedOrderIds = plan.placedOrderIds,
            executedBracketLegs = plan.executedBracketLegs.map { it.name },
            diaryEntries = plan.diaryEntries.map(::toDiaryRecord)
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

    private fun parseExecutedBracketLeg(value: String): TouchTurnOrderRole? =
        runCatching { TouchTurnOrderRole.valueOf(value.uppercase()) }.getOrNull()
}
