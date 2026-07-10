package daytrader.data.persistence

import daytrader.data.StrategiesAppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StrategiesAppStatePersistenceTest {
    @Test
    fun roundTrip_autoLiquidityFlushDefaultsAndFlushedZones() {
        val state = StrategiesAppState(
            autoLiquidityFlushEnabled = true,
            flushedLiquidityZoneDates = setOf("America/New_York:2026-06-04"),
        )
        val restored = StrategiesAppStatePersistence.fromDocument(
            StrategiesAppStatePersistence.toDocument(state)
        )
        assertEquals(true, restored.autoLiquidityFlushEnabled)
        assertEquals(setOf("America/New_York:2026-06-04"), restored.flushedLiquidityZoneDates)
    }

    @Test
    fun fromDocument_defaultsAutoLiquidityFlushOff() {
        val restored = StrategiesAppStatePersistence.fromDocument(
            StrategiesScreenDocument(globalAutoStartEnabled = true)
        )
        assertFalse(restored.autoLiquidityFlushEnabled)
        assertEquals(emptySet(), restored.flushedLiquidityZoneDates)
    }
}
