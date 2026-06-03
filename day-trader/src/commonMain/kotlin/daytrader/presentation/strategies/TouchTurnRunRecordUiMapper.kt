package daytrader.presentation.strategies

import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.OhlcBar
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRunContext
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.presentation.Formatters

/** Compact, collapsible Touch Turn session facts for session history rows. */
data class TouchTurnRunRecordUi(
    /** One-line hint when collapsed (outcome + stop). */
    val teaser: String,
    /** Dense detail when the row is expanded; middle-dot separated. */
    val body: String
)

object TouchTurnRunRecordUiMapper {
    fun from(record: TouchTurnRunRecord, session: StrategySession? = null): TouchTurnRunRecordUi {
        val currency = record.marketInputs.currencyCode
        val stopTrigger = effectiveStopTrigger(record, session)
        val outcomeLine = TouchTurnSessionReasonUi.forDecisionOutcome(record.decision.outcome).headline
        val stopLine = TouchTurnSessionReasonUi.forStopTrigger(
            trigger = stopTrigger,
            stopErrorMessage = record.stopEvent.stopErrorMessage,
            decisionOutcome = record.decision.outcome
        ).headline
        val teaser = "$outcomeLine · $stopLine"
        val body = buildList {
            add(
                buildString {
                    append(startedByShort(record.runContext.startedBy))
                    append(" · ")
                    append(brokerShort(record.runContext))
                    append(" · ")
                    append(Formatters.maxAtRisk(record.runContext.maxDollars))
                }
            )
            record.marketInputs.dataErrorMessage?.let { add("Err: $it") }
            record.marketInputs.adr14?.let { adr ->
                add("ADR ${Formatters.moneyPlain(adr, currency)}")
            }
            record.marketInputs.openingBar?.let { add(compactBar(it, currency)) }
            record.decision.plannedBracket?.let { bracket ->
                val qty = record.decision.plannedQuantity ?: 0
                add(
                    "${TouchTurnLogic.tradeSideLabel(bracket.side).take(1)}×$qty " +
                        "${fmt(bracket.entry, currency)}/" +
                        "${fmt(bracket.stopLoss, currency)}/" +
                        "${fmt(bracket.takeProfit, currency)}"
                )
            }
            val pnlAtStop = record.stopEvent.brokerUnrealizedPnLAtStop ?: session?.pnl
            pnlAtStop?.let { pnl ->
                add("PnL@stop ${Formatters.money(pnl, currency, showSign = true)}")
            }
            record.stopEvent.stopErrorMessage?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        return TouchTurnRunRecordUi(teaser = teaser, body = body)
    }

    fun liquidityYesNo(record: TouchTurnRunRecord): String = when (record.decision.outcome) {
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY -> "No"
        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED -> "—"
        else -> "Yes"
    }

    fun ordersYesNo(record: TouchTurnRunRecord): String = when (record.decision.outcome) {
        TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED -> "Yes"
        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED -> "—"
        else -> "No"
    }

    fun effectiveStopTrigger(record: TouchTurnRunRecord, session: StrategySession?): TouchTurnSessionStopTrigger {
        if (record.stopEvent.stopTrigger != TouchTurnSessionStopTrigger.MANUAL) {
            return record.stopEvent.stopTrigger
        }
        if (record.milestones.closingSessionAt != null &&
            record.decision.outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
        ) {
            return TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN
        }
        if (session != null &&
            record.decision.outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED &&
            DeploymentSessionStopLogic.tradeCycleComplete(session.sessionTrades)
        ) {
            return TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN
        }
        return record.stopEvent.stopTrigger
    }

    private fun fmt(price: Double, currency: String): String =
        Formatters.moneyPlain(price, currency).trim()

    private fun compactBar(bar: OhlcBar, currency: String): String {
        val prices = "${fmt(bar.open, currency)}/${fmt(bar.high, currency)}/" +
            "${fmt(bar.low, currency)}/${fmt(bar.close, currency)}"
        val time = bar.time?.trim()?.takeIf { it.isNotEmpty() }
        return if (time != null) "Bar $prices ($time)" else "Bar $prices"
    }

    private fun outcomeShort(outcome: TouchTurnSessionOutcome): String = when (outcome) {
        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED -> "Data fail"
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY -> "Not liq."
        TouchTurnSessionOutcome.NO_TRADE_DOJI -> "Doji"
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED -> "Close gate"
        TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED -> "Live close"
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE -> "No touch"
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE -> "No quote"
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED -> "Window expired"
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED -> "No order"
        TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION -> "Volume exhaustion"
        TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED -> "Bracket"
    }

    private fun stopShort(trigger: TouchTurnSessionStopTrigger): String = when (trigger) {
        TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN -> "Trade closed"
        TouchTurnSessionStopTrigger.NO_TRADE_DECISION -> "No trade"
        TouchTurnSessionStopTrigger.OPEN_DEADLINE -> "Deadline"
        TouchTurnSessionStopTrigger.MANUAL -> "Manual"
        TouchTurnSessionStopTrigger.PRE_MARKET_CLOSE -> "Pre-close"
        TouchTurnSessionStopTrigger.ERROR -> "Error"
        TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN -> "App exit"
    }

    private fun startedByShort(startedBy: TouchTurnSessionStartedBy): String = when (startedBy) {
        TouchTurnSessionStartedBy.MANUAL -> "Manual"
        TouchTurnSessionStartedBy.AUTO_MARKET_OPEN -> "Auto"
    }

    private fun brokerShort(context: TouchTurnRunContext): String = when (context.brokerKind) {
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Paper·IB"
        BrokerKind.EMULATOR -> "Emu"
        BrokerKind.INTERACTIVE_BROKERS -> "IB"
        null -> brokerShort(context.brokerId)
    }

    private fun brokerShort(brokerId: BrokerId): String = when (brokerId) {
        BrokerId.INTERACTIVE_BROKERS -> "IB"
        BrokerId.EMULATOR -> "Emu"
    }
}
