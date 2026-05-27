package daytrader.data.persistence

import daytrader.domain.SessionStatus
import daytrader.domain.SessionTrade
import daytrader.domain.StrategySession
import daytrader.domain.dedupeByExecId
import daytrader.domain.roundTripCount

/**
 * Reduces what we persist in [deployments.json] to what the running app needs.
 * Verbose / forensic detail belongs in [daytrader.diagnostics.SessionTrace].
 */
internal object SessionPersistenceSlimmer {

    fun toSlimTradeRecord(trade: SessionTrade): SessionTradeRecord =
        SessionTradeRecord(
            execId = trade.execId,
            orderId = trade.orderId,
            permId = trade.orderId.toLong(),
            parentOrderId = trade.parentOrderId,
            side = trade.side,
            quantity = trade.quantity,
            price = trade.price,
            time = trade.time,
            currency = trade.currency,
            commission = null,
            realizedPnL = trade.realizedPnL
        )

    fun prepareClosedSessionForPersist(session: StrategySession): StrategySession {
        val deduped = session.sessionTrades.dedupeByExecId()
        val runRecord = session.touchTurnRunRecord
        return session.copy(
            trades = deduped.roundTripCount(),
            sessionTrades = deduped,
            touchTurnMilestones = if (runRecord != null) null else session.touchTurnMilestones,
            touchTurnStartedBy = if (runRecord != null) null else session.touchTurnStartedBy
        )
    }

    fun prepareDeploymentSessionsForPersist(sessions: List<StrategySession>): List<StrategySession> =
        sessions.map { session ->
            if (session.status == SessionStatus.CLOSED) {
                prepareClosedSessionForPersist(session)
            } else {
                session
            }
        }

    fun restoreMilestonesFromRecord(
        record: SessionHistoryRecord
    ): TouchTurnMilestoneTimestampsRecord? =
        record.touchTurnMilestones
            ?: record.touchTurnRunRecord?.milestones

    fun restoreStartedByFromRecord(record: SessionHistoryRecord): String? =
        record.touchTurnStartedBy
            ?: record.touchTurnRunRecord?.runContext?.startedBy
}
