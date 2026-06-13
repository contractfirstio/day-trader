package daytrader.replay

import daytrader.data.persistence.SessionTradeRecord
import daytrader.data.persistence.TouchTurnRunPersistence
import daytrader.data.persistence.TouchTurnRunRecordRecord
import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.replay.support.ReplaySessionFixtures
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionReplayCaptureSummaryTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun toReplayCaptureSummary_noTradeSession_isFlat() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val summary = bundle.toReplayCaptureSummary()
        assertNotNull(summary)
        assertEquals(0.0, summary.pnl)
        assertFalse(summary.positionOpened)
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY, summary.outcome)
    }

    @Test
    fun toReplayCaptureSummary_withoutSessionClosed_returnsNull() {
        val contents = ReplaySessionFixtures.minimalContents().copy(
            applicationJsonl = ReplaySessionFixtures.minimalContents().applicationJsonl
                .lineSequence()
                .filter { !it.contains("\"session_closed\"") }
                .joinToString("\n")
        )
        val bundle = SessionBundleLoader.load(contents).getOrThrow()
        assertNull(bundle.toReplayCaptureSummary())
    }

    @Test
    fun toReplayCaptureSummary_winningTrade_usesDedupedFillPnl() {
        val fills = listOf(
            SessionTrade(
                execId = "entry-1",
                orderId = 1,
                permId = 100L,
                parentOrderId = 0,
                side = "BOT",
                quantity = 100,
                price = 100.0,
                time = "2026-06-04T09:46:00",
                realizedPnL = 0.0
            ),
            SessionTrade(
                execId = "exit-1",
                orderId = 2,
                permId = 101L,
                parentOrderId = 1,
                side = "SLD",
                quantity = 100,
                price = 100.425,
                time = "2026-06-04T10:00:00",
                realizedPnL = 42.50
            )
        )
        val runRecord = ReplaySessionFixtures.minimalRunRecord().copy(
            decision = ReplaySessionFixtures.minimalRunRecord().decision.copy(
                outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                executedLegs = listOf(TouchTurnOrderRole.ENTRY, TouchTurnOrderRole.TAKE_PROFIT)
            )
        )
        val applicationJsonl = buildString {
            append(ReplaySessionFixtures.minimalContents().applicationJsonl.lineSequence().first())
            append('\n')
            append(sessionClosedLine(runRecord, fills))
        }
        val bundle = SessionBundleLoader.load(
            ReplaySessionFixtures.minimalContents().copy(applicationJsonl = applicationJsonl)
        ).getOrThrow()
        val summary = bundle.toReplayCaptureSummary()
        assertNotNull(summary)
        assertEquals(42.50, summary.pnl)
        assertEquals(true, summary.positionOpened)
        assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, summary.outcome)
    }

    private fun sessionClosedLine(
        runRecord: daytrader.domain.TouchTurnRunRecord,
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
            {"at":"2026-06-04T10:00:00.000","epochMs":1780581600000,"type":"session_closed","brokerKind":"EMULATOR","deploymentId":"dep-replay-1","sessionId":"sess-replay-1","symbol":"AAPL","details":{"stopTrigger":"TRADE_OUTCOME_KNOWN"},"data":${json.encodeToString(data)}}
        """.trimIndent()
    }
}
