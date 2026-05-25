package daytrader.broker.emulator

internal object EmulatorLog {
    fun firstCandleScheduled(symbol: String, barTime: String, secondsUntilClose: Long) {
        println(
            "[Emulator] First 15m candle for $symbol closes in ${secondsUntilClose}s " +
                "(bar open $barTime, 15m bar duration)"
        )
    }

    fun bracketPlaced(
        symbol: String,
        orderIds: List<Int>,
        entryPrice: Double,
        walkFloor: Double,
        walkCeiling: Double
    ) {
        println(
            "[Emulator] Touch Turn bracket placed for $symbol (orders $orderIds); " +
                "market set to $entryPrice — after entry, price walks $walkFloor..$walkCeiling"
        )
    }

    fun bracketExitWalkStarted(symbol: String, floor: Double, ceiling: Double) {
        println(
            "[Emulator] $symbol position open — price walking $floor..$ceiling to trigger TP or STOP"
        )
    }

    fun orderFilled(symbol: String, orderId: Int, qty: Int, price: Double, positionQty: Int) {
        println(
            "[Emulator] Order $orderId filled $qty $symbol @ $price — position qty=$positionQty"
        )
    }
}
