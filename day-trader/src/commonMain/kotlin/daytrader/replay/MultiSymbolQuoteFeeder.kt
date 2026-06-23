package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.marketdata.MarketQuoteBus
import daytrader.platform.MutableTradingClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hybrid-style per-symbol captured quote streaming for replay.
 *
 * [ensureStreaming] holds a subscription (like IB refcount) without publishing quotes.
 * [enableDrip] arms timed drip for a symbol after [ReplayPlaybackOrchestrator] fast-forwards
 * the opening bar so bootstrap is not flushed ahead of virtual time.
 */
class MultiSymbolQuoteFeeder(
    private val registry: ReplayCaptureRegistry,
    private val quoteBus: MarketQuoteBus?,
    private val marketDataGateway: ReplayMarketDataGateway,
    private val clock: MutableTradingClock,
    private val scope: CoroutineScope
) {
    private val feeders = mutableMapOf<String, QuoteFeeder>()
    private val streamRefCount = mutableMapOf<String, Int>()
    private val dripEnabled = mutableSetOf<String>()
    private val openingBarQuotesReady = mutableSetOf<String>()
    private val clockMutex = Mutex()
    private var mergedDripJob: Job? = null

    @Volatile
    var quoteIntervalMs: () -> Long = { ReplayPlaybackConfig.DEFAULT_QUOTE_INTERVAL_MS }

    /**
     * After each published quote (interactive drip and max-speed backtest path): poll stop rules
     * and drain. Return false when no [daytrader.domain.DeploymentStatus.RUNNING] deployment
     * remains for [symbol] so drip halts.
     */
    var onAfterQuotePublished: (suspend (symbol: String) -> Boolean)? = null

    /** Min OPEN_DEADLINE epoch across running deployments for [symbol]; wired by orchestrator. */
    var resolveStopDeadlineEpochMs: ((symbol: String) -> Long?)? = null

    /** Fired when [markOpeningBarQuotesReady] arms bracket/liquidity gating for [symbol]. */
    var onOpeningBarQuotesReady: ((symbol: String) -> Unit)? = null

    /**
     * Suspend ingest wired by [ReplayHybridRuntime] for max-speed replay (no runBlocking per tick).
     */
    var backtestQuoteIngest: (suspend (QuoteEvent) -> Unit)? = null

    var onDripFinished: (() -> Unit)? = null

    /**
     * Wired by [ReplayHybridRuntime] for throttled interactive replay (runBlocking ingest).
     */
    var onCapturedQuotePublished: ((QuoteEvent) -> Unit)? = null
        set(value) {
            field = value
            feeders.values.forEach { it.onCapturedQuotePublished = value }
        }

    fun isOpeningBarQuotesReady(symbol: String): Boolean =
        SymbolMarkets.normalizeSymbol(symbol) in openingBarQuotesReady

    /** Called after fast-forward has published captured quotes through the opening bar. */
    fun markOpeningBarQuotesReady(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return
        if (!openingBarQuotesReady.add(norm)) return
        onOpeningBarQuotesReady?.invoke(norm)
    }

    fun registerBundle(bundle: SessionBundle) {
        registry.register(bundle)
        feeders.remove(SymbolMarkets.normalizeSymbol(bundle.symbol))
    }

    fun feederForSymbol(symbol: String): QuoteFeeder? {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return feeders.getOrPut(norm) {
            val bundle = registry.bundleFor(norm) ?: return null
            QuoteFeeder(bundle, quoteBus, marketDataGateway).also { feeder ->
                feeder.onCapturedQuotePublished = onCapturedQuotePublished
            }
        }
    }

    /** Refcounted subscription; does not publish quotes until [enableDrip]. */
    fun ensureStreaming(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return
        streamRefCount[norm] = (streamRefCount[norm] ?: 0) + 1
    }

    /** Arms timed quote drip for [symbol] after opening-bar fast-forward. */
    fun enableDrip(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return
        dripEnabled.add(norm)
        startMergedDripIfNeeded()
    }

    fun disableDripForSymbol(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        dripEnabled.remove(norm)
        if (dripEnabled.isEmpty()) {
            mergedDripJob?.cancel()
            mergedDripJob = null
        }
    }

    /** @return true when no subscribers remain for [symbol]. */
    fun releaseStreaming(symbol: String): Boolean {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        if (norm.isBlank()) return false
        val next = (streamRefCount[norm] ?: 0) - 1
        val fullyReleased = next <= 0
        if (fullyReleased) {
            streamRefCount.remove(norm)
            dripEnabled.remove(norm)
            openingBarQuotesReady.remove(norm)
            feeders.remove(norm)
        } else {
            streamRefCount[norm] = next
        }
        if (streamRefCount.isEmpty()) {
            mergedDripJob?.cancel()
            mergedDripJob = null
        }
        return fullyReleased
    }

    fun isStreaming(symbol: String): Boolean =
        (streamRefCount[SymbolMarkets.normalizeSymbol(symbol)] ?: 0) > 0

    internal fun cachedFeederForSymbol(symbol: String): QuoteFeeder? =
        feeders[SymbolMarkets.normalizeSymbol(symbol)]

    fun publishUpTo(symbol: String, epochMs: Long) {
        feederForSymbol(symbol)?.publishUpTo(epochMs)
    }

    suspend fun publishUpToForBacktest(symbol: String, epochMs: Long, onQuote: suspend (QuoteEvent) -> Unit) {
        feederForSymbol(symbol)?.publishUpToForBacktest(epochMs, onQuote)
    }

    fun seedGatewayQuotesUpTo(symbol: String, epochMs: Long) {
        feederForSymbol(symbol)?.seedGatewayQuotesUpTo(epochMs)
    }

    fun seekToFirstQuoteAfter(symbol: String, epochMs: Long) {
        feederForSymbol(symbol)?.seekToFirstQuoteAfter(epochMs)
    }

    /** Capture [ordersPlacedAt] anchor in quote-timeline epoch space (not live replay session milestones). */
    fun ordersPlacedAnchorEpochMsFor(symbol: String): Long? =
        registry.bundleFor(symbol)?.let { ReplayQuoteFillAnchor.ordersPlacedAnchorEpochMs(it) }

    fun resetSymbol(symbol: String) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        feeders[norm]?.reset()
        dripEnabled.remove(norm)
        openingBarQuotesReady.remove(norm)
    }

    fun resetAll() {
        mergedDripJob?.cancel()
        mergedDripJob = null
        feeders.values.forEach { it.reset() }
        feeders.clear()
        streamRefCount.clear()
        dripEnabled.clear()
        openingBarQuotesReady.clear()
    }

    fun stopDrip() {
        mergedDripJob?.cancel()
        mergedDripJob = null
        dripEnabled.clear()
    }

    private fun startMergedDripIfNeeded() {
        if (mergedDripJob?.isActive == true) return
        if (dripEnabled.isEmpty()) return
        mergedDripJob = scope.launch {
            try {
                runMergedDrip()
            } catch (_: CancellationException) {
                // expected on session teardown
            } finally {
                onDripFinished?.invoke()
            }
        }
    }

    private suspend fun runMergedDrip() {
        while (dripEnabled.isNotEmpty() && streamRefCount.isNotEmpty()) {
            val target = pickNextQuoteTarget() ?: break
            val (symbol, nextEvent) = target
            val deadline = resolveStopDeadlineEpochMs?.invoke(symbol)
            val cappedAtDeadline = deadline != null && nextEvent.epochMs > deadline
            if (cappedAtDeadline) {
                publishThroughDeadline(symbol, deadline!!)
                clockMutex.withLock { clock.advanceTo(deadline) }
            } else {
                val published = publishNextQuote(symbol) ?: break
                clockMutex.withLock { clock.advanceTo(published.epochMs) }
                if (!ReplayQuoteSpeed.isMaxSpeed(quoteIntervalMs())) {
                    val intervalMs = quoteIntervalMs()
                    if (intervalMs > 0L) delay(intervalMs)
                }
            }
            val continueDrip = onAfterQuotePublished?.invoke(symbol) ?: true
            if (!continueDrip) disableDripForSymbol(symbol)
        }
    }

    private suspend fun publishThroughDeadline(symbol: String, deadlineMs: Long) {
        val feeder = feederForSymbol(symbol) ?: return
        if (ReplayQuoteSpeed.isMaxSpeed(quoteIntervalMs())) {
            val ingest = backtestQuoteIngest ?: return
            feeder.publishUpToForBacktest(deadlineMs, ingest)
        } else {
            feeder.publishUpTo(deadlineMs)
        }
    }

    private suspend fun publishNextQuote(symbol: String): QuoteEvent? {
        val feeder = feederForSymbol(symbol) ?: return null
        return if (ReplayQuoteSpeed.isMaxSpeed(quoteIntervalMs())) {
            val ingest = backtestQuoteIngest ?: return null
            feeder.publishNextForBacktest(ingest)
        } else {
            feeder.publishNext()
        }
    }

    private fun pickNextQuoteTarget(): Pair<String, QuoteEvent>? {
        var bestSymbol: String? = null
        var bestEvent: QuoteEvent? = null
        for (symbol in dripEnabled) {
            if ((streamRefCount[symbol] ?: 0) <= 0) continue
            val event = feederForSymbol(symbol)?.peekNext() ?: continue
            if (bestEvent == null || event.epochMs < bestEvent.epochMs) {
                bestEvent = event
                bestSymbol = symbol
            }
        }
        val symbol = bestSymbol ?: return null
        val event = bestEvent ?: return null
        return symbol to event
    }
}
