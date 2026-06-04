package daytrader.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentListingCandidates
import daytrader.domain.ResolvedInstrument
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.IbStreamingMarketDataType
import daytrader.gateway.LiveQuote
import daytrader.marketdata.MarketQuoteBus
import daytrader.ui.InstrumentListingPicker
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val PRICE_FEED_TESTER_SUBSCRIBER_ID = "price-feed-tester"
private const val STALE_AFTER_MS = 15_000L

private data class FeedRowState(
    val bid: String = "—",
    val ask: String = "—",
    val last: String = "—",
    val tickVolume: String = "—",
    val updateCount: Int = 0,
    val lastUpdateEpochMs: Long? = null,
    val status: FeedStatus = FeedStatus.Idle
)

private enum class FeedStatus(val label: String) {
    Idle("Idle"),
    Watching("Watching"),
    Receiving("Receiving"),
    Stale("Stale"),
    NoData("No data yet")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceFeedTesterDialog(
    brokerKind: BrokerKind,
    brokerGateway: BrokerGateway,
    marketDataGateway: BrokerGateway?,
    quoteBus: MarketQuoteBus?,
    ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)?,
    releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)?,
    getStreamingMarketDataType: (() -> IbStreamingMarketDataType)? = null,
    setStreamingMarketDataType: ((IbStreamingMarketDataType) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val resolveGateway = marketDataGateway ?: brokerGateway
    val resolveConnection by resolveGateway.connectionState.collectAsState()
    val availableFeeds = remember(brokerKind, marketDataGateway, quoteBus) {
        PriceFeedOptionCatalog.available(
            brokerKind = brokerKind,
            hasSeparateMarketDataGateway = marketDataGateway != null && marketDataGateway !== brokerGateway,
            hasQuoteBus = quoteBus != null
        )
    }
    var symbolInput by remember { mutableStateOf("") }
    var selectedFeeds by remember(availableFeeds) {
        mutableStateOf(PriceFeedOptionCatalog.defaultSelection(availableFeeds))
    }
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var listingCandidates by remember { mutableStateOf<List<ResolvedInstrument>>(emptyList()) }
    var selectedListing by remember { mutableStateOf<ResolvedInstrument?>(null) }
    var marketDataType by remember {
        mutableStateOf(getStreamingMarketDataType?.invoke() ?: IbStreamingMarketDataType.DELAYED_FROZEN)
    }
    var isRunning by remember { mutableStateOf(false) }
    var activeSymbol by remember { mutableStateOf<String?>(null) }
    var activeInstrument by remember { mutableStateOf<InstrumentIdentity?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val feedRows = remember { mutableStateMapOf<PriceFeedOption, FeedRowState>() }
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val executionQuotes by brokerGateway.quotes.collectAsState()
    val marketDataQuotes by (marketDataGateway ?: brokerGateway).quotes.collectAsState()
    val executionConnection by brokerGateway.connectionState.collectAsState()
    val marketDataConnection by (marketDataGateway ?: brokerGateway).connectionState.collectAsState()
    val supportsIbMarketDataType = setStreamingMarketDataType != null

    LaunchedEffect(symbolInput, resolveConnection, isRunning) {
        if (isRunning) return@LaunchedEffect
        val trimmed = symbolInput.trim()
        selectedListing = null
        listingCandidates = emptyList()
        resolveError = null
        if (trimmed.isBlank() || trimmed.length < 2) {
            resolving = false
            return@LaunchedEffect
        }
        delay(400)
        resolving = true
        if (resolveConnection != GatewayConnectionState.Connected) {
            val heuristic = DeploymentMarket.fromSymbolHeuristic(trimmed)
            listingCandidates = listOf(heuristic)
            selectedListing = heuristic
            resolving = false
            resolveError = "IB not connected — using offline listing guess."
            return@LaunchedEffect
        }
        resolveGateway.resolveInstrument(trimmed).fold(
            onSuccess = { resolution ->
                val prepared = InstrumentListingCandidates.prepareForUi(resolution.candidates)
                listingCandidates = prepared
                selectedListing = when {
                    prepared.size == 1 -> prepared.first()
                    else -> null
                }
                resolveError = null
            },
            onFailure = { error ->
                val heuristic = DeploymentMarket.fromSymbolHeuristic(trimmed)
                listingCandidates = listOf(heuristic)
                selectedListing = heuristic
                resolveError = error.message ?: "Lookup failed — using offline guess."
            }
        )
        resolving = false
    }

    LaunchedEffect(isRunning) {
        while (isActive && isRunning) {
            delay(500)
            clockTick = System.currentTimeMillis()
        }
    }

    LaunchedEffect(isRunning, activeSymbol, selectedFeeds) {
        val symbol = activeSymbol ?: return@LaunchedEffect
        if (!isRunning) return@LaunchedEffect

        if (PriceFeedOption.QUOTE_BUS in selectedFeeds) {
            val bus = quoteBus ?: return@LaunchedEffect
            val channel = bus.subscribe(
                subscriberId = PRICE_FEED_TESTER_SUBSCRIBER_ID,
                capacity = MarketQuoteBus.UI_SUBSCRIBER_BUFFER,
                onOverflow = BufferOverflow.DROP_OLDEST
            )
            try {
                for (update in channel) {
                    if (!isRunning || update.symbol != symbol) continue
                    applyQuote(feedRows, PriceFeedOption.QUOTE_BUS, update.quote)
                }
            } finally {
                bus.unsubscribe(PRICE_FEED_TESTER_SUBSCRIBER_ID)
            }
        }
    }

    LaunchedEffect(isRunning, activeSymbol, executionQuotes, marketDataQuotes, clockTick) {
        val symbol = activeSymbol ?: return@LaunchedEffect
        if (!isRunning) return@LaunchedEffect

        if (PriceFeedOption.EXECUTION_GATEWAY in selectedFeeds) {
            val quote = executionQuotes[symbol]
            if (quote != null) {
                applyQuote(feedRows, PriceFeedOption.EXECUTION_GATEWAY, quote)
            } else {
                markWatching(feedRows, PriceFeedOption.EXECUTION_GATEWAY)
            }
        }
        if (PriceFeedOption.MARKET_DATA_GATEWAY in selectedFeeds) {
            val quote = marketDataQuotes[symbol]
            if (quote != null) {
                applyQuote(feedRows, PriceFeedOption.MARKET_DATA_GATEWAY, quote)
            } else {
                markWatching(feedRows, PriceFeedOption.MARKET_DATA_GATEWAY)
            }
        }
        refreshStaleStatuses(feedRows, selectedFeeds, clockTick)
    }

    DisposableEffect(Unit) {
        onDispose {
            stopFeed(
                symbol = activeSymbol,
                instrument = activeInstrument,
                releaseLiveMarketData = releaseLiveMarketData
            )
            quoteBus?.unsubscribe(PRICE_FEED_TESTER_SUBSCRIBER_ID)
        }
    }

    fun stopAll() {
        stopFeed(activeSymbol, activeInstrument, releaseLiveMarketData)
        isRunning = false
        activeSymbol = null
        activeInstrument = null
        selectedFeeds.forEach { feed ->
            feedRows[feed] = FeedRowState(status = FeedStatus.Idle)
        }
    }

    AlertDialog(
        onDismissRequest = {
            stopAll()
            onDismiss()
        },
        title = { Text("Price feed tester") },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 480.dp, max = 640.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Subscribe to a symbol and compare quote paths. Use this to verify IB " +
                        "streaming before Touch Turn live gates run at bar close.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = symbolInput,
                    onValueChange = {
                        symbolInput = it.uppercase()
                        statusMessage = null
                    },
                    label = { Text("Symbol") },
                    placeholder = { Text("e.g. NWG, 700, SPY") },
                    singleLine = true,
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth()
                )
                InstrumentLookupSection(
                    resolving = resolving,
                    resolveError = resolveError,
                    candidates = listingCandidates,
                    selectedListing = selectedListing,
                    onSelectListing = { selectedListing = it },
                    enabled = !isRunning
                )
                if (supportsIbMarketDataType) {
                    Text(
                        text = "IB streaming price type",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IbStreamingMarketDataType.entries.forEach { type ->
                            FilterChip(
                                selected = marketDataType == type,
                                onClick = {
                                    if (isRunning) return@FilterChip
                                    marketDataType = type
                                    setStreamingMarketDataType?.invoke(type)
                                },
                                label = { Text(type.label) },
                                enabled = !isRunning
                            )
                        }
                    }
                    Text(
                        text = marketDataType.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Text(
                    text = "Quote paths to watch",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableFeeds.forEach { feed ->
                        FilterChip(
                            selected = feed in selectedFeeds,
                            onClick = {
                                if (isRunning) return@FilterChip
                                selectedFeeds = if (feed in selectedFeeds) {
                                    (selectedFeeds - feed).ifEmpty { setOf(feed) }
                                } else {
                                    selectedFeeds + feed
                                }
                            },
                            label = { Text(feed.label) },
                            enabled = !isRunning
                        )
                    }
                }
                ConnectionSummary(
                    brokerKind = brokerKind,
                    executionConnection = executionConnection,
                    marketDataConnection = marketDataConnection,
                    hasSeparateMarketDataGateway = marketDataGateway != null &&
                        marketDataGateway !== brokerGateway,
                    marketDataType = if (supportsIbMarketDataType) marketDataType else null
                )
                statusMessage?.let { message ->
                    Text(text = message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                selectedFeeds.sortedBy { availableFeeds.indexOf(it) }.forEach { feed ->
                    FeedCard(
                        feed = feed,
                        row = feedRows[feed] ?: FeedRowState(),
                        nowEpochMs = clockTick
                    )
                }
            }
        },
        confirmButton = {
            if (isRunning) {
                Button(
                    onClick = { stopAll() },
                    colors = ButtonDefaults.buttonColors(containerColor = LossRed)
                ) {
                    Text("Stop feed")
                }
            } else {
                Button(
                    onClick = {
                        val trimmed = symbolInput.trim()
                        if (trimmed.isBlank()) {
                            statusMessage = "Enter a symbol."
                            return@Button
                        }
                        if (selectedFeeds.isEmpty()) {
                            statusMessage = "Select at least one quote path."
                            return@Button
                        }
                        if (listingCandidates.size > 1 && selectedListing == null) {
                            statusMessage = "Select a listing / exchange."
                            return@Button
                        }
                        val listing = selectedListing
                            ?: listingCandidates.singleOrNull()
                            ?: DeploymentMarket.fromSymbolHeuristic(trimmed)
                        val instrument = listing.identity
                            ?: InstrumentIdentity.heuristic(trimmed, listing.currencyCode)
                        val norm = SymbolMarkets.normalizeSymbol(trimmed)
                        val needsSubscription = selectedFeeds.any { it.requiresStreamingSubscription() }
                        if (needsSubscription && ensureLiveMarketData == null) {
                            statusMessage = "Streaming subscription is not available in this broker mode."
                            return@Button
                        }
                        statusMessage = null
                        selectedFeeds.forEach { feed ->
                            feedRows[feed] = FeedRowState(status = FeedStatus.Watching)
                        }
                        setStreamingMarketDataType?.invoke(marketDataType)
                        activeInstrument = instrument
                        activeSymbol = norm
                        if (needsSubscription) {
                            ensureLiveMarketData?.invoke(trimmed, instrument)
                        }
                        isRunning = true
                        statusMessage = buildString {
                            append("Watching $norm · ${InstrumentListingCandidates.listingLabel(listing)}")
                            if (supportsIbMarketDataType) {
                                append(" · IB ${marketDataType.label}")
                            }
                        }
                    }
                ) {
                    Text("Start feed")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                stopAll()
                onDismiss()
            }) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun InstrumentLookupSection(
    resolving: Boolean,
    resolveError: String?,
    candidates: List<ResolvedInstrument>,
    selectedListing: ResolvedInstrument?,
    onSelectListing: (ResolvedInstrument) -> Unit,
    enabled: Boolean
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Instrument lookup", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            when {
                resolving -> Text("Looking up instrument…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                candidates.isEmpty() ->
                    Text(
                        "Enter at least 2 characters to look up listings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                candidates.size == 1 -> {
                    val only = candidates.first()
                    only.companyName?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                    Text(
                        InstrumentListingCandidates.listingLabel(only),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                else -> InstrumentListingPicker(
                    candidates = candidates,
                    selected = selectedListing,
                    onSelect = onSelectListing,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            resolveError?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB300))
            }
            if (!enabled && selectedListing != null) {
                Text(
                    "Listing locked while feed is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ConnectionSummary(
    brokerKind: BrokerKind,
    executionConnection: GatewayConnectionState,
    marketDataConnection: GatewayConnectionState,
    hasSeparateMarketDataGateway: Boolean,
    marketDataType: IbStreamingMarketDataType?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connections", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                text = "Execution · ${connectionLabel(executionConnection, brokerKind)}",
                style = MaterialTheme.typography.bodySmall,
                color = connectionColor(executionConnection)
            )
            if (hasSeparateMarketDataGateway) {
                Text(
                    text = "Market data · ${connectionShortLabel(marketDataConnection)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = connectionColor(marketDataConnection)
                )
            }
            marketDataType?.let { type ->
                Text(
                    text = "Streaming type · ${type.label} (IB code ${type.ibCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun FeedCard(
    feed: PriceFeedOption,
    row: FeedRowState,
    nowEpochMs: Long
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(feed.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = row.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(row.status)
                )
            }
            Text(
                text = feed.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            QuoteLine(label = "Bid", value = row.bid)
            QuoteLine(label = "Ask", value = row.ask)
            QuoteLine(label = "Last", value = row.last)
            QuoteLine(label = "Tick vol", value = row.tickVolume)
            Text(
                text = buildString {
                    append("Updates: ${row.updateCount}")
                    row.lastUpdateEpochMs?.let { last ->
                        append(" · last ")
                        append(formatAge(nowEpochMs - last))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun QuoteLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.widthIn(min = 56.dp), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

private fun applyQuote(
    feedRows: MutableMap<PriceFeedOption, FeedRowState>,
    feed: PriceFeedOption,
    quote: LiveQuote
) {
    val previous = feedRows[feed]
    feedRows[feed] = FeedRowState(
        bid = formatPrice(quote.bid),
        ask = formatPrice(quote.ask),
        last = formatPrice(quote.last),
        tickVolume = formatPrice(quote.tickVolume),
        updateCount = (previous?.updateCount ?: 0) + 1,
        lastUpdateEpochMs = quote.quoteEpochMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
        status = FeedStatus.Receiving
    )
}

private fun markWatching(
    feedRows: MutableMap<PriceFeedOption, FeedRowState>,
    feed: PriceFeedOption
) {
    val previous = feedRows[feed] ?: FeedRowState()
    if (previous.status == FeedStatus.Receiving) return
    feedRows[feed] = previous.copy(
        status = if (previous.updateCount > 0) previous.status else FeedStatus.NoData
    )
}

private fun refreshStaleStatuses(
    feedRows: MutableMap<PriceFeedOption, FeedRowState>,
    selectedFeeds: Set<PriceFeedOption>,
    nowEpochMs: Long
) {
    selectedFeeds.forEach { feed ->
        val row = feedRows[feed] ?: return@forEach
        val last = row.lastUpdateEpochMs
        if (row.status == FeedStatus.Receiving && last != null && nowEpochMs - last > STALE_AFTER_MS) {
            feedRows[feed] = row.copy(status = FeedStatus.Stale)
        }
    }
}

private fun stopFeed(
    symbol: String?,
    instrument: InstrumentIdentity?,
    releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)?
) {
    if (symbol.isNullOrBlank()) return
    releaseLiveMarketData?.invoke(symbol, instrument)
}

private fun formatPrice(value: Double?): String =
    value?.let { "%.4f".format(it) } ?: "—"

private fun formatAge(deltaMs: Long): String {
    if (deltaMs < 1_000L) return "just now"
    val seconds = deltaMs / 1_000
    if (seconds < 60) return "${seconds}s ago"
    return "${seconds / 60}m ${seconds % 60}s ago"
}

private fun connectionLabel(state: GatewayConnectionState, brokerKind: BrokerKind): String {
    val name = when (brokerKind) {
        BrokerKind.INTERACTIVE_BROKERS -> "IB"
        BrokerKind.EMULATOR -> "Emulator"
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Paper"
        BrokerKind.REPLAY -> "Replay"
    }
    return "$name · ${connectionShortLabel(state)}"
}

private fun connectionShortLabel(state: GatewayConnectionState): String = when (state) {
    GatewayConnectionState.Disconnected -> "disconnected"
    GatewayConnectionState.Connecting -> "connecting"
    GatewayConnectionState.Connected -> "connected"
    is GatewayConnectionState.Error -> "error"
}

private fun connectionColor(state: GatewayConnectionState): Color = when (state) {
    GatewayConnectionState.Connected -> GainGreen
    GatewayConnectionState.Connecting -> Color(0xFFFFB300)
    is GatewayConnectionState.Error -> LossRed
    GatewayConnectionState.Disconnected -> TextSecondary
}

private fun statusColor(status: FeedStatus): Color = when (status) {
    FeedStatus.Receiving -> GainGreen
    FeedStatus.Stale -> Color(0xFFFFB300)
    FeedStatus.NoData -> TextSecondary
    FeedStatus.Watching -> Color(0xFFFFB300)
    FeedStatus.Idle -> TextSecondary
}
