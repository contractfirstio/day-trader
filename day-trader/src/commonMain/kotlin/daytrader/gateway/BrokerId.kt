package daytrader.gateway

enum class BrokerId {
    INTERACTIVE_BROKERS,
    EMULATOR;

    companion object {
        fun from(kind: BrokerKind): BrokerId = when (kind) {
            BrokerKind.INTERACTIVE_BROKERS -> INTERACTIVE_BROKERS
            BrokerKind.EMULATOR -> EMULATOR
        }
    }
}
