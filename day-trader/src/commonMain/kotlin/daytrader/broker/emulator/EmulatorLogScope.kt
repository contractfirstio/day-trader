package daytrader.broker.emulator

import daytrader.data.persistence.AppDataFiles

/**
 * Routes [EmulatorLog] output to a session directory while a Touch Turn session is active.
 * Falls back to the global `{broker-scope}/emulator/engine.jsonl` when unbound.
 */
object EmulatorLogScope {
    private var deploymentId: String? = null
    private var sessionId: String? = null

    fun bind(deploymentId: String, sessionId: String) {
        this.deploymentId = deploymentId
        this.sessionId = sessionId
    }

    fun clear() {
        deploymentId = null
        sessionId = null
    }

    fun resolveEngineLogPath(): String {
        val dep = deploymentId
        val sess = sessionId
        return if (dep != null && sess != null) {
            AppDataFiles.sessionEmulatorEngineLogFileName(dep, sess)
        } else {
            AppDataFiles.emulatorEngineLogFileName()
        }
    }
}
