package daytrader.broker

data class IbGatewayConfig(
    val host: String = "127.0.0.1",
    val port: Int = 4001,
    val clientId: Int = 1,
    /** Empty string subscribes to all accounts for portfolio/PnL updates. */
    val accountCode: String = ""
) {
    val endpoint: String get() = "$host:$port"

    companion object {
        fun load(): IbGatewayConfig = IbGatewaySettingsStore.load()

        fun fromEnvironment(): IbGatewayConfig = load()
    }
}
