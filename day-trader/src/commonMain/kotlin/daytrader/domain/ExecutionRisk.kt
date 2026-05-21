package daytrader.domain

import kotlin.math.abs

data class ExecutionRiskReward(
    val riskDollars: Double?,
    val upsideDollars: Double?,
    val unrealizedPnL: Double?,
    val riskPercentOfMax: Double?
)

fun ActiveExecution.riskReward(
    maxDollars: Int,
    rewardMultiple: Double
): ExecutionRiskReward {
    if (state != ExecutionState.FILLED) {
        return ExecutionRiskReward(null, null, null, null)
    }
    val entry = entryPrice ?: return ExecutionRiskReward(null, null, null, null)
    val qty = quantity
    if (qty <= 0) return ExecutionRiskReward(null, null, null, null)

    val stop = stopPrice
    val riskDollars = stop?.let {
        abs(entry - it) * qty
    }

    val target = targetPrice ?: stop?.let { stopPrice ->
        val r = abs(entry - stopPrice)
        val multiplier = rewardMultiple
        when (side) {
            TradeSide.LONG -> entry + (r * multiplier)
            TradeSide.SHORT -> entry - (r * multiplier)
        }
    }

    val upsideDollars = target?.let {
        abs(it - entry) * qty
    }

    val unrealizedPnL = marketPrice?.let { market ->
        when (side) {
            TradeSide.LONG -> (market - entry) * qty
            TradeSide.SHORT -> (entry - market) * qty
        }
    }

    val riskPercentOfMax = riskDollars?.takeIf { maxDollars > 0 }?.let {
        (it / maxDollars) * 100.0
    }

    return ExecutionRiskReward(
        riskDollars = riskDollars,
        upsideDollars = upsideDollars,
        unrealizedPnL = unrealizedPnL,
        riskPercentOfMax = riskPercentOfMax
    )
}

fun ActiveExecution.positionLabel(symbol: String): String = when (state) {
    ExecutionState.FLAT -> "Flat"
    ExecutionState.WORKING -> "${side.label()} $quantity $symbol (working)"
    ExecutionState.FILLED -> {
        val entry = entryPrice?.let { "%.2f".format(it) } ?: "—"
        "${side.label()} $quantity $symbol @ $entry"
    }
}
