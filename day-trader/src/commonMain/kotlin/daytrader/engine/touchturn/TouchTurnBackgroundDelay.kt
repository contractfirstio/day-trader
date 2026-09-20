package daytrader.engine.touchturn

import daytrader.gateway.BrokerKind
import kotlinx.coroutines.delay

/**
 * Background poll loops in replay must use wall [delay] so [daytrader.replay.ReplayClock]
 * advances only under orchestrator / quote-drip control.
 */
internal suspend fun delayTouchTurnBackgroundLoop(
    brokerKind: BrokerKind,
    intervalMs: Long,
    delayMillis: suspend (Long) -> Unit
) {
    if (brokerKind == BrokerKind.REPLAY) {
        delay(intervalMs)
    } else {
        delayMillis(intervalMs)
    }
}
