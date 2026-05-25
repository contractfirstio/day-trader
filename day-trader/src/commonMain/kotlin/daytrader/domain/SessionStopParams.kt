package daytrader.domain

import daytrader.gateway.BrokerId

/** Extra context when a run stops (Touch Turn run record and stop trigger). */
data class SessionStopParams(
    val stopTrigger: TouchTurnSessionStopTrigger? = null,
    val brokerId: BrokerId? = null,
    val stopErrorMessage: String? = null,
    val brokerUnrealizedPnLAtStop: Double? = null,
    val hasOpenPosition: Boolean = false,
    val hasOpenOrders: Boolean = false
)
