package daytrader.ui

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
import daytrader.presentation.navigation.AppScreen
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark

@Composable
fun App() {
    val dependencies = rememberAppDependencies()
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
                    AppScreen.POSITIONS -> PositionsScreen(dependencies.positionsViewModel)
                    AppScreen.STRATEGIES -> StrategiesScreen(dependencies.strategiesViewModel)
                }
            }
        }
    }
}
