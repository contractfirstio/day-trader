package daytrader.marketdata

import daytrader.broker.SymbolMarkets
import daytrader.gateway.LiveQuote
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process pub/sub for live quotes: one publisher, many subscribers each with an
 * independent FIFO [Channel].
 *
 * IB and synthetic producers call [publish] (non-blocking). Subscribers (emulator exchange,
 * UI relay) drain their channel on a single consumer coroutine each.
 */
class MarketQuoteBus(
    private val defaultSubscriberBuffer: Int = DEFAULT_SUBSCRIBER_BUFFER
) {
    private val sequence = AtomicLong(0L)
    private val subscribers = ConcurrentHashMap<String, SubscriberChannel>()

    /**
     * @param subscriberId stable key (use [EMULATOR_SUBSCRIBER_ID] / [UI_SUBSCRIBER_ID] constants).
     * @param onOverflow [BufferOverflow.DROP_OLDEST] for UI (keep latest under load);
     *   [BufferOverflow.SUSPEND] for emulator (never drop ticks).
     */
    fun subscribe(
        subscriberId: String,
        capacity: Int = defaultSubscriberBuffer,
        onOverflow: BufferOverflow = BufferOverflow.SUSPEND
    ): ReceiveChannel<QuoteUpdate> {
        val channel = if (capacity == Channel.UNLIMITED) {
            Channel<QuoteUpdate>(Channel.UNLIMITED)
        } else {
            Channel(
                capacity = capacity,
                onBufferOverflow = onOverflow
            )
        }
        subscribers[subscriberId] = SubscriberChannel(channel, onOverflow, unlimited = capacity == Channel.UNLIMITED)
        return channel
    }

    /** FIFO queue that never drops ticks (emulator fill evaluation). */
    fun subscribeUnlimited(subscriberId: String): ReceiveChannel<QuoteUpdate> =
        subscribe(subscriberId, capacity = Channel.UNLIMITED)

    fun unsubscribe(subscriberId: String) {
        subscribers.remove(subscriberId)?.channel?.close()
    }

    /** Thread-safe, non-blocking: safe to call from IB callback threads. */
    fun publish(
        symbol: String,
        quote: LiveQuote,
        priorClose: Double? = null,
        source: QuoteSource = QuoteSource.EXTERNAL
    ) {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        val normalizedQuote = quote.copy(symbol = norm)
        val update = QuoteUpdate(
            symbol = norm,
            quote = normalizedQuote,
            priorClose = priorClose,
            source = source,
            sequence = sequence.incrementAndGet()
        )
        subscribers.values.forEach { it.deliver(update) }
    }

    fun publish(update: QuoteUpdate) {
        val norm = SymbolMarkets.normalizeSymbol(update.symbol)
        val withSeq = update.copy(
            symbol = norm,
            quote = update.quote.copy(symbol = norm),
            sequence = sequence.incrementAndGet()
        )
        subscribers.values.forEach { it.deliver(withSeq) }
    }

    private data class SubscriberChannel(
        val channel: Channel<QuoteUpdate>,
        val onOverflow: BufferOverflow,
        val unlimited: Boolean = false
    ) {
        fun deliver(update: QuoteUpdate) {
            if (unlimited) {
                channel.trySend(update)
                return
            }
            when (onOverflow) {
                BufferOverflow.DROP_OLDEST -> {
                    while (true) {
                        if (channel.trySend(update).isSuccess) break
                        channel.tryReceive()
                    }
                }
                else -> channel.trySend(update)
            }
        }
    }

    companion object {
        const val EMULATOR_SUBSCRIBER_ID = "emulator-exchange"
        const val UI_SUBSCRIBER_ID = "ui-quotes"
        const val DEFAULT_SUBSCRIBER_BUFFER = 4_096
        const val UI_SUBSCRIBER_BUFFER = 512
    }
}
