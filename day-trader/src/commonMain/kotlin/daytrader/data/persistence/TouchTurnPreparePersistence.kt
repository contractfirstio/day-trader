package daytrader.data.persistence

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnPrepareCheck
import daytrader.domain.TouchTurnSessionPrepare
import daytrader.domain.TouchTurnSignalContext

object TouchTurnPreparePersistence {
    fun toDomain(record: TouchTurnSessionPrepareRecord?): TouchTurnSessionPrepare? {
        if (record == null) return null
        val first = record.firstCandle ?: return null
        return TouchTurnSessionPrepare(
            sessionDateIso = record.sessionDateIso,
            preparedAtEpochMillis = record.preparedAtEpochMillis,
            instrumentKey = record.instrumentKey,
            marketZoneId = record.marketZoneId,
            currencyCode = record.currencyCode,
            signalContext = TouchTurnSignalContext(
                firstCandle = OhlcBar(
                    open = first.open,
                    high = first.high,
                    low = first.low,
                    close = first.close,
                    time = first.time,
                    volume = first.volume
                ),
                atr14 = record.atr14,
                volumeSma20 = record.volumeSma20,
                todayOpeningBarPending = record.todayOpeningBarPending
            ),
            checks = record.checks.map { c ->
                TouchTurnPrepareCheck(
                    id = c.id,
                    status = c.status,
                    label = c.label,
                    detail = c.detail
                )
            },
            overallStatus = record.overallStatus
        )
    }

    fun toRecord(prepare: TouchTurnSessionPrepare?): TouchTurnSessionPrepareRecord? {
        if (prepare == null) return null
        val candle = prepare.signalContext.firstCandle
        return TouchTurnSessionPrepareRecord(
            sessionDateIso = prepare.sessionDateIso,
            preparedAtEpochMillis = prepare.preparedAtEpochMillis,
            instrumentKey = prepare.instrumentKey,
            marketZoneId = prepare.marketZoneId,
            currencyCode = prepare.currencyCode,
            atr14 = prepare.signalContext.atr14,
            volumeSma20 = prepare.signalContext.volumeSma20,
            todayOpeningBarPending = prepare.signalContext.todayOpeningBarPending,
            firstCandle = OhlcBarRecord(
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                time = candle.time,
                volume = candle.volume
            ),
            checks = prepare.checks.map { c ->
                TouchTurnPrepareCheckRecord(
                    id = c.id,
                    status = c.status,
                    label = c.label,
                    detail = c.detail
                )
            },
            overallStatus = prepare.overallStatus
        )
    }
}
