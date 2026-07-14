package daytrader.presentation.strategies

import daytrader.domain.ExecutionState
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.domain.sessionEntryNotional
import daytrader.gateway.AccountPosition
import kotlin.math.abs

/**
 * Capital put to work in the active session.
 *
 * Preference order:
 * 1. Entry-fill notional from persisted in-progress [sessionTrades]
 * 2. Demo / Quick Flip: live FILLED qty × entry
 * 3. Emulator / IB / paper: broker position avg cost (Touch Turn leaves live FLAT)
 */
object DeploymentInvestedNotional {
    fun resolve(
        instance: StrategyDeployment,
        hasOpenPosition: Boolean,
        brokerPosition: AccountPosition? = null,
    ): Double? {
        if (!hasOpenPosition) return null
        val fromFills = instance.inProgressSession()
            ?.sessionTrades
            ?.sessionEntryNotional()
            ?.takeIf { it > 0.0 }
        if (fromFills != null) return fromFills
        val live = instance.live
        if (live.state == ExecutionState.FILLED) {
            val entry = live.entryPrice
            val qty = live.quantity
            if (entry != null && qty > 0) return qty * entry
        }
        val position = brokerPosition ?: return null
        val qty = abs(position.quantity)
        if (qty <= 0) return null
        return qty * position.avgPrice
    }
}
