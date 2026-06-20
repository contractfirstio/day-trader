package daytrader.data

/**
 * Drains debounced persistence queues before process exit.
 */
object PersistenceDrain {
    fun flushAllBlocking(
        deployments: StrategyDeploymentRepository,
        watchlists: WatchlistRepository? = null,
        liquidity: LiquidityBucketRepository? = null,
        appState: StrategiesAppStateRepository? = null,
        replaySettings: ReplaySettingsRepository? = null,
    ) {
        deployments.flushPersistenceBlocking()
        watchlists?.flushPersistenceBlocking()
        liquidity?.flushPersistenceBlocking()
        appState?.flushPersistenceBlocking()
        replaySettings?.flushPersistenceBlocking()
    }
}
