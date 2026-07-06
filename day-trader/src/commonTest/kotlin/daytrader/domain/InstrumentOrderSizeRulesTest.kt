package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstrumentOrderSizeRulesTest {
    @Test
    fun default_isUnitLot() {
        assertTrue(InstrumentOrderSizeRules.DEFAULT.isUnitLot())
    }

    @Test
    fun fromIbValues_defaultsMissingToOne() {
        val rules = InstrumentOrderSizeRules.fromIbValues(minOrderSize = null, orderSizeIncrement = null)
        assertEquals(1, rules.minOrderSize)
        assertEquals(1, rules.orderSizeIncrement)
    }

    @Test
    fun fromIbValues_unitLotIgnoresIbSizeIncrement() {
        val rules = InstrumentOrderSizeRules.fromIbValues(minOrderSize = 1, orderSizeIncrement = 100)
        assertTrue(rules.isUnitLot())
        assertEquals(SnapOrderSizeResult.Ok(38), rules.snapQuantityDown(38))
    }

    @Test
    fun fromIbValues_hkBoardLot_unchanged() {
        val rules = InstrumentOrderSizeRules.fromIbValues(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        assertEquals(SnapOrderSizeResult.Ok(1_000), rules.snapQuantityDown(1_500))
        assertEquals(SnapOrderSizeResult.BelowMinimum(1_000), rules.snapQuantityDown(500))
    }

    @Test
    fun fromIbValues_usesIncrementOrFallsBackToMin() {
        assertEquals(
            InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000),
            InstrumentOrderSizeRules.fromIbValues(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        )
        assertEquals(
            InstrumentOrderSizeRules(minOrderSize = 100, orderSizeIncrement = 50),
            InstrumentOrderSizeRules.fromIbValues(minOrderSize = 100, orderSizeIncrement = 50)
        )
        assertEquals(
            InstrumentOrderSizeRules(minOrderSize = 500, orderSizeIncrement = 500),
            InstrumentOrderSizeRules.fromIbValues(minOrderSize = 500, orderSizeIncrement = null)
        )
    }

    @Test
    fun snapQuantityDown_unitLot_keepsRawQuantity() {
        val rules = InstrumentOrderSizeRules.DEFAULT
        assertEquals(SnapOrderSizeResult.Ok(10), rules.snapQuantityDown(10))
        assertEquals(SnapOrderSizeResult.Ok(1), rules.snapQuantityDown(1))
    }

    @Test
    fun snapQuantityDown_boardLot_floorsToValidMultiple() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        assertEquals(SnapOrderSizeResult.Ok(1_000), rules.snapQuantityDown(1_500))
        assertEquals(SnapOrderSizeResult.Ok(2_000), rules.snapQuantityDown(2_500))
        assertEquals(SnapOrderSizeResult.BelowMinimum(1_000), rules.snapQuantityDown(500))
    }

    @Test
    fun snapQuantityDown_mixedMinAndIncrement() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 100, orderSizeIncrement = 50)
        assertEquals(SnapOrderSizeResult.Ok(100), rules.snapQuantityDown(120))
        assertEquals(SnapOrderSizeResult.Ok(150), rules.snapQuantityDown(175))
        assertEquals(SnapOrderSizeResult.BelowMinimum(100), rules.snapQuantityDown(80))
    }

    @Test
    fun validateQuantity_acceptsValidLots() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        assertNull(rules.validateQuantity(1_000))
        assertNull(rules.validateQuantity(2_000))
    }

    @Test
    fun validateQuantity_rejectsInvalidLots() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        assertTrue(rules.validateQuantity(500)!!.contains("at least"))
        assertTrue(rules.validateQuantity(1_500)!!.contains("steps"))
    }

    @Test
    fun instrumentIdentity_orderSizeRules_reflectsFields() {
        val identity = InstrumentIdentity(
            symbol = "939",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD",
            minOrderSize = 1_000,
            orderSizeIncrement = 1_000
        )
        assertEquals(
            InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000),
            identity.orderSizeRules()
        )
    }
}
