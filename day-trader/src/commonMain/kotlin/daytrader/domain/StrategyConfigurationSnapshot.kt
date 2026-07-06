package daytrader.domain

import kotlinx.serialization.Serializable

/** Inputs that materially affect trade / no-trade decisions for a deployment run. */
@Serializable
data class StrategyConfigurationSnapshot(
    val strategyType: StrategyType,
    val maxDollars: Int,
    val touchTurnRules: TouchTurnRuleConfig? = null,
) {
    fun fingerprint(): String = "$FINGERPRINT_PREFIX${sha256Hex(canonicalString()).take(FINGERPRINT_HEX_LENGTH)}"

    fun canonicalString(): String = buildString {
        append("strategyType=").append(strategyType.name)
        append("|maxDollars=").append(maxDollars)
        touchTurnRules?.let { rules ->
            append("|atrLiquidityRatio=").append(rules.atrLiquidityRatio)
            append("|dailyAtrLookbackPeriods=").append(rules.dailyAtrLookbackPeriods)
            append("|entryInwardOffsetRatioOfRange=").append(rules.entryInwardOffsetRatioOfRange)
            append("|entryOutwardOffsetRatioOfRange=").append(rules.entryOutwardOffsetRatioOfRange)
            append("|takeProfitFibRatioGreen=").append(rules.takeProfitFibRatioGreen)
            append("|takeProfitFibRatioRed=").append(rules.takeProfitFibRatioRed)
            append("|takeProfitToStopLossRatio=").append(rules.takeProfitToStopLossRatio)
            append("|closedBarRefetchSettleMs=").append(rules.closedBarRefetchSettleMs)
            append("|stopAfterOpenMinutes=").append(rules.stopAfterOpenMinutes)
            append("|trailingStopTriggerFractionOfEntryToTp=")
                .append(rules.trailingStopTriggerFractionOfEntryToTp)
            append("|trailingStopArmFractionOfEntryToStop=")
                .append(rules.trailingStopArmFractionOfEntryToStop)
            append("|liquidityRangeDailyAtr=").append(rules.enables.liquidityRangeDailyAtr)
            append("|openDeadline=").append(rules.enables.openDeadline)
            append("|adjustableTrailingStop=").append(rules.enables.adjustableTrailingStop)
            append("|invertTradeSide=").append(rules.invertTradeSide)
        }
    }

    companion object {
        const val FINGERPRINT_PREFIX = "cfg-v1:"
        const val FINGERPRINT_HEX_LENGTH = 12
    }
}

fun StrategyDeployment.currentConfigurationSnapshot(): StrategyConfigurationSnapshot =
    StrategyConfigurationSnapshot(
        strategyType = strategyType,
        maxDollars = maxDollars,
        touchTurnRules = touchTurnRules.takeIf { isTouchTurn },
    )

fun StrategyDeployment.currentConfigurationFingerprint(): String =
    currentConfigurationSnapshot().fingerprint()

fun StrategySession.configurationSnapshot(deployment: StrategyDeployment): StrategyConfigurationSnapshot =
    StrategyConfigurationSnapshot(
        strategyType = deployment.strategyType,
        maxDollars = maxAtRisk,
        touchTurnRules = when {
            touchTurnRunRecord?.rules != null -> touchTurnRunRecord.rules
            deployment.isTouchTurn -> deployment.touchTurnRules
            else -> null
        },
    )

fun StrategySession.resolvedConfigurationFingerprint(deployment: StrategyDeployment): String =
    configurationFingerprint ?: configurationSnapshot(deployment).fingerprint()

fun StrategySession.withConfigurationFingerprint(deployment: StrategyDeployment): StrategySession =
    copy(configurationFingerprint = configurationSnapshot(deployment).fingerprint())

fun List<StrategySession>.rollupsForConfiguration(
    asOfSessionDate: String,
    deployment: StrategyDeployment,
): SessionRollups {
    val fingerprint = deployment.currentConfigurationFingerprint()
    return filter { session ->
        session.status == SessionStatus.CLOSED &&
            session.resolvedConfigurationFingerprint(deployment) == fingerprint
    }.rollups(asOfSessionDate)
}

fun configurationRollupsForDeployments(
    instances: List<StrategyDeployment>,
    asOfSessionDate: String,
): SessionRollups {
    val matchingSessions = instances.flatMap { deployment ->
        val fingerprint = deployment.currentConfigurationFingerprint()
        deployment.sessionHistory.filter { session ->
            session.status == SessionStatus.CLOSED &&
                session.resolvedConfigurationFingerprint(deployment) == fingerprint
        }
    }
    return matchingSessions.rollups(asOfSessionDate)
}

private fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.encodeToByteArray())
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            append(((byte.toInt() and 0xff) + 0x100).toString(16).substring(1))
        }
    }
}
