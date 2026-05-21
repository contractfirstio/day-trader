enum class AppScreen {
    POSITIONS,
    STRATEGIES
}

enum class StrategyType(val displayName: String, val description: String) {
    TOUCH_AND_TURN_SCALPER(
        displayName = "Touch and Turn Scalper",
        description = "Scalps reversals when price touches prior session high/low and turns."
    )
}

enum class InstanceStatus {
    RUNNING,
    STOPPED,
    ERROR
}

enum class InstanceFilter {
    ALL,
    RUNNING,
    STOPPED
}

enum class StrategyDetailTab {
    CONFIGURATION,
    ACTIVITY,
    PERFORMANCE
}

data class StrategyInstance(
    val id: String,
    val name: String,
    val strategyType: StrategyType,
    val status: InstanceStatus,
    val symbol: String,
    val timeframe: String,
    val riskDollars: Int,
    val positionSize: Int,
    val stopLossTicks: Int,
    val sessionWindow: String,
    val todayPnL: Double,
    val tradesToday: Int,
    val lastSignal: String,
    val lastOrder: String,
    val openPosition: String,
    val lastUpdate: String
) {
    val paramsSummary: String
        get() = "$symbol · $timeframe · \$${riskDollars} risk"

    val formattedTodayPnL: String
        get() = "${if (todayPnL >= 0) "+" else ""}$${String.format("%,.2f", todayPnL)}"
}

fun newStrategyInstanceId(): String = "inst-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun defaultTouchAndTurnInstance(
    name: String,
    symbol: String,
    timeframe: String,
    riskDollars: Int,
    status: InstanceStatus = InstanceStatus.STOPPED
): StrategyInstance = StrategyInstance(
    id = newStrategyInstanceId(),
    name = name,
    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
    status = status,
    symbol = symbol,
    timeframe = timeframe,
    riskDollars = riskDollars,
    positionSize = 100,
    stopLossTicks = 4,
    sessionWindow = "09:30 – 16:00 ET",
    todayPnL = 0.0,
    tradesToday = 0,
    lastSignal = "—",
    lastOrder = "—",
    openPosition = "Flat",
    lastUpdate = "—"
)

fun mockStrategyInstances(): List<StrategyInstance> = listOf(
    defaultTouchAndTurnInstance(
        name = "T&T — SPY 1m",
        symbol = "SPY",
        timeframe = "1m",
        riskDollars = 500,
        status = InstanceStatus.RUNNING
    ).copy(
        todayPnL = 142.50,
        tradesToday = 7,
        lastSignal = "Long @ 521.40 — touch of prior high",
        lastOrder = "BUY 100 SPY @ 521.42",
        openPosition = "Long 100 SPY",
        lastUpdate = "12:04:03"
    ),
    defaultTouchAndTurnInstance(
        name = "T&T — QQQ 5m",
        symbol = "QQQ",
        timeframe = "5m",
        riskDollars = 350,
        status = InstanceStatus.STOPPED
    ).copy(
        todayPnL = -28.00,
        tradesToday = 3,
        lastSignal = "Flat — no touch level",
        lastOrder = "—",
        openPosition = "Flat",
        lastUpdate = "11:52:18"
    )
)
