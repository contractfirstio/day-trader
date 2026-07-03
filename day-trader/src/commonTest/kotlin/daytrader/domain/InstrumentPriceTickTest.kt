package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentPriceTickTest {
    @Test
    fun roundToMinTick_spyBracketLegs_conformToPennyTick() {
        val minTick = 0.01
        assertEquals(742.37, InstrumentPriceTick.roundToMinTick(742.37, minTick))
        assertEquals(743.44, InstrumentPriceTick.roundToMinTick(743.44342, minTick))
        assertEquals(741.83, InstrumentPriceTick.roundToMinTick(741.83329, minTick))
    }

    @Test
    fun resolveMinTick_usesInstrumentMinPriceTickWhenPresent() {
        val instrument = InstrumentIdentity(
            symbol = "SPY",
            exchange = "SMART",
            currency = "USD",
            minPriceTick = 0.05
        )
        assertEquals(0.05, InstrumentPriceTick.resolveMinTick(instrument, "SPY"))
    }

    @Test
    fun roundForInstrument_hkTieredTick_roundsToBandIncrement() {
        val instrument = InstrumentIdentity(
            symbol = "7709",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD",
            minPriceTick = 0.001,
            marketRuleId = 2431,
            priceIncrements = listOf(
                InstrumentPriceIncrement(lowEdge = 0.0, increment = 0.001),
                InstrumentPriceIncrement(lowEdge = 100.0, increment = 0.05)
            )
        )
        assertEquals(122.95, InstrumentPriceTick.roundForInstrument(122.96, instrument, "07709"))
        assertEquals(123.0, InstrumentPriceTick.roundForInstrument(122.98, instrument, "07709"))
    }

    @Test
    fun incrementAtPrice_picksHighestMatchingLowEdge() {
        val increments = listOf(
            InstrumentPriceIncrement(lowEdge = 0.0, increment = 0.001),
            InstrumentPriceIncrement(lowEdge = 100.0, increment = 0.05)
        )
        assertEquals(0.05, InstrumentPriceTick.incrementAtPrice(122.96, increments))
        assertEquals(0.001, InstrumentPriceTick.incrementAtPrice(50.0, increments))
    }
}
