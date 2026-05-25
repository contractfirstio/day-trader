package daytrader.gateway

enum class BrokerKind {
    INTERACTIVE_BROKERS,
    EMULATOR;

    val displayName: String
        get() = when (this) {
            INTERACTIVE_BROKERS -> "Interactive Brokers"
            EMULATOR -> "Broker Emulator"
        }

    /** Subdirectory under the app data root — keeps IB and emulator JSON separate on disk. */
    val dataDirectorySegment: String
        get() = when (this) {
            INTERACTIVE_BROKERS -> "interactive-brokers"
            EMULATOR -> "emulator"
        }

    companion object {
        fun fromEnvironment(raw: String? = brokerEnvValue()): BrokerKind =
            when (raw?.trim()?.lowercase()) {
                "emulator", "sim", "mock", "paper-sim" -> EMULATOR
                "ib", "interactive", "interactive_brokers", "tws" -> INTERACTIVE_BROKERS
                null, "" -> INTERACTIVE_BROKERS
                else -> INTERACTIVE_BROKERS
            }
    }
}

expect fun brokerEnvValue(): String?
