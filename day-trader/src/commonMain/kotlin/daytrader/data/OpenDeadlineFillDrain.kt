package daytrader.data

import daytrader.broker.SessionTradeMatcher
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import kotlinx.coroutines.delay

/**
 * After OPEN_DEADLINE exit (tight stop or market fallback), IB may report flat positions before
 * executions arrive. Poll [BrokerGateway.fills] briefly so [TouchTurnManualStopHandler] snapshots
 * the exit fill.
 */
object OpenDeadlineFillDrain {
    const val FILL_DRAIN_TIMEOUT_MS = 5_000L
    const val POLL_INTERVAL_MS = 100L

    fun sessionFillExecIds(instance: StrategyDeployment, fills: List<BrokerFill>): Set<String> {
        val session = instance.inProgressSession() ?: return emptySet()
        return SessionTradeMatcher.fillsForSession(
            symbol = instance.symbol,
            startedAt = session.startedAt,
            stoppedAt = null,
            fills = fills
        ).map { it.execId }.toSet()
    }

    suspend fun awaitClosingFill(
        gateway: BrokerGateway,
        instance: StrategyDeployment,
        fillsSeenBefore: Set<String>,
        timeoutMs: Long = FILL_DRAIN_TIMEOUT_MS,
        pollIntervalMs: Long = POLL_INTERVAL_MS,
        delayMillis: suspend (Long) -> Unit = { delay(it) }
    ): List<BrokerFill> {
        val session = instance.inProgressSession() ?: return gateway.fills.value
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            gateway.refreshFills()
            val sessionFills = SessionTradeMatcher.fillsForSession(
                symbol = instance.symbol,
                startedAt = session.startedAt,
                stoppedAt = null,
                fills = gateway.fills.value
            )
            if (sessionFills.any { it.execId !in fillsSeenBefore }) {
                return gateway.fills.value
            }
            delayMillis(pollIntervalMs)
        }
        return gateway.fills.value
    }
}
