package daytrader.broker.emulator

internal object EmulatorLog {
    fun firstCandleScheduled(symbol: String, barTime: String, secondsUntilClose: Long) {
        println(
            "[Emulator] First 15m candle for $symbol closes in ${secondsUntilClose}s " +
                "(bar open $barTime, 15m bar duration)"
        )
    }
}
