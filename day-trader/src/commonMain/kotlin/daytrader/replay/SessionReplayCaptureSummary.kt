package daytrader.replay

import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.sessionRealizedPnL

/** Closed-session result extracted from a captured session bundle for replay picker display. */
data class SessionReplayCaptureSummary(
    val pnl: Double,
    val currencyCode: String,
    val positionOpened: Boolean,
    val outcome: TouchTurnSessionOutcome
)

fun SessionBundle.toReplayCaptureSummary(): SessionReplayCaptureSummary? {
    val groundTruth = groundTruth ?: return null
    val record = groundTruth.runRecord
    val deduped = groundTruth.dedupedFills
    val positionOpened = deduped.any { it.parentOrderId == 0 } ||
        (
            record.decision.outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED &&
                record.decision.executedLegs.isNotEmpty()
            )
    return SessionReplayCaptureSummary(
        pnl = deduped.sessionRealizedPnL(),
        currencyCode = record.marketInputs.currencyCode,
        positionOpened = positionOpened,
        outcome = record.decision.outcome
    )
}
