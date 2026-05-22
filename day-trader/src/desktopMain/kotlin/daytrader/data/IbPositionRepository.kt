package daytrader.data

import daytrader.broker.BrokerPosition
import daytrader.broker.IbGatewayConnection
import daytrader.domain.Position
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class IbPositionRepository(
    gateway: IbGatewayConnection,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : PositionRepository {

    private val _positions = MutableStateFlow<List<Position>>(emptyList())
    override val positions: StateFlow<List<Position>> = _positions.asStateFlow()

    init {
        gateway.positions
            .onEach { brokerPositions ->
                _positions.value = brokerPositions.map(::toDomain)
            }
            .launchIn(scope)
    }

    private fun toDomain(broker: BrokerPosition): Position = Position(
        symbol = broker.symbol,
        companyName = broker.companyName,
        quantity = broker.quantity,
        avgPrice = broker.avgPrice,
        marketPrice = broker.marketPrice,
        dailyChangePct = broker.dailyChangePct,
        totalUnrealizedPnL = broker.totalUnrealizedPnL,
        currency = broker.currency
    )
}
