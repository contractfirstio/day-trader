package daytrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind

@Composable
fun AppTopBar(
    brokerGateway: BrokerGateway,
    brokerKind: BrokerKind,
    marketDataGateway: BrokerGateway? = null,
    selectedMarketZoneId: String?,
    onMarketClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MarketSessionsStatusBar(
            selectedMarketZoneId = selectedMarketZoneId,
            onMarketClick = onMarketClick
        )
        ConnectionStatusBar(
            brokerGateway = brokerGateway,
            brokerKind = brokerKind,
            marketDataGateway = marketDataGateway
        )
    }
}
