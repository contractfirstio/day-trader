package daytrader.data.persistence

import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnNoPositionCancelOutcome
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnTradeSide

internal object TouchTurnPersistence {
    fun toDomain(record: TouchTurnSessionRecord?): TouchTurnSessionContext? {
        record ?: return null
        return TouchTurnSessionContext(
            sessionDate = record.sessionDate,
            status = parseStatus(record.status),
            openingBarTime = record.openingBarTime ?: record.candle?.time,
            candle = record.candle?.toDomain(),
            setup = record.setup?.toDomain(record.candle?.toDomain()),
            errorMessage = record.errorMessage,
            currencyCode = record.currencyCode,
            marketZoneId = record.marketZoneId,
            adr14 = record.adr14,
            rangeThreshold = record.rangeThreshold,
            entryOrdersPermitted = record.entryOrdersPermitted,
            ordersPlacedForSession = record.ordersPlacedForSession,
            noPositionBracketCancelOutcome = parseNoPositionCancelOutcome(record.noPositionBracketCancelOutcome),
            milestones = record.milestones?.toDomain() ?: TouchTurnMilestoneTimestamps(),
            decisionOutcome = parseOutcome(record.decisionOutcome),
            plannedQuantity = record.plannedQuantity,
            plannedBracket = record.plannedBracket?.toDomain(),
            bracketOrderIds = record.toBracketOrderIds()
        )
    }

    fun toRecord(context: TouchTurnSessionContext?): TouchTurnSessionRecord? {
        context ?: return null
        return TouchTurnSessionRecord(
            sessionDate = context.sessionDate,
            status = statusLabel(context.status),
            openingBarTime = context.openingBarTime,
            candle = context.candle?.toRecord(),
            setup = context.setup?.toRecord(),
            errorMessage = context.errorMessage,
            currencyCode = context.currencyCode,
            marketZoneId = context.marketZoneId,
            adr14 = context.adr14,
            rangeThreshold = context.rangeThreshold,
            entryOrdersPermitted = context.entryOrdersPermitted,
            ordersPlacedForSession = context.ordersPlacedForSession,
            noPositionBracketCancelOutcome = context.noPositionBracketCancelOutcome?.name?.lowercase(),
            milestones = context.milestones.toRecord(),
            decisionOutcome = context.decisionOutcome?.name?.lowercase(),
            plannedQuantity = context.plannedQuantity,
            plannedBracket = context.plannedBracket?.toRecord(),
            bracketParentOrderId = context.bracketOrderIds?.parentOrderId,
            bracketTakeProfitOrderId = context.bracketOrderIds?.takeProfitOrderId,
            bracketStopLossOrderId = context.bracketOrderIds?.stopLossOrderId,
            bracketAdjustableStopOrderId = context.bracketOrderIds?.adjustableStopOrderId
        )
    }

    private fun TouchTurnSessionRecord.toBracketOrderIds(): TouchTurnBracketOrderIds? {
        val parent = bracketParentOrderId ?: return null
        val tp = bracketTakeProfitOrderId ?: return null
        val stop = bracketStopLossOrderId ?: return null
        return TouchTurnBracketOrderIds(
            parentOrderId = parent,
            takeProfitOrderId = tp,
            stopLossOrderId = stop,
            adjustableStopOrderId = bracketAdjustableStopOrderId
        )
    }

    fun milestonesToDomain(record: TouchTurnMilestoneTimestampsRecord): TouchTurnMilestoneTimestamps =
        record.toDomain()

    fun milestonesToRecord(milestones: TouchTurnMilestoneTimestamps): TouchTurnMilestoneTimestampsRecord =
        milestones.toRecord()

    private fun TouchTurnMilestoneTimestampsRecord.toDomain(): TouchTurnMilestoneTimestamps =
        TouchTurnMilestoneTimestamps(
            startingSessionAt = startingSessionAt,
            dataReadyAt = dataReadyAt,
            dataFailedAt = dataFailedAt,
            barClosedAt = barClosedAt,
            liquidityEvaluatedAt = liquidityEvaluatedAt,
            closeConfirmedAt = closeConfirmedAt,
            fiveMinConfirmedAt = fiveMinConfirmedAt,
            ordersPlacedAt = ordersPlacedAt,
            positionOpenedAt = positionOpenedAt,
            closingSessionAt = closingSessionAt
        )

    private fun TouchTurnMilestoneTimestamps.toRecord(): TouchTurnMilestoneTimestampsRecord =
        TouchTurnMilestoneTimestampsRecord(
            startingSessionAt = startingSessionAt,
            dataReadyAt = dataReadyAt,
            dataFailedAt = dataFailedAt,
            barClosedAt = barClosedAt,
            liquidityEvaluatedAt = liquidityEvaluatedAt,
            closeConfirmedAt = closeConfirmedAt,
            fiveMinConfirmedAt = fiveMinConfirmedAt,
            ordersPlacedAt = ordersPlacedAt,
            positionOpenedAt = positionOpenedAt,
            closingSessionAt = closingSessionAt
        )

    private fun parseNoPositionCancelOutcome(value: String?): TouchTurnNoPositionCancelOutcome? {
        value ?: return null
        return runCatching { TouchTurnNoPositionCancelOutcome.valueOf(value.uppercase()) }.getOrNull()
    }

    private fun OhlcBarRecord.toDomain(): OhlcBar = OhlcBar(
        open = open,
        high = high,
        low = low,
        close = close,
        time = time,
        volume = volume
    )

    private fun OhlcBar.toRecord(): OhlcBarRecord = OhlcBarRecord(
        open = open,
        high = high,
        low = low,
        close = close,
        time = time,
        volume = volume
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
            takeProfit = takeProfit,
            closePositionRatio = candle?.let(TouchTurnLogic::closePositionRatio),
            bodyRatio = candle?.let(TouchTurnLogic::bodyRatio)
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

    private fun parseOutcome(value: String?): TouchTurnSessionOutcome? {
        value ?: return null
        return runCatching { TouchTurnSessionOutcome.valueOf(value.uppercase()) }.getOrNull()
    }

    private fun TouchTurnPlannedBracketRecord.toDomain(): TouchTurnPlannedBracket =
        TouchTurnPlannedBracket(
            side = parseTradeSide(side),
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            trailTriggerPrice = trailTriggerPrice
        )

    private fun TouchTurnPlannedBracket.toRecord(): TouchTurnPlannedBracketRecord =
        TouchTurnPlannedBracketRecord(
            side = side.name,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            trailTriggerPrice = trailTriggerPrice
        )
}
