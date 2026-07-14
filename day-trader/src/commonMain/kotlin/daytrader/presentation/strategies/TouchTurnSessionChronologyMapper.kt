package daytrader.presentation.strategies

import daytrader.domain.OhlcBar
import daytrader.domain.SessionFillDisplay
import daytrader.domain.SessionTradeDetailsBuilder
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.effectivePnL
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.presentation.Formatters
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class TouchTurnSessionChronologyEvent(
    val timeLabel: String,
    val title: String,
    val detail: String? = null,
)

data class TouchTurnSessionChronologyUi(
    val events: List<TouchTurnSessionChronologyEvent>,
)

/**
 * Chronological outline of a closed Touch Turn run for the Trading-tab Close step.
 * Merges pipeline milestones, decision/stop reason, and broker fills by wall time.
 */
object TouchTurnSessionChronologyMapper {

    fun fromClosedRun(closedRun: StrategySession): TouchTurnSessionChronologyUi {
        val record = closedRun.touchTurnRunRecord
        val milestones = record?.milestones
            ?: closedRun.touchTurnMilestones
            ?: TouchTurnMilestoneTimestamps()
        val draft = mutableListOf<DraftEvent>()
        val currency = record?.marketInputs?.currencyCode ?: "USD"

        addMilestone(
            draft = draft,
            iso = milestones.startingSessionAt ?: closedRun.startedAt.takeIf { it.isNotBlank() },
            title = "Session started",
            detail = startDetail(closedRun, record),
            sequence = 0,
        )
        record?.marketInputs?.dataErrorMessage?.takeIf { it.isNotBlank() }?.let { err ->
            addMilestone(
                draft = draft,
                iso = milestones.dataFailedAt ?: milestones.dataReadyAt,
                title = "Market data failed",
                detail = err,
                sequence = 10,
            )
        }
        addMilestone(
            draft = draft,
            iso = milestones.dataReadyAt,
            title = "Market data ready",
            detail = dataReadyDetail(record),
            sequence = 20,
        )
        addMilestone(
            draft = draft,
            iso = milestones.barClosedAt,
            title = "Opening bar closed",
            detail = openingBarDetail(record),
            sequence = 30,
        )
        addMilestone(
            draft = draft,
            iso = milestones.liquidityEvaluatedAt,
            title = "Liquidity evaluated",
            detail = liquidityDetail(record),
            sequence = 40,
        )
        addMilestone(
            draft = draft,
            iso = milestones.closeConfirmedAt,
            title = "Close confirmed",
            detail = "Opening-bar close confirmation passed",
            sequence = 50,
        )
        addMilestone(
            draft = draft,
            iso = milestones.fiveMinConfirmedAt,
            title = "5-minute confirmation",
            detail = fiveMinDetail(record),
            sequence = 60,
        )

        val outcome = record?.decision?.outcome
        if (outcome != null && outcome != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED) {
            val decisionIso = milestones.liquidityEvaluatedAt
                ?: milestones.closeConfirmedAt
                ?: milestones.fiveMinConfirmedAt
                ?: milestones.dataFailedAt
                ?: milestones.closingSessionAt
                ?: closedRun.stoppedAt.takeIf { it.isNotBlank() }
            val reason = TouchTurnSessionReasonUi.forDecisionOutcome(outcome)
            addMilestone(
                draft = draft,
                iso = decisionIso,
                title = "Decision",
                detail = listOfNotNull(reason.headline, reason.detail, record.decision.detailMessage)
                    .distinct()
                    .joinToString(" — "),
                sequence = 70,
            )
        }

        addMilestone(
            draft = draft,
            iso = milestones.ordersPlacedAt,
            title = "Orders placed",
            detail = ordersDetail(record),
            sequence = 80,
        )
        addMilestone(
            draft = draft,
            iso = milestones.positionOpenedAt,
            title = "Position opened",
            detail = positionOpenedDetail(record),
            sequence = 90,
        )

        SessionTradeDetailsBuilder.fillDisplays(closedRun.sessionTrades).forEachIndexed { index, fill ->
            draft += DraftEvent(
                sortKey = parseEpoch(fill.time) ?: Long.MAX_VALUE - 1000 + index,
                sequence = 100 + index,
                event = TouchTurnSessionChronologyEvent(
                    timeLabel = Formatters.milestoneTimeFromIso(fill.time) ?: shortFillTime(fill.time),
                    title = "${fill.roleLabel} fill",
                    detail = fillDetail(fill),
                ),
            )
        }

        val stopIso = milestones.closingSessionAt
            ?: closedRun.stoppedAt.takeIf { it.isNotBlank() }
        addMilestone(
            draft = draft,
            iso = stopIso,
            title = "Session closed",
            detail = stopDetail(closedRun, record),
            sequence = 200,
        )

        val events = draft
            .sortedWith(compareBy({ it.sortKey }, { it.sequence }))
            .map { it.event }
            .distinctBy { "${it.timeLabel}|${it.title}|${it.detail}" }
        return TouchTurnSessionChronologyUi(events = events)
    }

    private data class DraftEvent(
        val sortKey: Long,
        val sequence: Int,
        val event: TouchTurnSessionChronologyEvent,
    )

    private fun addMilestone(
        draft: MutableList<DraftEvent>,
        iso: String?,
        title: String,
        detail: String?,
        sequence: Int,
    ) {
        val stamp = iso?.takeIf { it.isNotBlank() } ?: return
        draft += DraftEvent(
            sortKey = parseEpoch(stamp) ?: sequence.toLong(),
            sequence = sequence,
            event = TouchTurnSessionChronologyEvent(
                timeLabel = Formatters.milestoneTimeFromIso(stamp) ?: "—",
                title = title,
                detail = detail?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun startDetail(closedRun: StrategySession, record: TouchTurnRunRecord?): String {
        val context = record?.runContext
        val startedBy = when (context?.startedBy ?: closedRun.touchTurnStartedBy) {
            TouchTurnSessionStartedBy.AUTO_MARKET_OPEN -> "Auto"
            TouchTurnSessionStartedBy.MANUAL, null -> "Manual"
        }
        val broker = context?.let { brokerShort(it.brokerKind, it.brokerId) } ?: "—"
        val maxDollars = context?.maxDollars ?: closedRun.maxAtRisk
        return "$startedBy · $broker · ${Formatters.maxAtRisk(maxDollars)}"
    }

    private fun dataReadyDetail(record: TouchTurnRunRecord?): String? {
        record ?: return null
        val parts = buildList {
            record.marketInputs.marketZoneId.takeIf { it.isNotBlank() }?.let {
                add(it.substringAfterLast('/').replace('_', ' '))
            }
            record.marketInputs.dailyAtr14?.let { add("Daily ATR ${fmt(it, record.marketInputs.currencyCode)}") }
            record.marketInputs.atr14?.let { add("15m ATR ${fmt(it, record.marketInputs.currencyCode)}") }
            record.marketInputs.adr14?.let { add("ADR ${fmt(it, record.marketInputs.currencyCode)}") }
            record.marketInputs.openingBar?.let { add(compactBar(it, record.marketInputs.currencyCode)) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun openingBarDetail(record: TouchTurnRunRecord?): String? =
        record?.marketInputs?.openingBar?.let { compactBar(it, record.marketInputs.currencyCode) }

    private fun liquidityDetail(record: TouchTurnRunRecord?): String? {
        record ?: return null
        val outcome = record.decision.outcome
        val liquid = when (outcome) {
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY -> false
            TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED -> null
            else -> true
        }
        val bracket = record.decision.plannedBracket
        return buildList {
            when (liquid) {
                true -> add("Liquid")
                false -> add("Not liquid")
                null -> add("Liquidity unknown")
            }
            bracket?.let {
                add(TouchTurnLogic.tradeSideLabel(it.side))
                add(
                    "entry ${fmt(it.entry, record.marketInputs.currencyCode)} / " +
                        "stop ${fmt(it.stopLoss, record.marketInputs.currencyCode)} / " +
                        "TP ${fmt(it.takeProfit, record.marketInputs.currencyCode)}"
                )
            }
            record.marketInputs.openingBar?.let { bar ->
                add("range ${fmt(bar.high - bar.low, record.marketInputs.currencyCode)}")
            }
        }.joinToString(" · ")
    }

    private fun fiveMinDetail(record: TouchTurnRunRecord?): String? {
        val state = record?.fiveMinuteConfirmation ?: return "5-minute confirmation recorded"
        val status = state.status.name.lowercase().replace('_', ' ')
        val hammer = state.confirmedHammerBar?.let { compactBar(it, record.marketInputs.currencyCode) }
        return listOfNotNull(status.replaceFirstChar { it.titlecase() }, hammer).joinToString(" · ")
    }

    private fun ordersDetail(record: TouchTurnRunRecord?): String? {
        record ?: return null
        val qty = record.decision.plannedQuantity
        val bracket = record.decision.plannedBracket ?: return qty?.let { "Qty $it" }
        val side = TouchTurnLogic.tradeSideLabel(bracket.side)
        val currency = record.marketInputs.currencyCode
        return buildString {
            append(side)
            qty?.let { append(" ×$it") }
            append(" · entry ${fmt(bracket.entry, currency)}")
            append(" · stop ${fmt(bracket.stopLoss, currency)}")
            append(" · TP ${fmt(bracket.takeProfit, currency)}")
        }
    }

    private fun positionOpenedDetail(record: TouchTurnRunRecord?): String? {
        val bracket = record?.decision?.plannedBracket ?: return null
        val qty = record.decision.plannedQuantity
        return buildString {
            append(TouchTurnLogic.tradeSideLabel(bracket.side))
            qty?.let { append(" ×$it") }
            append(" @ ${fmt(bracket.entry, record.marketInputs.currencyCode)}")
        }
    }

    private fun fillDetail(fill: SessionFillDisplay): String {
        val currency = fill.currency
        return buildString {
            append(fill.actionLabel)
            append(" ${fill.quantity} @ ${Formatters.moneyPlain(fill.price, currency)}")
            fill.commission?.let {
                append(" · commission ${Formatters.money(-it, currency, showSign = true)}")
            }
            fill.realizedPnL?.takeIf { kotlin.math.abs(it) >= 0.005 }?.let {
                append(" · P&L ${Formatters.money(it, currency, showSign = true)}")
            }
        }
    }

    private fun stopDetail(closedRun: StrategySession, record: TouchTurnRunRecord?): String {
        if (record == null) {
            val pnl = closedRun.effectivePnL()
            return "Stopped · ${Formatters.runPnLDisplay(pnl, closedRun.positionOpened)}"
        }
        val trigger = TouchTurnRunRecordUiMapper.effectiveStopTrigger(record, closedRun)
        val stopUi = TouchTurnSessionReasonUi.forStopTrigger(
            trigger = trigger,
            stopErrorMessage = record.stopEvent.stopErrorMessage,
            decisionOutcome = record.decision.outcome,
        )
        val pnl = record.stopEvent.brokerUnrealizedPnLAtStop ?: closedRun.effectivePnL()
        return buildList {
            add(stopUi.headline)
            stopUi.detail?.let(::add)
            add("P&L ${Formatters.money(pnl, record.marketInputs.currencyCode, showSign = true)}")
        }.joinToString(" — ")
    }

    private fun compactBar(bar: OhlcBar, currency: String): String {
        val prices = "${fmt(bar.open, currency)}/${fmt(bar.high, currency)}/" +
            "${fmt(bar.low, currency)}/${fmt(bar.close, currency)}"
        val time = bar.time?.trim()?.takeIf { it.isNotEmpty() }
        return if (time != null) "OHLC $prices ($time)" else "OHLC $prices"
    }

    private fun fmt(price: Double, currency: String): String =
        Formatters.moneyPlain(price, currency).trim()

    private fun brokerShort(kind: BrokerKind?, brokerId: BrokerId): String = when (kind) {
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Paper·IB"
        BrokerKind.REPLAY -> "Replay"
        BrokerKind.EMULATOR -> "Emu"
        BrokerKind.INTERACTIVE_BROKERS -> "IB"
        null -> when (brokerId) {
            BrokerId.INTERACTIVE_BROKERS -> "IB"
            BrokerId.EMULATOR -> "Emu"
        }
    }

    private fun shortFillTime(raw: String): String {
        val tIndex = raw.indexOf('T')
        return if (tIndex >= 0 && raw.length >= tIndex + 6) {
            raw.substring(tIndex + 1, tIndex + 6)
        } else {
            raw.ifBlank { "—" }
        }
    }

    private fun parseEpoch(iso: String): Long? {
        val trimmed = iso.trim()
        if (trimmed.isEmpty()) return null
        val candidates = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        )
        for (formatter in candidates) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
                    .atZone(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try next
            }
        }
        // Truncate fractional seconds if needed
        val tIndex = trimmed.indexOf('T')
        if (tIndex > 0 && trimmed.length >= tIndex + 9) {
            val compact = trimmed.substring(0, (tIndex + 9).coerceAtMost(trimmed.length))
            return runCatching {
                LocalDateTime.parse(compact, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }
        return null
    }
}
