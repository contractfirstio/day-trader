package daytrader.domain

import kotlinx.serialization.Serializable

/**
 * Clock used with [TouchTurnRuleConfig.trailingActivateAfterMinutes] before trailing may arm.
 * [SESSION_OPEN] = RTH open + N minutes; [FILL] = entry fill + N minutes.
 */
@Serializable
enum class TouchTurnTrailingActivateClockBase {
    SESSION_OPEN,
    FILL
}

/**
 * Evaluates whether adjustable trailing may attach / arm given rule config and clock inputs.
 * Defaults (`minutes=0`, `requirePriceTrigger=true`) match today's immediate IB adjustable path.
 */
object TouchTurnTrailingActivation {
    /** True when the IB/emulator adjustable leg should be sent with the initial bracket. */
    fun attachAdjustableAtPlacement(rules: TouchTurnRuleConfig): Boolean {
        if (!rules.enables.adjustableTrailingStop) return false
        return rules.trailingActivateAfterMinutes <= 0 && rules.trailingRequirePriceTrigger
    }

    /**
     * Absolute epoch when the time gate opens, or null when there is no time gate
     * ([TouchTurnRuleConfig.trailingActivateAfterMinutes] ≤ 0).
     */
    fun activationEpochMs(rules: TouchTurnRuleConfig, clockBaseEpochMs: Long): Long? {
        if (!rules.enables.adjustableTrailingStop) return null
        if (rules.trailingActivateAfterMinutes <= 0) return null
        return clockBaseEpochMs + rules.trailingActivateAfterMinutes * 60_000L
    }

    /** Resolves the clock-base epoch for [rules.trailingActivateClockBase], or null when unknown. */
    fun clockBaseEpochMs(
        rules: TouchTurnRuleConfig,
        sessionOpenEpochMs: Long?,
        entryFillEpochMs: Long?
    ): Long? = when (rules.trailingActivateClockBase) {
        TouchTurnTrailingActivateClockBase.SESSION_OPEN -> sessionOpenEpochMs
        TouchTurnTrailingActivateClockBase.FILL -> entryFillEpochMs
    }

    fun isTimeEligible(
        rules: TouchTurnRuleConfig,
        nowEpochMs: Long,
        activationEpochMs: Long?
    ): Boolean {
        if (!rules.enables.adjustableTrailingStop) return false
        if (rules.trailingActivateAfterMinutes <= 0) return true
        if (activationEpochMs == null) return false
        return nowEpochMs >= activationEpochMs
    }

    /**
     * Whether trailing may arm now (time gate + optional price trigger).
     * [priceTriggerCrossed] is ignored when [TouchTurnRuleConfig.trailingRequirePriceTrigger] is false.
     */
    fun mayArmTrailing(
        rules: TouchTurnRuleConfig,
        nowEpochMs: Long,
        activationEpochMs: Long?,
        priceTriggerCrossed: Boolean
    ): Boolean {
        if (!isTimeEligible(rules, nowEpochMs, activationEpochMs)) return false
        if (!rules.trailingRequirePriceTrigger) return true
        return priceTriggerCrossed
    }

    /**
     * Whether a deferred adjustable attachment should be submitted now (position working, time ok).
     * Price trigger remains an IB/emulator concern when [TouchTurnRuleConfig.trailingRequirePriceTrigger].
     */
    fun mayAttachDeferredAdjustable(
        rules: TouchTurnRuleConfig,
        nowEpochMs: Long,
        activationEpochMs: Long?
    ): Boolean {
        if (!rules.enables.adjustableTrailingStop) return false
        if (attachAdjustableAtPlacement(rules)) return false
        return isTimeEligible(rules, nowEpochMs, activationEpochMs)
    }
}
