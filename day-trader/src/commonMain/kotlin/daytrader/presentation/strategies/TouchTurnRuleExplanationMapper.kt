package daytrader.presentation.strategies

import daytrader.domain.FirstCandleColor
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStopLogic
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.TouchTurnVolumeCheck
import daytrader.presentation.Formatters

/**
 * Builds pipeline rule rows and optional step-by-step explanations for closed-session review.
 */
object TouchTurnRuleExplanationMapper {

    fun evaluationEpochMillis(session: TouchTurnSessionContext): Long {
        session.milestones.liquidityEvaluatedAt?.let(::parseIsoToEpochMillis)?.let { return it }
        session.milestones.closeConfirmedAt?.let(::parseIsoToEpochMillis)?.let { return it }
        session.milestones.ordersPlacedAt?.let(::parseIsoToEpochMillis)?.let { return it }
        val barTime = session.candle?.time ?: session.openingBarTime
        if (barTime != null) {
            TouchTurnLogic.barEndEpochMillis(barTime, session.marketZoneId)?.plus(1_000)?.let { return it }
        }
        return System.currentTimeMillis()
    }

    fun buildChecks(
        session: TouchTurnSessionContext,
        evaluationInstant: Long,
        verboseExplanations: Boolean,
        requireLivePriceChecks: Boolean
    ): List<RuleCheckUi> {
        val candle = session.candle ?: return emptyList()
        val setup = session.setup ?: return emptyList()
        val rules = session.rules
        val currency = session.currencyCode
        val zone = session.marketZoneId
        return TouchTurnRuleConfig.toggleDefinitions.map { definition ->
            val enabled = TouchTurnRuleConfig.isToggleEnabled(rules, definition.key)
            val check = when (definition.key) {
                "liquidityRange15mAtr" -> liquidityRange15mAtrCheck(
                    session, candle, rules, currency, evaluationInstant, enabled
                )
                "liquidityRangeDailyAtr" -> liquidityRangeDailyAtrCheck(
                    session, candle, rules, currency, evaluationInstant, enabled
                )
                "notDoji" -> notDojiCheck(setup, candle, rules, enabled)
                "volumeExhaustion" -> volumeExhaustionCheck(session, candle, rules, currency, enabled)
                "barCloseTurn" -> barCloseTurnCheck(
                    session, setup, candle, rules, currency, evaluationInstant, enabled
                )
                "entryWindow" -> entryWindowCheck(
                    session, candle, rules, zone, evaluationInstant, enabled
                )
                "liveQuoteRequired" -> liveQuoteCheck(
                    session, requireLivePriceChecks, enabled
                )
                "liveBarAgreement" -> liveBarAgreementCheck(
                    session, candle, setup, rules, currency, requireLivePriceChecks, enabled
                )
                "liveTurnConfirmation" -> liveTurnConfirmationCheck(
                    session, candle, setup, rules, currency, requireLivePriceChecks, enabled
                )
                "liveEntryTouchable" -> liveEntryTouchableCheck(
                    session, setup, rules, currency, requireLivePriceChecks, enabled
                )
                "postEntryVolumeBuffer" -> postEntryVolumeBufferCheck(
                    session, rules, currency, enabled
                )
                "openDeadline" -> openDeadlineCheck(
                    session, rules, evaluationInstant, enabled
                )
                else -> RuleCheckUi(
                    key = definition.key,
                    label = definition.label,
                    description = definition.description,
                    passed = null,
                    enabled = enabled
                )
            }
            if (verboseExplanations && enabled) check else check.copy(explanationSteps = emptyList())
        }
    }

    private fun liquidityRange15mAtrCheck(
        session: TouchTurnSessionContext,
        candle: OhlcBar,
        rules: TouchTurnRuleConfig,
        currency: String,
        evaluationInstant: Long,
        enabled: Boolean
    ): RuleCheckUi = liquidityRangeGateCheck(
        key = "liquidityRange15mAtr",
        label = "Liquidity range (15m ATR)",
        description = "Opening 15m bar range must be at least 25% of 15m ATR(14).",
        atrLabel = "15m ATR14",
        atrValue = session.atr14,
        threshold = session.liquidityThresholds.threshold15mAtr,
        candle = candle,
        currency = currency,
        evaluationInstant = evaluationInstant,
        enabled = enabled,
        session = session
    )

    private fun liquidityRangeDailyAtrCheck(
        session: TouchTurnSessionContext,
        candle: OhlcBar,
        rules: TouchTurnRuleConfig,
        currency: String,
        evaluationInstant: Long,
        enabled: Boolean
    ): RuleCheckUi = liquidityRangeGateCheck(
        key = "liquidityRangeDailyAtr",
        label = "Liquidity range (daily ATR)",
        description = "Opening 15m bar range must be at least 25% of daily ATR(14) on close.",
        atrLabel = "Daily ATR14",
        atrValue = session.dailyAtr14,
        threshold = session.liquidityThresholds.thresholdDailyAtr,
        candle = candle,
        currency = currency,
        evaluationInstant = evaluationInstant,
        enabled = enabled,
        session = session
    )

    private fun liquidityRangeGateCheck(
        key: String,
        label: String,
        description: String,
        atrLabel: String,
        atrValue: Double?,
        threshold: Double?,
        candle: OhlcBar,
        currency: String,
        evaluationInstant: Long,
        enabled: Boolean,
        session: TouchTurnSessionContext
    ): RuleCheckUi {
        val closeStatus = session.candleCloseStatus(evaluationInstant)
        val ratio = session.rules.atrLiquidityRatio
        val gatePassed = threshold != null && candle.range >= threshold
        val passed = when {
            !enabled -> null
            closeStatus != FirstCandleCloseStatus.CLOSED -> null
            threshold == null -> null
            gatePassed -> true
            else -> false
        }
        val steps = buildList {
            add("Wait for the opening 15-minute bar to finish printing.")
            add(
                "Measure bar range: high ${fmt(candle.high, currency)} − low ${fmt(candle.low, currency)} " +
                    "= ${fmt(candle.range, currency)}."
            )
            if (atrValue != null && atrValue > 0.0 && threshold != null) {
                add(
                    "Liquidity threshold = $atrLabel ${fmt(atrValue, currency)} × ${ratio} " +
                        "= ${fmt(threshold, currency)}."
                )
            } else if (threshold != null) {
                add("Liquidity threshold for this run: ${fmt(threshold, currency)}.")
            } else {
                add("$atrLabel was not available — this gate could not be evaluated.")
            }
            if (threshold != null) {
                add(
                    "Compare range ${fmt(candle.range, currency)} against threshold ${fmt(threshold, currency)} " +
                        "(need range ≥ threshold)."
                )
            }
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = key,
            label = label,
            description = description,
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                closeStatus != FirstCandleCloseStatus.CLOSED -> null
                threshold == null -> "ATR unavailable"
                gatePassed -> "OK"
                else -> "Below threshold"
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun notDojiCheck(
        setup: TouchTurnBracketSetup,
        candle: OhlcBar,
        rules: TouchTurnRuleConfig,
        enabled: Boolean
    ): RuleCheckUi {
        val color = TouchTurnLogic.firstCandleColor(candle)
        val passed = when {
            !enabled -> null
            else -> setup.isActionable
        }
        val steps = buildList {
            add("Read the opening bar open ${candle.open} and close ${candle.close}.")
            add("Classify candle colour: ${TouchTurnLogic.candleColorLabel(color)}.")
            add("A doji (open = close) is not actionable for Touch Turn brackets.")
            if (setup.isLiquidityCandle) {
                add("Liquidity bar qualifies — colour determines short (green) or long (red) setup.")
            } else {
                add("Bar is not a liquidity candle; actionable flag still requires liquidity + non-doji.")
            }
            add(
                if (setup.isActionable) {
                    "Bar is actionable (${TouchTurnLogic.tradeSideLabel(setup.side)} at " +
                        "${setup.entry})."
                } else {
                    "Bar is not actionable."
                }
            )
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = "notDoji",
            label = "Not a doji",
            description = "Bar must be actionable (not a flat doji).",
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                setup.isActionable -> "Actionable"
                else -> "Doji"
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun volumeExhaustionCheck(
        session: TouchTurnSessionContext,
        candle: OhlcBar,
        rules: TouchTurnRuleConfig,
        currency: String,
        enabled: Boolean
    ): RuleCheckUi {
        val volumeSma20 = session.volumeSma20 ?: 0.0
        val volumeCheck = TouchTurnVolumeCheck.fromSession(session)
        val exhausted = enabled &&
            TouchTurnLogic.isVolumeExhaustion(candle.volume, volumeSma20, rules)
        val passed = when {
            !enabled -> null
            volumeSma20 <= 0.0 -> null
            else -> !exhausted
        }
        val threshold = TouchTurnLogic.volumeExhaustionThreshold(volumeSma20, rules)
        val ratio = rules.volumeExhaustionRatio
        val steps = buildList {
            add("Read opening-bar volume: ${formatVolume(candle.volume)}.")
            if (volumeSma20 > 0.0) {
                add("Volume SMA${rules.volumeSmaPeriods}: ${formatVolume(volumeSma20)}.")
                add(
                    "Exhaustion cap = SMA × ${ratio} = ${formatVolume(threshold)} " +
                        "(volume above this blocks entry)."
                )
                volumeCheck?.volumeRatio?.let { volRatio ->
                    add(
                        "Observed ratio = volume / SMA = ${"%.2f".format(volRatio)}× " +
                            "(limit ${ratio}×)."
                    )
                }
                add(
                    if (exhausted) {
                        "Volume ${formatVolume(candle.volume)} exceeds cap ${formatVolume(threshold)}."
                    } else {
                        "Volume ${formatVolume(candle.volume)} is at or below cap ${formatVolume(threshold)}."
                    }
                )
            } else {
                add("Volume SMA was not available — exhaustion gate could not be evaluated.")
            }
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = "volumeExhaustion",
            label = "Volume exhaustion",
            description = "Block entry when opening-bar volume exceeds the exhaustion multiple of SMA20.",
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                volumeSma20 <= 0.0 -> null
                exhausted -> "Exhausted"
                else -> "OK"
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun barCloseTurnCheck(
        session: TouchTurnSessionContext,
        setup: TouchTurnBracketSetup,
        candle: OhlcBar,
        rules: TouchTurnRuleConfig,
        currency: String,
        evaluationInstant: Long,
        enabled: Boolean
    ): RuleCheckUi {
        val closeConfirmation = session.pipelineCloseConfirmation(evaluationInstant)
        val closeRatio = TouchTurnLogic.closePositionRatio(candle)
        val inZone = TouchTurnLogic.closePositionInTurnZone(setup, candle, candle.close, rules)
        val passed = when {
            !enabled -> null
            closeConfirmation == TouchTurnCloseConfirmation.PASSED -> true
            closeConfirmation == TouchTurnCloseConfirmation.FAILED ||
                closeConfirmation == TouchTurnCloseConfirmation.EXPIRED -> false
            else -> null
        }
        val zoneLabel = when (setup.candleColor) {
            FirstCandleColor.GREEN ->
                "lower band (≤ ${(rules.closePositionShortMax * 100).toInt()}% of range from low)"
            FirstCandleColor.RED ->
                "upper band (≥ ${(rules.closePositionLongMin * 100).toInt()}% of range from low)"
            else -> "turn zone"
        }
        val steps = buildList {
            add(
                "${TouchTurnLogic.tradeSideLabel(setup.side)} entry at ${fmt(setup.entry, currency)} " +
                    "(${setup.candleColor.name.lowercase()} liquidity bar)."
            )
            closeRatio?.let { ratio ->
                add(
                    "Close sits at ${(ratio * 100).toInt()}% of bar range — turn zone is $zoneLabel."
                )
            }
            add(
                if (inZone) {
                    "Close is inside the confirming turn zone."
                } else {
                    "Close is outside the confirming turn zone."
                }
            )
            add("Pipeline close confirmation: ${closeConfirmation.name.replace('_', ' ').lowercase()}.")
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = "barCloseTurn",
            label = "Bar close turn",
            description = "15m bar close must confirm the turn zone before entry.",
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                else -> closeRatio?.let { "Close at ${(it * 100).toInt()}% of range" }
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun entryWindowCheck(
        session: TouchTurnSessionContext,
        candle: OhlcBar,
        rules: TouchTurnRuleConfig,
        zone: String,
        evaluationInstant: Long,
        enabled: Boolean
    ): RuleCheckUi {
        val barTime = candle.time
        val barEnd = barTime?.let { TouchTurnLogic.barEndEpochMillis(it, zone) }
        val deadline = barEnd?.plus(rules.closeConfirmationAfterCloseMs)
        val withinDeadline = barEnd != null && deadline != null &&
            evaluationInstant in barEnd..deadline
        val expiredOutcome = session.decisionOutcome == TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
        val passed = when {
            !enabled -> null
            session.ordersPlacedForSession || session.entryOrdersPermitted == true -> true
            expiredOutcome -> false
            session.decisionOutcome != null && !expiredOutcome -> true
            barEnd == null -> null
            withinDeadline -> true
            evaluationInstant > (deadline ?: 0L) -> false
            else -> null
        }
        val steps = buildList {
            add("After the 15m bar closes, bracket placement must finish inside the entry window.")
            if (barEnd != null && deadline != null) {
                add(
                    "Bar ended at epoch $barEnd; deadline is +${rules.closeConfirmationAfterCloseMs} ms " +
                        "(${rules.closeConfirmationAfterCloseMs / 1000}s)."
                )
                session.milestones.liquidityEvaluatedAt?.let {
                    add("Liquidity evaluated at $it.")
                }
                session.milestones.ordersPlacedAt?.let {
                    add("Orders placed at $it.")
                }
                add(
                    if (withinDeadline) {
                        "Decision at evaluation time was still inside the window."
                    } else if (evaluationInstant > deadline) {
                        "Evaluation time was after the entry window closed."
                    } else {
                        "Window timing relative to bar close was checked at session end."
                    }
                )
            } else {
                add("Bar close time was not available for window calculation.")
            }
            session.decisionOutcome?.let { outcome ->
                add("Session outcome: ${outcome.name.replace('_', ' ').lowercase()}.")
            }
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = "entryWindow",
            label = "Entry window",
            description = "Turn confirmation and bracket placement must complete within the post-close window.",
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                passed == true -> "Within window"
                passed == false -> "Expired"
                else -> null
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun liveQuoteCheck(
        session: TouchTurnSessionContext,
        requireLivePriceChecks: Boolean,
        enabled: Boolean
    ): RuleCheckUi {
        val outcome = session.decisionOutcome
        val passed = liveRulePassed(
            enabled = enabled,
            requireLivePriceChecks = requireLivePriceChecks,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
            session = session
        )
        val steps = liveRuleSteps(
            requireLivePriceChecks = requireLivePriceChecks,
            ruleName = "Live quote required",
            applicableDetail = "Bid and ask must be present before entry orders are sent.",
            notApplicableDetail = "Emulator / historical path — live bid/ask not required.",
            outcome = outcome,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
            passed = passed
        )
        return RuleCheckUi(
            key = "liveQuoteRequired",
            label = "Live quote required",
            description = "IB-live mode: bid and ask must be available before placing entry.",
            passed = passed,
            detail = liveRuleDetail(enabled, requireLivePriceChecks, passed),
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun liveBarAgreementCheck(
        session: TouchTurnSessionContext,
        candle: OhlcBar,
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig,
        currency: String,
        requireLivePriceChecks: Boolean,
        enabled: Boolean
    ): RuleCheckUi {
        val maxGap = candle.range * rules.barLiveDivergenceMaxRatioOfRange
        val passed = liveRulePassed(
            enabled = enabled,
            requireLivePriceChecks = requireLivePriceChecks,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            session = session
        )
        val steps = liveRuleSteps(
            requireLivePriceChecks = requireLivePriceChecks,
            ruleName = "Bar / live agreement",
            applicableDetail =
                "Live mid must be within ${fmt(maxGap, currency)} of bar close " +
                    "(${rules.barLiveDivergenceMaxRatioOfRange}× range).",
            notApplicableDetail = "Emulator / historical path — bar/live agreement not enforced.",
            outcome = session.decisionOutcome,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            passed = passed
        )
        if (requireLivePriceChecks && enabled) {
            steps.add(
                "Bar close ${fmt(candle.close, currency)}; max allowed gap from live mid = " +
                    "${fmt(maxGap, currency)}."
            )
        }
        steps.add(stepResult(passed))
        return RuleCheckUi(
            key = "liveBarAgreement",
            label = "Bar / live agreement",
            description = "IB-live mode: live mid must agree with the completed bar close within tolerance.",
            passed = passed,
            detail = liveRuleDetail(enabled, requireLivePriceChecks, passed),
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun liveTurnConfirmationCheck(
        session: TouchTurnSessionContext,
        candle: OhlcBar,
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig,
        currency: String,
        requireLivePriceChecks: Boolean,
        enabled: Boolean
    ): RuleCheckUi {
        val passed = liveRulePassed(
            enabled = enabled,
            requireLivePriceChecks = requireLivePriceChecks,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
            session = session
        )
        val steps = liveRuleSteps(
            requireLivePriceChecks = requireLivePriceChecks,
            ruleName = "Live turn confirmation",
            applicableDetail =
                "Live mid must confirm the turn zone on the tape (same zone rules as bar close).",
            notApplicableDetail = "Emulator / historical path — live turn confirmation not enforced.",
            outcome = session.decisionOutcome,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
            passed = passed
        )
        if (requireLivePriceChecks && enabled) {
            steps.add(
                "Entry ${fmt(setup.entry, currency)}; live price must sit on the confirming side in the turn zone."
            )
        }
        steps.add(stepResult(passed))
        return RuleCheckUi(
            key = "liveTurnConfirmation",
            label = "Live turn confirmation",
            description = "IB-live mode: live mid must confirm the turn zone on the tape.",
            passed = passed,
            detail = liveRuleDetail(enabled, requireLivePriceChecks, passed),
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun liveEntryTouchableCheck(
        session: TouchTurnSessionContext,
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig,
        currency: String,
        requireLivePriceChecks: Boolean,
        enabled: Boolean
    ): RuleCheckUi {
        val buffer = TouchTurnLogic.entryTouchBuffer(setup, rules)
        val passed = liveRulePassed(
            enabled = enabled,
            requireLivePriceChecks = requireLivePriceChecks,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            session = session
        )
        val touchSide = when (setup.side) {
            TouchTurnTradeSide.LONG -> "ask must stay at or above entry − buffer"
            TouchTurnTradeSide.SHORT -> "bid must stay at or below entry + buffer"
        }
        val steps = liveRuleSteps(
            requireLivePriceChecks = requireLivePriceChecks,
            ruleName = "Live entry touchable",
            applicableDetail =
                "Entry limit ${fmt(setup.entry, currency)} with touch buffer ${fmt(buffer, currency)}; $touchSide.",
            notApplicableDetail = "Emulator / historical path — live entry touch not enforced.",
            outcome = session.decisionOutcome,
            failedOutcome = TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            passed = passed
        )
        steps.add(stepResult(passed))
        return RuleCheckUi(
            key = "liveEntryTouchable",
            label = "Live entry touchable",
            description = "IB-live mode: live price must still be touchable at the entry limit.",
            passed = passed,
            detail = liveRuleDetail(enabled, requireLivePriceChecks, passed),
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun postEntryVolumeBufferCheck(
        session: TouchTurnSessionContext,
        rules: TouchTurnRuleConfig,
        currency: String,
        enabled: Boolean
    ): RuleCheckUi {
        val ordersPlaced = session.ordersPlacedForSession
        val volumeSma20 = session.volumeSma20 ?: 0.0
        val threshold = TouchTurnLogic.volumeExhaustionThreshold(volumeSma20, rules)
        val passed = when {
            !enabled -> null
            !ordersPlaced -> null
            else -> true
        }
        val steps = buildList {
            add("After the entry order is working, live volume is accumulated for a short window.")
            if (!ordersPlaced) {
                add("No entry order was placed this session — buffer monitor did not run.")
            } else {
                add(
                    "Observation window: ${rules.volumeBufferObservationMs} ms " +
                        "(${rules.volumeBufferObservationMs / 1000}s) after entry submission."
                )
                if (volumeSma20 > 0.0) {
                    add(
                        "Cancel if accumulated volume exceeds ${formatVolume(threshold)} " +
                            "(SMA × ${rules.volumeExhaustionRatio})."
                    )
                }
                add("Entry was not cancelled by the post-entry volume buffer during this run.")
            }
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = "postEntryVolumeBuffer",
            label = "Post-entry volume buffer",
            description = "After entry is working, cancel if live volume exceeds exhaustion threshold " +
                "during the observation window.",
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                !ordersPlaced -> "Not run"
                else -> "Completed"
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun openDeadlineCheck(
        session: TouchTurnSessionContext,
        rules: TouchTurnRuleConfig,
        evaluationInstant: Long,
        enabled: Boolean
    ): RuleCheckUi {
        val openEpoch = TouchTurnLogic.marketOpenEpochMillis(
            sessionDateIso = session.sessionDate,
            marketZoneId = session.marketZoneId,
            firstCandleBarTime = session.candle?.time ?: session.openingBarTime
        )
        val remainingMs = openEpoch?.let {
            TouchTurnSessionStopLogic.millisUntilStopAfterOpen(it, rules, evaluationInstant)
        }
        val passed = when {
            !enabled -> null
            openEpoch == null -> null
            remainingMs == null -> null
            remainingMs > 0L -> true
            else -> false
        }
        val steps = buildList {
            add(
                "RTH open anchor: ${session.sessionDate} in ${session.marketZoneId} " +
                    "(first 15m bar or session calendar open)."
            )
            add("Configured auto-stop: ${rules.stopAfterOpenMinutes} minutes after that open.")
            if (openEpoch == null) {
                add("Open anchor was not available at evaluation time.")
            } else if (!enabled) {
                add("Rule disabled — session would not auto-stop on this deadline.")
            } else when (remainingMs) {
                null -> add("Remaining time before auto-stop was unavailable.")
                0L -> add("Evaluation time was at or past the open deadline.")
                else -> add(
                    "At evaluation, ${TouchTurnSessionStopLogic.pendingStopAfterOpenLabel(
                        remainingMs,
                        rules.stopAfterOpenMinutes
                    ).removePrefix("Auto-stop in ")} remained before auto-stop."
                )
            }
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = "openDeadline",
            label = "RTH open deadline",
            description = "Stop the session and flatten working orders/position after the configured maximum " +
                "minutes from regular-hours open.",
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                openEpoch == null -> "Open anchor unavailable"
                remainingMs == 0L -> "Past deadline"
                remainingMs != null ->
                    "${rules.stopAfterOpenMinutes}m limit · ${remainingMs / 60_000}m remaining"
                else -> null
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun liveRulePassed(
        enabled: Boolean,
        requireLivePriceChecks: Boolean,
        failedOutcome: TouchTurnSessionOutcome,
        session: TouchTurnSessionContext
    ): Boolean? {
        if (!enabled) return null
        if (!requireLivePriceChecks) return true
        return when (session.decisionOutcome) {
            failedOutcome -> false
            TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED -> true
            null -> if (session.ordersPlacedForSession || session.entryOrdersPermitted == true) true else null
            else -> if (session.entryOrdersPermitted == false) false else true
        }
    }

    private fun liveRuleDetail(
        enabled: Boolean,
        requireLivePriceChecks: Boolean,
        passed: Boolean?
    ): String? = when {
        !enabled -> "Disabled"
        !requireLivePriceChecks -> "N/A"
        passed == true -> "OK"
        passed == false -> "Failed"
        else -> null
    }

    private fun liveRuleSteps(
        requireLivePriceChecks: Boolean,
        ruleName: String,
        applicableDetail: String,
        notApplicableDetail: String,
        outcome: TouchTurnSessionOutcome?,
        failedOutcome: TouchTurnSessionOutcome,
        passed: Boolean?
    ): MutableList<String> = buildList {
        add("$ruleName gate.")
        if (requireLivePriceChecks) {
            add(applicableDetail)
            outcome?.let {
                add("Recorded session outcome: ${it.name.replace('_', ' ').lowercase()}.")
            }
            if (outcome == failedOutcome) {
                add("This outcome indicates the live gate failed.")
            }
        } else {
            add(notApplicableDetail)
        }
    }.toMutableList()

    private fun stepResult(passed: Boolean?): String = when (passed) {
        true -> "Result: passed."
        false -> "Result: failed."
        null -> "Result: not evaluated or inconclusive."
    }

    private fun fmt(amount: Double, currency: String): String = Formatters.moneyPlain(amount, currency)

    private fun formatVolume(volume: Double): String =
        if (volume >= 1_000_000) "%.2fM".format(volume / 1_000_000.0)
        else if (volume >= 1_000) "%.1fK".format(volume / 1_000.0)
        else "%.0f".format(volume)

    private fun parseIsoToEpochMillis(iso: String): Long? = runCatching {
        java.time.LocalDateTime.parse(iso, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
