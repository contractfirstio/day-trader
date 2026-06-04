package daytrader.data.persistence

import daytrader.domain.TouchTurnRuleConfig

object TouchTurnRuleConfigPersistence {
    fun toDomain(record: TouchTurnRuleConfigRecord?): TouchTurnRuleConfig =
        record?.let {
            TouchTurnRuleConfig(
                atrLiquidityRatio = it.atrLiquidityRatio,
                volumeExhaustionRatio = it.volumeExhaustionRatio,
                atrLookbackPeriods = it.atrLookbackPeriods,
                volumeSmaPeriods = it.volumeSmaPeriods,
                closeConfirmationMinDistanceRatioOfRange = it.closeConfirmationMinDistanceRatioOfRange,
                closePositionShortMax = it.closePositionShortMax,
                closePositionLongMin = it.closePositionLongMin,
                barLiveDivergenceMaxRatioOfRange = it.barLiveDivergenceMaxRatioOfRange,
                entryTouchBufferRatioOfRange = it.entryTouchBufferRatioOfRange,
                minStopDistance = it.minStopDistance,
                takeProfitFibRatioGreen = it.takeProfitFibRatioGreen,
                takeProfitFibRatioRed = it.takeProfitFibRatioRed,
                closeConfirmationAfterCloseMs = it.closeConfirmationAfterCloseMs,
                closedBarRefetchSettleMs = it.closedBarRefetchSettleMs,
                volumeBufferObservationMs = it.volumeBufferObservationMs
            )
        } ?: TouchTurnRuleConfig.DEFAULT

    fun toRecord(config: TouchTurnRuleConfig): TouchTurnRuleConfigRecord =
        TouchTurnRuleConfigRecord(
            atrLiquidityRatio = config.atrLiquidityRatio,
            volumeExhaustionRatio = config.volumeExhaustionRatio,
            atrLookbackPeriods = config.atrLookbackPeriods,
            volumeSmaPeriods = config.volumeSmaPeriods,
            closeConfirmationMinDistanceRatioOfRange = config.closeConfirmationMinDistanceRatioOfRange,
            closePositionShortMax = config.closePositionShortMax,
            closePositionLongMin = config.closePositionLongMin,
            barLiveDivergenceMaxRatioOfRange = config.barLiveDivergenceMaxRatioOfRange,
            entryTouchBufferRatioOfRange = config.entryTouchBufferRatioOfRange,
            minStopDistance = config.minStopDistance,
            takeProfitFibRatioGreen = config.takeProfitFibRatioGreen,
            takeProfitFibRatioRed = config.takeProfitFibRatioRed,
            closeConfirmationAfterCloseMs = config.closeConfirmationAfterCloseMs,
            closedBarRefetchSettleMs = config.closedBarRefetchSettleMs,
            volumeBufferObservationMs = config.volumeBufferObservationMs
        )
}
