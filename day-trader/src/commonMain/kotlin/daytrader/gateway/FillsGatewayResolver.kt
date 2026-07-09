package daytrader.gateway

/**
 * IB executions for the Trades screen live on the IB gateway.
 * Hybrid paper mode executes on the emulator but account fills come from the IB market-data connection.
 */
fun fillsGatewayFor(
    brokerKind: BrokerKind,
    brokerGateway: BrokerGateway?,
    touchTurnSessionGateway: BrokerGateway?,
): BrokerGateway? = when (brokerKind) {
    BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> touchTurnSessionGateway ?: brokerGateway
    else -> brokerGateway ?: touchTurnSessionGateway
}
