package daytrader.broker.emulator

import kotlin.random.Random

/**
 * Generates synthetic bid/ask/last movement for full emulator mode.
 *
 * Not used when [EmulatorPricingSource.LIVE_EXCHANGE] is active — external ticks drive the book.
 */
internal class SyntheticQuoteSimulator(
    private val config: BrokerEmulatorConfig,
    private val quoteBook: EmulatorQuoteBook,
    private val random: Random
) {
    fun advanceEntryApproach(symbol: String, pending: BracketEntryPending) {
        val quote = quoteBook.quoteOrNull(symbol) ?: return
        val step = pending.range * config.touchTurnEntryStepPctOfRange * (0.5 + random.nextDouble())
        val epsilon = pending.range * 0.01
        when (pending.scenario) {
            TouchTurnEntryScenario.NEVER_FILL -> when {
                pending.isBuyEntry -> quote.setMid(quote.last + step)
                else -> quote.setMid(quote.last - step)
            }
            TouchTurnEntryScenario.APPROACH_AND_FILL -> when {
                pending.isBuyEntry -> {
                    var newAsk = quote.ask - step
                    if (pending.ticksElapsed < config.touchTurnEntryMinApproachTicks) {
                        newAsk = maxOf(newAsk, pending.entryPrice + epsilon)
                    }
                    quote.ask = newAsk.coerceAtLeast(0.01)
                    quote.last = (quote.ask + quote.bid) / 2.0
                    quote.bid = quote.last - quote.halfSpread
                }
                else -> {
                    var newBid = quote.bid + step
                    if (pending.ticksElapsed < config.touchTurnEntryMinApproachTicks) {
                        newBid = minOf(newBid, pending.entryPrice - epsilon)
                    }
                    quote.bid = newBid.coerceAtLeast(0.01)
                    quote.last = (quote.ask + quote.bid) / 2.0
                    quote.ask = quote.last + quote.halfSpread
                }
            }
            TouchTurnEntryScenario.IMMEDIATE -> Unit
        }
    }

    fun applyBracketWalk(symbol: String, walk: BracketPriceWalk, nextAggressivePrice: Double) {
        quoteBook.quoteOrNull(symbol)?.setAggressivePrice(nextAggressivePrice, walk.isLongPosition)
    }

    fun applyBackgroundJitter(symbol: String) {
        quoteBook.mutate(symbol) { quote ->
            val jitter = 1.0 + random.nextDouble(-config.marketTickJitterPct, config.marketTickJitterPct)
            quote.setMid(quote.last * jitter)
        }
    }
}
