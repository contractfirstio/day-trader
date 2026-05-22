package daytrader.presentation.positions

import daytrader.domain.Position
import daytrader.presentation.Formatters

object PositionUiMapper {
    fun toRowUi(position: Position): PositionRowUi = PositionRowUi(
        companyName = position.companyName,
        symbol = position.symbol,
        formattedPnL = Formatters.money(position.totalUnrealizedPnL, position.currency, showSign = true),
        isPositivePnL = position.totalUnrealizedPnL >= 0
    )
}
