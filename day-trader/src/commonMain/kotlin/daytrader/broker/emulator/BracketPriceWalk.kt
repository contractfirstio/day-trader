package daytrader.broker.emulator

/**
 * Oscillates live price between bracket stop and take-profit after entry fills.
 */
internal class BracketPriceWalk(
    val floor: Double,
    val ceiling: Double,
    val takeProfitPrice: Double,
    val stopLossPrice: Double,
    /** +1 = price rising toward TP, -1 = price falling toward TP. */
    val towardTakeProfitDirection: Int,
    var direction: Int
)
