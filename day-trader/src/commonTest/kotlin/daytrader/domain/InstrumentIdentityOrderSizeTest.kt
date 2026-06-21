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
}
