package daytrader.engine.touchturn

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSignalContext

/**
 * Shared signal brain for liquidity (ATR) and volume-exhaustion gates.
 * Used identically from Full IB, hybrid, and emulator modes.
 */
object VolumeExhaustionSignalEngine {

    data class PrePlacementEvaluation(
        val setup: TouchTurnBracketSetup,
        val volumeExhausted: Boolean,
        val liquidityPassed: Boolean,
        val abortReason: String?
    )

    fun evaluateAtBarClose(session: TouchTurnSessionContext): PrePlacementEvaluation? {
        val candle = session.candle ?: return null
        val setup = session.setup ?: return null
        val volumeSma = session.volumeSma20 ?: 0.0
        val volumeExhausted = TouchTurnLogic.isVolumeExhaustion(candle.volume, volumeSma)
        val blockOutcome = TouchTurnLogic.barSetupBlockOutcome(setup, volumeExhausted)
        val abortReason = when (blockOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION -> "volume_exhaustion"
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY -> "not_liquidity"
            TouchTurnSessionOutcome.NO_TRADE_DOJI -> "doji"
            else -> null
        }
        return PrePlacementEvaluation(
            setup = setup,
            volumeExhausted = volumeExhausted,
            liquidityPassed = setup.isLiquidityCandle,
            abortReason = abortReason
        )
    }

    fun logSignalContext(instanceId: String, symbol: String, context: TouchTurnSignalContext) {
        val candle = context.firstCandle
        VolumeExhaustionLog.signalDetected(
            instanceId = instanceId,
            symbol = symbol,
            detail = "range=${candle.range} atr14=${context.atr14} volume=${candle.volume} " +
                "volumeSma20=${context.volumeSma20}"
        )
    }

    fun logPrePlacement(instanceId: String, symbol: String, evaluation: PrePlacementEvaluation) {
        if (evaluation.abortReason != null) {
            VolumeExhaustionLog.filterAborted(instanceId, symbol, evaluation.abortReason)
        } else {
            VolumeExhaustionLog.filterPassed(
                instanceId,
                symbol,
                "liquidity=true volumeExhausted=false"
            )
        }
    }

    fun outcomeForAbort(abortReason: String?): TouchTurnSessionOutcome? = when (abortReason) {
        "volume_exhaustion" -> TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION
        "not_liquidity" -> TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        "doji" -> TouchTurnSessionOutcome.NO_TRADE_DOJI
        else -> null
    }

    fun bufferVolumeThreshold(volumeSma20: Double): Double =
        TouchTurnLogic.volumeExhaustionThreshold(volumeSma20)

    fun openingBarVolumeExhausted(candle: OhlcBar, volumeSma20: Double): Boolean =
        TouchTurnLogic.isVolumeExhaustion(candle.volume, volumeSma20)
}
