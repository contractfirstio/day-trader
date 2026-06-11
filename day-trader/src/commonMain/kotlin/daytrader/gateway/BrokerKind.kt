package daytrader.gateway

import kotlinx.serialization.Serializable

@Serializable
enum class BrokerKind {
    INTERACTIVE_BROKERS,
    EMULATOR,
    /** Emulator execution with live IB market data (ADR, opening bar, streaming marks). */
    EMULATOR_LIVE_IB_MARKET_DATA,
    /** Offline replay of a captured hybrid/emulator session (virtual time + recorded IB data). */
    REPLAY;

    val displayName: String
        get() = when (this) {
            INTERACTIVE_BROKERS -> "Interactive Brokers"
            EMULATOR -> "Broker Emulator"
            EMULATOR_LIVE_IB_MARKET_DATA -> "Paper Trading (Live IB Data)"
            REPLAY -> "Session Replay"
        }

    val usesEmulatorExecution: Boolean
        get() = this == EMULATOR || this == EMULATOR_LIVE_IB_MARKET_DATA || this == REPLAY

    /**
     * Live IB quotes and Touch Turn entry gates (1m post-close window, live turn/quote checks).
     * True for real IB, paper-with-live-IB-data, and replay of those captures — not offline emulator.
     */
    val usesLiveIbMarketData: Boolean
        get() = this == INTERACTIVE_BROKERS ||
            this == EMULATOR_LIVE_IB_MARKET_DATA ||
            this == REPLAY

    /** Live IB quotes are persisted to session `prices.jsonl` (Hybrid + IB only). */
    val capturesSessionMarketData: Boolean
        get() = this == INTERACTIVE_BROKERS || this == EMULATOR_LIVE_IB_MARKET_DATA

    /** Subdirectory under the app data root — one folder per startup choice. */
    val dataDirectorySegment: String
        get() = when (this) {
            INTERACTIVE_BROKERS -> "interactive-brokers"
            EMULATOR -> "emulator"
            EMULATOR_LIVE_IB_MARKET_DATA -> "paper-live-ib"
            REPLAY -> "replay"
        }

    companion object {
        fun fromEnvironment(raw: String? = brokerEnvValue()): BrokerKind =
            when (raw?.trim()?.lowercase()) {
                "emulator", "sim", "mock", "paper-sim" -> EMULATOR
                "hybrid", "paper-live", "emulator-live-ib", "emulator_live_ib" ->
                    EMULATOR_LIVE_IB_MARKET_DATA
                "replay", "session-replay" -> REPLAY
                "ib", "interactive", "interactive_brokers", "tws" -> INTERACTIVE_BROKERS
                null, "" -> INTERACTIVE_BROKERS
                else -> INTERACTIVE_BROKERS
            }
    }
}

expect fun brokerEnvValue(): String?
