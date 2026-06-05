package daytrader.domain

import kotlinx.serialization.Serializable

enum class TouchTurnPrepareCheckId {
    IB_CONNECTED,
    FLAT_POSITION,
    MARKET_LISTING,
    HISTORICAL_BOOTSTRAP,
    OPENING_BAR_TIME,
    LIVE_BID_ASK
}

enum class TouchTurnPrepareStatus {
    PASS,
    WARN,
    FAIL
}

@Serializable
data class TouchTurnPrepareCheck(
    val id: String,
    val status: String,
    val label: String,
    val detail: String? = null
)

@Serializable
data class TouchTurnSessionPrepare(
    /** ISO session date (yyyy-MM-dd) in the deployment market zone. */
    val sessionDateIso: String,
    val preparedAtEpochMillis: Long,
    val instrumentKey: String,
    val marketZoneId: String,
    val currencyCode: String,
    val signalContext: TouchTurnSignalContext,
    val checks: List<TouchTurnPrepareCheck>,
    val overallStatus: String
) {
    fun overall(): TouchTurnPrepareOverallStatus =
        runCatching { TouchTurnPrepareOverallStatus.valueOf(overallStatus) }
            .getOrDefault(TouchTurnPrepareOverallStatus.FAIL)

    fun isReady(): Boolean = overall() == TouchTurnPrepareOverallStatus.PASS

    companion object {
        fun overallFromChecks(checks: List<TouchTurnPrepareCheck>): TouchTurnPrepareOverallStatus =
            when {
                checks.any { it.status == TouchTurnPrepareStatus.FAIL.name } ->
                    TouchTurnPrepareOverallStatus.FAIL
                checks.any { it.status == TouchTurnPrepareStatus.WARN.name } ->
                    TouchTurnPrepareOverallStatus.WARN
                else -> TouchTurnPrepareOverallStatus.PASS
            }

        fun isValidForStart(
            prepare: TouchTurnSessionPrepare?,
            deployment: StrategyDeployment,
            sessionDateIso: String,
            nowEpochMillis: Long = System.currentTimeMillis()
        ): Boolean {
            if (prepare == null) return false
            if (prepare.checks.any { it.status == TouchTurnPrepareStatus.FAIL.name }) return false
            if (!prepare.signalContext.hasBootstrapMetrics()) return false
            if (prepare.sessionDateIso != sessionDateIso) return false
            if (prepare.instrumentKey != DeploymentMarket.effectiveInstrument(deployment).dedupeKey()) {
                return false
            }
            if (prepare.marketZoneId != DeploymentMarket.effectiveZoneId(deployment)) return false
            val age = nowEpochMillis - prepare.preparedAtEpochMillis
            return age in 0..TouchTurnPrepareDefaults.MAX_AGE_MS
        }

        /** Full bootstrap reuse on Start (skip IB fetch) — requires today's opening bar in cache. */
        fun canReuseBootstrapOnStart(
            prepare: TouchTurnSessionPrepare?,
            deployment: StrategyDeployment,
            sessionDateIso: String,
            nowEpochMillis: Long = System.currentTimeMillis()
        ): Boolean =
            isValidForStart(prepare, deployment, sessionDateIso, nowEpochMillis) &&
                prepare?.signalContext?.todayOpeningBarPending == false
    }
}

enum class TouchTurnPrepareOverallStatus {
    PASS,
    WARN,
    FAIL
}

/** Frozen pre-flight checks captured when a Touch Turn session starts. */
@Serializable
data class TouchTurnPrepareSnapshot(
    val preparedAtEpochMillis: Long? = null,
    val overallStatus: String,
    val checks: List<TouchTurnPrepareCheck> = emptyList(),
    val bootstrapReusedFromPrepare: Boolean? = null,
    val atr14: Double? = null,
    val volumeSma20: Double? = null,
    val todayOpeningBarPending: Boolean? = null
) {
    fun overall(): TouchTurnPrepareOverallStatus =
        runCatching { TouchTurnPrepareOverallStatus.valueOf(overallStatus) }
            .getOrDefault(TouchTurnPrepareOverallStatus.FAIL)

    companion object {
        fun from(prepare: TouchTurnSessionPrepare): TouchTurnPrepareSnapshot =
            TouchTurnPrepareSnapshot(
                preparedAtEpochMillis = prepare.preparedAtEpochMillis,
                overallStatus = prepare.overallStatus,
                checks = prepare.checks,
                atr14 = prepare.signalContext.atr14,
                volumeSma20 = prepare.signalContext.volumeSma20,
                todayOpeningBarPending = prepare.signalContext.todayOpeningBarPending
            )
    }

    fun withBootstrapReused(reused: Boolean): TouchTurnPrepareSnapshot =
        copy(bootstrapReusedFromPrepare = reused)
}

object TouchTurnPrepareDefaults {
    /** Reuse prepared bootstrap on Start when younger than this (same session day + listing). */
    const val MAX_AGE_MS = 4 * 60 * 60 * 1000L
}

fun StrategyDeployment.withTouchTurnPrepare(prepare: TouchTurnSessionPrepare?): StrategyDeployment =
    copy(touchTurnPrepare = prepare)

fun StrategyDeployment.applyPreparedBootstrap(
    sessionDate: String,
    prepare: TouchTurnSessionPrepare
): StrategyDeployment {
    val ctx = prepare.signalContext
    return beginTouchTurnSession(sessionDate).withFirstFifteenMinuteCandle(
        sessionDate = sessionDate,
        candle = ctx.firstCandle,
        atr14 = ctx.atr14,
        volumeSma20 = ctx.volumeSma20,
        adr14 = ctx.atr14,
        currencyCode = prepare.currencyCode,
        marketZoneId = prepare.marketZoneId
    )
}

fun StrategyDeployment.clearTouchTurnPrepareIfInstrumentChanged(
    previousInstrumentKey: String?
): StrategyDeployment {
    val current = DeploymentMarket.effectiveInstrument(this).dedupeKey()
    if (previousInstrumentKey != null && previousInstrumentKey != current) {
        return copy(touchTurnPrepare = null)
    }
    return this
}
