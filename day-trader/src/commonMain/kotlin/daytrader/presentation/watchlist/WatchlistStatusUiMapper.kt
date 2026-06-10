package daytrader.presentation.watchlist

import daytrader.data.HomeMarketRegimeSummary
import daytrader.data.ReversalScoreBatchResult
import daytrader.data.ReversalScoreCalculationStage
import daytrader.data.ReversalScoreEntryResult
import daytrader.data.ReversalScoreProgress
import daytrader.data.WatchlistScanResult
import daytrader.domain.MacroTrendState
import daytrader.domain.RthMarketSessions
import daytrader.domain.ReversalScoreComponents
import daytrader.domain.ReversalScoreResult
import daytrader.domain.Watchlist
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.presentation.Formatters
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WatchlistStatusUiMapper {

    private val calculatedAtFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")

    fun formatCalculatedAt(epochMs: Long): String =
        calculatedAtFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun resolvedReversalBatch(
        inMemory: ReversalScoreBatchResult?,
        watchlist: Watchlist?
    ): ReversalScoreBatchResult? {
        if (inMemory != null) return inMemory
        watchlist ?: return null
        val scoredEntries = watchlist.entries.filter {
            it.reversalScore != null && it.reversalScoreAtEpochMs != null
        }
        if (scoredEntries.isEmpty() && watchlist.lastReversalScoreHomeMarketRegimes.isEmpty()) return null
        val calculatedAt = scoredEntries.maxOfOrNull { it.reversalScoreAtEpochMs!! }
            ?: watchlist.entries.mapNotNull { it.reversalScoreAtEpochMs }.maxOrNull()
            ?: return null
        return ReversalScoreBatchResult(
            calculatedAtEpochMs = calculatedAt,
            homeMarketRegimes = watchlist.lastReversalScoreHomeMarketRegimes.map { regime ->
                HomeMarketRegimeSummary(
                    marketZoneId = regime.marketZoneId,
                    benchmarkSymbol = regime.benchmarkSymbol,
                    benchmarkLabel = regime.benchmarkLabel,
                    macroTrendState = regime.macroTrend,
                    lastPrice = regime.lastPrice,
                    sma200 = regime.sma200
                )
            },
            entryResults = scoredEntries.mapNotNull { entry ->
                entry.reversalScore?.let { score ->
                    ReversalScoreEntryResult(
                        entryId = entry.id,
                        symbol = entry.symbol,
                        result = ReversalScoreResult(
                            compositeScore = score,
                            rawComposite = 0.0,
                            priceZScore = 0.0,
                            rvol = 0.0,
                            ivRank = 0.0,
                            components = ReversalScoreComponents(
                                priceZ = 0.0,
                                ivRankZ = 0.0,
                                rvolZ = 0.0,
                                hfMacroFearZ = 0.0,
                                structuralVixZ = 0.0,
                                yieldCurveZ = 0.0
                            )
                        )
                    )
                }
            }
        )
    }

    fun buildStatusStrip(
        execution: GatewayConnectionState,
        marketData: GatewayConnectionState,
        brokerKind: BrokerKind,
        lastReversalResult: ReversalScoreBatchResult?
    ): WatchlistStatusStripUi {
        val connectionChips = when (brokerKind) {
            BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA, BrokerKind.REPLAY -> listOf(
                connectionChip("Paper execution", execution),
                connectionChip("IB market data", marketData)
            )
            else -> listOf(connectionChip(brokerKind.displayName, execution))
        }
        val ago = lastReversalResult?.calculatedAtEpochMs?.let(::relativeTimeShort)
        val macroChips = lastReversalResult?.homeMarketRegimes.orEmpty().mapNotNull { regime ->
            regime.macroTrendState?.let { trend ->
                val label = buildString {
                    append(regime.benchmarkLabel)
                    append(' ')
                    append(trend.name.lowercase().replaceFirstChar { it.uppercase() })
                    ago?.let { append(" · $it") }
                }
                WatchlistConnectionChipUi(
                    label = label,
                    tone = when (trend) {
                        MacroTrendState.BULL -> WatchlistConnectionChipTone.CONNECTED
                        MacroTrendState.BEAR -> WatchlistConnectionChipTone.ERROR
                    }
                )
            }
        }
        return WatchlistStatusStripUi(
            connectionChips = connectionChips,
            macroChips = macroChips
        )
    }

    fun buildMacroRegimeCards(result: ReversalScoreBatchResult): List<WatchlistMacroRegimeCardUi> {
        val succeeded = result.entryResults.count { it.score != null }
        if (succeeded == 0 && result.homeMarketRegimes.isEmpty()) return emptyList()
        val failed = result.failedCount
        val scoredLabel = when {
            failed == 0 -> "$succeeded scored"
            else -> "$succeeded scored · $failed failed"
        }
        val calculatedAtLabel = "Latest score · ${formatCalculatedAt(result.calculatedAtEpochMs)}"
        return result.homeMarketRegimes
            .sortedBy { regimeSortOrder(it.marketZoneId) }
            .map { regime ->
                val trend = regime.macroTrendState
                WatchlistMacroRegimeCardUi(
                    benchmarkLabel = regime.benchmarkLabel,
                    trend = trend,
                    trendLabel = trend?.name ?: "UNAVAILABLE",
                    indexPriceLabel = regime.lastPrice?.let(Formatters::price) ?: "—",
                    distanceFromSmaLabel = if (regime.lastPrice != null && regime.sma200 != null && regime.sma200 > 0.0) {
                        formatDistanceFromSma(regime.lastPrice, regime.sma200)
                    } else {
                        "—"
                    },
                    actionHint = macroActionHint(trend),
                    scoredLabel = scoredLabel,
                    calculatedAtLabel = calculatedAtLabel
                )
            }
    }

    fun buildActivitySummary(
        scanResult: WatchlistScanResult?,
        reversalResult: ReversalScoreBatchResult?
    ): WatchlistActivitySummaryUi? {
        val proximityLabel = scanResult?.let { result ->
            if (result.entryResults.isEmpty()) return@let null
            when {
                result.nearHits.isEmpty() ->
                    "Proximity · ${result.entryResults.size} scanned, none near entry" +
                        scanFailureSuffix(result.failedCount)
                else ->
                    "Proximity · ${result.nearHits.size} near entry" +
                        scanFailureSuffix(result.failedCount)
            }
        }
        val reversalLabel = reversalResult?.let { result ->
            if (result.entryResults.isEmpty()) return@let null
            val succeeded = result.entryResults.count { it.score != null }
            when {
                result.failedCount == 0 -> "Reversal · $succeeded scored"
                else -> "Reversal · $succeeded/${result.entryResults.size} scored"
            }
        }
        if (proximityLabel == null && reversalLabel == null) return null
        return WatchlistActivitySummaryUi(
            proximityLabel = proximityLabel,
            proximityHighlighted = scanResult?.nearHits?.isNotEmpty() == true,
            reversalLabel = reversalLabel
        )
    }

    fun buildReversalScoreProgress(progress: ReversalScoreProgress): ReversalScoreProgressUi {
        val macroActive = progress.stage == ReversalScoreCalculationStage.MACRO_VOL ||
            progress.stage == ReversalScoreCalculationStage.YIELD_CURVE
        val homeMarketActive = progress.stage == ReversalScoreCalculationStage.HOME_MARKET_REGIME
        val symbolsActive = progress.stage == ReversalScoreCalculationStage.SYMBOLS

        val macroComplete = progress.stage.ordinal >= ReversalScoreCalculationStage.HOME_MARKET_REGIME.ordinal
        val homeMarketComplete = progress.stage == ReversalScoreCalculationStage.SYMBOLS
        val symbolsComplete = symbolsActive &&
            progress.total > 0 &&
            progress.completed >= progress.total

        fun stepStatus(complete: Boolean, active: Boolean): ReversalScoreProgressStepStatus = when {
            complete -> ReversalScoreProgressStepStatus.COMPLETE
            active -> ReversalScoreProgressStepStatus.ACTIVE
            else -> ReversalScoreProgressStepStatus.PENDING
        }

        val detailLabel = when (progress.stage) {
            ReversalScoreCalculationStage.MACRO_VOL -> "Fetching VIX / macro volatility…"
            ReversalScoreCalculationStage.YIELD_CURVE -> "Fetching yield curve…"
            ReversalScoreCalculationStage.HOME_MARKET_REGIME -> {
                val benchmark = progress.symbol.takeIf { it.isNotBlank() } ?: "home market"
                "Evaluating $benchmark 200-day regime…"
            }
            ReversalScoreCalculationStage.SYMBOLS ->
                "Scoring ${progress.completed}/${progress.total} · ${progress.symbol}"
        }

        return ReversalScoreProgressUi(
            steps = listOf(
                ReversalScoreProgressStepUi("Macro", stepStatus(macroComplete, macroActive)),
                ReversalScoreProgressStepUi("Home", stepStatus(homeMarketComplete, homeMarketActive)),
                ReversalScoreProgressStepUi(
                    "Symbols",
                    stepStatus(symbolsComplete, symbolsActive)
                )
            ),
            detailLabel = detailLabel
        )
    }

    fun formatPriceSublabel(epochMs: Long?): String? {
        epochMs ?: return null
        return "As of ${formatCalculatedAt(epochMs)}"
    }

    fun formatReversalScoreSublabel(epochMs: Long?): String? {
        epochMs ?: return null
        return "Scored ${formatCalculatedAt(epochMs)}"
    }

    fun isReversalScoreStale(calculatedAtEpochMs: Long?, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        calculatedAtEpochMs ?: return false
        return Duration.ofMillis(nowEpochMs - calculatedAtEpochMs).toHours() >= 24
    }

    private fun connectionChip(label: String, state: GatewayConnectionState): WatchlistConnectionChipUi {
        val tone = when (state) {
            GatewayConnectionState.Connected -> WatchlistConnectionChipTone.CONNECTED
            GatewayConnectionState.Connecting -> WatchlistConnectionChipTone.CONNECTING
            is GatewayConnectionState.Error -> WatchlistConnectionChipTone.ERROR
            GatewayConnectionState.Disconnected -> WatchlistConnectionChipTone.DISCONNECTED
        }
        val suffix = when (state) {
            GatewayConnectionState.Connected -> "connected"
            GatewayConnectionState.Connecting -> "connecting"
            is GatewayConnectionState.Error -> "error"
            GatewayConnectionState.Disconnected -> "offline"
        }
        return WatchlistConnectionChipUi(label = "$label · $suffix", tone = tone)
    }

    private fun macroActionHint(trend: MacroTrendState?): String = when (trend) {
        MacroTrendState.BULL -> "Favor dips (score ≤20); trim rips (score ≥80)"
        MacroTrendState.BEAR -> "Favor rips (score ≥80); bounce plays only (score ≤20)"
        null -> "Wait for extreme scores before reversal trades"
    }

    private fun formatDistanceFromSma(last: Double, sma: Double): String {
        val pct = ((last - sma) / sma) * 100.0
        val arrow = when {
            pct > 0.05 -> "↑"
            pct < -0.05 -> "↓"
            else -> "→"
        }
        return "$arrow ${formatSignedPercent(pct)} vs 200-SMA"
    }

    private fun formatSignedPercent(value: Double): String {
        val sign = if (value >= 0) "+" else ""
        return "$sign%.1f%%".format(value)
    }

    private fun relativeTimeShort(epochMs: Long): String {
        val minutes = Duration.ofMillis(System.currentTimeMillis() - epochMs).toMinutes()
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / (24 * 60)}d ago"
        }
    }

    private fun scanFailureSuffix(failedCount: Int): String =
        if (failedCount > 0) " ($failedCount failed)" else ""

    private fun regimeSortOrder(marketZoneId: String): Int = when (marketZoneId) {
        RthMarketSessions.US.zoneId -> 0
        RthMarketSessions.EUR.zoneId -> 1
        RthMarketSessions.HK.zoneId -> 2
        else -> 3
    }
}
