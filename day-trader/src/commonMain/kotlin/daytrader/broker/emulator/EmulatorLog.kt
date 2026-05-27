package daytrader.broker.emulator

internal object EmulatorLog {
    fun firstCandleColor(
        symbol: String,
        isGreen: Boolean,
        fetchIndex: Int,
        colorMode: EmulatorFirstCandleColorMode
    ) {
        val side = if (isGreen) "SHORT (green bar)" else "LONG (red bar)"
        val mode = when (colorMode) {
            EmulatorFirstCandleColorMode.AUTO ->
                if (fetchIndex > 0) "auto-alternate#$fetchIndex" else "auto"
            EmulatorFirstCandleColorMode.GREEN -> "forced-green"
            EmulatorFirstCandleColorMode.RED -> "forced-red"
        }
        println("[Emulator] First 15m candle for $symbol: $side [$mode]")
    }

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
        initialMark: Double,
        walkFloor: Double,
        walkCeiling: Double,
        entryScenario: TouchTurnEntryScenario
    ) {
        val entryNote = when (entryScenario) {
            TouchTurnEntryScenario.IMMEDIATE -> "entry fills immediately"
            TouchTurnEntryScenario.APPROACH_AND_FILL ->
                "live price at bar close $initialMark, walking to entry $entryPrice"
            TouchTurnEntryScenario.NEVER_FILL ->
                "live price at bar close $initialMark, drifting away from entry $entryPrice"
        }
        println(
            "[Emulator] Touch Turn bracket placed for $symbol (orders $orderIds); " +
                "$entryNote; TP/SL activate after entry fills; then price walks $walkFloor..$walkCeiling"
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

    fun sessionOrdersCancelled(symbol: String, count: Int) {
        println("[Emulator] Session stop — cancelled $count open order(s) for $symbol")
    }

    fun sessionPositionClosed(symbol: String, action: String, quantity: Int, price: Double) {
        println(
            "[Emulator] Session stop — market $action $quantity $symbol @ $price to flatten position"
        )
    }
}
