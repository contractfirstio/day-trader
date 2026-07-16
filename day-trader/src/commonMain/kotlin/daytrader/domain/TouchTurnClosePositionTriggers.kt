package daytrader.domain

import kotlinx.serialization.Serializable

/** Action when a configured opening-bar trigger matches on a liquidity-qualified bar. */
@Serializable
enum class TouchTurnClosePositionTriggerMode {
    OFF,
    SKIP,
    SWITCH_TO_TOUCH_TURN
}

enum class TouchTurnClosePositionTriggerEvaluation {
    NONE,
    SKIP,
    SWITCH_TO_TOUCH_TURN
}

object TouchTurnClosePositionTriggers {
    /**
     * Evaluates bar-color, cp, and body bounds on a liquidity-qualified opening bar.
     * Skip wins over switch; switch applies only when deployment invert is on.
     */
    fun evaluate(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig
    ): TouchTurnClosePositionTriggerEvaluation {
        val matched = matchedActions(setup, rules)
        if (matched.isEmpty()) return TouchTurnClosePositionTriggerEvaluation.NONE
        if (matched.any { it == TouchTurnClosePositionTriggerMode.SKIP }) {
            return TouchTurnClosePositionTriggerEvaluation.SKIP
        }
        if (rules.invertTradeSide && matched.any { it == TouchTurnClosePositionTriggerMode.SWITCH_TO_TOUCH_TURN }) {
            return TouchTurnClosePositionTriggerEvaluation.SWITCH_TO_TOUCH_TURN
        }
        return TouchTurnClosePositionTriggerEvaluation.NONE
    }

    fun skipOutcome(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig
    ): TouchTurnSessionOutcome? {
        if (evaluate(setup, rules) != TouchTurnClosePositionTriggerEvaluation.SKIP) return null
        return when {
            matchedColorActions(setup, rules).any { it == TouchTurnClosePositionTriggerMode.SKIP } ->
                TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_COLOR_SKIPPED
            matchedBodyActions(setup, rules).any { it == TouchTurnClosePositionTriggerMode.SKIP } ->
                TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_SHAPE_TRIGGER_SKIPPED
            else ->
                TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED
        }
    }

    fun rulesWithEvaluation(
        evaluation: TouchTurnClosePositionTriggerEvaluation,
        rules: TouchTurnRuleConfig
    ): TouchTurnRuleConfig = when (evaluation) {
        TouchTurnClosePositionTriggerEvaluation.SWITCH_TO_TOUCH_TURN -> rules.copy(invertTradeSide = false)
        TouchTurnClosePositionTriggerEvaluation.NONE,
        TouchTurnClosePositionTriggerEvaluation.SKIP -> rules
    }

    internal fun resolvedAction(
        threshold: Double?,
        action: TouchTurnClosePositionTriggerMode,
        closePositionGate: Boolean
    ): TouchTurnClosePositionTriggerMode? {
        if (threshold == null) return null
        return when (action) {
            TouchTurnClosePositionTriggerMode.OFF ->
                if (closePositionGate) TouchTurnClosePositionTriggerMode.SKIP else null
            else -> action
        }
    }

    private fun resolvedColorAction(
        action: TouchTurnClosePositionTriggerMode
    ): TouchTurnClosePositionTriggerMode? = when (action) {
        TouchTurnClosePositionTriggerMode.OFF -> null
        else -> action
    }

    private fun matchedActions(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig
    ): List<TouchTurnClosePositionTriggerMode> {
        if (!triggersApply(setup, rules)) return emptyList()
        return buildList {
            addAll(matchedColorActions(setup, rules))
            addAll(matchedClosePositionActions(setup, rules))
            addAll(matchedBodyActions(setup, rules))
        }
    }

    private fun matchedColorActions(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig
    ): List<TouchTurnClosePositionTriggerMode> = buildList {
        when (setup.candleColor) {
            FirstCandleColor.GREEN ->
                resolvedColorAction(rules.greenLiquidityBarAction)?.let(::add)
            FirstCandleColor.RED ->
                resolvedColorAction(rules.redLiquidityBarAction)?.let(::add)
            FirstCandleColor.DOJI -> Unit
        }
    }

    private fun matchedClosePositionActions(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig
    ): List<TouchTurnClosePositionTriggerMode> {
        val closePosition = setup.closePositionRatio ?: return emptyList()
        return buildList {
            when (setup.candleColor) {
                FirstCandleColor.GREEN -> {
                    matchBound(
                        threshold = rules.greenSkipClosePositionBelow,
                        action = rules.greenClosePositionBelowAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = closePosition,
                        matches = { cp, bound -> cp <= bound }
                    )?.let(::add)
                    matchBound(
                        threshold = rules.greenSkipClosePositionAbove,
                        action = rules.greenClosePositionAboveAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = closePosition,
                        matches = { cp, bound -> cp >= bound }
                    )?.let(::add)
                }
                FirstCandleColor.RED -> {
                    matchBound(
                        threshold = rules.redSkipClosePositionBelow,
                        action = rules.redClosePositionBelowAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = closePosition,
                        matches = { cp, bound -> cp <= bound }
                    )?.let(::add)
                    matchBound(
                        threshold = rules.redSkipClosePositionAbove,
                        action = rules.redClosePositionAboveAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = closePosition,
                        matches = { cp, bound -> cp >= bound }
                    )?.let(::add)
                }
                FirstCandleColor.DOJI -> Unit
            }
        }
    }

    private fun matchedBodyActions(
        setup: TouchTurnBracketSetup,
        rules: TouchTurnRuleConfig
    ): List<TouchTurnClosePositionTriggerMode> {
        val body = setup.bodyRatio ?: return emptyList()
        return buildList {
            when (setup.candleColor) {
                FirstCandleColor.GREEN -> {
                    matchBound(
                        threshold = rules.greenSkipBodyRatioBelow,
                        action = rules.greenBodyRatioBelowAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = body,
                        matches = { b, bound -> b <= bound }
                    )?.let(::add)
                    matchBound(
                        threshold = rules.greenSkipBodyRatioAbove,
                        action = rules.greenBodyRatioAboveAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = body,
                        matches = { b, bound -> b >= bound }
                    )?.let(::add)
                }
                FirstCandleColor.RED -> {
                    matchBound(
                        threshold = rules.redSkipBodyRatioBelow,
                        action = rules.redBodyRatioBelowAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = body,
                        matches = { b, bound -> b <= bound }
                    )?.let(::add)
                    matchBound(
                        threshold = rules.redSkipBodyRatioAbove,
                        action = rules.redBodyRatioAboveAction,
                        closePositionGate = rules.enables.closePositionGate,
                        value = body,
                        matches = { b, bound -> b >= bound }
                    )?.let(::add)
                }
                FirstCandleColor.DOJI -> Unit
            }
        }
    }

    private fun matchBound(
        threshold: Double?,
        action: TouchTurnClosePositionTriggerMode,
        closePositionGate: Boolean,
        value: Double,
        matches: (value: Double, bound: Double) -> Boolean
    ): TouchTurnClosePositionTriggerMode? {
        if (threshold == null) return null
        val resolved = resolvedAction(threshold, action, closePositionGate) ?: return null
        return resolved.takeIf { matches(value, threshold) }
    }

    private fun triggersApply(setup: TouchTurnBracketSetup, rules: TouchTurnRuleConfig): Boolean =
        setup.isLiquidityCandle
}
