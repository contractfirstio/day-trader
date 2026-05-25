package daytrader.broker.emulator

internal object EmulatorLog {
    fun firstCandleScheduled(symbol: String, barTime: String, secondsUntilClose: Long) {
        println(
            "[Emulator] First 15m candle for $symbol closes in ${secondsUntilClose}s " +
                "(bar open $barTime, 15m bar duration)"
        )
    }

    fun bracketPlaced(symbol: String, orderIds: List<Int>, entryPrice: Double) {
        println(
            "[Emulator] Touch Turn bracket placed for $symbol (orders $orderIds); " +
                "market set to $entryPrice — entry fills when ticks cross limit"
        )
    }

    fun orderFilled(symbol: String, orderId: Int, qty: Int, price: Double, positionQty: Int) {
        println(
            "[Emulator] Order $orderId filled $qty $symbol @ $price — position qty=$positionQty"
        )
    }
}
