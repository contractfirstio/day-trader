package daytrader.data.persistence

object AppDataFiles {
    const val DEPLOYMENTS = "deployments.json"
    const val STRATEGIES_SCREEN = "strategies-screen.json"
    const val SESSION_TRACES_DIR = "session-traces"
    const val SESSION_TRACE_PENDING = "_pending.jsonl"
    const val SESSION_TRACE_ORPHAN = "orphan.jsonl"

    /** Verbose JSONL for one session run: `session-traces/{deploymentId}/{sessionId}.jsonl`. */
    fun sessionTraceFileName(deploymentId: String, sessionId: String): String =
        "$SESSION_TRACES_DIR/$deploymentId/$sessionId.jsonl"

    /** Pre-session events (ADR/candle load) before [sessionId] exists. Flushed on session start. */
    fun sessionTracePendingFileName(deploymentId: String): String =
        "$SESSION_TRACES_DIR/$deploymentId/$SESSION_TRACE_PENDING"

    /** Fills/events with no deployment context (e.g. emulator without active run). */
    fun sessionTraceUnattributedFileName(): String =
        "$SESSION_TRACES_DIR/_unattributed/$SESSION_TRACE_ORPHAN"

    /** Pre-terminology-refactor format (`instances` + `performance` keys). */
    const val LEGACY_INSTANCES_JSON = "instances.json"
    const val LEGACY_STRATEGY_INSTANCES = "strategy-instances.json"
    const val LEGACY_STRATEGIES_APP_STATE = "app-state.json"
}
