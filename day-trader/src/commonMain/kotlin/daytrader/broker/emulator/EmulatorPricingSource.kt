package daytrader.broker.emulator

/**
 * Where bid/ask/last come from for **order fill evaluation** in the emulator.
 *
 * Exchange emulation (orders, positions, brackets) is the same in both modes; only the
 * price feed differs:
 * - [SYNTHETIC] — the emulator walks and jitters its own quote book.
 * - [LIVE_EXCHANGE] — bid/ask/last are pushed in via [BrokerEmulatorEngine.ingestExternalQuote]
 *   (hybrid paper trading uses IB streaming ticks).
 */
enum class EmulatorPricingSource {
    SYNTHETIC,
    LIVE_EXCHANGE;

    val isSynthetic: Boolean get() = this == SYNTHETIC
    val isExternal: Boolean get() = this == LIVE_EXCHANGE
}
