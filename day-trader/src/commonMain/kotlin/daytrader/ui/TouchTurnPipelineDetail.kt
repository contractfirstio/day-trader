package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.DeploymentStatus
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnLogic
import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.inProgressSession
import daytrader.domain.applyToChartSetup
import daytrader.presentation.strategies.TouchTurnExecutedBracketLegs
import daytrader.presentation.strategies.toLevelKinds
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.LiquidityCalculationUi
import daytrader.presentation.strategies.CloseConfirmationUi
import daytrader.presentation.strategies.OpeningBarDetailUi
import daytrader.presentation.strategies.SessionDataCaptureUi
import daytrader.presentation.strategies.TouchTurnLiveOrderChartUiState
import daytrader.presentation.strategies.TouchTurnPipelineDetailUiMapper
import daytrader.presentation.strategies.TouchTurnPipelineGraph
import daytrader.presentation.strategies.TouchTurnPipelineNodeId
import daytrader.presentation.strategies.TouchTurnReasonSeverity
import daytrader.presentation.strategies.TouchTurnSessionReasonUi
import daytrader.presentation.strategies.TouchTurnSessionStatusUi
import daytrader.presentation.strategies.TouchTurnRunRecordUiMapper
import daytrader.presentation.strategies.detailTitle
import daytrader.presentation.strategies.fmt
import daytrader.presentation.strategies.formattedAdr14
import daytrader.presentation.strategies.isSelectable
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TouchTurnSessionStatusBanner(
    status: TouchTurnSessionStatusUi?,
    modifier: Modifier = Modifier
) {
    if (status == null) return
    val (background, border, headlineColor) = when (status.severity) {
        TouchTurnReasonSeverity.Error -> Triple(
            LossRed.copy(alpha = 0.12f),
            LossRed.copy(alpha = 0.55f),
            LossRed
        )
        TouchTurnReasonSeverity.Warning -> Triple(
            Color(0xFFFFB74D).copy(alpha = 0.12f),
            Color(0xFFFFB74D).copy(alpha = 0.5f),
            Color(0xFFFFB74D)
        )
        TouchTurnReasonSeverity.Info -> Triple(
            TableHeaderBg,
            TextSecondary.copy(alpha = 0.35f),
            Color.White
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnSessionStatusBanner"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            status.headline,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = headlineColor,
            lineHeight = 15.sp
        )
        status.detail?.let { detail ->
            Text(
                detail,
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 14.sp,
                modifier = Modifier.testTag("TouchTurnSessionStatusBannerDetail")
            )
        }
    }
}

@Composable
fun TouchTurnPipelineSectionStart(
    instance: StrategyDeployment,
    graph: TouchTurnPipelineGraph?,
    lastClosedRun: StrategySession? = null,
    modifier: Modifier = Modifier
) {
    val run = instance.inProgressSession() ?: lastClosedRun
    val startedAt = run?.startedAt?.takeIf { it.isNotBlank() }
        ?: graph?.node(TouchTurnPipelineNodeId.Start)?.timestamp
    val stoppedAt = run?.stoppedAt?.takeIf { it.isNotBlank() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Symbol ${instance.symbol} · max risk ${Formatters.currencyPlain(instance.maxDollars.toDouble())}",
            fontSize = 11.sp,
            color = TextSecondary
        )
        val runningHint = graph?.statusBanner?.detail
            ?: graph?.statusBanner?.headline
        Text(
            when {
                instance.status == DeploymentStatus.RUNNING && runningHint != null -> runningHint
                instance.status == DeploymentStatus.RUNNING ->
                    "Session is running — follow the pipeline for bar close, liquidity, and orders."
                run != null -> "Session ended — review each pipeline step above."
                else -> "Deployment stopped. Start a session to begin the next run."
            },
            fontSize = 12.sp,
            color = Color.White
        )
        startedAt?.let { time ->
            Text("Started $time", fontSize = 11.sp, color = TextSecondary)
        }
        stoppedAt?.let { time ->
            Text("Stopped $time", fontSize = 11.sp, color = TextSecondary)
        }
        Text(
            "Tap a pipeline step above to inspect that phase of the run.",
            fontSize = 10.sp,
            color = TextSecondary.copy(alpha = 0.85f)
        )
    }
}

@Composable
fun TouchTurnPipelineSectionClose(
    closedRun: StrategySession?,
    graph: TouchTurnPipelineGraph?,
    modifier: Modifier = Modifier
) {
    val stoppedAt = closedRun?.stoppedAt?.takeIf { it.isNotBlank() }
        ?: graph?.node(TouchTurnPipelineNodeId.Close)?.timestamp
    val record = closedRun?.touchTurnRunRecord
    val outcome = record?.decision?.outcome
    val stopStatus = record?.let { r ->
        val trigger = TouchTurnRunRecordUiMapper.effectiveStopTrigger(r, closedRun)
        TouchTurnSessionReasonUi.forStopTrigger(
            trigger = trigger,
            stopErrorMessage = r.stopEvent.stopErrorMessage,
            decisionOutcome = r.decision.outcome
        )
    }
    Column(
        modifier = modifier.fillMaxWidth().testTag("TouchTurnPipelineSectionClose"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Session closed",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
        stoppedAt?.let { time ->
            Text("Stopped $time", fontSize = 11.sp, color = TextSecondary)
        }
        stopStatus?.let { TouchTurnSessionStatusBanner(status = it) }
        outcome?.let { o ->
            val explanation = TouchTurnSessionReasonUi.forDecisionOutcome(o)
            Text(
                explanation.detail ?: explanation.headline,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
        Text(
            "Use the pipeline steps above to review bar, liquidity, orders, and trade detail for this run.",
            fontSize = 10.sp,
            color = TextSecondary.copy(alpha = 0.85f)
        )
    }
}

@Composable
fun TouchTurnPipelineSectionData(
    session: TouchTurnSessionContext?,
    symbol: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("TouchTurnPipelineSectionData"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            session == null -> Text(
                "Loading Touch Turn session for $symbol from broker…",
                fontSize = 12.sp,
                color = TextSecondary
            )
            session.status == TouchTurnCandleStatus.LOADING -> {
                Text(
                    "Loading opening bar and 14-day ADR from broker…",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Text(
                    "Requests: 14-day average daily range and the first 15-minute RTH candle for $symbol.",
                    fontSize = 10.sp,
                    color = TextSecondary.copy(alpha = 0.85f),
                    lineHeight = 13.sp
                )
            }
            session.status == TouchTurnCandleStatus.FAILED -> {
                TouchTurnSessionStatusBanner(
                    status = TouchTurnSessionReasonUi.forDecisionOutcome(
                        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                        session
                    )
                )
            }
            else -> {
                val capture = remember(session) {
                    TouchTurnPipelineDetailUiMapper.sessionDataCapture(session)
                }
                Text(
                    "Session data captured from broker.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GainGreen,
                    modifier = Modifier.testTag("TouchTurnDataCaptureReady")
                )
                TouchTurnDataCaptureCard(capture = capture)
            }
        }
    }
}

@Composable
private fun TouchTurnDataCaptureCard(capture: SessionDataCaptureUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnDataCaptureCard"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Captured inputs",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )

        DataCaptureRow(label = "Market", value = capture.marketZoneAbbrev)
        DataCaptureRow(label = "Currency", value = capture.currency)
        capture.dataReadyAt?.let { readyAt ->
            DataCaptureRow(label = "Ready at", value = readyAt, testTag = "TouchTurnDataCaptureReadyAt")
        }

        if (capture.hasAdr) {
            HorizontalDivider(color = TableHeaderBg)
            Text(
                "14-day ADR",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            DataCaptureRow(
                label = "Average daily range",
                value = capture.formattedAdr14() ?: "—",
                emphasize = true,
                testTag = "TouchTurnDataCaptureAdr14"
            )
            DataCaptureRow(
                label = "Liquidity threshold (${capture.adrRatioPercent}% of ADR)",
                value = capture.fmt(capture.rangeThreshold),
                testTag = "TouchTurnDataCaptureThreshold"
            )
        }

        capture.candle?.let { candle ->
            HorizontalDivider(color = TableHeaderBg)
            Text(
                "Opening 15-minute bar",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            candle.time?.let { time ->
                Text(
                    time,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("TouchTurnDataCaptureBarTime")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DataCaptureRow(
                    label = "Open",
                    value = capture.fmt(candle.open),
                    modifier = Modifier.weight(1f)
                )
                DataCaptureRow(
                    label = "High",
                    value = capture.fmt(candle.high),
                    modifier = Modifier.weight(1f),
                    valueColor = GainGreen
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DataCaptureRow(
                    label = "Low",
                    value = capture.fmt(candle.low),
                    modifier = Modifier.weight(1f),
                    valueColor = LossRed
                )
                DataCaptureRow(
                    label = "Close",
                    value = capture.fmt(candle.close),
                    modifier = Modifier.weight(1f)
                )
            }
            DataCaptureRow(
                label = "Range (H − L)",
                value = capture.fmt(candle.range),
                emphasize = true,
                testTag = "TouchTurnDataCaptureBarRange"
            )
        }
    }
}

@Composable
private fun DataCaptureRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White,
    emphasize: Boolean = false,
    testTag: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = TextSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = if (emphasize) 12.sp else 11.sp,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
            color = valueColor,
            maxLines = 2
        )
    }
}

@Composable
fun TouchTurnPipelineSectionBar(
    session: TouchTurnSessionContext?,
    formingBarPriceChart: TouchTurnLiveOrderChartUiState? = null,
    modifier: Modifier = Modifier
) {
    val candle = session?.candle
    if (session == null || candle == null) {
        Text(
            "Opening bar not available yet.",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = modifier.testTag("TouchTurnPipelineSectionBar")
        )
        return
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(candle.time, session.marketZoneId) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val detail = remember(session, tick) {
        TouchTurnPipelineDetailUiMapper.openingBarDetail(session)
    }
    if (detail == null) {
        Text("Opening bar not available yet.", fontSize = 12.sp, color = TextSecondary)
        return
    }
    Column(
        modifier = modifier.testTag("TouchTurnPipelineSectionBar"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TouchTurnOpeningBarDetailCard(
            detail = detail,
            candle = candle,
            modifier = Modifier.fillMaxWidth()
        )
        if (detail.closeStatus == FirstCandleCloseStatus.FORMING) {
            TouchTurnPipelineLiveOrderChart(
                chart = formingBarPriceChart,
                modifier = Modifier.testTag("TouchTurnFormingBarPriceChart")
            )
        }
    }
}

@Composable
fun TouchTurnPipelineSectionLiquidity(
    session: TouchTurnSessionContext?,
    modifier: Modifier = Modifier
) {
    if (session?.candle == null) {
        Text(
            "Liquidity check runs after the opening bar is available.",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = modifier
        )
        return
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.candle?.time, session.marketZoneId) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val calc = remember(session, tick) {
        TouchTurnPipelineDetailUiMapper.liquidityCalculation(session)
    }
    if (calc == null) {
        Text("Liquidity data unavailable.", fontSize = 12.sp, color = TextSecondary)
        return
    }
    TouchTurnLiquidityCalculationCard(
        calc = calc,
        modifier = modifier.testTag("TouchTurnPipelineSectionLiquidity")
    )
}

@Composable
fun TouchTurnPipelineSectionOrdersPreview(
    session: TouchTurnSessionContext?,
    sessionTrades: List<SessionTrade> = emptyList(),
    sessionPnl: Double? = null,
    modifier: Modifier = Modifier
) {
    val candle = session?.candle
    if (session == null || candle == null) {
        Text(
            "Order preview appears after liquidity is confirmed.",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = modifier
        )
        return
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(candle.time, session.marketZoneId) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val closeStatus = remember(session, tick) { session.candleCloseStatus() }
    val liquidityEval = remember(session, tick) { session.liquidityEvaluation() }
    val closeConfirmation = remember(session, tick) { session.closeConfirmation() }
    val currency = session.currencyCode
    val fmt: (Double) -> String = { Formatters.moneyPlain(it, currency) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("TouchTurnPipelineSectionOrders"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (liquidityEval != LiquidityCandleEvaluation.LIQUIDITY ||
            closeConfirmation != TouchTurnCloseConfirmation.PASSED ||
            closeStatus != FirstCandleCloseStatus.CLOSED
        ) {
            Text(
                "Bracket preview is available once bar close passes liquidity and close-confirmation checks.",
                fontSize = 11.sp,
                color = TextSecondary
            )
            return@Column
        }
        val computedSetup = remember(session, tick) {
            session.setup?.takeIf { it.isLiquidityCandle }
                ?: TouchTurnLogic.computeBracketSetup(candle, session.rangeThreshold)
        }
        val isRecap = sessionTrades.isNotEmpty() || session.executedBracketLegs.isNotEmpty()
        val orderSetup = remember(session, computedSetup, isRecap) {
            val bracket = session.plannedBracket
            if (isRecap && bracket != null) {
                bracket.applyToChartSetup(computedSetup)
            } else {
                computedSetup
            }
        }
        val executedLevels = remember(session, sessionTrades, orderSetup, sessionPnl) {
            session.executedBracketLegs.takeIf { it.isNotEmpty() }?.toLevelKinds()
                ?: TouchTurnExecutedBracketLegs.resolve(
                    trades = sessionTrades,
                    plannedBracket = session.plannedBracket,
                    bracketSetup = orderSetup,
                    sessionPnl = sessionPnl
                )
        }
        Text(
            if (isRecap) {
                "Session recap — ${TouchTurnLogic.orderPreviewSummary(orderSetup)}. Pulsing lines filled during this run."
            } else {
                "Preview only — ${TouchTurnLogic.orderPreviewSummary(orderSetup)}"
            },
            fontSize = 10.sp,
            color = TextSecondary,
            lineHeight = 13.sp,
            modifier = Modifier.testTag(
                if (isRecap) "TouchTurnOrderRecapCaption" else "TouchTurnOrderPreviewCaption"
            )
        )
        if (orderSetup.isActionable) {
            TouchTurnOrderPreviewChart(
                candle = candle,
                setup = orderSetup,
                fmt = fmt,
                executedLevels = executedLevels,
                modifier = Modifier.testTag("TouchTurnOrderPreviewChart")
            )
        } else {
            Text(
                "Flat opening candle — no directional entry.",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
        if (session.ordersPlacedForSession) {
            Text(
                "Orders have been sent to the broker for this session.",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = GainGreen
            )
        } else {
            session.decisionOutcome?.let { outcome ->
                TouchTurnSessionStatusBanner(
                    status = TouchTurnSessionReasonUi.forDecisionOutcome(outcome, session)
                )
            } ?: if (session.entryOrdersPermitted == false) {
                TouchTurnSessionStatusBanner(
                    status = TouchTurnSessionStatusUi(
                        headline = "Orders not placed yet",
                        detail = TouchTurnSessionReasonUi.pendingEntryBlockDetail(session, System.currentTimeMillis()),
                        severity = TouchTurnReasonSeverity.Warning
                    )
                )
            } else {
                Unit
            }
        }
    }
}

@Composable
fun TouchTurnPipelineSectionConfirmation(
    session: TouchTurnSessionContext?,
    modifier: Modifier = Modifier
) {
    if (session?.candle == null) {
        Text("Close confirmation runs after liquidity is evaluated.", fontSize = 12.sp, color = TextSecondary)
        return
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.candle?.time, session.marketZoneId) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val confirmation = remember(session, tick) { TouchTurnPipelineDetailUiMapper.closeConfirmation(session) }
    if (confirmation == null) {
        Text("Close confirmation unavailable.", fontSize = 12.sp, color = TextSecondary)
        return
    }
    TouchTurnCloseConfirmationCard(confirmation = confirmation, modifier = modifier)
}

@Composable
fun TouchTurnPipelineSectionNoTrade(
    session: TouchTurnSessionContext?,
    graph: TouchTurnPipelineGraph?,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session?.candle?.time, session?.marketZoneId) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val liquidityEval = remember(session, tick) { session?.liquidityEvaluation() }
    val entryPermitted = session?.entryOrdersPermitted

    val noTradeExplanation = session?.decisionOutcome?.let {
        TouchTurnSessionReasonUi.forDecisionOutcome(it, session)
    } ?: when {
        liquidityEval == LiquidityCandleEvaluation.NOT_LIQUIDITY ->
            TouchTurnSessionReasonUi.forDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY)
        entryPermitted == false -> TouchTurnSessionStatusUi(
            headline = "Entry not permitted",
            detail = session?.let {
                TouchTurnSessionReasonUi.pendingEntryBlockDetail(it, System.currentTimeMillis())
            } ?: "Liquidity passed but entry orders were not permitted.",
            severity = TouchTurnReasonSeverity.Warning
        )
        graph?.activePath?.contains(TouchTurnPipelineNodeId.NoTrade) == true ->
            TouchTurnSessionStatusUi(
                headline = "No-trade path",
                detail = "Orders and position steps were skipped for this session.",
                severity = TouchTurnReasonSeverity.Warning
            )
        else -> TouchTurnSessionStatusUi(
            headline = "No-trade path",
            detail = "Used when liquidity fails, volume exhaustion triggers, or close confirmation fails or expires.",
            severity = TouchTurnReasonSeverity.Info
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("TouchTurnPipelineSectionNoTrade"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TouchTurnSessionStatusBanner(status = noTradeExplanation)
        graph?.node(TouchTurnPipelineNodeId.Liquidity)?.timestamp?.let { time ->
            Text("Liquidity evaluated $time", fontSize = 10.sp, color = TextSecondary)
        }
        graph?.node(TouchTurnPipelineNodeId.Confirmation)?.timestamp?.let { time ->
            Text("Close confirmation evaluated $time", fontSize = 10.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun TouchTurnCloseConfirmationCard(
    confirmation: CloseConfirmationUi,
    modifier: Modifier = Modifier
) {
    val statusColor = when (confirmation.confirmation) {
        TouchTurnCloseConfirmation.PASSED -> GainGreen
        TouchTurnCloseConfirmation.FAILED,
        TouchTurnCloseConfirmation.EXPIRED -> LossRed
        TouchTurnCloseConfirmation.AWAITING_LIQUIDITY -> Color(0xFFFFB74D)
        TouchTurnCloseConfirmation.UNKNOWN -> TextSecondary
    }
    val verdict = when (confirmation.confirmation) {
        TouchTurnCloseConfirmation.PASSED -> "Close confirmation passed — close confirms the turn vs entry."
        TouchTurnCloseConfirmation.FAILED -> "Close confirmation failed — close did not confirm the turn vs entry."
        TouchTurnCloseConfirmation.EXPIRED ->
            "Close confirmation expired — more than 1 minute since the 15-minute bar closed."
        TouchTurnCloseConfirmation.AWAITING_LIQUIDITY -> "Waiting for liquidity evaluation."
        TouchTurnCloseConfirmation.UNKNOWN -> "Close confirmation unavailable."
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(verdict, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        val close = confirmation.closePrice
        val entry = confirmation.entryPrice
        val stop = confirmation.stopPrice
        if (close != null && entry != null && stop != null) {
            Text(
                "Close ${Formatters.moneyPlain(close, confirmation.currency)} · " +
                    "Entry ${Formatters.moneyPlain(entry, confirmation.currency)} · " +
                    "Stop ${Formatters.moneyPlain(stop, confirmation.currency)}",
                fontSize = 11.sp,
                color = Color.White
            )
            Text(
                "Rule: green bar requires close below entry; red bar requires close above entry. " +
                    "Both checks must pass within 1 minute of bar close.",
                fontSize = 10.sp,
                color = TextSecondary
            )
            confirmation.remainingMillis?.let { remaining ->
                Text(
                    "Time remaining in confirmation window: ${formatConfirmationCountdown(remaining)}",
                    fontSize = 10.sp,
                    color = if (remaining > 0) Color(0xFFFFB74D) else TextSecondary
                )
            }
        }
    }
}

private fun formatConfirmationCountdown(remainingMillis: Long): String {
    val totalSec = remainingMillis / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return when {
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

@Composable
private fun TouchTurnOpeningBarDetailCard(
    detail: OpeningBarDetailUi,
    candle: daytrader.domain.OhlcBar,
    modifier: Modifier = Modifier
) {
    val closeColor = when (detail.closeStatus) {
        FirstCandleCloseStatus.CLOSED -> GainGreen
        FirstCandleCloseStatus.FORMING -> Color(0xFFFFB74D)
        FirstCandleCloseStatus.UNKNOWN -> TextSecondary
    }
    val bodyColor = when {
        detail.bodyChange > 0 -> GainGreen
        detail.bodyChange < 0 -> LossRed
        else -> TextSecondary
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            detail.barTime?.let { time ->
                Text(time, fontSize = 10.sp, color = TextSecondary, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    detail.closeStatusLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = closeColor,
                    modifier = Modifier.testTag("TouchTurnCandleCloseStatus")
                )
                detail.timeUntilCloseLabel?.let { countdown ->
                    Text(countdown, fontSize = 9.sp, color = Color(0xFFFFB74D))
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkBackground, RoundedCornerShape(8.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FirstCandleStick(
                    candle = candle,
                    color = detail.candleColor,
                    modifier = Modifier
                        .testTag("TouchTurnCandleColor")
                        .size(width = 44.dp, height = 80.dp)
                )
                Text(
                    detail.candleColorLabel,
                    fontSize = 9.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    modifier = Modifier.width(72.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "15-minute opening bar (OHLC)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                OpeningBarPriceRow(label = "Open", value = detail.fmt(detail.open))
                OpeningBarPriceRow(label = "High", value = detail.fmt(detail.high), valueColor = GainGreen)
                OpeningBarPriceRow(label = "Low", value = detail.fmt(detail.low), valueColor = LossRed)
                OpeningBarPriceRow(label = "Close", value = detail.fmt(detail.close))
                HorizontalDivider(color = TableHeaderBg)
                OpeningBarPriceRow(
                    label = "Range (H − L)",
                    value = detail.fmt(detail.range),
                    emphasize = true
                )
                OpeningBarPriceRow(
                    label = "Body (C − O)",
                    value = Formatters.money(detail.bodyChange, detail.currency, showSign = true),
                    valueColor = bodyColor
                )
            }
        }
    }
}

@Composable
private fun OpeningBarPriceRow(
    label: String,
    value: String,
    valueColor: Color = Color.White,
    emphasize: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = TextSecondary)
        Text(
            text = value,
            fontSize = if (emphasize) 12.sp else 11.sp,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
private fun TouchTurnLiquidityCalculationCard(
    calc: LiquidityCalculationUi,
    modifier: Modifier = Modifier
) {
    val statusColor = when (calc.evaluation) {
        LiquidityCandleEvaluation.LIQUIDITY -> GainGreen
        LiquidityCandleEvaluation.NOT_LIQUIDITY -> TextSecondary
        LiquidityCandleEvaluation.AWAITING_CLOSE -> Color(0xFFFFB74D)
        LiquidityCandleEvaluation.UNKNOWN -> TextSecondary
    }
    val verdict = when {
        calc.evaluation == LiquidityCandleEvaluation.AWAITING_CLOSE ->
            "Waiting for the opening bar to close before comparing range to threshold."
        calc.passes == true ->
            "Liquidity candle — bar range exceeds ${calc.adrRatioPercent}% of 14-day ADR. Trade path may continue."
        calc.passes == false ->
            "Not a liquidity candle — bar range did not exceed threshold. Session takes the no-trade branch."
        else -> TouchTurnLogic.liquidityEvaluationLabel(calc.evaluation)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            verdict,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = statusColor,
            modifier = Modifier.testTag("TouchTurnLiquidityStatus")
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Liquidity calculation",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )

            LiquidityCalcStep(
                step = "1",
                title = "14-day ADR (average daily range)",
                value = calc.formattedAdr14() ?: "—",
                detail = "Mean high − low over the last 14 completed sessions."
            )

            LiquidityCalcStep(
                step = "2",
                title = "Liquidity threshold (ADR × ${calc.adrRatioPercent}%)",
                value = calc.fmt(calc.rangeThreshold),
                detail = calc.formattedAdr14()?.let { adr ->
                    "$adr × ${calc.adrRatioPercent}% = ${calc.fmt(calc.rangeThreshold)}"
                } ?: "25% of 14-day ADR."
            )

            LiquidityCalcStep(
                step = "3",
                title = "Opening bar range (High − Low)",
                value = calc.fmt(calc.barRange),
                detail = "${calc.fmt(calc.barHigh)} − ${calc.fmt(calc.barLow)} = ${calc.fmt(calc.barRange)}"
            )

            if (calc.canCompare) {
                HorizontalDivider(color = TableHeaderBg)
                val comparison = if (calc.passes == true) {
                    "${calc.fmt(calc.barRange)} > ${calc.fmt(calc.rangeThreshold)}"
                } else {
                    "${calc.fmt(calc.barRange)} ≤ ${calc.fmt(calc.rangeThreshold)}"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("TouchTurnLiquidityComparison"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "4. Compare range to threshold",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Text(
                            comparison,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                    Text(
                        if (calc.passes == true) "Pass" else "Fail",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            } else {
                Text(
                    "Step 4 runs once the 15-minute bar has closed.",
                    fontSize = 10.sp,
                    color = TextSecondary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun LiquidityCalcStep(
    step: String,
    title: String,
    value: String,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            step,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = BrandRed,
            modifier = Modifier.width(14.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    value,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Text(detail, fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.85f), lineHeight = 12.sp)
        }
    }
}

@Composable
fun TouchTurnPipelineLiveOrderChart(
    chart: TouchTurnLiveOrderChartUiState?,
    modifier: Modifier = Modifier
) {
    if (chart == null) return
    TouchTurnLiveOrderPriceChart(
        chart = chart,
        modifier = modifier.testTag("TouchTurnPipelineLiveOrderChart")
    )
}

@Composable
fun TouchTurnPipelineDetailPanel(
    selectedNodeId: TouchTurnPipelineNodeId?,
    graph: TouchTurnPipelineGraph?,
    modifier: Modifier = Modifier,
    content: @Composable (TouchTurnPipelineNodeId) -> Unit
) {
    val nodeId = selectedNodeId ?: return
    val node = graph?.node(nodeId)
    if (node != null && !node.isSelectable()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BrandRed.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .testTag("TouchTurnPipelineDetailPanel")
    ) {
        TouchTurnPanelGroup(
            title = nodeId.detailTitle(),
            testTag = "TouchTurnPipelineDetail_${nodeId.name}",
            compact = true
        ) {
            content(nodeId)
        }
    }
}
