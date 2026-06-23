package daytrader.replay

import daytrader.diagnostics.SessionManifest
import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnSignalContext
import daytrader.gateway.LiveQuote

/**
 * Parsed session capture for hybrid/emulator replay and regression tests.
 *
 * Built by [SessionBundleLoader] from on-disk session logs (see Phase 0 capture).
 */
data class SessionBundle(
    val deploymentId: String,
    val sessionId: String,
    val symbol: String,
    val sessionDate: String?,
    val brokerKind: String?,
    val manifest: SessionManifest?,
    val timeline: SessionBundleTimeline,
    val historicalEvents: List<HistoricalEvent>,
    val quoteEvents: List<QuoteEvent>,
    val groundTruth: SessionGroundTruth?
) {
    val bootstrapContext: TouchTurnSignalContext?
        get() = historicalEvents.firstOrNull { !it.isClosedBarRefetch }?.context

    val refetchEvents: List<HistoricalEvent>
        get() = historicalEvents.filter { it.isClosedBarRefetch }

    /** Last refetch marked READY, or the final refetch if validation was not recorded. */
    val acceptedRefetchContext: TouchTurnSignalContext?
        get() = refetchEvents.lastOrNull { it.validation == "READY" }?.context
            ?: refetchEvents.lastOrNull()?.context

    val hasGroundTruth: Boolean
        get() = groundTruth != null
}

data class SessionBundleTimeline(
    val sessionStartedEpochMs: Long,
    val sessionStoppedEpochMs: Long?,
    val milestones: TouchTurnMilestoneTimestamps?,
    /**
     * Absolute epoch from captured `bracket_acknowledged` / `bracket_submitted` application log lines.
     * Used as the fill-anchor in quote [QuoteEvent.epochMs] space (not replay wall-clock milestones).
     */
    val ordersPlacedAnchorEpochMs: Long? = null,
)

data class HistoricalEvent(
    val epochMs: Long,
    val symbol: String,
    val isClosedBarRefetch: Boolean,
    val attempt: Int?,
    val validation: String?,
    val context: TouchTurnSignalContext
)

data class QuoteEvent(
    val epochMs: Long,
    val symbol: String,
    val quote: LiveQuote
)

data class SessionGroundTruth(
    val runRecord: TouchTurnRunRecord,
    val stopTrigger: String?,
    val rawFills: List<SessionTrade>,
    val dedupedFills: List<SessionTrade>
)

/** Raw file contents for [SessionBundleLoader.load]. */
data class SessionBundleContents(
    val manifestJson: String? = null,
    val applicationJsonl: String = "",
    val historicalJsonl: String = "",
    val pricesJsonl: String = "",
    val ibPriceTicksJsonl: String? = null
)
