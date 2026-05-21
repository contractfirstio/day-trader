import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import daytrader.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Day Trader"
    ) {
        App()
    }
}

