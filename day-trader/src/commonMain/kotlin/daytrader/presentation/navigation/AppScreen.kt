package daytrader.presentation.navigation

enum class AppScreen {
    POSITIONS,
    ORDERS,
    TRADES,
    STRATEGIES,
    WATCHLIST,
    LIQUIDITY
}

fun AppScreen.displayLabel(): String = when (this) {
    AppScreen.STRATEGIES -> "Strategies"
    AppScreen.WATCHLIST -> "Watchlist"
    AppScreen.LIQUIDITY -> "Liquidity"
    AppScreen.ORDERS -> "Orders"
    AppScreen.POSITIONS -> "Positions"
    AppScreen.TRADES -> "Trades"
}
