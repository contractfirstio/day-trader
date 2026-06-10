package daytrader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import daytrader.presentation.strategies.RuleCheckUi
import daytrader.presentation.strategies.RulesEvaluationUi
import daytrader.presentation.strategies.SessionDataCaptureUi
import daytrader.presentation.strategies.TouchTurnLiveOrderChartUiState
import daytrader.domain.TouchTurnPrepareStatus
import daytrader.presentation.strategies.TouchTurnPrepareCheckRowUi
import daytrader.presentation.strategies.TouchTurnSessionStartUi
import daytrader.presentation.strategies.TouchTurnSessionStartUiMapper
import daytrader.presentation.strategies.TouchTurnPipelineDetailUiMapper
import daytrader.presentation.strategies.TouchTurnPipelineGraph
import daytrader.presentation.strategies.TouchTurnPipelineNodeId
import daytrader.presentation.strategies.TouchTurnReasonSeverity
import daytrader.presentation.strategies.TouchTurnSessionReasonUi
import daytrader.presentation.strategies.TouchTurnSessionStatusUi
import daytrader.presentation.strategies.TouchTurnRunRecordUiMapper
import daytrader.presentation.strategies.detailTitle
import daytrader.presentation.strategies.fmt
import daytrader.presentation.strategies.formattedAtr14
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
    session: TouchTurnSessionContext? = null,
    startUi: TouchTurnSessionStartUi? = null,
    modifier: Modifier = Modifier
) {
    val ui = startUi ?: TouchTurnSessionStartUiMapper.forLive(
        instance = instance,
        session = session,
        lastClosedRun = lastClosedRun,
        graphCaption = graph?.statusBanner?.detail ?: graph?.statusBanner?.headline
    )
    TouchTurnSessionStartDetail(ui = ui, modifier = modifier)
}

@Composable
fun TouchTurnSessionStartDetail(
    ui: TouchTurnSessionStartUi,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("TouchTurnPipelineSectionStart"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            ui.headline,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
        ui.detail?.let { detail ->
            Text(detail, fontSize = 11.sp, color = TextSecondary, lineHeight = 14.sp)
        }
        TouchTurnSessionStartFactsCard(ui = ui)
        if (ui.prepareChecks.isNotEmpty()) {
            TouchTurnPrepareChecksCard(
                checks = ui.prepareChecks,
                overallLabel = ui.prepareOverallLabel,
                preparedAtLabel = ui.preparePreparedAtLabel
            )
        } else {
            Text(
                "Prepare was not run before Start — bootstrap loads when the session begins.",
                fontSize = 10.sp,
                color = TextSecondary,
                lineHeight = 13.sp,
                modifier = Modifier.testTag("TouchTurnSessionStartNoPrepare")
            )
        }
        ui.bootstrapPathLabel?.let { path ->
            Text(path, fontSize = 10.sp, color = TextSecondary, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun TouchTurnSessionStartFactsCard(ui: TouchTurnSessionStartUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnSessionStartFactsCard"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Session facts",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        ui.startedAtLabel?.let { DataCaptureRow(label = "Started", value = it) }
        ui.stoppedAtLabel?.let { DataCaptureRow(label = "Stopped", value = it) }
        ui.startedByLabel?.let { DataCaptureRow(label = "Start mode", value = it) }
        ui.brokerLabel?.let { DataCaptureRow(label = "Broker", value = it) }
        ui.marketLabel?.let { DataCaptureRow(label = "Market", value = it) }
        ui.sessionDateLabel?.let { DataCaptureRow(label = "Session date", value = it) }
        DataCaptureRow(label = "Max risk", value = ui.maxRiskLabel, emphasize = true)
    }
}

@Composable
fun TouchTurnPrepareChecksCard(
    checks: List<TouchTurnPrepareCheckRowUi>,
    overallLabel: String?,
    preparedAtLabel: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnPrepareChecksCard"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pre-flight checks",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            overallLabel?.let { label ->
                Text(
                    label,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        preparedAtLabel?.let { at ->
            Text("Prepare run $at (market local)", fontSize = 10.sp, color = TextSecondary)
        }
        checks.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    touchTurnPrepareStatusGlyph(row.status),
                    fontSize = 11.sp,
                    color = touchTurnPrepareCheckColor(row.status),
                    modifier = Modifier.width(14.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.label, fontSize = 10.sp, color = Color.White)
                    row.detail?.let { detail ->
                        Text(detail, fontSize = 9.sp, color = TextSecondary, lineHeight = 12.sp)
                    }
                }
            }
        }
    }
}

private fun touchTurnPrepareStatusGlyph(status: TouchTurnPrepareStatus): String = when (status) {
    TouchTurnPrepareStatus.PASS -> "✓"
    TouchTurnPrepareStatus.WARN -> "!"
    TouchTurnPrepareStatus.FAIL -> "✗"
}

private fun touchTurnPrepareCheckColor(status: TouchTurnPrepareStatus): Color = when (status) {
    TouchTurnPrepareStatus.PASS -> GainGreen
    TouchTurnPrepareStatus.WARN -> Color(0xFFFFB74D)
    TouchTurnPrepareStatus.FAIL -> LossRed
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
    formingBarPriceChart: TouchTurnLiveOrderChartUiState? = null,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session?.resolvedOpeningBarTime(), session?.marketZoneId) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
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
                    "Loading opening bar and 14-period ATR from broker…",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Text(
                    "Requests: 14-period ATR and the first 15-minute RTH candle for $symbol.",
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
                val barDetail = remember(session, tick) {
                    TouchTurnPipelineDetailUiMapper.openingBarDetail(session)
                }
                val candleColor = barDetail?.candleColor
                    ?: session.firstCandleColor()
                    ?: session.candle?.let { TouchTurnLogic.firstCandleColor(it) }
                Text(
                    "Session data captured from broker.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GainGreen,
                    modifier = Modifier.testTag("TouchTurnDataCaptureReady")
                )
                session.candle?.let { candle ->
                    if (candleColor != null) {
                        TouchTurnOpeningBarChart(
                            candle = candle,
                            candleColor = candleColor,
                            currencyCode = session.currencyCode,
                            closeStatus = barDetail?.closeStatus ?: session.candleCloseStatus(),
                            rangeThreshold = session.rangeThreshold.takeIf { it > 0.0 },
                            livePriceHistory = formingBarPriceChart?.priceHistory.orEmpty(),
                            currentPrice = formingBarPriceChart?.currentPrice,
                            quoteStrip = formingBarPriceChart?.quoteStrip,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } ?: formingBarPriceChart?.let { chart ->
                    TouchTurnPipelineLiveOrderChart(
                        chart = chart,
                        modifier = Modifier.testTag("TouchTurnDataLiveBarChart")
                    )
                }
                barDetail?.let { detail ->
                    TouchTurnOpeningBarStatusRow(detail = detail)
                }
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

        if (capture.hasAtr) {
            HorizontalDivider(color = TableHeaderBg)
            Text(
                "14-period ATR",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            DataCaptureRow(
                label = "Average true range",
                value = capture.formattedAtr14() ?: "—",
                emphasize = true,
                testTag = "TouchTurnDataCaptureAtr14"
            )
            DataCaptureRow(
                label = "Liquidity threshold (${capture.atrRatioPercent}% of ATR)",
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
    if (session == null || session.resolvedOpeningBarTime() == null) {
        Text(
            "Opening bar not available yet.",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = modifier.testTag("TouchTurnPipelineSectionBar")
        )
        return
    }
    val candle = session.candle
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.resolvedOpeningBarTime(), session.marketZoneId) {
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
    val showLiveChart = detail.closeStatus == FirstCandleCloseStatus.FORMING ||
        detail.closeStatus == FirstCandleCloseStatus.CLOSED
    Column(
        modifier = modifier.testTag("TouchTurnPipelineSectionBar"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (candle != null) {
            TouchTurnOpeningBarDetailCard(
                detail = detail,
                candle = candle,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            TouchTurnOpeningBarStatusRow(detail = detail)
            Text(
                "Loading final bar OHLC from broker after close…",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
        if (showLiveChart) {
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
    val closeConfirmation = remember(session, tick) { session.pipelineCloseConfirmation() }
    val currency = session.currencyCode
    val listingExch = daytrader.domain.InstrumentPriceScale.resolvedListingExch(
        currency = currency,
        marketZoneId = session.marketZoneId,
        primaryExch = null
    )
    val fmt: (Double) -> String = { Formatters.listingPricePlain(it, currency, listingExch) }

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
        if (TouchTurnLogic.setupActionableForEntry(orderSetup, session.rules)) {
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
fun TouchTurnPipelineSectionRules(
    session: TouchTurnSessionContext?,
    graph: TouchTurnPipelineGraph? = null,
    formingBarPriceChart: TouchTurnLiveOrderChartUiState? = null,
    sessionEnded: Boolean = false,
    requireLivePriceChecks: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (session?.candle == null) {
        Text(
            "Entry rules run after the opening bar closes and data is refreshed.",
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
    val evaluation = remember(session, tick, sessionEnded, requireLivePriceChecks) {
        TouchTurnPipelineDetailUiMapper.rulesEvaluation(
            session = session,
            verboseExplanations = sessionEnded,
            requireLivePriceChecks = requireLivePriceChecks
        )
    }
    Column(
        modifier = modifier.fillMaxWidth().testTag("TouchTurnPipelineSectionRules"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        session.decisionOutcome?.let { outcome ->
            TouchTurnSessionStatusBanner(
                status = TouchTurnSessionReasonUi.forDecisionOutcome(outcome, session)
            )
        }
        formingBarPriceChart?.let { chart ->
            TouchTurnPipelineLiveOrderChart(chart = chart)
        }
        evaluation?.let { RulesEvaluationCard(evaluation = it, verboseExplanations = sessionEnded) }
        graph?.node(TouchTurnPipelineNodeId.Rules)?.timestamp?.let { time ->
            Text("Rules evaluated $time", fontSize = 10.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun RulesEvaluationCard(
    evaluation: RulesEvaluationUi,
    verboseExplanations: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnRulesEvaluationCard"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (verboseExplanations) "Rule checks (tap a row for step-by-step detail)" else "Rule checks",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        evaluation.checks.forEach { check ->
            RuleCheckRow(check = check, verboseExplanations = verboseExplanations)
        }
        evaluation.entryOrdersPermitted?.let { permitted ->
            HorizontalDivider(color = TableHeaderBg)
            DataCaptureRow(
                label = "Entry permitted",
                value = if (permitted) "Yes" else "No",
                valueColor = if (permitted) GainGreen else LossRed,
                emphasize = true,
                testTag = "TouchTurnRulesEntryPermitted"
            )
        }
    }
}

@Composable
private fun RuleCheckRow(
    check: RuleCheckUi,
    verboseExplanations: Boolean
) {
    var expanded by rememberSaveable(check.key) { mutableStateOf(false) }
    val canExpand = verboseExplanations && check.enabled && check.explanationSteps.isNotEmpty()
    val (icon, color) = when {
        !check.enabled -> "—" to TextSecondary
        check.passed == true -> "✓" to GainGreen
        check.passed == false -> "✗" to LossRed
        else -> "…" to TextSecondary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("TouchTurnRuleCheck-${check.key}"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canExpand) {
                        Modifier.clickable { expanded = !expanded }
                    } else {
                        Modifier
                    }
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canExpand) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("TouchTurnRuleCheckExpand-${check.key}")
                )
            }
            Text(icon, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
            Text(check.label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
            check.detail?.let { detail ->
                Text(detail, fontSize = 10.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            }
        }
        Text(
            check.description,
            fontSize = 9.sp,
            color = TextSecondary.copy(alpha = 0.85f),
            lineHeight = 12.sp,
            modifier = Modifier.padding(start = if (canExpand) 28.dp else 20.dp)
        )
        if (canExpand) {
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.padding(start = 28.dp, top = 4.dp, end = 4.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    check.explanationSteps.forEachIndexed { index, step ->
                        Text(
                            "${index + 1}. $step",
                            fontSize = 9.sp,
                            color = TextSecondary,
                            lineHeight = 13.sp,
                            modifier = Modifier.testTag("TouchTurnRuleCheckStep-${check.key}-$index")
                        )
                    }
                }
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
        graph?.activePath?.contains(TouchTurnPipelineNodeId.Close) == true &&
            graph.activePath.contains(TouchTurnPipelineNodeId.Rules) &&
            !graph.activePath.contains(TouchTurnPipelineNodeId.Orders) ->
            TouchTurnSessionStatusUi(
                headline = "No trade",
                detail = "Orders and position steps were skipped for this session.",
                severity = TouchTurnReasonSeverity.Warning
            )
        else -> TouchTurnSessionStatusUi(
            headline = "No trade",
            detail = "Used when entry rules fail, volume exhausts, or close confirmation fails or expires.",
            severity = TouchTurnReasonSeverity.Info
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("TouchTurnPipelineSectionNoTrade"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TouchTurnSessionStatusBanner(status = noTradeExplanation)
        graph?.node(TouchTurnPipelineNodeId.Rules)?.timestamp?.let { time ->
            Text("Rules evaluated $time", fontSize = 10.sp, color = TextSecondary)
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
            val listingExch = daytrader.domain.InstrumentPriceScale.resolvedListingExch(
                currency = confirmation.currency,
                marketZoneId = if (confirmation.currency == "GBP") {
                    daytrader.domain.RthMarketSessions.EUR.zoneId
                } else {
                    null
                },
                primaryExch = null
            )
            Text(
                "Close ${Formatters.listingPricePlain(close, confirmation.currency, listingExch)} · " +
                    "Entry ${Formatters.listingPricePlain(entry, confirmation.currency, listingExch)} · " +
                    "Stop ${Formatters.listingPricePlain(stop, confirmation.currency, listingExch)}",
                fontSize = 11.sp,
                color = Color.White
            )
            Text(
                "Rule: green bar requires close at least 15% of bar range below entry; " +
                    "red bar requires close at least 15% of range above entry. " +
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
private fun TouchTurnOpeningBarStatusRow(
    detail: OpeningBarDetailUi,
    modifier: Modifier = Modifier
) {
    val closeColor = when (detail.closeStatus) {
        FirstCandleCloseStatus.CLOSED -> GainGreen
        FirstCandleCloseStatus.FORMING -> Color(0xFFFFB74D)
        FirstCandleCloseStatus.UNKNOWN -> TextSecondary
    }
    Row(
        modifier = modifier.fillMaxWidth(),
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
}

@Composable
private fun TouchTurnOpeningBarDetailCard(
    detail: OpeningBarDetailUi,
    candle: daytrader.domain.OhlcBar,
    modifier: Modifier = Modifier
) {
    val bodyColor = when {
        detail.bodyChange > 0 -> GainGreen
        detail.bodyChange < 0 -> LossRed
        else -> TextSecondary
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TouchTurnOpeningBarStatusRow(detail = detail)

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
            "Liquidity candle — bar range exceeds ${calc.atrRatioPercent}% of 14-period ATR. Trade path may continue."
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
                title = "14-period ATR (average true range)",
                value = calc.formattedAtr14() ?: "—",
                detail = "Mean true range over the last 14 completed sessions."
            )

            LiquidityCalcStep(
                step = "2",
                title = "Liquidity threshold (ATR × ${calc.atrRatioPercent}%)",
                value = calc.fmt(calc.rangeThreshold),
                detail = calc.formattedAtr14()?.let { atr ->
                    "$atr × ${calc.atrRatioPercent}% = ${calc.fmt(calc.rangeThreshold)}"
                } ?: "25% of 14-period ATR."
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
