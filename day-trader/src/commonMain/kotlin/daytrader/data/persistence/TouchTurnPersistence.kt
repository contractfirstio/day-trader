package daytrader.data.persistence

import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnNoPositionCancelOutcome
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnTradeSide

internal object TouchTurnPersistence {
    fun toDomain(record: TouchTurnSessionRecord?): TouchTurnSessionContext? {
        record ?: return null
        return TouchTurnSessionContext(
            sessionDate = record.sessionDate,
            status = parseStatus(record.status),
            candle = record.candle?.toDomain(),
            setup = record.setup?.toDomain(record.candle?.toDomain()),
            errorMessage = record.errorMessage,
            currencyCode = record.currencyCode,
            marketZoneId = record.marketZoneId,
            adr14 = record.adr14,
            rangeThreshold = record.rangeThreshold,
            entryOrdersPermitted = record.entryOrdersPermitted,
            noPositionBracketCancelOutcome = parseNoPositionCancelOutcome(record.noPositionBracketCancelOutcome)
        )
    }

    fun toRecord(context: TouchTurnSessionContext?): TouchTurnSessionRecord? {
        context ?: return null
        return TouchTurnSessionRecord(
            sessionDate = context.sessionDate,
            status = statusLabel(context.status),
            candle = context.candle?.toRecord(),
            setup = context.setup?.toRecord(),
            errorMessage = context.errorMessage,
            currencyCode = context.currencyCode,
            marketZoneId = context.marketZoneId,
            adr14 = context.adr14,
            rangeThreshold = context.rangeThreshold,
            entryOrdersPermitted = context.entryOrdersPermitted,
            noPositionBracketCancelOutcome = context.noPositionBracketCancelOutcome?.name?.lowercase()
        )
    }

    private fun parseNoPositionCancelOutcome(value: String?): TouchTurnNoPositionCancelOutcome? {
        value ?: return null
        return runCatching { TouchTurnNoPositionCancelOutcome.valueOf(value.uppercase()) }.getOrNull()
    }

    private fun OhlcBarRecord.toDomain(): OhlcBar = OhlcBar(
        open = open,
        high = high,
        low = low,
        close = close,
        time = time
    )

    private fun OhlcBar.toRecord(): OhlcBarRecord = OhlcBarRecord(
        open = open,
        high = high,
        low = low,
        close = close,
        time = time
    )

    private fun TouchTurnBracketSetupRecord.toDomain(candle: OhlcBar?): TouchTurnBracketSetup {
        if (candle != null && (candleColor == null || side == null)) {
            return TouchTurnLogic.computeBracketSetup(candle, rangeThreshold)
        }
        return TouchTurnBracketSetup(
            range = range,
            rangeThreshold = rangeThreshold,
            isLiquidityCandle = isLiquidityCandle,
            candleColor = parseCandleColor(candleColor),
            side = parseTradeSide(side),
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        )
    }

    private fun TouchTurnBracketSetup.toRecord(): TouchTurnBracketSetupRecord =
        TouchTurnBracketSetupRecord(
            range = range,
            rangeThreshold = rangeThreshold,
            isLiquidityCandle = isLiquidityCandle,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            candleColor = candleColor.name,
            side = side.name
        )

    private fun parseCandleColor(value: String?): FirstCandleColor =
        runCatching { FirstCandleColor.valueOf(value!!.uppercase()) }
            .getOrDefault(FirstCandleColor.DOJI)

    private fun parseTradeSide(value: String?): TouchTurnTradeSide =
        runCatching { TouchTurnTradeSide.valueOf(value!!.uppercase()) }
            .getOrDefault(TouchTurnTradeSide.LONG)

    private fun parseStatus(value: String): TouchTurnCandleStatus =
        runCatching { TouchTurnCandleStatus.valueOf(value.uppercase()) }
            .getOrDefault(TouchTurnCandleStatus.LOADING)

    private fun statusLabel(status: TouchTurnCandleStatus): String = status.name.lowercase()
}
