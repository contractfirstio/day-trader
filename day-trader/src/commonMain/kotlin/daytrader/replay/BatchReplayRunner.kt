package daytrader.replay

import daytrader.data.StrategyDeploymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Runs what-if backtests sequentially for every capture in the replay catalog, using each
 * deployment's current saved configuration.
 */
class BatchReplayRunner(
    private val controller: ReplaySessionController,
    private val repository: StrategyDeploymentRepository,
    private val loadBundle: (String) -> Result<SessionBundle>
) {
    private val _progress = MutableStateFlow(BatchReplayProgress())
    val progress: StateFlow<BatchReplayProgress> = _progress.asStateFlow()

    suspend fun runCatalog(catalog: List<ReplayCaptureRef>) {
        val targets = catalog.distinctBy { it.directoryPath }
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
            return
        }
        _progress.value = BatchReplayProgress(running = true, total = targets.size)
        val results = mutableListOf<ReplayBacktestResult>()
        var completed = 0
        var failed = 0
        for (target in targets) {
            _progress.update {
                it.copy(
                    currentSymbol = target.symbol,
                    currentDeploymentId = target.deploymentId,
                    completed = completed,
                    failed = failed
                )
            }
            val loaded = loadBundle(target.directoryPath)
            if (loaded.isFailure) {
                failed++
                results += ReplayBacktestResultBuilder.fromDeployment(
                    deployment = repository.deployments.value.find { it.id == target.deploymentId },
                    bundle = placeholderBundle(target),
                    captureDirectory = target.directoryPath,
                    errorMessage = loaded.exceptionOrNull()?.message ?: "Failed to load capture"
                )
                continue
            }
            val bundle = loaded.getOrThrow()
            if (!bundle.hasGroundTruth) {
                failed++
                results += ReplayBacktestResultBuilder.fromDeployment(
                    deployment = repository.deployments.value.find { it.id == bundle.deploymentId },
                    bundle = bundle,
                    captureDirectory = target.directoryPath,
                    errorMessage = "Capture missing session_closed ground truth"
                )
                continue
            }
            val result = runCatching { controller.runBacktestReplay(bundle, target.directoryPath) }
                .getOrElse { error ->
                    ReplayBacktestResultBuilder.fromDeployment(
                        deployment = repository.deployments.value.find { it.id == bundle.deploymentId },
                        bundle = bundle,
                        captureDirectory = target.directoryPath,
                        errorMessage = error.message ?: error::class.simpleName ?: "Replay failed"
                    )
                }
            results += result
            if (result.hasTangibleResult) {
                completed++
            } else {
                failed++
            }
        }
        val summary = ReplayBacktestResultBuilder.summarize(results)
        _progress.value = BatchReplayProgress(
            running = false,
            total = targets.size,
            completed = summary.tangibleResults,
            failed = summary.failed,
            summary = summary
        )
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
}
