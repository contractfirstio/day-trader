package daytrader.replay

import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.dedupeByExecId
import daytrader.domain.effectivePnL
import daytrader.domain.hasCompleteCommissionData
import daytrader.domain.resolveTouchTurnSessionOutcome
import daytrader.domain.roundTripCount
import daytrader.domain.sessionDisplayPnL

/** Outcome of replaying one captured session with the deployment's current configuration. */
data class ReplayBacktestResult(
    val deploymentId: String,
    val symbol: String,
    val sessionDate: String?,
    val captureDirectory: String?,
    val outcome: TouchTurnSessionOutcome?,
    val pnl: Double,
    val roundTrips: Int,
    val hasTangibleResult: Boolean,
    val originalPnl: Double = 0.0,
    val originalOutcome: TouchTurnSessionOutcome? = null,
    val pnlDelta: Double = 0.0,
    val usedGroundTruthFills: Boolean = false,
    val errorMessage: String? = null
)

data class BatchReplayProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val currentSymbol: String? = null,
    val currentDeploymentId: String? = null,
    val summary: BatchReplaySummary? = null
) {
    val finishedCount: Int get() = completed + failed
}

data class BatchReplaySummary(
    val totalPnl: Double,
    val originalTotalPnl: Double,
    val totalPnlDelta: Double,
    val wins: Int,
    val losses: Int,
    val noTrades: Int,
    val tangibleResults: Int,
    val failed: Int,
    val groundTruthFillSessions: Int,
    val results: List<ReplayBacktestResult>
)

object ReplayBacktestResultBuilder {
    fun fromDeployment(
        deployment: StrategyDeployment?,
        bundle: SessionBundle,
        captureDirectory: String?,
        usedGroundTruthFills: Boolean = false,
        errorMessage: String? = null
    ): ReplayBacktestResult {
        val originalPnl = ReplayBacktestPolicy.originalPnl(bundle)
        val originalOutcome = ReplayBacktestPolicy.originalOutcome(bundle)
        if (deployment == null) {
            return ReplayBacktestResult(
                deploymentId = bundle.deploymentId,
                symbol = bundle.symbol,
                sessionDate = bundle.sessionDate,
                captureDirectory = captureDirectory,
                outcome = null,
                pnl = 0.0,
                roundTrips = 0,
                hasTangibleResult = false,
                originalPnl = originalPnl,
                originalOutcome = originalOutcome,
                pnlDelta = -originalPnl,
                usedGroundTruthFills = usedGroundTruthFills,
                errorMessage = errorMessage ?: "Deployment missing after replay"
            )
        }
        val closed = deployment.sessionHistory.lastOrNull { it.status == SessionStatus.CLOSED }
        val trades = closed?.sessionTrades?.dedupeByExecId().orEmpty()
        val outcome = resolveOutcome(deployment, closed)
        val pnl = trades.sessionDisplayPnL().takeIf { it != 0.0 || trades.hasCompleteCommissionData() }
            ?: (closed?.effectivePnL() ?: 0.0)
        val roundTrips = trades.roundTripCount().takeIf { it > 0 } ?: (closed?.trades ?: 0)
        val tangible = errorMessage == null && closed != null && outcome != null
        return ReplayBacktestResult(
            deploymentId = deployment.id,
            symbol = deployment.symbol,
            sessionDate = bundle.sessionDate ?: closed?.date,
            captureDirectory = captureDirectory,
            outcome = outcome,
            pnl = pnl,
            roundTrips = roundTrips,
            hasTangibleResult = tangible,
            originalPnl = originalPnl,
            originalOutcome = originalOutcome,
            pnlDelta = pnl - originalPnl,
            usedGroundTruthFills = usedGroundTruthFills,
            errorMessage = errorMessage
        )
    }

    fun resolveOutcome(
        deployment: StrategyDeployment,
        closed: daytrader.domain.StrategySession?,
    ): TouchTurnSessionOutcome? {
        closed?.touchTurnRunRecord?.decision?.outcome?.let { return it }
        deployment.touchTurnSession?.decisionOutcome?.let { return it }
        deployment.touchTurnSession?.let(::resolveTouchTurnSessionOutcome)?.let { return it }
        return null
    }

    fun summarize(results: List<ReplayBacktestResult>): BatchReplaySummary {
        var wins = 0
        var losses = 0
        var noTrades = 0
        var totalPnl = 0.0
        var originalTotalPnl = 0.0
        var tangible = 0
        var failed = 0
        var groundTruthFillSessions = 0
        for (result in results) {
            if (result.errorMessage != null || !result.hasTangibleResult) {
                failed++
                continue
            }
            tangible++
            totalPnl += result.pnl
            originalTotalPnl += result.originalPnl
            if (result.usedGroundTruthFills) groundTruthFillSessions++
            when {
                result.roundTrips > 0 && result.pnl > 0.0 -> wins++
                result.roundTrips > 0 && result.pnl < 0.0 -> losses++
                else -> noTrades++
            }
        }
        return BatchReplaySummary(
            totalPnl = totalPnl,
            originalTotalPnl = originalTotalPnl,
            totalPnlDelta = totalPnl - originalTotalPnl,
            wins = wins,
            losses = losses,
            noTrades = noTrades,
            tangibleResults = tangible,
            failed = failed,
            groundTruthFillSessions = groundTruthFillSessions,
            results = results
        )
    }
}
