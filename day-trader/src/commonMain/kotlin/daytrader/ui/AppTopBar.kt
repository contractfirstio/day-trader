package daytrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import daytrader.broker.IbGatewayConnection

@Composable
fun AppTopBar(
    ibGateway: IbGatewayConnection,
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
            ibGateway = ibGateway,
            globalAutoStartEnabled = globalAutoStartEnabled,
            onGlobalAutoStartChange = onGlobalAutoStartChange
        )
    }
}
