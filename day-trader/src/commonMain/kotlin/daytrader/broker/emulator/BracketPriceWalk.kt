package daytrader.broker.emulator

/**
 * Oscillates live price between bracket stop and take-profit after entry fills.
 *
 * [targetExit] is chosen at random (default 50% TP / 50% SL) because strategy math places
 * the stop closer to entry than take-profit, so an unbiased walk from entry would hit stop first
 * far more often than ~50% of the time.
 */
internal class BracketPriceWalk(
    val floor: Double,
    val ceiling: Double,
    val takeProfitPrice: Double,
    val stopLossPrice: Double,
    /** +1 = price rising toward TP, -1 = price falling toward TP. */
    val towardTakeProfitDirection: Int,
    val targetExit: BracketExitTarget,
    /** Long exits fill on bid; short exits fill on ask. */
    val isLongPosition: Boolean,
    var direction: Int
)

internal enum class BracketExitTarget {
    TAKE_PROFIT,
    STOP_LOSS
}

/**
 * Probability a symmetric random walk on [stop, takeProfit] starting at [entry] hits take-profit first.
 * (Used for documentation/tests; emulator steers toward [BracketPriceWalk.targetExit] instead.)
 */
internal fun naturalTakeProfitFirstProbability(
    entry: Double,
    stopLoss: Double,
    takeProfit: Double
): Double {
    val span = kotlin.math.abs(takeProfit - stopLoss)
    if (span <= 1e-9) return 0.5
    return if (takeProfit > stopLoss) {
        ((entry - stopLoss) / span).coerceIn(0.0, 1.0)
    } else {
        ((stopLoss - entry) / span).coerceIn(0.0, 1.0)
    }
}
