package daytrader.gateway

import daytrader.engine.support.FakeBrokerGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FillsGatewayResolverTest {
    @Test
    fun hybridMode_usesIbMarketDataGateway() {
        val emulator = FakeBrokerGateway()
        val ib = FakeBrokerGateway()
        val resolved = fillsGatewayFor(
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA,
            brokerGateway = emulator,
            touchTurnSessionGateway = ib
        )
        assertSame(ib, resolved)
    }

    @Test
    fun interactiveBrokers_usesExecutionGateway() {
        val ib = FakeBrokerGateway()
        val resolved = fillsGatewayFor(
            brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            brokerGateway = ib,
            touchTurnSessionGateway = ib
        )
        assertSame(ib, resolved)
    }

    @Test
    fun emulator_usesEmulatorGateway() {
        val emulator = FakeBrokerGateway()
        val resolved = fillsGatewayFor(
            brokerKind = BrokerKind.EMULATOR,
            brokerGateway = emulator,
            touchTurnSessionGateway = emulator
        )
        assertSame(emulator, resolved)
    }
}
