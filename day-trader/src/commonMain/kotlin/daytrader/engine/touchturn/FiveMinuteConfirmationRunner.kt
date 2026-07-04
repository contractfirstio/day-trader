package daytrader.engine.touchturn

import daytrader.data.TouchTurnOrderLog
import daytrader.domain.DeploymentMarket
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.FiveMinuteConfirmationStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderSizingResult
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.effectiveTouchTurnRules
import daytrader.domain.orderSizeRules
import daytrader.domain.withFiveMinuteConfirmationConfirmed
import daytrader.domain.withFiveMinuteConfirmationReset
import daytrader.domain.withFiveMinuteConfirmationStarted
import daytrader.domain.withFiveMinuteConfirmationUpdated
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.withTouchTurnDecisionOutcome
import daytrader.domain.inProgressSession
import daytrader.diagnostics.SessionHistoricalLog
import daytrader.diagnostics.SessionTrace
import daytrader.gateway.BrokerGateway
import daytrader.marketdata.MarketDataProvider
import daytrader.data.StrategyDeploymentRepository

internal class FiveMinuteConfirmationRunner(
    private val marketData: MarketDataProvider,
    private val repository: StrategyDeploymentRepository,
    private val nowEpochMillis: () -> Long,
    private val delayMillis: suspend (Long) -> Unit,
    private val pollIntervalMs: (String) -> Long,
    private val replayOpeningBarQuotesReady: (String) -> Boolean,
    private val bracketSubmitSkipReason: (StrategyDeployment) -> String?,
    private val ensureEmulatorQuotesBeforeBracketSubmit: suspend (
        StrategyDeployment,
        daytrader.domain.TouchTurnBracketSetup,
        daytrader.domain.TouchTurnRuleConfig,
        daytrader.domain.TouchTurnOrderPlan
    ) -> Unit,
    private val registerPendingBracket: (
        instanceId: String,
        plan: daytrader.domain.TouchTurnOrderPlan,
        sessionId: String?,
        evaluatedAt: Long
    ) -> Unit,
    private val quoteForSymbol: (String) -> daytrader.gateway.LiveQuote?,
    private val onFinished: (instanceId: String) -> Unit
) {
    suspend fun runUntilResolved(instanceId: String, executionGw: BrokerGateway?) {
        while (true) {
            val instance = repository.deployments.value.find { it.id == instanceId } ?: return
            val session = instance.touchTurnSession ?: return
            val confirmation = session.fiveMinuteConfirmation ?: return
            if (confirmation.status != FiveMinuteConfirmationStatus.AWAITING) return
            if (session.ordersPlacedForSession) return

            val now = nowEpochMillis()
            if (FiveMinuteConfirmationLogic.isExpired(confirmation, now)) {
                repository.update(instanceId) { current ->
                    current.withFiveMinuteConfirmationReset(
                        TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_CONFIRMATION_EXPIRED,
                        detailMessage = "No qualifying 5-minute hammer within 15 minutes of the liquidity sweep."
                    )
                }
                onFinished(instanceId)
                return
            }

            val candle = session.candle
            if (candle == null) {
                delayMillis(pollIntervalMs(instance.symbol))
                continue
            }
            val setup = session.setup ?: return
            val rules = session.rules
            val barsResult = marketData.fetchFiveMinuteBars(
                symbol = instance.symbol,
                instrument = DeploymentMarket.effectiveInstrument(instance),
                afterBarOpenEpochMs = confirmation.sweepActiveStartedAtEpochMs,
                marketZoneId = session.marketZoneId
            )
            if (barsResult.isFailure) {
                delayMillis(pollIntervalMs(instance.symbol))
                continue
            }
            val bars = barsResult.getOrThrow()
            for (bar in bars) {
                val barTime = bar.time ?: continue
                if (barTime in confirmation.processedBarTimes) continue
                SessionHistoricalLog.recordFiveMinuteBar(
                    deploymentId = instanceId,
                    sessionId = instance.inProgressSession()?.id,
                    symbol = instance.symbol,
                    bar = bar,
                    sweepPrice = confirmation.sweepPrice
                )
                val evaluation = FiveMinuteConfirmationLogic.evaluateHammer(bar, setup.side, candle)
                val updatedProcessed = confirmation.processedBarTimes + barTime
                SessionTrace.fiveMinuteBarEvaluated(
                    deploymentId = instanceId,
                    sessionId = instance.inProgressSession()?.id,
                    symbol = instance.symbol,
                    barTime = barTime,
                    isHammer = evaluation.isHammer,
                    invalidatesSetup = evaluation.invalidatesSetup,
                    processedBarCount = updatedProcessed.size,
                    open = bar.open,
                    high = bar.high,
                    low = bar.low,
                    close = bar.close
                )
                if (evaluation.invalidatesSetup) {
                    repository.update(instanceId) { current ->
                        current.withFiveMinuteConfirmationUpdated(
                            confirmation.copy(processedBarTimes = updatedProcessed)
                        ).withFiveMinuteConfirmationReset(
                            TouchTurnSessionOutcome.NO_TRADE_FIVE_MIN_CONFIRMATION_INVALIDATED,
                            detailMessage = "5-minute bar closed outside the 15-minute sweep range."
                        )
                    }
                    onFinished(instanceId)
                    return
                }
                if (!evaluation.isHammer) {
                    repository.update(instanceId) { current ->
                        current.withFiveMinuteConfirmationUpdated(
                            confirmation.copy(processedBarTimes = updatedProcessed)
                        )
                    }
                    continue
                }
                submitHammerBracket(
                    instanceId = instanceId,
                    hammerBar = bar,
                    executionGw = executionGw,
                    processedBarTimes = updatedProcessed
                )
                return
            }
            delayMillis(pollIntervalMs(instance.symbol))
        }
    }

    private suspend fun submitHammerBracket(
        instanceId: String,
        hammerBar: OhlcBar,
        executionGw: BrokerGateway?,
        processedBarTimes: List<String>
    ) {
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
        val session = instance.touchTurnSession ?: return
        val setup = session.setup ?: return
        val rules = session.rules
        val evaluatedAt = nowEpochMillis()
        repository.update(instanceId) { current ->
            current.withFiveMinuteConfirmationUpdated(
                session.fiveMinuteConfirmation!!.copy(processedBarTimes = processedBarTimes)
            ).withFiveMinuteConfirmationConfirmed(hammerBar, evaluatedAt)
        }
        val afterConfirm = repository.deployments.value.find { it.id == instanceId } ?: return
        val confirmedAt = afterConfirm.touchTurnSession?.milestones?.fiveMinConfirmedAt
        if (confirmedAt != null) {
            SessionTrace.fiveMinuteConfirmationConfirmed(
                deploymentId = instanceId,
                sessionId = afterConfirm.inProgressSession()?.id,
                symbol = afterConfirm.symbol,
                barTime = hammerBar.time ?: "",
                entryPrice = hammerBar.close,
                confirmedAt = confirmedAt
            )
        }
        val afterSession = afterConfirm.touchTurnSession ?: return
        val hammerSetup = afterSession.setup ?: return
        bracketSubmitSkipReason(afterConfirm)?.let {
            onFinished(instanceId)
            return
        }
        if (!replayOpeningBarQuotesReady(afterConfirm.symbol)) {
            delayMillis(pollIntervalMs(afterConfirm.symbol))
            submitHammerBracket(instanceId, hammerBar, executionGw, processedBarTimes)
            return
        }
        val deploymentInstrument = DeploymentMarket.effectiveInstrument(afterConfirm)
        val orderSizeRules = deploymentInstrument?.orderSizeRules()
            ?: daytrader.domain.InstrumentOrderSizeRules.DEFAULT
        when (val sizing = TouchTurnOrderPlanner.sizeQuantity(
            afterConfirm.maxDollars,
            hammerSetup.entry,
            orderSizeRules
        )) {
            is TouchTurnOrderSizingResult.BelowMinimum -> {
                repository.update(instanceId) {
                    it.withTouchTurnDecisionOutcome(
                        TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
                        detailMessage = TouchTurnOrderPlanner.insufficientFundsDetailMessage(
                            maxDollars = afterConfirm.maxDollars,
                            currencyCode = afterSession.currencyCode,
                            entryPrice = hammerSetup.entry,
                            sizing = sizing
                        )
                    )
                }
                onFinished(instanceId)
                return
            }
            TouchTurnOrderSizingResult.InvalidInputs -> {
                onFinished(instanceId)
                return
            }
            is TouchTurnOrderSizingResult.Ok -> Unit
        }
        val plan = TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = afterConfirm.symbol,
            hammerBar = hammerBar,
            side = setup.side,
            maxDollars = afterConfirm.maxDollars,
            currencyCode = afterSession.currencyCode,
            instrument = deploymentInstrument,
            rules = rules
        ) ?: run {
            onFinished(instanceId)
            return
        }
        ensureEmulatorQuotesBeforeBracketSubmit(afterConfirm, hammerSetup, rules, plan)
        if (executionGw == null) {
            repository.update(instanceId) {
                it.withTouchTurnDecisionOutcome(TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED)
            }
            onFinished(instanceId)
            return
        }
        registerPendingBracket(
            instanceId,
            plan,
            afterConfirm.inProgressSession()?.id,
            evaluatedAt
        )
        val submitted = TouchTurnOrderLog.logHammerConfirmationBracket(
            instanceId = instanceId,
            symbol = afterConfirm.symbol,
            sessionDate = afterSession.sessionDate,
            maxDollars = afterConfirm.maxDollars,
            currencyCode = afterSession.currencyCode,
            instrument = deploymentInstrument,
            setup = hammerSetup,
            hammerBar = hammerBar,
            plan = plan,
            brokerGateway = executionGw
        )
        if (submitted) {
            SessionTrace.bracketSubmitRequested(
                deploymentId = instanceId,
                sessionId = afterConfirm.inProgressSession()?.id,
                symbol = afterConfirm.symbol,
                orderCount = plan.orders.size,
                entryPrice = hammerSetup.entry,
                currencyCode = afterSession.currencyCode,
                pendingBracketCount = 1,
                extraDetails = mapOf("confirmation" to "five_minute_hammer")
            )
        }
        onFinished(instanceId)
    }
}
