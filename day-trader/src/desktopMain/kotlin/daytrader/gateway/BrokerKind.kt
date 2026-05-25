package daytrader.gateway

actual fun brokerEnvValue(): String? = System.getenv("DAY_TRADER_BROKER")
