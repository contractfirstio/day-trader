package daytrader.presentation.positions

import daytrader.domain.Position
import daytrader.presentation.Formatters

object PositionUiMapper {
    fun toRowUi(position: Position): PositionRowUi = PositionRowUi(
        companyName = position.companyName,
        symbol = position.symbol,
        formattedPnL = Formatters.currency(position.totalUnrealizedPnL, showSign = true),
        isPositivePnL = position.totalUnrealizedPnL >= 0
    )
}
