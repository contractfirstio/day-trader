package daytrader.gateway

import daytrader.domain.InstrumentIdentity
import daytrader.domain.TouchTurnBracketResizeRequest
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnRuleConfig

sealed interface GatewayCommand {
    data object Connect : GatewayCommand

    data object Disconnect : GatewayCommand

    data object Reconnect : GatewayCommand

    data object Shutdown : GatewayCommand

    /** Clears emulator positions/orders/fills between sessions (no disconnect). */
    data object ResetSessionState : GatewayCommand

    data class FetchFirstFifteenMinuteCandle(
        val requestId: Long,
        val symbol: String,
        val instrument: InstrumentIdentity? = null
    ) : GatewayCommand

    data class FetchFourteenDayAdr(
        val requestId: Long,
        val symbol: String,
        val instrument: InstrumentIdentity? = null
    ) : GatewayCommand

    data class FetchTouchTurnSignalContext(
        val requestId: Long,
        val symbol: String,
        val instrument: InstrumentIdentity? = null,
        /** When true, reuse the bootstrap candle-color index for this symbol (closed-bar refetch). */
        val isClosedBarRefetch: Boolean = false,
        /** Deployment/session RTH zone; overrides instrument currency heuristics for IB bar day. */
        val marketZoneId: String? = null,
        /** Pre-open Prepare: succeed with ATR/volume when today's opening bar is not in history yet. */
        val allowMissingTodayOpeningBar: Boolean = false,
        val rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ) : GatewayCommand

    data class CancelOrder(val orderId: Int) : GatewayCommand

    data class ResolveInstrument(
        val requestId: Long,
        val symbol: String
    ) : GatewayCommand

    data class PlaceTouchTurnBracket(val plan: TouchTurnOrderPlan) : GatewayCommand

    data class ResizeTouchTurnBracket(
        val requestId: Long,
        val request: TouchTurnBracketResizeRequest
    ) : GatewayCommand

    /** Cancel all non-terminal working orders for [symbol] (session stop cleanup). */
    data class CancelOpenOrdersForSymbol(val symbol: String) : GatewayCommand

    /** Market-close an open position for [symbol] if quantity is non-zero (session stop cleanup). */
    data class CloseOpenPositionForSymbol(val symbol: String) : GatewayCommand

    /** Cancel working orders and close any open position for [symbol] (session stop). */
    data class FlattenSymbolForSymbol(val symbol: String) : GatewayCommand

    /** Reload executions/fills from the broker (IB: [reqExecutions]). */
    data object RequestExecutions : GatewayCommand

    /** One-shot latest daily bar close — does not hold a streaming market data line. */
    data class FetchLatestDailyClose(
        val requestId: Long,
        val symbol: String,
        val instrument: InstrumentIdentity? = null
    ) : GatewayCommand

    data class FetchReversalScoreSymbolSnapshot(
        val requestId: Long,
        val symbol: String,
        val instrument: InstrumentIdentity? = null
    ) : GatewayCommand

    data class FetchReversalScoreMacroVolatility(
        val requestId: Long
    ) : GatewayCommand

    data class FetchSpyRegimeSnapshot(
        val requestId: Long
    ) : GatewayCommand

    data class FetchHomeMarketRegimeSnapshot(
        val requestId: Long,
        val marketZoneId: String
    ) : GatewayCommand
}
