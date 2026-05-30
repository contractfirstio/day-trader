package daytrader.gateway

import kotlinx.serialization.Serializable

@Serializable
enum class BrokerKind {
    INTERACTIVE_BROKERS,
    EMULATOR,
    /** Emulator execution with live IB market data (ADR, opening bar, streaming marks). */
    EMULATOR_LIVE_IB_MARKET_DATA;

    val displayName: String
        get() = when (this) {
            INTERACTIVE_BROKERS -> "Interactive Brokers"
            EMULATOR -> "Broker Emulator"
            EMULATOR_LIVE_IB_MARKET_DATA -> "Paper Trading (Live IB Data)"
        }

    val usesEmulatorExecution: Boolean
        get() = this == EMULATOR || this == EMULATOR_LIVE_IB_MARKET_DATA

    val usesLiveIbMarketData: Boolean
        get() = this == EMULATOR_LIVE_IB_MARKET_DATA

    /** Subdirectory under the app data root — one folder per startup choice. */
    val dataDirectorySegment: String
        get() = when (this) {
            INTERACTIVE_BROKERS -> "interactive-brokers"
            EMULATOR -> "emulator"
            EMULATOR_LIVE_IB_MARKET_DATA -> "paper-live-ib"
        }

    companion object {
        fun fromEnvironment(raw: String? = brokerEnvValue()): BrokerKind =
            when (raw?.trim()?.lowercase()) {
                "emulator", "sim", "mock", "paper-sim" -> EMULATOR
                "hybrid", "paper-live", "emulator-live-ib", "emulator_live_ib" ->
                    EMULATOR_LIVE_IB_MARKET_DATA
                "ib", "interactive", "interactive_brokers", "tws" -> INTERACTIVE_BROKERS
                null, "" -> INTERACTIVE_BROKERS
                else -> INTERACTIVE_BROKERS
            }
    }
}

expect fun brokerEnvValue(): String?
