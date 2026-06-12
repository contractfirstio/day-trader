package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.Formatters
import daytrader.presentation.liquidity.LiquidityAllocatorRowUi
import daytrader.presentation.liquidity.LiquidityAllocatorViewModel
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
fun LiquidityAllocatorScreen(viewModel: LiquidityAllocatorViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(
            "Liquidity allocator",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Text(
            "Distribute unused no-trade budget to open entry orders (same currency only).",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.currencyOptions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.currencyOptions.forEach { option ->
                    FilterChip(
                        selected = option.currencyCode == uiState.selectedCurrency,
                        onClick = { viewModel.onCurrencySelected(option.currencyCode) },
                        label = {
                            Text(
                                "${option.currencyCode} · ${Formatters.maxAtRisk(option.available)}",
                                fontSize = 12.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GainGreen.copy(alpha = 0.2f),
                            selectedLabelColor = GainGreen
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        BucketSummaryCard(
            currencyCode = uiState.selectedCurrency,
            available = uiState.availableLiquidity,
            allocated = uiState.allocatedPending,
            remaining = uiState.remainingLiquidity,
            creditCount = uiState.creditCount,
            onDistributeEvenly = viewModel::distributeEvenly,
            onApplyAll = viewModel::applyAll,
            canApply = uiState.allocatedPending > 0 && uiState.rows.isNotEmpty()
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.rows.isEmpty()) {
            Text(
                "No open Touch Turn entry orders in ${uiState.selectedCurrency}.",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.rows, key = { it.deploymentId }) { row ->
                    AllocatorRowCard(
                        row = row,
                        onAllocationChanged = { dollars ->
                            viewModel.onAllocationChanged(row.deploymentId, dollars)
                        },
                        onApply = { viewModel.applyRow(row.deploymentId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketSummaryCard(
    currencyCode: String,
    available: Int,
    allocated: Int,
    remaining: Int,
    creditCount: Int,
    onDistributeEvenly: () -> Unit,
    onApplyAll: () -> Unit,
    canApply: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryMetric("Available", Formatters.maxAtRisk(available), currencyCode)
            SummaryMetric("Allocated", Formatters.maxAtRisk(allocated), currencyCode)
            SummaryMetric("Remaining", Formatters.maxAtRisk(remaining), currencyCode)
        }
        Text(
            "$creditCount no-trade credits today",
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onDistributeEvenly,
                enabled = available > 0,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = Color.White)
            ) {
                Text("Distribute evenly", fontSize = 12.sp)
            }
            Button(
                onClick = onApplyAll,
                enabled = canApply,
                colors = ButtonDefaults.buttonColors(containerColor = GainGreen, contentColor = Color.Black)
            ) {
                Text("Apply allocations", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, currencyCode: String) {
    Column {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Text("$value $currencyCode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun AllocatorRowCard(
    row: LiquidityAllocatorRowUi,
    onAllocationChanged: (Int) -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    row.symbol,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                row.companyName?.let {
                    Text(it, fontSize = 11.sp, color = TextSecondary)
                }
            }
            Text(
                "${row.sideLabel} @ ${row.entryPriceLabel}",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricChip("To entry", row.distanceToEntryLabel)
            MetricChip("Win rate", "${row.winRateLabel} (n=${row.winRateSampleSize})")
            row.entryTouchable?.let { touchable ->
                MetricChip("Touchable", if (touchable) "Yes" else "No")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Qty ${row.currentQuantity} → ${row.previewQuantity} · ${row.previewNotionalLabel} · risk ${row.previewRiskAtStopLabel}",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = if (row.allocationDollars == 0) "" else row.allocationDollars.toString(),
                onValueChange = { text ->
                    val parsed = text.filter { it.isDigit() }.toIntOrNull() ?: 0
                    onAllocationChanged(parsed)
                },
                label = { Text("Add ${row.currencyCode}", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = GainGreen,
                    unfocusedBorderColor = TextSecondary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (row.isApplying) {
                CircularProgressIndicator(
                    modifier = Modifier.width(36.dp).height(36.dp),
                    color = GainGreen,
                    strokeWidth = 2.dp
                )
            } else {
                Button(
                    onClick = onApply,
                    enabled = row.allocationDollars > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = GainGreen, contentColor = Color.Black)
                ) {
                    Text("Apply", fontSize = 12.sp)
                }
            }
        }

        row.applyError?.let { error ->
            Text(error, fontSize = 11.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = TextSecondary)
        Text(value, fontSize = 12.sp, color = Color.White)
    }
}
