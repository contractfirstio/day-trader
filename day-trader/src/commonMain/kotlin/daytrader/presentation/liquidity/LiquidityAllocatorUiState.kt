package daytrader.presentation.liquidity

import daytrader.domain.TouchTurnBracketOrderIds

data class LiquidityAllocatorUiState(
    val sessionDate: String = "",
    val selectedCurrency: String = "USD",
    val currencyOptions: List<LiquidityCurrencyOptionUi> = emptyList(),
    val availableLiquidity: Int = 0,
    val committedNotional: Int = 0,
    val remainingLiquidity: Int = 0,
    val creditCount: Int = 0,
    val canClearLiquidity: Boolean = false,
    val rows: List<LiquidityAllocatorRowUi> = emptyList(),
    val lastUpdatedEpochMs: Long = 0L,
    val globalMessage: String? = null
)

data class LiquidityCurrencyOptionUi(
    val currencyCode: String,
    val available: Int
)

data class LiquidityAllocatorRowUi(
    val deploymentId: String,
    val sessionId: String,
    val symbol: String,
    val companyName: String?,
    val currencyCode: String,
    val sideLabel: String,
    val entryPrice: Double,
    val entryPriceLabel: String,
    val currentQuantity: Int,
    val allocationAdditionalQty: Int,
    val orderSizeIncrement: Int,
    val allocationStepLabel: String,
    val committedNotional: Int,
    val committedNotionalLabel: String,
    val previewQuantity: Int,
    val previewNotionalLabel: String,
    val previewRiskAtStopLabel: String,
    val distanceToEntryLabel: String,
    val distanceToEntry: Double?,
    val entryTouchable: Boolean?,
    val winRateLabel: String,
    val winRateSampleSize: Int,
    val winDays: Int,
    val lossDays: Int,
    val bracketOrderIds: TouchTurnBracketOrderIds?,
    val isApplying: Boolean,
    val applyError: String?
)
