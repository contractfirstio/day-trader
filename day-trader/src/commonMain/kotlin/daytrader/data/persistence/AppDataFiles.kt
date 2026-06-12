package daytrader.data.persistence

object AppDataFiles {
    /** Global IB Gateway connection settings (app data root, not broker-scoped). */
    const val IB_GATEWAY_SETTINGS = "ib-gateway.json"

    const val DEPLOYMENTS = "deployments.json"
    const val DEPLOYMENTS_BACKUP = "deployments.json.bak"
    const val STRATEGIES_SCREEN = "strategies-screen.json"
    const val LIQUIDITY_BUCKETS = "liquidity-buckets.json"
    /** Broker-scoped: `{broker-scope}/watchlists.json` (separate file per emulator / hybrid / IB / replay). */
    const val WATCHLISTS = "watchlists.json"

    /**
     * Per-session log root (paired application + price logs).
     * macOS: `~/Library/Application Support/Day Trader/{broker-scope}/sessions/...`
     */
    const val SESSIONS_DIR = "sessions"
    const val SESSION_APPLICATION_LOG = "application.jsonl"
    const val SESSION_PRICES_LOG = "prices.jsonl"
    const val SESSION_HISTORICAL_LOG = "historical.jsonl"
    const val SESSION_MANIFEST = "manifest.json"
    const val SESSION_EMULATOR_ENGINE_LOG = "emulator-engine.jsonl"
    const val SESSION_PENDING_LOG = "_pending.jsonl"
    const val SESSION_ORPHAN_LOG = "orphan.jsonl"

    /**
     * Emulator broker/exchange diagnostics (not session-scoped — engine has no session id).
     * macOS: `~/Library/Application Support/Day Trader/{broker-scope}/emulator/...`
     */
    const val EMULATOR_DIR = "emulator"
    const val EMULATOR_ENGINE_LOG = "engine.jsonl"
    const val EMULATOR_PRICES_LOG = "prices.jsonl"

    /**
     * Execution gateway events (open-order snapshots, bracket acks) — correlates with session logs via epochMs.
     * macOS: `~/Library/Application Support/Day Trader/{broker-scope}/execution/gateway.jsonl`
     */
    const val EXECUTION_DIR = "execution"
    const val EXECUTION_GATEWAY_LOG = "gateway.jsonl"

    fun emulatorEngineLogFileName(): String =
        "$EMULATOR_DIR/$EMULATOR_ENGINE_LOG"

    fun executionGatewayLogFileName(): String =
        "$EXECUTION_DIR/$EXECUTION_GATEWAY_LOG"

    /**
     * Watchlist reversal score batch diagnostics.
     * macOS: `~/Library/Application Support/Day Trader/{broker-scope}/watchlist/reversal-score.jsonl`
     */
    const val WATCHLIST_DIR = "watchlist"
    const val REVERSAL_SCORE_LOG = "reversal-score.jsonl"

    fun reversalScoreLogFileName(): String =
        "$WATCHLIST_DIR/$REVERSAL_SCORE_LOG"

    fun emulatorPricesLogFileName(): String =
        "$EMULATOR_DIR/$EMULATOR_PRICES_LOG"

    const val IB_PRICES_DIR = "ib-prices"

    /** Directory for one session run's paired logs. */
    fun sessionDirectory(deploymentId: String, sessionId: String): String =
        "$SESSIONS_DIR/${safeFileNameComponent(deploymentId)}/${safeFileNameComponent(sessionId)}"

    /** Human-readable application events for one session. */
    fun sessionApplicationLogFileName(deploymentId: String, sessionId: String): String =
        "${sessionDirectory(deploymentId, sessionId)}/$SESSION_APPLICATION_LOG"

    /** High-volume quote updates for one session. */
    fun sessionPriceLogFileName(deploymentId: String, sessionId: String): String =
        "${sessionDirectory(deploymentId, sessionId)}/$SESSION_PRICES_LOG"

    /** Touch Turn bootstrap and closed-bar refetch payloads for session replay. */
    fun sessionHistoricalLogFileName(deploymentId: String, sessionId: String): String =
        "${sessionDirectory(deploymentId, sessionId)}/$SESSION_HISTORICAL_LOG"

    /** Session metadata and timeline anchors for replay. */
    fun sessionManifestFileName(deploymentId: String, sessionId: String): String =
        "${sessionDirectory(deploymentId, sessionId)}/$SESSION_MANIFEST"

    /** Emulator engine events scoped to one session (brackets, fills during replay). */
    fun sessionEmulatorEngineLogFileName(deploymentId: String, sessionId: String): String =
        "${sessionDirectory(deploymentId, sessionId)}/$SESSION_EMULATOR_ENGINE_LOG"

    /** Pre-session events before [sessionId] exists; flushed into application log on session start. */
    fun sessionPendingLogFileName(deploymentId: String): String =
        "$SESSIONS_DIR/${safeFileNameComponent(deploymentId)}/$SESSION_PENDING_LOG"

    /** Events with no deployment context. */
    fun sessionOrphanLogFileName(): String =
        "$SESSIONS_DIR/_unattributed/$SESSION_ORPHAN_LOG"

    /** Pre-terminology-refactor format (`instances` + `performance` keys). */
    const val LEGACY_INSTANCES_JSON = "instances.json"
    const val LEGACY_STRATEGY_INSTANCES = "strategy-instances.json"
    const val LEGACY_STRATEGIES_APP_STATE = "app-state.json"

    /** Per-symbol IB tick JSONL under `runs/{launchId}/{broker-scope}/ib-prices/`. */
    fun ibPriceLogFileName(symbolOrKey: String): String =
        "$IB_PRICES_DIR/${safeFileNameComponent(symbolOrKey)}.jsonl"

    fun safeFileNameComponent(value: String): String {
        val sanitized = value.replace(Regex("""[^\w.\-]"""), "_").trim('_', '.')
        return sanitized.take(120).ifBlank { "unknown" }
    }
}
