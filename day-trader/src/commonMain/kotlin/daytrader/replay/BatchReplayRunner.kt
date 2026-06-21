package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.StrategyDeployment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Runs what-if backtests sequentially for every capture in the replay catalog, using each
 * deployment's current saved configuration.
 *
 * Deployments stay visually idle during the run; outcomes are written once the full catalog
 * completes. Progress is exposed via [progress] for the replay control bar.
 */
class BatchReplayRunner(
    private val controller: ReplaySessionController,
    private val repository: StrategyDeploymentRepository,
    private val loadBundle: (String) -> Result<SessionBundle>,
    private val restoreEngineGlobalAutoStart: () -> Boolean = { true },
) {
    private val _progress = MutableStateFlow(BatchReplayProgress())
    val progress: StateFlow<BatchReplayProgress> = _progress.asStateFlow()

    /** Telemetry from the most recent [runCatalog]; null before the first run. */
    var lastRunDiagnostics: BatchReplayRunDiagnostics? = null
        private set

    suspend fun runCatalog(catalog: List<ReplayCaptureRef>) = withContext(controller.engineCoroutineContext) {
        val targets = catalog.distinctBy { it.directoryPath }
        val runStartedMs = System.currentTimeMillis()
        val sessionDiagnostics = mutableListOf<BatchReplaySessionDiagnostic>()
        if (targets.isEmpty()) {
            _progress.value = BatchReplayProgress(
                running = false,
                summary = BatchReplaySummary(
                    totalPnl = 0.0,
                    originalTotalPnl = 0.0,
                    totalPnlDelta = 0.0,
                    wins = 0,
                    losses = 0,
                    noTrades = 0,
                    tangibleResults = 0,
                    failed = 0,
                    groundTruthFillSessions = 0,
                    results = emptyList()
                )
            )
            return@withContext
        }

        val snapshots = seedAndSnapshotDeployments(targets)
        _progress.value = BatchReplayProgress(running = true, total = targets.size)
        controller.beginBatchReplayIsolation()
        val results = mutableListOf<ReplayBacktestResult>()
        val pendingOutcomes = mutableListOf<BatchReplayPendingOutcome>()
        var completed = 0
        var failed = 0

        try {
            for (target in targets) {
                _progress.update {
                    it.copy(
                        currentSymbol = target.symbol,
                        currentDeploymentId = target.deploymentId,
                        completed = completed,
                        failed = failed
                    )
                }
                val snapshot = snapshots[target.deploymentId]
                val loaded = loadBundle(target.directoryPath)
                if (loaded.isFailure) {
                    failed++
                    val loadError = loaded.exceptionOrNull()?.message ?: "Failed to load capture"
                    val loadResult = ReplayBacktestResultBuilder.fromDeployment(
                        deployment = repository.deployments.value.find { it.id == target.deploymentId },
                        bundle = placeholderBundle(target),
                        captureDirectory = target.directoryPath,
                        errorMessage = loadError
                    )
                    results += loadResult
                    sessionDiagnostics += loadResult.toDiagnostic(target, elapsedMs = 0L)
                    continue
                }
                val bundle = loaded.getOrThrow()
                if (!bundle.hasGroundTruth) {
                    failed++
                    val missingGt = ReplayBacktestResultBuilder.fromDeployment(
                        deployment = repository.deployments.value.find { it.id == bundle.deploymentId },
                        bundle = bundle,
                        captureDirectory = target.directoryPath,
                        errorMessage = "Capture missing session_closed ground truth"
                    )
                    results += missingGt
                    sessionDiagnostics += missingGt.toDiagnostic(target, elapsedMs = 0L)
                    continue
                }

                val sessionStartedMs = System.currentTimeMillis()
                val runOutcome = runCatching {
                    controller.runBacktestReplay(
                        bundle = bundle,
                        captureDirectory = target.directoryPath,
                        options = ReplayBacktestOptions(deferRepositoryUpdates = true),
                    )
                }
                restoreSnapshot(snapshot, bundle.deploymentId)
                controller.cleanupAfterBacktestRun()

                val result = runOutcome.fold(
                    onSuccess = { run ->
                        pendingOutcomes += BatchReplayPendingOutcome(
                            deploymentId = bundle.deploymentId,
                            result = run.result,
                            deploymentAfterReplay = run.deploymentAfterReplay,
                        )
                        run.result
                    },
                    onFailure = { error ->
                        ReplayBacktestResultBuilder.fromDeployment(
                            deployment = snapshot,
                            bundle = bundle,
                            captureDirectory = target.directoryPath,
                            errorMessage = error.message ?: error::class.simpleName ?: "Replay failed"
                        )
                    }
                )
                results += result
                sessionDiagnostics += result.toDiagnostic(
                    target = target,
                    elapsedMs = (System.currentTimeMillis() - sessionStartedMs).coerceAtLeast(0L),
                )
                if (result.hasTangibleResult) {
                    completed++
                } else {
                    failed++
                }
            }
        } finally {
            controller.endBatchReplayIsolation(restoreEngineGlobalAutoStart())
        }

        BatchReplayOutcomeApplier.applyAll(repository, snapshots, pendingOutcomes)
        val summary = ReplayBacktestResultBuilder.summarize(results)
        lastRunDiagnostics = BatchReplayRunDiagnostics(
            catalogTargetCount = targets.size,
            resultsCount = summary.results.size,
            tangibleResults = summary.tangibleResults,
            failedResults = summary.failed,
            totalElapsedMs = (System.currentTimeMillis() - runStartedMs).coerceAtLeast(0L),
            sessions = sessionDiagnostics.toList(),
        )
        _progress.value = BatchReplayProgress(
            running = false,
            total = targets.size,
            completed = summary.tangibleResults,
            failed = summary.failed,
            summary = summary
        )
    }

    private fun seedAndSnapshotDeployments(targets: List<ReplayCaptureRef>): Map<String, StrategyDeployment> {
        targets.forEach { target ->
            loadBundle(target.directoryPath)
                .onSuccess { bundle -> ReplaySessionController.seedDeploymentIfNeeded(repository, bundle) }
        }
        return targets.mapNotNull { target ->
            repository.deployments.value
                .find { it.id == target.deploymentId }
                ?.let { target.deploymentId to it.copy(sessionHistory = it.sessionHistory.map { session -> session.copy() }) }
        }.toMap()
    }

    private fun restoreSnapshot(snapshot: StrategyDeployment?, deploymentId: String) {
        if (snapshot != null) {
            BatchReplayOutcomeApplier.restoreSnapshot(repository, deploymentId, snapshot)
        }
    }

    private fun placeholderBundle(target: ReplayCaptureRef): SessionBundle =
        SessionBundle(
            deploymentId = target.deploymentId,
            sessionId = "unknown",
            symbol = target.symbol ?: "UNKNOWN",
            sessionDate = target.sessionDate,
            brokerKind = null,
            manifest = null,
            timeline = SessionBundleTimeline(
                sessionStartedEpochMs = target.sessionStartedEpochMs ?: 0L,
                sessionStoppedEpochMs = null,
                milestones = null
            ),
            historicalEvents = emptyList(),
            quoteEvents = emptyList(),
            groundTruth = null
        )

    private fun ReplayBacktestResult.toDiagnostic(
        target: ReplayCaptureRef,
        elapsedMs: Long,
    ): BatchReplaySessionDiagnostic =
        BatchReplaySessionDiagnostic(
            deploymentId = deploymentId,
            symbol = symbol,
            captureDirectory = captureDirectory ?: target.directoryPath,
            elapsedMs = elapsedMs,
            outcome = outcome,
            pnl = pnl,
            roundTrips = roundTrips,
            hasTangibleResult = hasTangibleResult,
            usedGroundTruthFills = usedGroundTruthFills,
            errorMessage = errorMessage,
        )
}
