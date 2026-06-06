package daytrader.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.data.StrategyCatalog
import kotlinx.coroutines.delay
import daytrader.domain.*
import daytrader.presentation.strategies.*
import daytrader.ui.theme.*

@Composable
internal fun StartBlockedByPositionDialog(
    alert: StartBlockedByPositionAlert,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("StartBlockedByPositionDialog"),
        containerColor = SurfaceDark,
        title = {
            Text("Cannot start deployment", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(alert.summary, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                Text(alert.positionDetails, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                Text(alert.reason, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
internal fun AddStrategyDeploymentDialog(
    onDismiss: () -> Unit,
    defaultMaxDollarsFor: (StrategyType) -> Int,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    prefill: StrategyDeploymentAddPrefill? = null,
    onCreate: (
        StrategyType,
        String,
        String,
        String,
        MarketSource,
        String?,
        InstrumentIdentity?,
        Int,
        Boolean
    ) -> Unit
) {
    var selectedStrategyType by remember(prefill) {
        mutableStateOf(prefill?.strategyType ?: StrategyType.TOUCH_AND_TURN_SCALPER)
    }
    var symbol by remember(prefill) { mutableStateOf(prefill?.symbol.orEmpty()) }
    var maxDollarsText by remember(prefill) {
        mutableStateOf(
            defaultMaxDollarsFor(prefill?.strategyType ?: StrategyType.TOUCH_AND_TURN_SCALPER).toString()
        )
    }
    var autoStartOnMarketOpen by remember(prefill) { mutableStateOf(false) }
    var resolving by remember(prefill) { mutableStateOf(false) }
    val prefillResolved = remember(prefill) { prefill?.toResolvedInstrument() }
    var candidates by remember(prefill) {
        mutableStateOf(prefillResolved?.let { listOf(it) } ?: emptyList())
    }
    var selectedResolved by remember(prefill) { mutableStateOf(prefillResolved) }
    var selectedMarketZoneId by remember(prefill) { mutableStateOf(prefill?.marketZoneId) }
    var marketSource by remember(prefill) {
        mutableStateOf(prefill?.marketSource ?: MarketSource.SYMBOL_INFERRED)
    }
    var userEditedMarket by remember(prefill) { mutableStateOf(prefill?.marketZoneId != null) }

    LaunchedEffect(symbol) {
        val trimmed = symbol.trim()
        if (trimmed.isBlank() || trimmed.length < 2) {
            resolving = false
            candidates = emptyList()
            selectedResolved = null
            selectedMarketZoneId = null
            return@LaunchedEffect
        }
        delay(400)
        resolving = true
        onResolveSymbol(trimmed) { result ->
            resolving = false
            result.onSuccess { resolution ->
                candidates = InstrumentListingCandidates.prepareForUi(resolution.candidates)
                selectedResolved = when {
                    candidates.size == 1 -> candidates.first()
                    else -> null
                }
                if (!userEditedMarket) {
                    when {
                        candidates.size == 1 ->
                            selectedResolved?.let { suggestion ->
                                selectedMarketZoneId = suggestion.marketZoneId
                                marketSource = suggestion.source
                            }
                        candidates.size > 1 -> selectedMarketZoneId = null
                    }
                }
                InstrumentResolveLog.uiReceived(
                    symbol = trimmed,
                    uiCount = candidates.size,
                    selected = selectedResolved?.let { InstrumentListingCandidates.listingLabel(it) }
                )
            }.onFailure { error ->
                candidates = emptyList()
                selectedResolved = null
                if (!userEditedMarket) selectedMarketZoneId = null
                InstrumentResolveLog.resolveFinished(
                    symbol = trimmed,
                    success = false,
                    rawCount = 0,
                    uiCount = 0,
                    listings = emptyList(),
                    error = error.message
                )
            }
        }
    }

    val resolved = selectedResolved
    val strategyTypeLocked = prefill?.strategyType != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Deploy strategy", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (strategyTypeLocked) {
                    Text(
                        StrategyCatalog.displayName(selectedStrategyType),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier.testTag("PrefilledStrategyType")
                    )
                    Text(
                        StrategyCatalog.description(selectedStrategyType),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    Text("Choose strategy", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    StrategyType.entries.forEach { type ->
                        StrategyTypePickerCard(
                            strategyType = type,
                            selected = selectedStrategyType == type,
                            onSelect = {
                                selectedStrategyType = type
                                maxDollarsText = defaultMaxDollarsFor(type).toString()
                            }
                        )
                    }
                }
                HorizontalDivider(color = TableHeaderBg)
                ConfigField(
                    label = "Symbol",
                    value = symbol,
                    onValueChange = {
                        symbol = it
                        userEditedMarket = false
                        selectedResolved = null
                    }
                )
                val resolvedCompanyName = resolved?.companyName?.takeIf { it.isNotBlank() }
                if (!resolving && resolvedCompanyName != null) {
                    Text(
                        text = resolvedCompanyName,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("ResolvedCompanyName")
                    )
                } else if (resolving && symbol.isNotBlank()) {
                    Text(
                        text = "Resolving company name…",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                if (!resolving && candidates.size > 1) {
                    InstrumentListingPicker(
                        candidates = candidates,
                        selected = selectedResolved,
                        onSelect = { picked ->
                            selectedResolved = picked
                            if (!userEditedMarket) {
                                selectedMarketZoneId = picked.marketZoneId
                                marketSource = picked.source
                            }
                        }
                    )
                }
                InstrumentResolutionPanel(
                    resolving = resolving,
                    resolved = resolved,
                    selectedMarketZoneId = selectedMarketZoneId,
                    persistedCompanyName = null,
                    persistedCurrencyCode = null,
                    canEditMarket = true,
                    onMarketSelected = { zoneId ->
                        userEditedMarket = true
                        selectedMarketZoneId = zoneId
                        marketSource = MarketSource.USER
                    }
                )
                ConfigField(
                    label = "Risk budget (\$)",
                    value = maxDollarsText,
                    onValueChange = { maxDollarsText = it },
                    enabled = selectedMarketZoneId != null
                )
                AutoStartOnMarketOpenField(
                    checked = autoStartOnMarketOpen,
                    enabled = selectedMarketZoneId != null,
                    onCheckedChange = { autoStartOnMarketOpen = it }
                )
            }
        },
        confirmButton = {
            val maxDollars = maxDollarsText.toIntOrNull() ?: 0
            val zoneId = selectedMarketZoneId
            val currency = when {
                zoneId == null -> "USD"
                marketSource == MarketSource.USER ->
                    DeploymentMarket.currencyForZone(zoneId)
                else -> resolved?.currencyCode ?: DeploymentMarket.currencyForZone(zoneId)
            }
            val companyName = resolved?.companyName?.takeIf { it.isNotBlank() }
            val listingChosen = candidates.isEmpty() || selectedResolved != null
            Button(
                onClick = {
                    if (zoneId != null) {
                        onCreate(
                            selectedStrategyType,
                            symbol.trim(),
                            zoneId,
                            currency,
                            marketSource,
                            companyName,
                            selectedResolved?.identity,
                            maxDollars,
                            autoStartOnMarketOpen
                        )
                    }
                },
                enabled = symbol.isNotBlank() && maxDollars > 0 && zoneId != null &&
                    listingChosen && !resolving,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("CreateStrategyDeploymentButton")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
internal fun InstrumentResolutionPanel(
    resolving: Boolean,
    resolved: ResolvedInstrument?,
    selectedMarketZoneId: String?,
    persistedCompanyName: String?,
    persistedCurrencyCode: String?,
    canEditMarket: Boolean,
    onMarketSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Market & currency", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        when {
            resolving -> Text("Looking up instrument…", fontSize = 12.sp, color = TextSecondary)
            resolved == null && selectedMarketZoneId == null && persistedCompanyName.isNullOrBlank() ->
                Text("Enter a symbol to resolve market and currency.", fontSize = 12.sp, color = TextSecondary)
            else -> {
                val zoneId = selectedMarketZoneId ?: resolved?.marketZoneId
                val currency = persistedCurrencyCode
                    ?: resolved?.currencyCode
                    ?: zoneId?.let { DeploymentMarket.currencyForZone(it) }
                    ?: "—"
                val session = zoneId?.let { DeploymentMarket.sessionForZone(it) }
                val marketLabel = session?.let { DeploymentMarket.sessionDisplayLabel(it) } ?: "—"
                val companyName = persistedCompanyName?.takeIf { it.isNotBlank() }
                    ?: resolved?.companyName?.takeIf { it.isNotBlank() }
                if (companyName != null) {
                    Text(
                        text = companyName,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp
                    )
                }
                Text(
                    text = resolved?.let { suggestion ->
                        val prefix = when (suggestion.source) {
                            MarketSource.IB -> "From IB"
                            MarketSource.SYMBOL_INFERRED -> "Estimated"
                            MarketSource.USER -> "Your selection"
                            MarketSource.LEGACY_INFERRED -> "Inferred"
                        }
                        "$prefix: ${suggestion.venueLabel}"
                    } ?: if (persistedCompanyName != null) {
                        "Saved market: $marketLabel · $currency"
                    } else {
                        "Select market below."
                    },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
                Text(
                    text = "Trading session: $marketLabel · $currency",
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RthMarketSessions.all.forEach { session ->
                val selected = selectedMarketZoneId == session.zoneId
                FilterChip(
                    selected = selected,
                    onClick = { if (canEditMarket) onMarketSelected(session.zoneId) },
                    enabled = canEditMarket,
                    label = {
                        Text(
                            DeploymentMarket.sessionDisplayLabel(session),
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandRed.copy(alpha = 0.35f),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
    }
}

@Composable
internal fun DeploymentMarketSection(
    deployment: StrategyDeployment,
    canEdit: Boolean,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdate: ((StrategyDeployment) -> StrategyDeployment) -> Unit
) {
    var ibSuggestion by remember(deployment.id) { mutableStateOf<ResolvedInstrument?>(null) }
    var resolving by remember { mutableStateOf(false) }

    LaunchedEffect(deployment.symbol, canEdit) {
        if (!canEdit) return@LaunchedEffect
        resolving = true
        onResolveSymbol(deployment.symbol) { result ->
            resolving = false
            result.onSuccess { resolution ->
                val savedKey = deployment.instrument?.dedupeKey()
                ibSuggestion = resolution.candidates.firstOrNull { candidate ->
                    savedKey != null && candidate.identity?.dedupeKey() == savedKey
                } ?: resolution.singleOrNull() ?: resolution.candidates.firstOrNull()
                val suggestion = ibSuggestion
                if (canEdit &&
                    suggestion?.companyName != null &&
                    deployment.companyName.isNullOrBlank()
                ) {
                    onUpdate { it.copy(companyName = suggestion.companyName) }
                }
            }
        }
    }

    val effectiveZone = DeploymentMarket.effectiveZoneId(deployment)
    val effectiveCurrency = DeploymentMarket.effectiveCurrencyCode(deployment)
    val session = DeploymentMarket.sessionForZone(effectiveZone)
    val mismatch = ibSuggestion?.let { it.marketZoneId != effectiveZone } == true

    InstrumentResolutionPanel(
        resolving = resolving && canEdit,
        resolved = ibSuggestion,
        selectedMarketZoneId = effectiveZone,
        persistedCompanyName = deployment.companyName,
        persistedCurrencyCode = effectiveCurrency,
        canEditMarket = canEdit,
        onMarketSelected = { zoneId ->
            onUpdate {
                it.copy(
                    marketZoneId = zoneId,
                    currencyCode = DeploymentMarket.currencyForZone(zoneId),
                    marketSource = MarketSource.USER
                )
            }
        }
    )
    if (mismatch && ibSuggestion != null) {
        Text(
            "IB suggests ${DeploymentMarket.sessionDisplayLabel(
                DeploymentMarket.sessionForZone(ibSuggestion!!.marketZoneId)
            )} (${ibSuggestion!!.venueLabel}). This deployment uses ${DeploymentMarket.sessionDisplayLabel(session)}.",
            fontSize = 12.sp,
            color = LossRed,
            lineHeight = 15.sp
        )
    }
    deployment.instrument?.let { identity ->
        Text(
            "Saved listing: ${identity.primaryExch ?: identity.exchange} · ${identity.currency}",
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 15.sp,
            modifier = Modifier.testTag("SavedInstrumentListing")
        )
    }
}

@Composable
internal fun StrategyTypePickerCard(
    strategyType: StrategyType,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) BrandRed else TableHeaderBg
    val backgroundColor = if (selected) BrandRed.copy(alpha = 0.12f) else DarkBackground
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("StrategyTypePicker-${strategyType.name}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                StrategyCatalog.displayName(strategyType),
                color = if (selected) Color.White else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = BrandRed, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(StrategyCatalog.description(strategyType), color = TextSecondary, fontSize = 12.sp)
    }
}
