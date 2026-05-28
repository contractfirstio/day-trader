package daytrader.diagnostics

import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession

/**
 * Persistent trace of user-driven UI actions (append-only JSONL via [SessionTrace]).
 */
object UiActionLog {
    fun log(
        action: String,
        deploymentId: String? = null,
        sessionId: String? = null,
        symbol: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        SessionTrace.log(
            type = "ui_action",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("action", action)
                putAll(details)
            }
        )
    }

    fun forDeployment(
        deployment: StrategyDeployment?,
        action: String,
        details: Map<String, String> = emptyMap()
    ) {
        if (deployment == null) {
            log(action = action, details = details)
            return
        }
        val session = deployment.inProgressSession()
        log(
            action = action,
            deploymentId = deployment.id,
            sessionId = session?.id,
            symbol = deployment.symbol,
            details = details
        )
    }
}
