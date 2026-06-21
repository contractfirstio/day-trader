package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.ActiveExecution
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.withoutClosedSessionHistory

data class BatchReplayPendingOutcome(
    val deploymentId: String,
    val result: ReplayBacktestResult,
    val deploymentAfterReplay: StrategyDeployment?,
)

/** Applies batch replay outcomes after the full catalog completes. */
object BatchReplayOutcomeApplier {
    fun mergeReplayOutcome(baseline: StrategyDeployment, replayed: StrategyDeployment): StrategyDeployment {
        val newClosed = replayed.sessionHistory.filter { it.status == SessionStatus.CLOSED }
        val trimmed = baseline.withoutClosedSessionHistory()
        return trimmed.copy(
            sessionHistory = trimmed.sessionHistory + newClosed,
            status = DeploymentStatus.STOPPED,
            live = ActiveExecution.flat(),
            touchTurnSession = null,
        )
    }

    fun restoreSnapshot(
        repository: StrategyDeploymentRepository,
        deploymentId: String,
        snapshot: StrategyDeployment,
    ) {
        repository.update(deploymentId) { snapshot }
    }

    fun applyAll(
        repository: StrategyDeploymentRepository,
        snapshots: Map<String, StrategyDeployment>,
        outcomes: List<BatchReplayPendingOutcome>,
    ) {
        for (outcome in outcomes) {
            if (!outcome.result.hasTangibleResult) continue
            val baseline = snapshots[outcome.deploymentId] ?: continue
            val replayed = outcome.deploymentAfterReplay ?: continue
            repository.update(outcome.deploymentId) {
                mergeReplayOutcome(baseline, replayed)
            }
        }
        repository.flushPersistenceBlocking()
    }
}
