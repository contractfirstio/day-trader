package daytrader.data

import daytrader.domain.Position
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class GatewayPositionRepository(
    gateway: BrokerGateway,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : PositionRepository {

    private val _positions = MutableStateFlow<List<Position>>(emptyList())
    override val positions: StateFlow<List<Position>> = _positions.asStateFlow()

    init {
        gateway.positions
            .onEach { accountPositions ->
                _positions.value = accountPositions.map(::toDomain)
            }
            .launchIn(scope)
    }

    private fun toDomain(account: AccountPosition): Position = Position(
        symbol = account.symbol,
        companyName = account.companyName,
        quantity = account.quantity,
        avgPrice = account.avgPrice,
        marketPrice = account.marketPrice,
        dailyChangePct = account.dailyChangePct,
        totalUnrealizedPnL = account.totalUnrealizedPnL,
        currency = account.currency
    )
}
