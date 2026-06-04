package daytrader.gateway

/** IB [reqMarketDataType](https://interactivebrokers.github.io/tws-api/market_data_type.html) values for streaming quotes. */
enum class IbStreamingMarketDataType(
    val ibCode: Int,
    val label: String,
    val description: String
) {
    LIVE(
        ibCode = 1,
        label = "Live",
        description = "Real-time bid/ask/last (requires IB market data subscription)."
    ),
    DELAYED(
        ibCode = 3,
        label = "Delayed",
        description = "Delayed quotes (~15–20 minutes behind during RTH)."
    ),
    DELAYED_FROZEN(
        ibCode = 4,
        label = "Delayed frozen",
        description = "Delayed during the session; last quote frozen after the close (app default)."
    );

    companion object {
        fun fromIbCode(code: Int): IbStreamingMarketDataType =
            entries.firstOrNull { it.ibCode == code } ?: DELAYED_FROZEN
    }
}
