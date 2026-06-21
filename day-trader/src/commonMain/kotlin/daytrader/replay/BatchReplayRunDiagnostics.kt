package daytrader.replay

import daytrader.domain.TouchTurnSessionOutcome

/** Per-session telemetry collected during a headless batch what-if run. */
data class BatchReplaySessionDiagnostic(
    val deploymentId: String,
    val symbol: String,
    val captureDirectory: String,
    val elapsedMs: Long,
    val outcome: TouchTurnSessionOutcome?,
    val pnl: Double,
    val roundTrips: Int,
    val hasTangibleResult: Boolean,
    val usedGroundTruthFills: Boolean,
    val errorMessage: String?,
)

/** Aggregate telemetry for the last [BatchReplayRunner.runCatalog] invocation. */
data class BatchReplayRunDiagnostics(
    val catalogTargetCount: Int,
    val resultsCount: Int,
    val tangibleResults: Int,
    val failedResults: Int,
    val totalElapsedMs: Long,
    val sessions: List<BatchReplaySessionDiagnostic>,
) {
    fun toTraceDetails(): Map<String, String> {
        val sessionSummary = sessions.joinToString("; ") { session ->
            "${session.deploymentId}:${session.symbol}:${session.elapsedMs}ms:" +
                "pnl=${session.pnl}:outcome=${session.outcome}:ok=${session.hasTangibleResult}"
        }
        return mapOf(
            "catalogTargetCount" to catalogTargetCount.toString(),
            "resultsCount" to resultsCount.toString(),
            "tangibleResults" to tangibleResults.toString(),
            "failedResults" to failedResults.toString(),
            "totalElapsedMs" to totalElapsedMs.toString(),
            "sessions" to sessionSummary,
        )
    }

    fun assertContract(
        expectedSessionCount: Int,
        maxTotalElapsedMs: Long? = null,
        requirePositiveTradePnlForDeploymentIds: Set<String> = emptySet(),
    ) {
        val issues = mutableListOf<String>()
        if (catalogTargetCount != expectedSessionCount) {
            issues += "catalog targets=$catalogTargetCount expected=$expectedSessionCount"
        }
        if (resultsCount != expectedSessionCount) {
            issues += "summary results=$resultsCount expected=$expectedSessionCount (only ${resultsCount} session(s) produced results)"
        }
        maxTotalElapsedMs?.let { budget ->
            if (totalElapsedMs > budget) {
                issues += "total elapsed ${totalElapsedMs}ms exceeds budget ${budget}ms"
            }
        }
        for (deploymentId in requirePositiveTradePnlForDeploymentIds) {
            val session = sessions.find { it.deploymentId == deploymentId }
            if (session == null) {
                issues += "missing diagnostic for deployment $deploymentId"
                continue
            }
            if (!session.hasTangibleResult) {
                issues += "$deploymentId: not tangible (${session.errorMessage ?: session.outcome})"
            } else if (session.pnl <= 0.0) {
                issues += "$deploymentId: expected positive P&L, got ${session.pnl} outcome=${session.outcome}"
            } else if (session.outcome == TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED) {
                issues += "$deploymentId: ended in NO_TRADE_DATA_FAILED"
            }
        }
        if (issues.isNotEmpty()) {
            val perSession = sessions.joinToString("\n") { diagnostic ->
                "  · ${diagnostic.deploymentId} ${diagnostic.symbol} ${diagnostic.elapsedMs}ms " +
                    "pnl=${diagnostic.pnl} outcome=${diagnostic.outcome} tangible=${diagnostic.hasTangibleResult}" +
                    (diagnostic.errorMessage?.let { " err=$it" }.orEmpty())
            }
            error(
                buildString {
                    append("Batch replay contract failed:\n")
                    issues.forEach { append("  - ").append(it).append('\n') }
                    append("Sessions:\n").append(perSession)
                }
            )
        }
    }
}
