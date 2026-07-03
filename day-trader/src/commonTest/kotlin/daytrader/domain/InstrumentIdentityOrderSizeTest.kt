package daytrader.domain

import daytrader.data.persistence.InstrumentIdentityPersistence
import daytrader.data.persistence.InstrumentIdentityRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentIdentityOrderSizeTest {
    @Test
    fun fromContractSnapshot_carriesOrderSizeFields() {
        val snapshot = InstrumentMarketResolver.ContractSnapshot(
            symbol = "939",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD",
            minOrderSize = 1_000,
            orderSizeIncrement = 1_000
        )

        val identity = InstrumentIdentity.fromContractSnapshot(snapshot, conId = 46_636_696L)

        assertEquals(1_000, identity.minOrderSize)
        assertEquals(1_000, identity.orderSizeIncrement)
        assertEquals(46_636_696L, identity.conId)
    }

    @Test
    fun fromContractSnapshot_carriesMinPriceTick() {
        val snapshot = InstrumentMarketResolver.ContractSnapshot(
            symbol = "SPY",
            exchange = "SMART",
            primaryExch = "ARCA",
            currency = "USD",
            minPriceTick = 0.01
        )

        val identity = InstrumentIdentity.fromContractSnapshot(snapshot)

        assertEquals(0.01, identity.minPriceTick)
    }

    @Test
    fun persistenceRoundTrip_preservesOrderSizeFields() {
        val identity = InstrumentIdentity(
            symbol = "939",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD",
            conId = 46_636_696L,
            minOrderSize = 1_000,
            orderSizeIncrement = 1_000
        )

        val restored = InstrumentIdentityPersistence.toDomain(
            InstrumentIdentityPersistence.toRecord(identity)
        )

        assertEquals(identity, restored)
    }

    @Test
    fun persistenceRestoresNullOrderSizeFieldsAsUnitLot() {
        val record = InstrumentIdentityRecord(
            symbol = "AAPL",
            exchange = "SMART",
            currency = "USD",
            minOrderSize = null,
            orderSizeIncrement = null
        )

        val identity = InstrumentIdentityPersistence.toDomain(record)

        assertEquals(1, identity?.minOrderSize)
        assertEquals(1, identity?.orderSizeIncrement)
    }

    @Test
    fun heuristicIdentity_defaultsToUnitLot() {
        val identity = InstrumentIdentity.heuristic("AAPL")
        assertEquals(1, identity.minOrderSize)
        assertEquals(1, identity.orderSizeIncrement)
    }
}
