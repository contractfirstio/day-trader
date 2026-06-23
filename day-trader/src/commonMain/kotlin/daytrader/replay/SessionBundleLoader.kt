package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.data.persistence.SessionTradeRecord
import daytrader.data.persistence.TouchTurnRunPersistence
import daytrader.data.persistence.TouchTurnRunRecordRecord
import daytrader.diagnostics.SessionManifest
import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnSignalContext
import daytrader.gateway.LiveQuote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

object SessionBundleLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun load(contents: SessionBundleContents): Result<SessionBundle> = runCatching {
        val manifest = contents.manifestJson?.let { parseManifest(it) }
        val applicationLines = parseJsonl(contents.applicationJsonl, ApplicationTraceLine.serializer())
        val started = applicationLines.lastOrNull { it.type == "session_started" }
        val closed = applicationLines.lastOrNull { it.type == "session_closed" }

        val deploymentId = manifest?.deploymentId
            ?: started?.deploymentId
            ?: closed?.deploymentId
            ?: error("Session bundle missing deploymentId (manifest or session_started)")
        val sessionId = manifest?.sessionId
            ?: started?.sessionId
            ?: closed?.sessionId
            ?: error("Session bundle missing sessionId (manifest or session_started)")
        val symbol = manifest?.symbol
            ?: started?.symbol
            ?: closed?.symbol
            ?: error("Session bundle missing symbol")

        val sessionDate = manifest?.sessionDate ?: started?.details?.get("sessionDate")
        val brokerKind = manifest?.brokerKind ?: started?.brokerKind ?: closed?.brokerKind

        val timeline = buildTimeline(manifest, applicationLines, started, closed)
        val historicalEvents = parseHistorical(contents.historicalJsonl)
        val quoteEvents = buildQuoteTimeline(
            pricesJsonl = contents.pricesJsonl,
            ibPriceTicksJsonl = contents.ibPriceTicksJsonl,
            symbol = symbol
        )
        val groundTruth = closed?.let { parseGroundTruth(it) }

        SessionBundle(
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            sessionDate = sessionDate,
            brokerKind = brokerKind,
            manifest = manifest,
            timeline = timeline,
            historicalEvents = historicalEvents,
            quoteEvents = quoteEvents,
            groundTruth = groundTruth
        )
    }

    private fun parseManifest(raw: String): SessionManifest =
        json.decodeFromString(SessionManifest.serializer(), raw)

    private fun buildTimeline(
        manifest: SessionManifest?,
        applicationLines: List<ApplicationTraceLine>,
        started: ApplicationTraceLine?,
        closed: ApplicationTraceLine?
    ): SessionBundleTimeline {
        val startedEpoch = manifest?.timeline?.sessionStartedEpochMs ?: started?.epochMs
            ?: error("Session bundle missing session start timestamp")
        val stoppedEpoch = manifest?.timeline?.sessionStoppedEpochMs ?: closed?.epochMs
        val milestones = manifest?.timeline?.milestones ?: closed?.let { line ->
            parseGroundTruth(line)?.runRecord?.milestones
        }
        val ordersPlacedAnchorEpochMs = applicationLines
            .lastOrNull { it.type == "bracket_acknowledged" }
            ?.epochMs
            ?: applicationLines.lastOrNull { it.type == "bracket_submitted" }
                ?.epochMs
        return SessionBundleTimeline(
            sessionStartedEpochMs = startedEpoch,
            sessionStoppedEpochMs = stoppedEpoch,
            milestones = milestones,
            ordersPlacedAnchorEpochMs = ordersPlacedAnchorEpochMs,
        )
    }

    private fun parseHistorical(jsonl: String): List<HistoricalEvent> =
        parseJsonl(jsonl, HistoricalCaptureLine.serializer()).map { line ->
            HistoricalEvent(
                epochMs = line.epochMs,
                symbol = SymbolMarkets.normalizeSymbol(line.symbol),
                isClosedBarRefetch = line.isClosedBarRefetch,
                attempt = line.attempt,
                validation = line.validation,
                context = line.context
            )
        }

    private fun buildQuoteTimeline(
        pricesJsonl: String,
        ibPriceTicksJsonl: String?,
        symbol: String
    ): List<QuoteEvent> {
        val fromPrices = parsePriceEvents(pricesJsonl)
        val fromIb = ibPriceTicksJsonl?.let { IbPriceTickMerger.mergeJsonl(it, symbolFilter = symbol) }
            .orEmpty()
        return mergeQuoteTimelines(fromPrices, fromIb)
    }

    internal fun parsePriceEvents(pricesJsonl: String): List<QuoteEvent> =
        parseJsonl(pricesJsonl, PriceCaptureLine.serializer()).mapNotNull { line ->
            val quote = line.toLiveQuote() ?: return@mapNotNull null
            QuoteEvent(
                epochMs = line.epochMs,
                symbol = SymbolMarkets.normalizeSymbol(line.symbol),
                quote = quote
            )
        }

    internal fun mergeQuoteTimelines(
        prices: List<QuoteEvent>,
        ibTicks: List<QuoteEvent>
    ): List<QuoteEvent> {
        if (ibTicks.isEmpty()) return prices.sortedBy { it.epochMs }
        if (prices.isEmpty()) return ibTicks.sortedBy { it.epochMs }
        val merged = (prices + ibTicks).sortedBy { it.epochMs }
        val deduped = mutableListOf<QuoteEvent>()
        var lastKey: Pair<String, LiveQuote>? = null
        for (event in merged) {
            val key = event.symbol to event.quote
            if (lastKey?.first == key.first && quotesEqual(lastKey.second, key.second)) continue
            deduped.add(event)
            lastKey = key
        }
        return deduped
    }

    private fun parseGroundTruth(closed: ApplicationTraceLine): SessionGroundTruth? {
        val data = closed.data ?: return null
        val runRecordElement = data["touchTurnRunRecord"] ?: return null
        val persisted = json.decodeFromJsonElement(TouchTurnRunRecordRecord.serializer(), runRecordElement)
        val runRecord = TouchTurnRunPersistence.toDomain(persisted) ?: return null
        return SessionGroundTruth(
            runRecord = runRecord,
            stopTrigger = closed.details["stopTrigger"],
            rawFills = decodeSessionTrades(data["rawFills"]),
            dedupedFills = decodeSessionTrades(data["dedupedFills"])
        )
    }

    private fun decodeSessionTrades(element: kotlinx.serialization.json.JsonElement?): List<SessionTrade> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            runCatching {
                val record = json.decodeFromJsonElement(SessionTradeRecord.serializer(), item)
                SessionTrade(
                    execId = record.execId,
                    orderId = record.orderId,
                    permId = record.permId,
                    parentOrderId = record.parentOrderId,
                    side = record.side,
                    quantity = record.quantity,
                    price = record.price,
                    time = record.time,
                    currency = record.currency,
                    commission = record.commission,
                    realizedPnL = record.realizedPnL,
                )
            }.getOrNull()
        }
    }

    private fun quotesEqual(a: LiveQuote, b: LiveQuote): Boolean =
        a.bid == b.bid && a.ask == b.ask && a.last == b.last && a.tickVolume == b.tickVolume

    private fun <T> parseJsonl(text: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> json.decodeFromString(serializer, line) }
            .toList()
}

@Serializable
private data class ApplicationTraceLine(
    val at: String,
    val epochMs: Long,
    val type: String,
    val brokerKind: String? = null,
    val deploymentId: String? = null,
    val sessionId: String? = null,
    val symbol: String? = null,
    val details: Map<String, String> = emptyMap(),
    val data: JsonObject? = null
)

@Serializable
private data class HistoricalCaptureLine(
    val at: String,
    val epochMs: Long,
    val kind: String,
    val symbol: String,
    val isClosedBarRefetch: Boolean,
    val attempt: Int? = null,
    val validation: String? = null,
    val context: TouchTurnSignalContext
)

@Serializable
private data class PriceCaptureLine(
    val at: String,
    val epochMs: Long,
    val brokerId: String? = null,
    val symbol: String,
    val bid: Double? = null,
    val ask: Double? = null,
    val last: Double? = null,
    val tickVolume: Double? = null,
    val quoteEpochMillis: Long? = null,
    val kind: String = "quote_snapshot"
) {
    fun toLiveQuote(): LiveQuote? {
        if (bid == null && ask == null && last == null && tickVolume == null) return null
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return LiveQuote(
            symbol = norm,
            bid = bid,
            ask = ask,
            last = last,
            tickVolume = tickVolume,
            quoteEpochMillis = quoteEpochMillis ?: epochMs
        )
    }
}
