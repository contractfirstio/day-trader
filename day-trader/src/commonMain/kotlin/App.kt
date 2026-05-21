import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ==========================================
// Color Palette & Enums
// ==========================================
val DarkBackground = Color(0xFF121318)
val SurfaceDark = Color(0xFF1C1D24)
val TableHeaderBg = Color(0xFF252730)
val BrandRed = Color(0xFFD32F2F)
val GainGreen = Color(0xFF00C853)
val LossRed = Color(0xFFFF3D00)
val TextSecondary = Color(0xFF9AA0A6)

enum class SortableColumn {
    SYMBOL, COMPANY, QUANTITY, AVG_PRICE, LAST_PRICE, MARKET_VALUE, DAILY_CHANGE, UNREALIZED_PNL
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

// ==========================================
// Data Models
// ==========================================
data class PositionItem(
    val symbol: String,
    val companyName: String,
    val quantity: Int,
    val avgPrice: Double,
    val marketPrice: Double,
    val dailyChangePct: Double,
    val totalUnrealizedPnL: Double
) {
    val marketValue: Double get() = quantity * marketPrice
    val formattedAvgPrice: String get() = "$${String.format("%.2f", avgPrice)}"
    val formattedMarketPrice: String get() = "$${String.format("%.2f", marketPrice)}"
    val formattedMarketValue: String get() = "$${String.format("%,.2f", marketValue)}"
    val formattedDailyChange: String get() = "${if (dailyChangePct >= 0) "+" else ""}${String.format("%.2f", dailyChangePct)}%"
    val formattedPnL: String get() = "${if (totalUnrealizedPnL >= 0) "+" else ""}$${String.format("%,.2f", totalUnrealizedPnL)}"
}

// ==========================================
// Main App Shell
// ==========================================
@Composable
fun App() {
    var currentScreen by remember { mutableStateOf(AppScreen.POSITIONS) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = SurfaceDark,
                    header = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = "Logo",
                            tint = BrandRed,
                            modifier = Modifier.padding(vertical = 16.dp).size(32.dp)
                        )
                    }
                ) {
                    NavigationRailItem(
                        selected = currentScreen == AppScreen.POSITIONS,
                        onClick = { currentScreen = AppScreen.POSITIONS },
                        icon = { Icon(Icons.Default.Wallet, "Positions") },
                        label = { Text("Positions") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = GainGreen,
                            selectedTextColor = Color.White,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationRailItem(
                        selected = currentScreen == AppScreen.STRATEGIES,
                        onClick = { currentScreen = AppScreen.STRATEGIES },
                        icon = { Icon(Icons.Default.AutoGraph, "Strategies") },
                        label = { Text("Strategies") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = GainGreen,
                            selectedTextColor = Color.White,
                            indicatorColor = Color.Transparent
                        )
                    )
                }

                when (currentScreen) {
                    AppScreen.POSITIONS -> PositionsScreen()
                    AppScreen.STRATEGIES -> StrategiesScreen()
                }
            }
        }
    }
}
