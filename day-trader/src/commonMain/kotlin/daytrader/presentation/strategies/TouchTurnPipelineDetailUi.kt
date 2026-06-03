package daytrader.presentation.strategies

import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.presentation.Formatters

/** Values loaded from IB at the Data pipeline step (ADR + first 15m RTH bar). */
data class SessionDataCaptureUi(
    val status: TouchTurnCandleStatus,
    val errorMessage: String?,
    val marketZoneAbbrev: String,
    val currency: String,
    val dataReadyAt: String?,
    val adr14: Double?,
    val rangeThreshold: Double,
    val adrRatioPercent: Int,
    val candle: OhlcBar?
) {
    val hasAdr: Boolean get() = adr14 != null && adr14 > 0.0
    val hasOpeningBar: Boolean get() = candle != null
    val isReady: Boolean get() = status == TouchTurnCandleStatus.READY && (hasAdr || hasOpeningBar)
}

data class OpeningBarDetailUi(
    val barTime: String?,
    val closeStatus: FirstCandleCloseStatus,
    val closeStatusLabel: String,
    val timeUntilCloseLabel: String?,
    val candleColor: FirstCandleColor,
    val candleColorLabel: String,
    val currency: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val range: Double,
    val bodyChange: Double
)

data class LiquidityCalculationUi(
    val evaluation: LiquidityCandleEvaluation,
    val adr14: Double?,
    val adrRatioPercent: Int,
    val rangeThreshold: Double,
    val barHigh: Double,
    val barLow: Double,
    val barRange: Double,
    val passes: Boolean?,
    val currency: String
) {
    val canCompare: Boolean
        get() = passes != null && evaluation != LiquidityCandleEvaluation.UNKNOWN
}

data class CloseConfirmationUi(
    val confirmation: TouchTurnCloseConfirmation,
    val closePositionRatio: Double?,
    val closePrice: Double?,
    val entryPrice: Double?,
    val stopPrice: Double?,
    val remainingMillis: Long?,
    val passes: Boolean?,
    val currency: String
)

object TouchTurnPipelineDetailUiMapper {
    fun sessionDataCapture(session: TouchTurnSessionContext): SessionDataCaptureUi =
        SessionDataCaptureUi(
            status = session.status,
            errorMessage = session.errorMessage,
            marketZoneAbbrev = TouchTurnLogic.marketOpenZoneAbbrev(session.marketZoneId),
            currency = session.currencyCode,
            dataReadyAt = session.milestones.dataReadyAt,
            adr14 = session.adr14,
            rangeThreshold = session.rangeThreshold,
            adrRatioPercent = (TouchTurnDefaults.ADR_LIQUIDITY_RATIO * 100).toInt(),
            candle = session.candle
        )

    fun openingBarDetail(
        session: TouchTurnSessionContext,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): OpeningBarDetailUi? {
        val barTime = session.resolvedOpeningBarTime() ?: return null
        val closeStatus = session.candleCloseStatus(nowEpochMillis)
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, session.marketZoneId)
        val timeUntilClose = when (closeStatus) {
            FirstCandleCloseStatus.FORMING -> barEnd?.let { end ->
                val remaining = (end - nowEpochMillis).coerceAtLeast(0L)
                formatCountdown(remaining)
            }
            else -> null
        }
        val candle = session.candle
        val color = session.firstCandleColor()
            ?: candle?.let { TouchTurnLogic.firstCandleColor(it) }
            ?: FirstCandleColor.DOJI
        return OpeningBarDetailUi(
            barTime = barTime,
            closeStatus = closeStatus,
            closeStatusLabel = TouchTurnLogic.closeStatusLabel(closeStatus),
            timeUntilCloseLabel = timeUntilClose,
            candleColor = color,
            candleColorLabel = TouchTurnLogic.candleColorLabel(color),
            currency = session.currencyCode,
            open = candle?.open ?: 0.0,
            high = candle?.high ?: 0.0,
            low = candle?.low ?: 0.0,
            close = candle?.close ?: 0.0,
            range = candle?.range ?: 0.0,
            bodyChange = candle?.let { it.close - it.open } ?: 0.0
        )
    }

    fun liquidityCalculation(
        session: TouchTurnSessionContext,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): LiquidityCalculationUi? {
        val candle = session.candle ?: return null
        val evaluation = session.liquidityEvaluation(nowEpochMillis)
        val passes = when (evaluation) {
            LiquidityCandleEvaluation.LIQUIDITY -> true
            LiquidityCandleEvaluation.NOT_LIQUIDITY -> false
            else -> null
        }
        return LiquidityCalculationUi(
            evaluation = evaluation,
            adr14 = session.adr14,
            adrRatioPercent = (TouchTurnDefaults.ADR_LIQUIDITY_RATIO * 100).toInt(),
            rangeThreshold = session.rangeThreshold,
            barHigh = candle.high,
            barLow = candle.low,
            barRange = candle.range,
            passes = passes,
            currency = session.currencyCode
        )
    }

    fun closeConfirmation(
        session: TouchTurnSessionContext,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): CloseConfirmationUi? {
        val candle = session.candle ?: return null
        val setup = session.setup
        val confirmation = resolvedCloseConfirmation(session, nowEpochMillis)
        val ratio = TouchTurnLogic.closePositionRatio(candle)
        val remainingMillis = when (confirmation) {
            TouchTurnCloseConfirmation.PASSED -> null
            else -> TouchTurnLogic.closeConfirmationRemainingMillis(
                candle,
                session.marketZoneId,
                nowEpochMillis
            )
        }
        return CloseConfirmationUi(
            confirmation = confirmation,
            closePositionRatio = ratio,
            closePrice = candle.close,
            entryPrice = setup?.entry,
            stopPrice = setup?.stopLoss,
            remainingMillis = remainingMillis,
            passes = when (confirmation) {
                TouchTurnCloseConfirmation.PASSED -> true
                TouchTurnCloseConfirmation.FAILED,
                TouchTurnCloseConfirmation.EXPIRED -> false
                else -> null
            },
            currency = session.currencyCode
        )
    }

    /**
     * Once brackets are submitted, confirmation is frozen as passed — live re-evaluation
     * would show EXPIRED after the 1-minute post-close window even though orders were valid.
     */
    private fun resolvedCloseConfirmation(
        session: TouchTurnSessionContext,
        nowEpochMillis: Long
    ): TouchTurnCloseConfirmation = session.pipelineCloseConfirmation(nowEpochMillis)

    private fun formatCountdown(remainingMs: Long): String {
        val totalSec = remainingMs / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return if (minutes > 0) "${minutes}m ${seconds}s until close" else "${seconds}s until close"
    }
}

fun LiquidityCalculationUi.formattedAdr14(): String? =
    adr14?.let { Formatters.moneyPlain(it, currency) }

fun LiquidityCalculationUi.fmt(amount: Double): String =
    Formatters.moneyPlain(amount, currency)

fun OpeningBarDetailUi.fmt(amount: Double): String =
    Formatters.moneyPlain(amount, currency)

fun SessionDataCaptureUi.fmt(amount: Double): String =
    Formatters.moneyPlain(amount, currency)

fun SessionDataCaptureUi.formattedAdr14(): String? =
    adr14?.let { fmt(it) }
