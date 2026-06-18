package daytrader.presentation.strategies

import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionStopLogic
import daytrader.domain.TouchTurnTrailingStopWarnings
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
        return TouchTurnRuleConfig.toggleDefinitions.map { definition ->
            val enabled = TouchTurnRuleConfig.isToggleEnabled(rules, definition.key)
            val check = when (definition.key) {
                "liquidityRangeDailyAtr" -> liquidityRangeDailyAtrCheck(
                    session, candle, rules, currency, evaluationInstant, enabled
                )
                "openDeadline" -> openDeadlineCheck(
                    session, rules, evaluationInstant, enabled
                )
                "adjustableTrailingStop" -> adjustableTrailingStopCheck(
                    session, setup, rules, currency, enabled
                )
                else -> RuleCheckUi(
                    key = definition.key,
                    label = definition.label,
                    description = definition.description,
                    passed = null,
                    enabled = enabled
                )
            }
            if (verboseExplanations && enabled) {
                check
            } else if (check.enabled && check.passed == false && check.explanationSteps.isNotEmpty()) {
                check
            } else {
                check.copy(explanationSteps = emptyList())
            }
        }
    }

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

    private fun adjustableTrailingStopCheck(
        session: TouchTurnSessionContext,
        setup: daytrader.domain.TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig,
        currency: String,
        enabled: Boolean
    ): RuleCheckUi {
        val params = rules.computeAdjustableStop(
            setup.entry,
            setup.stopLoss,
            setup.takeProfit
        )
        val validationError = TouchTurnTrailingStopWarnings.validationError(rules, setup)
        val passed = when {
            !enabled -> null
            validationError != null -> false
            params != null -> true
            else -> false
        }
        val steps = buildList {
            add(
                "Entry ${fmt(setup.entry, currency)}, stop ${fmt(setup.stopLoss, currency)}, " +
                    "target ${fmt(setup.takeProfit, currency)}."
            )
            add(
                "Trail arm at ${rules.trailingStopTriggerFractionOfEntryToTp}× entry-to-target distance; " +
                    "arm stop at ${rules.trailingStopArmFractionOfEntryToStop}× entry-to-stop toward initial stop; " +
                    "then ratchets 1:1 with further favorable price."
            )
            validationError?.let { add("Configuration invalid: $it") }
            params?.let {
                val armNote = if (rules.trailingStopArmFractionOfEntryToStop > 0.0) {
                    " (${rules.trailingStopArmFractionOfEntryToStop}× entry→stop)"
                } else {
                    " (entry)"
                }
                add(
                    "Trail arms at ${fmt(it.triggerPrice, currency)} → stop ${fmt(it.armStopPrice, currency)}$armNote."
                )
            }
            add(stepResult(passed))
        }
        return RuleCheckUi(
            key = "adjustableTrailingStop",
            label = "Adjustable trailing stop",
            description = "After price reaches the trail-arm level, move the stop to entry (minus optional " +
                "cushion) and ratchet it with further favorable price.",
            passed = passed,
            detail = when {
                !enabled -> "Disabled"
                validationError != null -> validationError
                params != null -> "Arms at ${fmt(params.triggerPrice, currency)}"
                else -> "Not configured"
            },
            enabled = enabled,
            explanationSteps = steps
        )
    }

    private fun stepResult(passed: Boolean?): String = when (passed) {
        true -> "Result: passed."
        false -> "Result: failed."
        null -> "Result: not evaluated or inconclusive."
    }

    private fun fmt(amount: Double, currency: String): String = Formatters.moneyPlain(amount, currency)

    private fun parseIsoToEpochMillis(iso: String): Long? = runCatching {
        java.time.LocalDateTime.parse(iso, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}
