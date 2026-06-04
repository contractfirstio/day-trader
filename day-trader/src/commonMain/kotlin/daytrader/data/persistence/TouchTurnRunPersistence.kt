package daytrader.data.persistence

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnPlannedBracket
import daytrader.domain.TouchTurnRunContext
import daytrader.domain.TouchTurnRunMarketInputs
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnVolumeCheck
import daytrader.domain.TouchTurnVolumeCheckPhase
import daytrader.domain.TouchTurnSessionDecision
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnStopEvent
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind

internal object TouchTurnRunPersistence {
    fun toDomain(record: TouchTurnRunRecordRecord?): TouchTurnRunRecord? {
        record ?: return null
        return TouchTurnRunRecord(
            runContext = TouchTurnRunContext(
                maxDollars = record.runContext.maxDollars,
                startedBy = parseStartedBy(record.runContext.startedBy),
                brokerId = parseBrokerId(record.runContext.brokerId),
                brokerKind = parseBrokerKind(record.runContext.brokerKind)
            ),
            marketInputs = TouchTurnRunMarketInputs(
                openingBar = record.marketInputs.openingBar?.toDomain(),
                adr14 = record.marketInputs.adr14,
                atr14 = record.marketInputs.atr14,
                volumeSma20 = record.marketInputs.volumeSma20,
                volumeCheck = record.marketInputs.volumeCheck?.toDomain(),
                currencyCode = record.marketInputs.currencyCode,
                marketZoneId = record.marketInputs.marketZoneId,
                dataErrorMessage = record.marketInputs.dataErrorMessage
            ),
            decision = TouchTurnSessionDecision(
                outcome = parseOutcome(record.decision.outcome),
                plannedQuantity = record.decision.plannedQuantity,
                plannedBracket = record.decision.plannedBracket?.toDomain(),
                executedLegs = record.decision.executedLegs.mapNotNull(::parseOrderRole)
            ),
            stopEvent = TouchTurnStopEvent(
                stopTrigger = resolveStopTrigger(
                    raw = record.stopEvent.stopTrigger,
                    outcome = parseOutcome(record.decision.outcome),
                    closingSessionAt = record.milestones.closingSessionAt
                ),
                stopErrorMessage = record.stopEvent.stopErrorMessage,
                brokerUnrealizedPnLAtStop = record.stopEvent.brokerUnrealizedPnLAtStop
            ),
            milestones = TouchTurnPersistence.milestonesToDomain(record.milestones)
        )
    }

    fun toRecord(record: TouchTurnRunRecord?): TouchTurnRunRecordRecord? {
        record ?: return null
        return TouchTurnRunRecordRecord(
            runContext = TouchTurnRunContextRecord(
                maxDollars = record.runContext.maxDollars,
                startedBy = record.runContext.startedBy.name.lowercase(),
                brokerId = record.runContext.brokerId.name.lowercase(),
                brokerKind = record.runContext.brokerKind?.name?.lowercase()
            ),
            marketInputs = TouchTurnRunMarketInputsRecord(
                openingBar = record.marketInputs.openingBar?.toRecord(),
                adr14 = record.marketInputs.adr14,
                atr14 = record.marketInputs.atr14,
                volumeSma20 = record.marketInputs.volumeSma20,
                volumeCheck = record.marketInputs.volumeCheck?.toRecord(),
                currencyCode = record.marketInputs.currencyCode,
                marketZoneId = record.marketInputs.marketZoneId,
                dataErrorMessage = record.marketInputs.dataErrorMessage
            ),
            decision = TouchTurnSessionDecisionRecord(
                outcome = record.decision.outcome.name.lowercase(),
                plannedQuantity = record.decision.plannedQuantity,
                plannedBracket = record.decision.plannedBracket?.toRecord(),
                executedLegs = record.decision.executedLegs.map { it.name.lowercase() }
            ),
            stopEvent = TouchTurnStopEventRecord(
                stopTrigger = record.stopEvent.stopTrigger.name.lowercase(),
                stopErrorMessage = record.stopEvent.stopErrorMessage,
                brokerUnrealizedPnLAtStop = null
            ),
            milestones = TouchTurnPersistence.milestonesToRecord(record.milestones)
        )
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

    private fun TouchTurnVolumeCheckRecord.toDomain(): TouchTurnVolumeCheck = TouchTurnVolumeCheck(
        phase = parseVolumeCheckPhase(phase),
        openingBarVolume = openingBarVolume,
        volumeSma20 = volumeSma20,
        exhaustionThreshold = exhaustionThreshold,
        volumeExhausted = volumeExhausted,
        volumeRatio = volumeRatio,
        exhaustionRatio = exhaustionRatio,
        barTime = barTime
    )

    private fun TouchTurnVolumeCheck.toRecord(): TouchTurnVolumeCheckRecord = TouchTurnVolumeCheckRecord(
        phase = phase.name.lowercase(),
        openingBarVolume = openingBarVolume,
        volumeSma20 = volumeSma20,
        exhaustionThreshold = exhaustionThreshold,
        volumeExhausted = volumeExhausted,
        volumeRatio = volumeRatio,
        exhaustionRatio = exhaustionRatio,
        barTime = barTime
    )

    private fun parseVolumeCheckPhase(value: String): TouchTurnVolumeCheckPhase =
        runCatching { TouchTurnVolumeCheckPhase.valueOf(value.uppercase()) }
            .getOrDefault(TouchTurnVolumeCheckPhase.LIQUIDITY_EVALUATED)

    private fun TouchTurnPlannedBracketRecord.toDomain(): TouchTurnPlannedBracket =
        TouchTurnPlannedBracket(
            side = parseTradeSide(side),
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        )

    private fun TouchTurnPlannedBracket.toRecord(): TouchTurnPlannedBracketRecord =
        TouchTurnPlannedBracketRecord(
            side = side.name,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit
        )

    private fun parseStartedBy(value: String): TouchTurnSessionStartedBy =
        runCatching { TouchTurnSessionStartedBy.valueOf(value.uppercase()) }
            .getOrDefault(TouchTurnSessionStartedBy.MANUAL)

    private fun parseOutcome(value: String): TouchTurnSessionOutcome =
        runCatching { TouchTurnSessionOutcome.valueOf(value.uppercase()) }
            .getOrDefault(TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED)

    private fun resolveStopTrigger(
        raw: String,
        outcome: TouchTurnSessionOutcome,
        closingSessionAt: String?
    ): TouchTurnSessionStopTrigger {
        parseStopTriggerString(raw)?.let { return it }
        if (closingSessionAt != null &&
            outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
        ) {
            return TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN
        }
        return TouchTurnSessionStopTrigger.MANUAL
    }

    private fun parseStopTriggerString(value: String): TouchTurnSessionStopTrigger? {
        val normalized = value.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .uppercase()
        if (normalized.isBlank() || normalized == "UNKNOWN") return null
        return runCatching { TouchTurnSessionStopTrigger.valueOf(normalized) }.getOrNull()
    }

    private fun parseBrokerId(value: String): BrokerId =
        runCatching { BrokerId.valueOf(value.uppercase()) }
            .getOrDefault(BrokerId.EMULATOR)

    private fun parseBrokerKind(value: String?): BrokerKind? {
        value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { BrokerKind.valueOf(value.uppercase()) }.getOrNull()
    }

    private fun parseTradeSide(value: String): TouchTurnTradeSide =
        runCatching { TouchTurnTradeSide.valueOf(value.uppercase()) }
            .getOrDefault(TouchTurnTradeSide.LONG)

    private fun parseOrderRole(value: String): TouchTurnOrderRole? =
        runCatching { TouchTurnOrderRole.valueOf(value.uppercase()) }.getOrNull()
}
