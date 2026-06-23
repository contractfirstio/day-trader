package daytrader.replay

import daytrader.domain.StrategyDeployment

/** Options for [ReplaySessionController.runBacktestReplay]. */
data class ReplayBacktestOptions(
    /**
     * When true (batch Run Replay), session outcomes are computed in memory and the caller
     * restores deployment snapshots until all catalog items finish.
     */
    val deferRepositoryUpdates: Boolean = false,
    /**
     * When true, overlay captured ground-truth fills after replay (verify-capture / CI only).
     * Batch and headless backtest default to emulator re-simulation only.
     */
    val applyGroundTruthFills: Boolean = false,
)

/** Result of one headless backtest run, including the post-replay deployment when available. */
data class ReplayBacktestRun(
    val result: ReplayBacktestResult,
    val deploymentAfterReplay: StrategyDeployment?,
)
