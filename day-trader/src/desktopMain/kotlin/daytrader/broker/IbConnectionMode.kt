package daytrader.broker

/** How [DesktopIbGatewayConnection] handles gateway commands and inbound snapshots. */
enum class IbConnectionMode {
    /** Live brokerage: orders, positions, fills, and market data. */
    FULL,

    /**
     * Market data and Touch Turn historical requests only (ADR, first 15m bar).
     * Orders are not sent to IB; live ticks are forwarded via [DesktopIbGatewayConnection.onLiveMark].
     */
    MARKET_DATA_ONLY
}
