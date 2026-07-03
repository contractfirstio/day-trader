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
    fun resolveMinTick_defaultsToPennyForUsSymbols() {
        assertEquals(0.01, InstrumentPriceTick.resolveMinTick(instrument = null, symbol = "SPY"))
    }
}
