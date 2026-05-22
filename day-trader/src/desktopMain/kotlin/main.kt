import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import daytrader.broker.DesktopIbGatewayConnection
import daytrader.data.IbPositionRepository
import daytrader.ui.App

fun main() = application {
    val ibGateway = DesktopIbGatewayConnection()
    val positionRepository = IbPositionRepository(ibGateway)
    ibGateway.connect()

    Window(
        onCloseRequest = {
            ibGateway.shutdown()
            exitApplication()
        },
        title = "Day Trader",
        state = rememberWindowState(size = DpSize(2234.dp, 1357.dp))
    ) {
        App(ibGateway = ibGateway, positionRepository = positionRepository)
    }
}

