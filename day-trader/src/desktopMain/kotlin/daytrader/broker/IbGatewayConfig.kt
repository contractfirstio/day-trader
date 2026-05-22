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
        fun fromEnvironment(): IbGatewayConfig = IbGatewayConfig(
            host = System.getenv("DAY_TRADER_IB_HOST") ?: "127.0.0.1",
            port = System.getenv("DAY_TRADER_IB_PORT")?.toIntOrNull() ?: 4001,
            clientId = System.getenv("DAY_TRADER_IB_CLIENT_ID")?.toIntOrNull() ?: 1,
            accountCode = System.getenv("DAY_TRADER_IB_ACCOUNT").orEmpty()
        )
    }
}
