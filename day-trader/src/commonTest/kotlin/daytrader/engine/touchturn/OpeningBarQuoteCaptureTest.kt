package daytrader.engine.touchturn

import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOpeningBarPriceSample
import daytrader.domain.TouchTurnSessionContext
import kotlin.test.Test
import kotlin.test.assertEquals

class OpeningBarQuoteCaptureTest {

    private val capture = OpeningBarQuoteCapture()
    private val deploymentId = "inst-test"
    private val barTime = "20250522  09:30:00"
    private val zoneId = "America/New_York"
    private val barStart = TouchTurnLogic.barStartEpochMillis(barTime, zoneId)!!
    private val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zoneId)!!

    private val session = TouchTurnSessionContext(
        sessionDate = "2025-05-22",
        status = TouchTurnCandleStatus.READY,
        openingBarTime = barTime,
        marketZoneId = zoneId
    )

    @Test
    fun mergeForBarWindow_prefersSupplementalTimelineOverLiveCapture() {
        val supplemental = listOf(
            sample(barStart + 60_000, 100.2),
            sample(barStart + 120_000, 102.0),
            sample(barStart + 180_000, 100.1),
            sample(barStart + 240_000, 103.0)
        )
        capture.appendAll(deploymentId, listOf(sample(barStart + 90_000, 101.5)))
        val merged = capture.mergeForBarWindow(deploymentId, session, supplemental)
        assertEquals(supplemental, merged)
    }

    @Test
    fun mergeForBarWindow_usesLiveCaptureWhenSupplementalEmpty() {
        val live = listOf(
            sample(barStart + 60_000, 100.2),
            sample(barStart + 120_000, 102.0)
        )
        capture.appendAll(deploymentId, live)
        val merged = capture.mergeForBarWindow(deploymentId, session, supplemental = emptyList())
        assertEquals(live, merged)
    }

    private fun sample(epochMs: Long, price: Double) =
        TouchTurnOpeningBarPriceSample(epochMs = epochMs, price = price)
}
