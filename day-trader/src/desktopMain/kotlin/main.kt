import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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
        title = "Day Trader"
    ) {
        App(ibGateway = ibGateway, positionRepository = positionRepository)
    }
}

