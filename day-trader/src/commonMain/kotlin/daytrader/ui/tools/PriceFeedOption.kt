package daytrader.ui.tools

import daytrader.gateway.BrokerKind

/** Observable quote paths exposed in the price feed tester. */
enum class PriceFeedOption(val label: String, val description: String) {
    MARKET_DATA_GATEWAY(
        label = "Market data gateway",
        description = "Quotes on the Touch Turn / IB session gateway (hybrid: IB adapter → UI relay)."
    ),
    EXECUTION_GATEWAY(
        label = "Execution gateway",
        description = "Quotes published on the primary broker gateway (paper emulator or full IB)."
    ),
    QUOTE_BUS(
        label = "Quote bus (raw)",
        description = "Direct IB ticks on the in-process bus before gateway relay (hybrid / replay)."
    );

    fun requiresStreamingSubscription(): Boolean =
        this == MARKET_DATA_GATEWAY || this == QUOTE_BUS
}

object PriceFeedOptionCatalog {
    fun available(
        brokerKind: BrokerKind,
        hasSeparateMarketDataGateway: Boolean,
        hasQuoteBus: Boolean
    ): List<PriceFeedOption> = buildList {
        when (brokerKind) {
            BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA, BrokerKind.REPLAY -> {
                if (hasSeparateMarketDataGateway) add(PriceFeedOption.MARKET_DATA_GATEWAY)
                add(PriceFeedOption.EXECUTION_GATEWAY)
                if (hasQuoteBus) add(PriceFeedOption.QUOTE_BUS)
            }
            BrokerKind.INTERACTIVE_BROKERS -> {
                add(PriceFeedOption.EXECUTION_GATEWAY)
            }
            BrokerKind.EMULATOR -> {
                add(PriceFeedOption.EXECUTION_GATEWAY)
            }
        }
    }

    fun defaultSelection(available: List<PriceFeedOption>): Set<PriceFeedOption> =
        when {
            PriceFeedOption.MARKET_DATA_GATEWAY in available -> setOf(PriceFeedOption.MARKET_DATA_GATEWAY)
            else -> available.take(1).toSet()
        }
}
