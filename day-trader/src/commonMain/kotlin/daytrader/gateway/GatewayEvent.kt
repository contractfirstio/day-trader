package daytrader.gateway

import daytrader.domain.OhlcBar
import daytrader.domain.InstrumentResolution
import daytrader.domain.ReversalScoreMacroVolSnapshot
import daytrader.domain.ReversalScoreSymbolSnapshot
import daytrader.domain.SpyRegimeSnapshot
import daytrader.domain.TouchTurnSignalContext

sealed interface GatewayEvent {
    data class ConnectionStateChanged(val state: GatewayConnectionState) : GatewayEvent

    data class PositionsSnapshot(val positions: List<AccountPosition>) : GatewayEvent

    data class OpenOrdersSnapshot(val orders: List<WorkingOrder>) : GatewayEvent

    /**
     * Emitted after bracket legs are submitted to the broker (emulator) or queued to TWS (IB).
     * [OpenOrdersSnapshot] may follow asynchronously on IB when callbacks arrive.
     */
    data class TouchTurnBracketPlaced(val ack: TouchTurnBracketAck) : GatewayEvent

    data class FillsSnapshot(val fills: List<BrokerFill>) : GatewayEvent

    /** Live quote snapshots for UI display (bid/ask/last). */
    data class QuotesSnapshot(val quotes: Map<String, LiveQuote>) : GatewayEvent

    data class FirstFifteenMinuteCandleReady(
        val requestId: Long,
        val result: Result<OhlcBar>
    ) : GatewayEvent

    data class FourteenDayAdrReady(
        val requestId: Long,
        val result: Result<Double>
    ) : GatewayEvent

    data class TouchTurnSignalContextReady(
        val requestId: Long,
        val result: Result<TouchTurnSignalContext>
    ) : GatewayEvent

    data class InstrumentResolved(
        val requestId: Long,
        val result: Result<InstrumentResolution>
    ) : GatewayEvent

    data class LatestDailyCloseReady(
        val requestId: Long,
        val result: Result<Double>
    ) : GatewayEvent

    data class ReversalScoreSymbolSnapshotReady(
        val requestId: Long,
        val result: Result<ReversalScoreSymbolSnapshot>
    ) : GatewayEvent

    data class ReversalScoreMacroVolatilityReady(
        val requestId: Long,
        val result: Result<ReversalScoreMacroVolSnapshot>
    ) : GatewayEvent

    data class SpyRegimeSnapshotReady(
        val requestId: Long,
        val result: Result<SpyRegimeSnapshot>
    ) : GatewayEvent
}
