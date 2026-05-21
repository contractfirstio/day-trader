package daytrader.presentation.positions

import daytrader.domain.Position
import daytrader.presentation.Formatters

object PositionUiMapper {
    fun toRowUi(position: Position): PositionRowUi = PositionRowUi(
        symbol = position.symbol,
        companyName = position.companyName,
        quantity = position.quantity,
        formattedAvgPrice = Formatters.currencyPlain(position.avgPrice),
        formattedMarketPrice = Formatters.currencyPlain(position.marketPrice),
        formattedMarketValue = Formatters.currency(position.marketValue),
        formattedDailyChange = Formatters.percent(position.dailyChangePct),
        formattedPnL = Formatters.currency(position.totalUnrealizedPnL, showSign = true),
        isPositiveDailyChange = position.dailyChangePct >= 0,
        isPositivePnL = position.totalUnrealizedPnL >= 0
    )
}
