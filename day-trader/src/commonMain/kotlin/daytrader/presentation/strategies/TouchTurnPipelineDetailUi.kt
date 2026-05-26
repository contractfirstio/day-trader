package daytrader.presentation.strategies

import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import daytrader.presentation.Formatters

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

object TouchTurnPipelineDetailUiMapper {
    fun openingBarDetail(
        session: TouchTurnSessionContext,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): OpeningBarDetailUi? {
        val candle = session.candle ?: return null
        val closeStatus = session.candleCloseStatus(nowEpochMillis)
        val barEnd = candle.time?.let { TouchTurnLogic.barEndEpochMillis(it, session.marketZoneId) }
        val timeUntilClose = when (closeStatus) {
            FirstCandleCloseStatus.FORMING -> barEnd?.let { end ->
                val remaining = (end - nowEpochMillis).coerceAtLeast(0L)
                formatCountdown(remaining)
            }
            else -> null
        }
        val color = session.firstCandleColor() ?: TouchTurnLogic.firstCandleColor(candle)
        return OpeningBarDetailUi(
            barTime = candle.time,
            closeStatus = closeStatus,
            closeStatusLabel = TouchTurnLogic.closeStatusLabel(closeStatus),
            timeUntilCloseLabel = timeUntilClose,
            candleColor = color,
            candleColorLabel = TouchTurnLogic.candleColorLabel(color),
            currency = session.currencyCode,
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            range = candle.range,
            bodyChange = candle.close - candle.open
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
