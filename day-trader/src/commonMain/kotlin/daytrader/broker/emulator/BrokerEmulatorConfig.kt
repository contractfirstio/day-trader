package daytrader.broker.emulator

data class BrokerEmulatorConfig(
    val accountId: String = "EMU001",
    val connectDelayMs: Long = 350L,
    val reconnectDelayMs: Long = 500L,
    val marketTickIntervalMs: Long = 2_000L,
    val marketTickJitterPct: Double = 0.0012,
    val historicalDelayMs: Long = 120L,
    val simulateOrderProgress: Boolean = true,
    val orderProgressIntervalMs: Long = 8_000L
) {
    companion object {
        val Default = BrokerEmulatorConfig()
    }
}
