package daytrader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.presentation.ui.UiFaultBus
import daytrader.presentation.ui.UiRecoveryBus

@Composable
fun AppTopBar(
    brokerGateway: BrokerGateway,
    brokerKind: BrokerKind,
    marketDataGateway: BrokerGateway? = null,
    selectedMarketZoneId: String?,
    onMarketClick: (String) -> Unit,
    onOpenPriceFeedTester: (() -> Unit)? = null,
    onChangeBrokerMode: (() -> Unit)? = null,
    portfolioExposureLabel: String? = null,
    portfolioExposureOverCap: Boolean = false,
    onKillSwitch: (() -> Unit)? = null,
    killSwitchEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeUiFaults by UiFaultBus.faults.collectAsState()
    Column(modifier = modifier.fillMaxWidth()) {
        MarketSessionsStatusBar(
            selectedMarketZoneId = selectedMarketZoneId,
            onMarketClick = onMarketClick
        )
        ConnectionStatusBar(
            brokerGateway = brokerGateway,
            brokerKind = brokerKind,
            marketDataGateway = marketDataGateway,
            onOpenPriceFeedTester = onOpenPriceFeedTester,
            onChangeBrokerMode = onChangeBrokerMode,
            portfolioExposureLabel = portfolioExposureLabel,
            portfolioExposureOverCap = portfolioExposureOverCap,
            onKillSwitch = onKillSwitch,
            killSwitchEnabled = killSwitchEnabled,
            activeUiFaults = activeUiFaults.values.toList(),
            onResetUi = if (activeUiFaults.isNotEmpty()) UiRecoveryBus::resetAllUiState else null,
        )
    }
}
