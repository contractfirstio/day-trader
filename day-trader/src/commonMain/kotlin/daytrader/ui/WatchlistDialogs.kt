package daytrader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.*
import kotlinx.coroutines.delay
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
internal fun AddWatchlistEntryDialog(
    onDismiss: () -> Unit,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onAdd: (
        String,
        String,
        String,
        String?,
        InstrumentIdentity?,
        String?
    ) -> Unit
) {
    var symbol by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<ResolvedInstrument>>(emptyList()) }
    var selectedResolved by remember { mutableStateOf<ResolvedInstrument?>(null) }
    var selectedMarketZoneId by remember { mutableStateOf<String?>(null) }
    var marketSource by remember { mutableStateOf(MarketSource.SYMBOL_INFERRED) }
    var userEditedMarket by remember { mutableStateOf(false) }

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
            }.onFailure {
                candidates = emptyList()
                selectedResolved = null
                if (!userEditedMarket) selectedMarketZoneId = null
            }
        }
    }

    val resolved = selectedResolved

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Add to watchlist", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        modifier = Modifier.testTag("WatchlistResolvedCompanyName")
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
                    label = "Notes (optional)",
                    value = notes,
                    onValueChange = { notes = it },
                    enabled = selectedMarketZoneId != null
                )
            }
        },
        confirmButton = {
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
                        onAdd(
                            symbol.trim(),
                            zoneId,
                            currency,
                            companyName,
                            selectedResolved?.identity,
                            notes.trim().ifBlank { null }
                        )
                    }
                },
                enabled = symbol.isNotBlank() && zoneId != null && listingChosen && !resolving,
                modifier = Modifier.testTag("AddWatchlistEntryConfirmButton")
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
