package daytrader.presentation.ui

import daytrader.presentation.navigation.AppScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiRecoveryBusTest {
    @Test
    fun resetAllUiState_clearsFaultsAndIncrementsGeneration() {
        UiFaultBus.clearAll()
        val initialGeneration = UiRecoveryBus.generation.value
        UiFaultBus.report(AppScreen.STRATEGIES, "test", IllegalStateException("boom"))
        assertTrue(UiFaultBus.faults.value.isNotEmpty())

        UiRecoveryBus.resetAllUiState()

        assertTrue(UiFaultBus.faults.value.isEmpty())
        assertEquals(initialGeneration + 1, UiRecoveryBus.generation.value)
    }

    @Test
    fun safeUiMap_reportsFaultAndReturnsNull() {
        UiFaultBus.clearAll()
        val result = safeUiMap<String>(AppScreen.ORDERS, "safeUiMapTest") {
            error("mapper failed")
        }
        assertEquals(null, result)
        assertEquals(AppScreen.ORDERS, UiFaultBus.faults.value[AppScreen.ORDERS]?.screen)
    }
}
