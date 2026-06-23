package daytrader.replay.support

import daytrader.data.persistence.TouchTurnRunPersistence
import daytrader.data.persistence.TouchTurnRunRecordRecord
import daytrader.data.persistence.SessionTradeRecord
import daytrader.domain.OhlcBar
import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnLiquidityThresholds
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRunContext
import daytrader.domain.TouchTurnRunMarketInputs
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
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

    const val TRADE_CAPTURE_PATH = "/tmp/batch-replay-trade"
    const val TRADE_DEPLOYMENT_ID = "dep-batch-trade"
    private const val TRADE_SESSION_ID = "sess-batch-trade"
    private const val TRADE_SYMBOL = "AAPL"
    private const val TRADE_STOPPED_EPOCH_MS = 1_780_582_500_000L

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

    /**
     * Red liquidity bar with captured quotes that cross entry then take-profit.
     * Used by [daytrader.e2e.E2EBatchReplayTest] to assert batch replay produces real P&L.
     *
     * @param totalQuoteCount total lines in prices.jsonl including post-bar entry/TP quotes (min 8)
     */
    fun tradeLifecycleContents(totalQuoteCount: Int = DEFAULT_TRADE_QUOTE_COUNT): SessionBundleContents {
        val bar = tradeLiquidityBar()
        val rules = tradeLifecycleRules()
        val setup = TouchTurnLogic.computeBracketSetup(
            bar = bar,
            liquidityThresholds = TouchTurnLiquidityThresholds(thresholdDailyAtr = 2.45 * 0.25),
            rules = rules
        )
        val entry = setup.entry
        val takeProfit = setup.takeProfit
        val fills = tradeLifecycleFills(entry = entry, takeProfit = takeProfit)
        val runRecord = tradeLifecycleRunRecord(bar = bar, rules = rules, fills = fills)
        return SessionBundleContents(
            manifestJson = tradeManifestJson(),
            applicationJsonl = buildString {
                append(tradeSessionStartedLine())
                append('\n')
                append(tradeSessionClosedLine(runRecord, fills))
            },
            historicalJsonl = listOf(
                tradeHistoricalBootstrapLine(bar),
                tradeHistoricalRefetchLine(attempt = 1, validation = "NOT_YET_FINAL", bar = bar),
                tradeHistoricalRefetchLine(attempt = 2, validation = "READY", bar = bar)
            ).joinToString("\n"),
            pricesJsonl = tradeLifecycleQuoteLines(
                entry = entry,
                takeProfit = takeProfit,
                totalQuoteCount = totalQuoteCount,
            )
        )
    }

    /** Same as [tradeLifecycleContents] but with hybrid broker kind for disk replay tests. */
    fun hybridTradeLifecycleContents(totalQuoteCount: Int = DEFAULT_TRADE_QUOTE_COUNT): SessionBundleContents =
        asHybridReplayable(tradeLifecycleContents(totalQuoteCount))

    /**
     * Trade-lifecycle capture with a pre-[ordersPlacedAt] trap quote (paper parity pattern).
     */
    fun entryFillParityContents(): SessionBundleContents {
        val bar = tradeLiquidityBar()
        val rules = tradeLifecycleRules()
        val setup = TouchTurnLogic.computeBracketSetup(
            bar = bar,
            liquidityThresholds = TouchTurnLiquidityThresholds(thresholdDailyAtr = 2.45 * 0.25),
            rules = rules
        )
        val entry = setup.entry
        val trapBid = entry + 0.01
        val trapMs = BAR_END_EPOCH_MS + 4_000L
        val fills = tradeLifecycleFills(entry, setup.takeProfit)
        val runRecord = tradeLifecycleRunRecord(bar, rules, fills)
        val quoteLines = tradeLifecycleQuoteLines(entry, setup.takeProfit).lines().toMutableList()
        val trapIdx = quoteLines.indexOfFirst { it.contains("\"epochMs\":$trapMs") }
        if (trapIdx >= 0) {
            quoteLines[trapIdx] = tradePriceLine(trapMs, bid = trapBid, ask = trapBid + 0.02, last = trapBid + 0.01)
        }
        return tradeLifecycleContents().copy(
            manifestJson = tradeManifestJson().replace(
                "\"sessionStoppedAt\": \"2026-06-04T10:05:00\"",
                "\"sessionStoppedAt\": \"2026-06-04T10:05:00\",\n            \"milestones\": { \"ordersPlacedAt\": \"2026-06-04T09:45:07\" }"
            ),
            pricesJsonl = quoteLines.joinToString("\n"),
            applicationJsonl = buildString {
                append(tradeSessionStartedLine())
                append('\n')
                append(tradeSessionClosedLine(runRecord, fills))
            }
        )
    }

    fun entryFillParityRules(): TouchTurnRuleConfig = tradeLifecycleRules()

    private const val ENTRY_FILL_DEPLOYMENT_ID = "dep-entry-fill-parity"
    private const val ENTRY_FILL_SESSION_ID = "sess-entry-fill-parity"
    private const val ENTRY_FILL_SYMBOL = "00148"
    private const val ENTRY_FILL_SESSION_DATE = "2026-06-10"

    fun entryFillParityBar(): OhlcBar = OhlcBar(
        open = 138.0,
        high = 139.2,
        low = 135.2,
        close = 138.5,
        time = "20260610  09:30:00",
        volume = 2_000_000.0
    )

    private fun entryFillParityFills(entry: Double, stopLoss: Double): List<SessionTrade> {
        val quantity = 500
        return listOf(
            SessionTrade(
                execId = "hk-entry",
                orderId = 1,
                permId = 100L,
                parentOrderId = 0,
                side = "SLD",
                quantity = quantity,
                price = entry,
                time = "2026-06-10T09:45:10",
                realizedPnL = 0.0
            ),
            SessionTrade(
                execId = "hk-exit",
                orderId = 2,
                permId = 101L,
                parentOrderId = 1,
                side = "BOT",
                quantity = quantity,
                price = stopLoss,
                time = "2026-06-10T09:46:00",
                realizedPnL = quantity * (entry - stopLoss)
            )
        )
    }

    private fun entryFillParityRunRecord(
        bar: OhlcBar,
        rules: TouchTurnRuleConfig,
        fills: List<SessionTrade>
    ): TouchTurnRunRecord = TouchTurnRunRecord(
        runContext = TouchTurnRunContext(
            maxDollars = 100_000,
            startedBy = TouchTurnSessionStartedBy.MANUAL,
            brokerId = BrokerId.EMULATOR
        ),
        marketInputs = TouchTurnRunMarketInputs(
            openingBar = bar,
            adr14 = 2.45,
            atr14 = 2.45,
            dailyAtr14 = 2.45,
            volumeSma20 = 1_500_000.0,
            marketZoneId = "Asia/Hong_Kong",
            currencyCode = "HKD"
        ),
        decision = TouchTurnSessionDecision(
            outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            executedLegs = listOf(TouchTurnOrderRole.ENTRY, TouchTurnOrderRole.STOP_LOSS)
        ),
        rules = rules,
        stopEvent = TouchTurnStopEvent(stopTrigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN),
        milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-06-10T09:30:00",
            dataReadyAt = "2026-06-10T09:31:00",
            barClosedAt = "2026-06-10T09:45:05",
            liquidityEvaluatedAt = "2026-06-10T09:45:06",
            ordersPlacedAt = "2026-06-10T09:45:07",
            positionOpenedAt = "2026-06-10T09:45:10",
            closingSessionAt = "2026-06-10T09:46:00"
        )
    )

    private fun entryFillManifestJson(openMs: Long, stoppedMs: Long): String = """
        {
          "version": 1,
          "brokerKind": "EMULATOR",
          "deploymentId": "$ENTRY_FILL_DEPLOYMENT_ID",
          "sessionId": "$ENTRY_FILL_SESSION_ID",
          "symbol": "$ENTRY_FILL_SYMBOL",
          "sessionDate": "$ENTRY_FILL_SESSION_DATE",
          "instrument": {
            "symbol": "$ENTRY_FILL_SYMBOL",
            "secType": "STK",
            "exchange": "SEHK",
            "primaryExch": "SEHK",
            "currency": "HKD",
            "minOrderSize": 500,
            "orderSizeIncrement": 500
          },
          "timeline": {
            "sessionStartedEpochMs": $openMs,
            "sessionStartedAt": "2026-06-10T09:30:00",
            "sessionStoppedEpochMs": $stoppedMs,
            "sessionStoppedAt": "2026-06-10T09:47:00",
            "milestones": {
              "ordersPlacedAt": "2026-06-10T09:45:07"
            }
          }
        }
    """.trimIndent()

    private fun entryFillSessionStartedLine(openMs: Long): String = """
        {"at":"2026-06-10T09:30:00.000","epochMs":$openMs,"type":"session_started","brokerKind":"EMULATOR","deploymentId":"$ENTRY_FILL_DEPLOYMENT_ID","sessionId":"$ENTRY_FILL_SESSION_ID","symbol":"$ENTRY_FILL_SYMBOL","details":{"sessionDate":"$ENTRY_FILL_SESSION_DATE","startedAt":"2026-06-10T09:30:00","startedBy":"MANUAL","strategy":"TOUCH_AND_TURN_SCALPER","maxAtRisk":"50000"}}
    """.trimIndent()

    private fun entryFillSessionClosedLine(
        stoppedMs: Long,
        runRecord: TouchTurnRunRecord,
        fills: List<SessionTrade>
    ): String {
        val persisted = TouchTurnRunPersistence.toRecord(runRecord)!!
        val fillElements = fills.map { trade ->
            json.encodeToJsonElement(
                SessionTradeRecord.serializer(),
                SessionTradeRecord(
                    execId = trade.execId,
                    orderId = trade.orderId,
                    permId = trade.permId,
                    parentOrderId = trade.parentOrderId,
                    side = trade.side,
                    quantity = trade.quantity,
                    price = trade.price,
                    time = trade.time,
                    currency = trade.currency,
                    commission = trade.commission,
                    realizedPnL = trade.realizedPnL
                )
            )
        }
        val data = buildJsonObject {
            put("rawFills", JsonArray(fillElements))
            put("dedupedFills", JsonArray(fillElements))
            put(
                "touchTurnRunRecord",
                json.encodeToJsonElement(TouchTurnRunRecordRecord.serializer(), persisted)
            )
        }
        return """
            {"at":"2026-06-10T09:47:00.000","epochMs":$stoppedMs,"type":"session_closed","brokerKind":"EMULATOR","deploymentId":"$ENTRY_FILL_DEPLOYMENT_ID","sessionId":"$ENTRY_FILL_SESSION_ID","symbol":"$ENTRY_FILL_SYMBOL","details":{"stopTrigger":"TRADE_OUTCOME_KNOWN","recordedPnl":"-1250.0"},"data":${json.encodeToString(data)}}
        """.trimIndent()
    }

    private fun entryFillHistoricalBootstrapLine(openMs: Long, bar: OhlcBar): String {
        val context = TouchTurnSignalContext(
            firstCandle = bar,
            atr14 = 2.45,
            dailyAtr14 = 2.45,
            volumeSma20 = 1_500_000.0
        )
        return entryFillHistoricalLine(openMs + 5_000, isClosedBarRefetch = false, context = context)
    }

    private fun entryFillHistoricalRefetchLine(
        barEndMs: Long,
        attempt: Int,
        validation: String,
        bar: OhlcBar
    ): String {
        val context = TouchTurnSignalContext(
            firstCandle = bar,
            atr14 = 2.45,
            dailyAtr14 = 2.45,
            volumeSma20 = 1_500_000.0
        )
        return entryFillHistoricalLine(
            epochMs = barEndMs + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + attempt * 2_000L,
            isClosedBarRefetch = true,
            attempt = attempt,
            validation = validation,
            context = context
        )
    }

    private fun entryFillHistoricalLine(
        epochMs: Long,
        isClosedBarRefetch: Boolean,
        context: TouchTurnSignalContext,
        attempt: Int? = null,
        validation: String? = null
    ): String {
        val attemptJson = attempt?.toString() ?: "null"
        val validationJson = validation?.let { "\"$it\"" } ?: "null"
        return """
            {"at":"2026-06-10T09:30:00.000","epochMs":$epochMs,"kind":"signal_context","symbol":"$ENTRY_FILL_SYMBOL","isClosedBarRefetch":$isClosedBarRefetch,"attempt":$attemptJson,"validation":$validationJson,"context":${json.encodeToString(context)}}
        """.trimIndent()
    }

    private fun entryFillPriceLine(epochMs: Long, bid: Double, ask: Double, last: Double): String = """
        {"at":"2026-06-10T09:30:00.000","epochMs":$epochMs,"brokerId":"INTERACTIVE_BROKERS","symbol":"$ENTRY_FILL_SYMBOL","bid":$bid,"ask":$ask,"last":$last,"tickVolume":null,"quoteEpochMillis":$epochMs,"kind":"quote_snapshot"}
    """.trimIndent()

    /** Rewrites EMULATOR captures to hybrid IB market-data captures accepted by [ReplaySourceValidation]. */
    fun asHybridReplayable(contents: SessionBundleContents): SessionBundleContents {
        val hybridKind = "EMULATOR_LIVE_IB_MARKET_DATA"
        return contents.copy(
            manifestJson = contents.manifestJson?.replace(
                "\"brokerKind\": \"EMULATOR\"",
                "\"brokerKind\": \"$hybridKind\""
            ),
            applicationJsonl = contents.applicationJsonl.replace(
                "\"brokerKind\":\"EMULATOR\"",
                "\"brokerKind\":\"$hybridKind\""
            ),
        )
    }

    private const val DEFAULT_TRADE_QUOTE_COUNT = 22
    private const val POST_BAR_TRADE_QUOTE_COUNT = 7

    fun tradeLifecycleRules(): TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT.copy(
        entryInwardOffsetRatioOfRange = 0.0,
        enables = TouchTurnRuleEnables(
            liquidityRangeDailyAtr = true,
            openDeadline = false,
            adjustableTrailingStop = false
        )
    )

    private fun tradeLiquidityBar(): OhlcBar = OhlcBar(
        open = 101.0,
        high = 101.0,
        low = 100.0,
        close = 100.60,
        time = "20260604  09:30:00",
        volume = 800_000.0
    )

    private fun tradeLifecycleFills(entry: Double, takeProfit: Double): List<SessionTrade> {
        val quantity = 5
        return listOf(
            SessionTrade(
                execId = "batch-entry",
                orderId = 1,
                permId = 100L,
                parentOrderId = 0,
                side = "BOT",
                quantity = quantity,
                price = entry,
                time = "2026-06-04T09:46:00",
                realizedPnL = 0.0
            ),
            SessionTrade(
                execId = "batch-exit",
                orderId = 2,
                permId = 101L,
                parentOrderId = 1,
                side = "SLD",
                quantity = quantity,
                price = takeProfit,
                time = "2026-06-04T09:48:00",
                realizedPnL = quantity * (takeProfit - entry)
            )
        )
    }

    private fun tradeLifecycleRunRecord(
        bar: OhlcBar,
        rules: TouchTurnRuleConfig,
        fills: List<SessionTrade>
    ): TouchTurnRunRecord = TouchTurnRunRecord(
        runContext = TouchTurnRunContext(
            maxDollars = 500,
            startedBy = TouchTurnSessionStartedBy.MANUAL,
            brokerId = BrokerId.EMULATOR
        ),
        marketInputs = TouchTurnRunMarketInputs(
            openingBar = bar,
            adr14 = 2.45,
            atr14 = 2.45,
            dailyAtr14 = 2.45,
            volumeSma20 = 980_000.0
        ),
        decision = TouchTurnSessionDecision(
            outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            executedLegs = listOf(TouchTurnOrderRole.ENTRY, TouchTurnOrderRole.TAKE_PROFIT)
        ),
        rules = rules,
        stopEvent = TouchTurnStopEvent(stopTrigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN),
        milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-06-04T09:30:00",
            dataReadyAt = "2026-06-04T09:31:00",
            barClosedAt = "2026-06-04T09:45:05",
            liquidityEvaluatedAt = "2026-06-04T09:45:06",
            ordersPlacedAt = "2026-06-04T09:45:07",
            positionOpenedAt = "2026-06-04T09:46:00",
            closingSessionAt = "2026-06-04T09:48:00"
        )
    )

    private fun tradeLifecycleQuoteLines(
        entry: Double,
        takeProfit: Double,
        totalQuoteCount: Int = DEFAULT_TRADE_QUOTE_COUNT,
    ): String {
        require(totalQuoteCount >= POST_BAR_TRADE_QUOTE_COUNT + 1) {
            "totalQuoteCount must include post-bar entry/TP quotes"
        }
        val lines = mutableListOf<String>()
        val openingQuoteCount = totalQuoteCount - POST_BAR_TRADE_QUOTE_COUNT
        val openingStart = STARTED_EPOCH_MS + 60_000
        val openingEnd = BAR_END_EPOCH_MS
        if (openingQuoteCount <= 1) {
            lines += tradePriceLine(openingStart, bid = 100.55, ask = 100.57, last = 100.56)
        } else {
            val step = (openingEnd - openingStart) / (openingQuoteCount - 1).coerceAtLeast(1)
            repeat(openingQuoteCount) { index ->
                val epoch = openingStart + step * index
                lines += tradePriceLine(epoch, bid = 100.55, ask = 100.57, last = 100.56)
            }
        }
        val postBarQuotes = listOf(
            BAR_END_EPOCH_MS + 4_000L to Triple(100.40, 100.42, 100.41),
            BAR_END_EPOCH_MS + 8_000L to Triple(100.20, 100.22, 100.21),
            BAR_END_EPOCH_MS + 12_000L to Triple(100.00, entry, entry),
            BAR_END_EPOCH_MS + 16_000L to Triple(100.25, 100.27, 100.26),
            BAR_END_EPOCH_MS + 20_000L to Triple(100.36, 100.38, 100.37),
            BAR_END_EPOCH_MS + 24_000L to Triple(takeProfit, takeProfit + 0.02, takeProfit + 0.01),
            BAR_END_EPOCH_MS + 28_000L to Triple(takeProfit + 0.05, takeProfit + 0.07, takeProfit + 0.06),
        )
        postBarQuotes.forEach { (quoteEpoch, prices) ->
            lines += tradePriceLine(
                quoteEpoch,
                bid = prices.first,
                ask = prices.second,
                last = prices.third
            )
        }
        return lines.joinToString("\n")
    }

    private fun tradeManifestJson(): String = """
        {
          "version": 1,
          "brokerKind": "EMULATOR",
          "deploymentId": "$TRADE_DEPLOYMENT_ID",
          "sessionId": "$TRADE_SESSION_ID",
          "symbol": "$TRADE_SYMBOL",
          "sessionDate": "$SESSION_DATE",
          "timeline": {
            "sessionStartedEpochMs": $STARTED_EPOCH_MS,
            "sessionStartedAt": "2026-06-04T09:30:00",
            "sessionStoppedEpochMs": $TRADE_STOPPED_EPOCH_MS,
            "sessionStoppedAt": "2026-06-04T10:05:00"
          }
        }
    """.trimIndent()

    private fun tradeSessionStartedLine(): String = """
        {"at":"2026-06-04T09:30:00.000","epochMs":$STARTED_EPOCH_MS,"type":"session_started","brokerKind":"EMULATOR","deploymentId":"$TRADE_DEPLOYMENT_ID","sessionId":"$TRADE_SESSION_ID","symbol":"$TRADE_SYMBOL","details":{"sessionDate":"$SESSION_DATE","startedAt":"2026-06-04T09:30:00","startedBy":"MANUAL","strategy":"TOUCH_AND_TURN_SCALPER","maxAtRisk":"500"}}
    """.trimIndent()

    private fun tradeSessionClosedLine(
        runRecord: TouchTurnRunRecord,
        fills: List<SessionTrade>
    ): String {
        val persisted = TouchTurnRunPersistence.toRecord(runRecord)!!
        val fillElements = fills.map { trade ->
            json.encodeToJsonElement(
                SessionTradeRecord.serializer(),
                SessionTradeRecord(
                    execId = trade.execId,
                    orderId = trade.orderId,
                    permId = trade.permId,
                    parentOrderId = trade.parentOrderId,
                    side = trade.side,
                    quantity = trade.quantity,
                    price = trade.price,
                    time = trade.time,
                    currency = trade.currency,
                    commission = trade.commission,
                    realizedPnL = trade.realizedPnL
                )
            )
        }
        val data = buildJsonObject {
            put("rawFills", JsonArray(fillElements))
            put("dedupedFills", JsonArray(fillElements))
            put(
                "touchTurnRunRecord",
                json.encodeToJsonElement(TouchTurnRunRecordRecord.serializer(), persisted)
            )
        }
        return """
            {"at":"2026-06-04T10:05:00.000","epochMs":$TRADE_STOPPED_EPOCH_MS,"type":"session_closed","brokerKind":"EMULATOR","deploymentId":"$TRADE_DEPLOYMENT_ID","sessionId":"$TRADE_SESSION_ID","symbol":"$TRADE_SYMBOL","details":{"stopTrigger":"TRADE_OUTCOME_KNOWN","recordedPnl":"${fills.last().realizedPnL}"},"data":${json.encodeToString(data)}}
        """.trimIndent()
    }

    private fun tradeHistoricalBootstrapLine(bar: OhlcBar): String {
        val context = TouchTurnSignalContext(
            firstCandle = bar.copy(high = 110.0, low = 99.0, close = 105.0, volume = 800_000.0),
            atr14 = 2.45,
            dailyAtr14 = 2.45,
            volumeSma20 = 0.0
        )
        return tradeHistoricalLine(
            epochMs = STARTED_EPOCH_MS + 5_000,
            isClosedBarRefetch = false,
            context = context
        )
    }

    private fun tradeHistoricalRefetchLine(
        attempt: Int,
        validation: String,
        bar: OhlcBar
    ): String {
        val context = TouchTurnSignalContext(
            firstCandle = bar,
            atr14 = 2.45,
            dailyAtr14 = 2.45,
            volumeSma20 = 0.0
        )
        return tradeHistoricalLine(
            epochMs = BAR_END_EPOCH_MS + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + attempt * 2_000L,
            isClosedBarRefetch = true,
            attempt = attempt,
            validation = validation,
            context = context
        )
    }

    private fun tradeHistoricalLine(
        epochMs: Long,
        isClosedBarRefetch: Boolean,
        context: TouchTurnSignalContext,
        attempt: Int? = null,
        validation: String? = null
    ): String {
        val attemptJson = attempt?.toString() ?: "null"
        val validationJson = validation?.let { "\"$it\"" } ?: "null"
        return """
            {"at":"2026-06-04T09:30:00.000","epochMs":$epochMs,"kind":"signal_context","symbol":"$TRADE_SYMBOL","isClosedBarRefetch":$isClosedBarRefetch,"attempt":$attemptJson,"validation":$validationJson,"context":${json.encodeToString(context)}}
        """.trimIndent()
    }

    private fun tradePriceLine(
        epochMs: Long,
        bid: Double,
        ask: Double,
        last: Double
    ): String = """
        {"at":"2026-06-04T09:30:00.000","epochMs":$epochMs,"brokerId":"INTERACTIVE_BROKERS","symbol":"$TRADE_SYMBOL","bid":$bid,"ask":$ask,"last":$last,"tickVolume":null,"quoteEpochMillis":$epochMs,"kind":"quote_snapshot"}
    """.trimIndent()

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
        rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
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
            dailyAtr14 = 2.45,
            volumeSma20 = 0.0
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
            dailyAtr14 = 2.45,
            volumeSma20 = 0.0
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
