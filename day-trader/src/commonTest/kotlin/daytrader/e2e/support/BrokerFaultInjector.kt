package daytrader.e2e.support

import daytrader.gateway.BrokerGateway
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.delay

/**
 * Controlled broker faults for E2E and integration tests.
 */
object BrokerFaultInjector {
    suspend fun disconnectReconnect(
        gateway: BrokerGateway,
        settleMs: Long = 50L,
    ) {
        gateway.disconnect()
        delay(settleMs)
        gateway.connect()
        delay(settleMs)
    }

    fun orphanLimitOrder(symbol: String, orderId: Int = 9_001): WorkingOrder =
        WorkingOrder(
            orderId = orderId,
            symbol = symbol,
            action = "BUY",
            quantity = 10,
            filled = 0,
            remaining = 10,
            orderType = "LMT",
            limitPrice = 100.0,
            stopPrice = null,
            status = "Submitted",
            currency = "USD",
        )
}
