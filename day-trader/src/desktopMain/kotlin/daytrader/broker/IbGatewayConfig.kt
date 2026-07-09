package daytrader.broker

data class IbGatewayConfig(
    val host: String = "127.0.0.1",
    val port: Int = 4001,
    val clientId: Int = 1,
    /** Empty string subscribes to all accounts for portfolio/PnL updates. */
    val accountCode: String = "",
    /** Flex Web Service token for settled trade history sync. */
    val flexToken: String = "",
    /** Activity Flex Query ID with the Trades section enabled. */
    val flexTradesQueryId: String = "",
) {
    val hasFlexTradeSync: Boolean
        get() = flexToken.isNotBlank() && flexTradesQueryId.isNotBlank()
    val endpoint: String get() = "$host:$port"

    companion object {
        fun load(): IbGatewayConfig = IbGatewaySettingsStore.load()

        fun fromEnvironment(): IbGatewayConfig = load()
    }
}
