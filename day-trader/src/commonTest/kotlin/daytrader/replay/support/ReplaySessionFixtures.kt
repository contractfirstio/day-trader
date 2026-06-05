package daytrader.replay.support

import daytrader.data.persistence.TouchTurnRunPersistence
import daytrader.data.persistence.TouchTurnRunRecordRecord
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRunContext
import daytrader.domain.TouchTurnRunMarketInputs
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnSessionDecision
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnSignalContext
import daytrader.domain.TouchTurnStopEvent
import daytrader.gateway.BrokerId
import daytrader.replay.SessionBundleContents
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

object ReplaySessionFixtures {
    private val json = Json { encodeDefaults = false }

    private const val DEPLOYMENT_ID = "dep-replay-1"
    private const val SESSION_ID = "sess-replay-1"
    private const val SYMBOL = "AAPL"
    private const val SESSION_DATE = "2026-06-04"
    /** 2026-06-04 09:30:00 America/New_York — aligned with IB bar open time. */
    private const val STARTED_EPOCH_MS = 1_780_579_800_000L
    /** 2026-06-04 09:45:00 America/New_York — first 15m RTH bar close. */
    private const val BAR_END_EPOCH_MS = 1_780_580_700_000L
    private const val STOPPED_EPOCH_MS = 1_780_581_600_000L

    fun minimalContents(): SessionBundleContents = SessionBundleContents(
        manifestJson = manifestJson(),
        applicationJsonl = listOf(
            sessionStartedLine(),
            sessionClosedLine()
        ).joinToString("\n"),
        historicalJsonl = listOf(
            historicalBootstrapLine(),
            historicalRefetchLine(attempt = 1, validation = "NOT_YET_FINAL"),
            historicalRefetchLine(attempt = 2, validation = "READY", candle = notLiquidityClosedBar())
        ).joinToString("\n"),
        pricesJsonl = listOf(
            priceLine(epochMs = STARTED_EPOCH_MS + 60_000, bid = 100.14, ask = 100.16, last = 100.15),
            priceLine(epochMs = STARTED_EPOCH_MS + 120_000, bid = 100.14, ask = 100.16, last = 100.15)
        ).joinToString("\n")
    )

    fun minimalRunRecord(): TouchTurnRunRecord = TouchTurnRunRecord(
        runContext = TouchTurnRunContext(
            maxDollars = 500,
            startedBy = TouchTurnSessionStartedBy.MANUAL,
            brokerId = BrokerId.EMULATOR
        ),
        marketInputs = TouchTurnRunMarketInputs(
            openingBar = notLiquidityClosedBar(),
            adr14 = 2.45,
            atr14 = 2.45,
            volumeSma20 = 980_000.0
        ),
        decision = TouchTurnSessionDecision(
            outcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        ),
        stopEvent = TouchTurnStopEvent(
            stopTrigger = TouchTurnSessionStopTrigger.NO_TRADE_DECISION
        ),
        milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-06-04T09:30:00",
            dataReadyAt = "2026-06-04T09:31:00",
            barClosedAt = "2026-06-04T09:45:05",
            liquidityEvaluatedAt = "2026-06-04T09:45:06"
        )
    )

    /** Range 0.30 < ATR threshold 0.6125 → not a liquidity candle. */
    private fun notLiquidityClosedBar(): OhlcBar = OhlcBar(
        open = 100.0,
        high = 100.30,
        low = 100.0,
        close = 100.15,
        time = "20260604  09:30:00",
        volume = 50_000.0
    )

    private fun manifestJson(): String = """
        {
          "version": 1,
          "brokerKind": "EMULATOR",
          "deploymentId": "$DEPLOYMENT_ID",
          "sessionId": "$SESSION_ID",
          "symbol": "$SYMBOL",
          "sessionDate": "$SESSION_DATE",
          "timeline": {
            "sessionStartedEpochMs": $STARTED_EPOCH_MS,
            "sessionStartedAt": "2026-06-04T09:30:00",
            "sessionStoppedEpochMs": $STOPPED_EPOCH_MS,
            "sessionStoppedAt": "2026-06-04T10:00:00"
          }
        }
    """.trimIndent()

    private fun sessionStartedLine(): String = """
        {"at":"2026-06-04T09:30:00.000","epochMs":$STARTED_EPOCH_MS,"type":"session_started","brokerKind":"EMULATOR","deploymentId":"$DEPLOYMENT_ID","sessionId":"$SESSION_ID","symbol":"$SYMBOL","details":{"sessionDate":"$SESSION_DATE","startedAt":"2026-06-04T09:30:00","startedBy":"MANUAL","strategy":"TOUCH_AND_TURN_SCALPER","maxAtRisk":"500"}}
    """.trimIndent()

    private fun sessionClosedLine(): String {
        val persisted = TouchTurnRunPersistence.toRecord(minimalRunRecord())!!
        val data = buildJsonObject {
            put("rawFills", JsonArray(emptyList()))
            put("dedupedFills", JsonArray(emptyList()))
            put(
                "touchTurnRunRecord",
                json.encodeToJsonElement(TouchTurnRunRecordRecord.serializer(), persisted)
            )
        }
        return """
            {"at":"2026-06-04T10:00:00.000","epochMs":$STOPPED_EPOCH_MS,"type":"session_closed","brokerKind":"EMULATOR","deploymentId":"$DEPLOYMENT_ID","sessionId":"$SESSION_ID","symbol":"$SYMBOL","details":{"stopTrigger":"NO_TRADE_DECISION"},"data":${json.encodeToString(data)}}
        """.trimIndent()
    }

    private fun historicalBootstrapLine(): String {
        val context = TouchTurnSignalContext(
            firstCandle = OhlcBar(
                open = 100.0,
                high = 110.0,
                low = 99.0,
                close = 105.0,
                time = "20260604  09:30:00",
                volume = 800_000.0
            ),
            atr14 = 2.45,
            volumeSma20 = 980_000.0
        )
        return historicalLine(
            epochMs = STARTED_EPOCH_MS + 5_000,
            isClosedBarRefetch = false,
            context = context
        )
    }

    private fun historicalRefetchLine(
        attempt: Int,
        validation: String,
        candle: OhlcBar = notLiquidityClosedBar()
    ): String {
        val context = TouchTurnSignalContext(
            firstCandle = candle,
            atr14 = 2.45,
            volumeSma20 = 980_000.0
        )
        return historicalLine(
            epochMs = BAR_END_EPOCH_MS + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + attempt * 2_000L,
            isClosedBarRefetch = true,
            attempt = attempt,
            validation = validation,
            context = context
        )
    }

    private fun historicalLine(
        epochMs: Long,
        isClosedBarRefetch: Boolean,
        context: TouchTurnSignalContext,
        attempt: Int? = null,
        validation: String? = null
    ): String {
        val attemptJson = attempt?.toString() ?: "null"
        val validationJson = validation?.let { "\"$it\"" } ?: "null"
        return """
            {"at":"2026-06-04T09:30:00.000","epochMs":$epochMs,"kind":"signal_context","symbol":"$SYMBOL","isClosedBarRefetch":$isClosedBarRefetch,"attempt":$attemptJson,"validation":$validationJson,"context":${json.encodeToString(context)}}
        """.trimIndent()
    }

    private fun priceLine(
        epochMs: Long,
        bid: Double,
        ask: Double,
        last: Double,
        tickVolume: Double? = null
    ): String {
        val tickVolumeJson = tickVolume?.toString() ?: "null"
        return """
            {"at":"2026-06-04T09:30:00.000","epochMs":$epochMs,"brokerId":"INTERACTIVE_BROKERS","symbol":"$SYMBOL","bid":$bid,"ask":$ask,"last":$last,"tickVolume":$tickVolumeJson,"quoteEpochMillis":$epochMs,"kind":"quote_snapshot"}
        """.trimIndent()
    }
}
