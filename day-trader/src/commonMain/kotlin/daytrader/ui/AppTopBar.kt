package daytrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import daytrader.gateway.BrokerGateway

@Composable
fun AppTopBar(
    brokerGateway: BrokerGateway,
    selectedMarketZoneId: String?,
    onMarketClick: (String) -> Unit,
    globalAutoStartEnabled: Boolean,
    onGlobalAutoStartChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MarketSessionsStatusBar(
            selectedMarketZoneId = selectedMarketZoneId,
            onMarketClick = onMarketClick
        )
        ConnectionStatusBar(
            brokerGateway = brokerGateway,
            globalAutoStartEnabled = globalAutoStartEnabled,
            onGlobalAutoStartChange = onGlobalAutoStartChange
        )
    }
}
