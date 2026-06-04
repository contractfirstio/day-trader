package daytrader.diagnostics

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnSignalContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SessionHistoricalLogTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun sessionHistoricalLine_serializesSignalContext() {
        val context = TouchTurnSignalContext(
            firstCandle = OhlcBar(
                open = 100.0,
                high = 110.0,
                low = 99.0,
                close = 108.0,
                time = "20260604  09:30:00",
                volume = 1_200_000.0
            ),
            atr14 = 2.45,
            volumeSma20 = 980_000.0
        )
        val line = SessionHistoricalLine(
            at = "2026-06-04T09:30:00.000",
            epochMs = 1_717_500_600_000L,
            kind = "signal_context",
            symbol = "AAPL",
            isClosedBarRefetch = false,
            context = context
        )
        val encoded = json.encodeToString(SessionHistoricalLine.serializer(), line)
        assertContains(encoded, "\"kind\":\"signal_context\"")
        assertContains(encoded, "\"isClosedBarRefetch\":false")
        assertContains(encoded, "\"atr14\":2.45")
        val decoded = json.decodeFromString(SessionHistoricalLine.serializer(), encoded)
        assertEquals(context, decoded.context)
    }
}
