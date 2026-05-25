import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import daytrader.data.GatewayPositionRepository
import daytrader.gateway.BrokerRuntime
import daytrader.ui.App

fun main() = application {
    val brokerRuntime = BrokerRuntime.create()
    val positionRepository = GatewayPositionRepository(brokerRuntime.gateway)
    brokerRuntime.start()

    Window(
        onCloseRequest = {
            brokerRuntime.shutdown()
            exitApplication()
        },
        title = "Day Trader",
        state = rememberWindowState(size = DpSize(2234.dp, 1357.dp))
    ) {
        App(brokerGateway = brokerRuntime.gateway, positionRepository = positionRepository)
    }
}
