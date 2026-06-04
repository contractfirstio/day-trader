package daytrader.domain

import kotlinx.serialization.Serializable

/** When a volume gate snapshot was captured in the Touch Turn pipeline. */
@Serializable
enum class TouchTurnVolumeCheckPhase {
    /** Initial IB historical fetch at session bootstrap (opening bar may still be forming). */
    SIGNAL_CONTEXT,
    /** Post-close refetch applied the completed opening bar OHLC + volume. */
    CLOSED_BAR_LOADED,
    /** Liquidity evaluation at bar close (authoritative for no-trade decision). */
    LIQUIDITY_EVALUATED
}

/**
 * Frozen inputs/outputs for the opening-bar volume exhaustion gate
 * ([TouchTurnLogic.isVolumeExhaustion]).
 */
@Serializable
data class TouchTurnVolumeCheck(
    val phase: TouchTurnVolumeCheckPhase,
    val openingBarVolume: Double,
    val volumeSma20: Double,
    val exhaustionThreshold: Double,
    val volumeExhausted: Boolean,
    /** openingBarVolume / volumeSma20 when SMA > 0. */
    val volumeRatio: Double? = null,
    val exhaustionRatio: Double = TouchTurnDefaults.VOLUME_EXHAUSTION_RATIO,
    val barTime: String? = null
) {
    fun toTraceDetails(prefix: String = ""): Map<String, String> {
        val p = if (prefix.isEmpty()) "" else "$prefix."
        return mapOf(
            "${p}phase" to phase.name,
            "${p}openingBarVolume" to openingBarVolume.toString(),
            "${p}volumeSma20" to volumeSma20.toString(),
            "${p}exhaustionThreshold" to exhaustionThreshold.toString(),
            "${p}volumeExhausted" to volumeExhausted.toString(),
            "${p}volumeRatio" to (volumeRatio?.toString() ?: "null"),
            "${p}exhaustionRatio" to exhaustionRatio.toString(),
            "${p}barTime" to (barTime ?: "null")
        )
    }

    companion object {
        fun build(
            phase: TouchTurnVolumeCheckPhase,
            candleVolume: Double,
            volumeSma20: Double?,
            barTime: String? = null
        ): TouchTurnVolumeCheck? {
            val sma = volumeSma20 ?: return null
            if (sma <= 0.0) return null
            val threshold = TouchTurnLogic.volumeExhaustionThreshold(sma)
            return TouchTurnVolumeCheck(
                phase = phase,
                openingBarVolume = candleVolume,
                volumeSma20 = sma,
                exhaustionThreshold = threshold,
                volumeExhausted = TouchTurnLogic.isVolumeExhaustion(candleVolume, sma),
                volumeRatio = candleVolume / sma,
                barTime = barTime
            )
        }

        fun fromSession(
            session: TouchTurnSessionContext?,
            phase: TouchTurnVolumeCheckPhase = TouchTurnVolumeCheckPhase.LIQUIDITY_EVALUATED
        ): TouchTurnVolumeCheck? {
            session ?: return null
            val candle = session.candle ?: return null
            return build(
                phase = phase,
                candleVolume = candle.volume,
                volumeSma20 = session.volumeSma20,
                barTime = candle.time
            )
        }

        fun traceDetailsFromSession(
            session: TouchTurnSessionContext?,
            phase: TouchTurnVolumeCheckPhase = TouchTurnVolumeCheckPhase.LIQUIDITY_EVALUATED
        ): Map<String, String> =
            fromSession(session, phase)?.toTraceDetails() ?: buildMap {
                val sma = session?.volumeSma20
                put("volumeSma20", sma?.toString() ?: "null")
                if (sma != null && sma > 0.0) {
                    put("exhaustionThreshold", TouchTurnLogic.volumeExhaustionThreshold(sma).toString())
                }
                session?.candle?.time?.let { put("barTime", it) }
            }
    }
}
