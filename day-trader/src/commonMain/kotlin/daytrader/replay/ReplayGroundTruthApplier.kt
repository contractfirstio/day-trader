package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.SessionStatus
import daytrader.domain.dedupeByExecId
import daytrader.domain.roundTripCount
import daytrader.domain.sessionRealizedPnL

/** Replaces emulator fills on a closed replay session with captured ground-truth fills. */
object ReplayGroundTruthApplier {
    fun apply(
        repository: StrategyDeploymentRepository,
        deploymentId: String,
        bundle: SessionBundle
    ) {
        val fills = bundle.groundTruth?.dedupedFills?.dedupeByExecId().orEmpty()
        if (fills.isEmpty()) return
        repository.update(deploymentId) { deployment ->
            val closed = deployment.sessionHistory.lastOrNull { it.status == SessionStatus.CLOSED }
                ?: return@update deployment
            val groundTruth = bundle.groundTruth ?: return@update deployment
            val pnl = fills.sessionRealizedPnL()
            val runRecord = closed.touchTurnRunRecord
            val updatedRecord = runRecord?.copy(
                decision = groundTruth.runRecord.decision,
                stopEvent = groundTruth.runRecord.stopEvent,
            ) ?: groundTruth.runRecord
            val updated = closed.copy(
                sessionTrades = fills,
                pnl = pnl,
                trades = fills.roundTripCount(),
                positionOpened = fills.any { it.parentOrderId == 0 },
                touchTurnRunRecord = updatedRecord,
            )
            deployment.copy(
                sessionHistory = deployment.sessionHistory.map { session ->
                    if (session.id == closed.id) updated else session
                }
            )
        }
    }
}
