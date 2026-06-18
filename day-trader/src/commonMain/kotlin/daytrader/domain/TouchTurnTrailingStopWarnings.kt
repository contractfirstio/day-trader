package daytrader.domain

/** Runtime warnings when adjustable trailing is enabled but cannot be applied to a bracket setup. */
object TouchTurnTrailingStopWarnings {
    /** Full validation message for the given setup, or null when trailing applies or is disabled. */
    fun validationError(rules: TouchTurnRuleConfig, setup: TouchTurnBracketSetup): String? {
        if (!rules.enables.adjustableTrailingStop) return null
        return TouchTurnAdjustableStop.validate(
            entry = setup.entry,
            stopLoss = setup.stopLoss,
            takeProfit = setup.takeProfit,
            triggerFraction = rules.trailingStopTriggerFractionOfEntryToTp,
            armFractionOfEntryToStop = rules.trailingStopArmFractionOfEntryToStop
        )
    }

    fun validationError(session: TouchTurnSessionContext): String? {
        val setup = session.setup ?: return null
        return validationError(session.rules, setup)
    }

    /** Short hint shown under live price charts. */
    fun chartHint(rules: TouchTurnRuleConfig, setup: TouchTurnBracketSetup): String? =
        validationError(rules, setup)?.let { error ->
            "Trailing disabled — $error"
        }

    fun chartHint(session: TouchTurnSessionContext): String? {
        val setup = session.setup ?: return null
        return chartHint(session.rules, setup)
    }

    fun combineChartHints(vararg hints: String?): String? {
        val parts = hints.filterNot { it.isNullOrBlank() }.map { it!!.trim() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
