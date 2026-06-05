package daytrader.data.persistence

import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables

object TouchTurnRuleConfigPersistence {
    fun toDomain(record: TouchTurnRuleConfigRecord?): TouchTurnRuleConfig =
        record?.let {
            TouchTurnRuleConfig(
                atrLiquidityRatio = it.atrLiquidityRatio,
                volumeExhaustionRatio = it.volumeExhaustionRatio,
                atrLookbackPeriods = it.atrLookbackPeriods,
                volumeSmaPeriods = it.volumeSmaPeriods,
                closePositionShortMax = it.closePositionShortMax,
                closePositionLongMin = it.closePositionLongMin,
                barLiveDivergenceMaxRatioOfRange = it.barLiveDivergenceMaxRatioOfRange,
                entryTouchBufferRatioOfRange = it.entryTouchBufferRatioOfRange,
                minStopDistance = it.minStopDistance,
                takeProfitFibRatioGreen = it.takeProfitFibRatioGreen,
                takeProfitFibRatioRed = it.takeProfitFibRatioRed,
                closeConfirmationAfterCloseMs = it.closeConfirmationAfterCloseMs,
                closedBarRefetchSettleMs = it.closedBarRefetchSettleMs,
                volumeBufferObservationMs = it.volumeBufferObservationMs,
                enables = TouchTurnRuleEnables(
                    liquidityRange = it.enableLiquidityRange,
                    notDoji = it.enableNotDoji,
                    volumeExhaustion = it.enableVolumeExhaustion,
                    barCloseTurn = it.enableBarCloseTurn,
                    entryWindow = it.enableEntryWindow,
                    liveQuoteRequired = it.enableLiveQuoteRequired,
                    liveBarAgreement = it.enableLiveBarAgreement,
                    liveTurnConfirmation = it.enableLiveTurnConfirmation,
                    liveEntryTouchable = it.enableLiveEntryTouchable,
                    postEntryVolumeBuffer = it.enablePostEntryVolumeBuffer
                )
            )
        } ?: TouchTurnRuleConfig.DEFAULT

    fun toRecord(config: TouchTurnRuleConfig): TouchTurnRuleConfigRecord =
        TouchTurnRuleConfigRecord(
            atrLiquidityRatio = config.atrLiquidityRatio,
            volumeExhaustionRatio = config.volumeExhaustionRatio,
            atrLookbackPeriods = config.atrLookbackPeriods,
            volumeSmaPeriods = config.volumeSmaPeriods,
            closePositionShortMax = config.closePositionShortMax,
            closePositionLongMin = config.closePositionLongMin,
            barLiveDivergenceMaxRatioOfRange = config.barLiveDivergenceMaxRatioOfRange,
            entryTouchBufferRatioOfRange = config.entryTouchBufferRatioOfRange,
            minStopDistance = config.minStopDistance,
            takeProfitFibRatioGreen = config.takeProfitFibRatioGreen,
            takeProfitFibRatioRed = config.takeProfitFibRatioRed,
            closeConfirmationAfterCloseMs = config.closeConfirmationAfterCloseMs,
            closedBarRefetchSettleMs = config.closedBarRefetchSettleMs,
            volumeBufferObservationMs = config.volumeBufferObservationMs,
            enableLiquidityRange = config.enables.liquidityRange,
            enableNotDoji = config.enables.notDoji,
            enableVolumeExhaustion = config.enables.volumeExhaustion,
            enableBarCloseTurn = config.enables.barCloseTurn,
            enableEntryWindow = config.enables.entryWindow,
            enableLiveQuoteRequired = config.enables.liveQuoteRequired,
            enableLiveBarAgreement = config.enables.liveBarAgreement,
            enableLiveTurnConfirmation = config.enables.liveTurnConfirmation,
            enableLiveEntryTouchable = config.enables.liveEntryTouchable,
            enablePostEntryVolumeBuffer = config.enables.postEntryVolumeBuffer
        )
}
