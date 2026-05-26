package daytrader.presentation.strategies

import daytrader.domain.OhlcBar

data class LivePriceChartUiState(
    val symbol: String,
    val currencyCode: String,
    val candles: List<LiveMinuteCandleUi>
)

data class LiveMinuteCandleUi(
    val bucketStartMillis: Long,
    val bar: OhlcBar,
    val isForming: Boolean
)
