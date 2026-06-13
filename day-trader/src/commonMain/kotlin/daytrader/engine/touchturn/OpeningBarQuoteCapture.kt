package daytrader.engine.touchturn

import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOpeningBarPriceSample
import daytrader.domain.TouchTurnSessionContext
import daytrader.gateway.LiveQuote
import java.util.concurrent.ConcurrentHashMap

/** Accumulates opening-bar marks per deployment for extreme-bounce evaluation. */
internal class OpeningBarQuoteCapture {
    private val samplesByDeployment = ConcurrentHashMap<String, MutableList<TouchTurnOpeningBarPriceSample>>()

    fun clear(deploymentId: String) {
        samplesByDeployment.remove(deploymentId)
    }

    fun recordQuote(
        deploymentId: String,
        session: TouchTurnSessionContext,
        quote: LiveQuote,
        nowEpochMillis: Long
    ) {
        if (session.candle != null && session.setup != null) return
        val barTime = session.resolvedOpeningBarTime() ?: return
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, session.marketZoneId) ?: return
        if (nowEpochMillis >= barEnd) return
        val price = TouchTurnLogic.resolveLiveMid(quote.bid, quote.ask, quote.last) ?: return
        val epochMs = quote.quoteEpochMillis.takeIf { it > 0L } ?: nowEpochMillis
        append(deploymentId, TouchTurnOpeningBarPriceSample(epochMs = epochMs, price = price))
    }

    fun appendAll(deploymentId: String, samples: List<TouchTurnOpeningBarPriceSample>) {
        if (samples.isEmpty()) return
        val bucket = samplesByDeployment.getOrPut(deploymentId) { mutableListOf() }
        synchronized(bucket) {
            bucket.addAll(samples)
            bucket.sortBy { it.epochMs }
        }
    }

    fun snapshot(deploymentId: String): List<TouchTurnOpeningBarPriceSample> =
        samplesByDeployment[deploymentId]?.toList().orEmpty()

    fun mergeForBarWindow(
        deploymentId: String,
        session: TouchTurnSessionContext,
        supplemental: List<TouchTurnOpeningBarPriceSample>
    ): List<TouchTurnOpeningBarPriceSample> {
        val barTime = session.resolvedOpeningBarTime() ?: return dedupeSamples(supplemental)
        val zoneId = session.marketZoneId
        val barStart = TouchTurnLogic.barStartEpochMillis(barTime, zoneId) ?: return dedupeSamples(supplemental)
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zoneId) ?: return dedupeSamples(supplemental)
        // Replay supplies the full captured timeline; live capture via StateFlow is a lossy subset.
        val source = if (supplemental.isNotEmpty()) supplemental else snapshot(deploymentId)
        return source
            .filter { it.price > 0.0 && it.epochMs in barStart..barEnd }
            .let { dedupeSamples(it) }
    }

    private fun dedupeSamples(samples: List<TouchTurnOpeningBarPriceSample>): List<TouchTurnOpeningBarPriceSample> =
        samples
            .distinctBy { "${it.epochMs}:${it.price}" }
            .sortedBy { it.epochMs }

    private fun append(deploymentId: String, sample: TouchTurnOpeningBarPriceSample) {
        val bucket = samplesByDeployment.getOrPut(deploymentId) { mutableListOf() }
        synchronized(bucket) {
            bucket.add(sample)
        }
    }
}
