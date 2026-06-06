package daytrader.presentation.strategies

import daytrader.presentation.Formatters
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.LiveQuote
import kotlin.math.roundToInt

/**
 * Live bid/ask/last with distance to entry for the chart quote strip.
 * Fill distance uses the same side as bracket placement: long → ask vs entry, short → bid vs entry.
 */
data class TouchTurnQuoteStripUi(
    val bid: Double?,
    val ask: Double?,
    val last: Double?,
    val currencyCode: String,
    val entryPrice: Double?,
    val entrySide: TouchTurnTradeSide?,
    /** Smallest fill gap observed this session (long → ask, short → bid). */
    val closestApproach: TouchTurnClosestApproachUi? = null
) {
    /** Positive = not yet fillable; zero/negative = ask/bid has crossed entry. */
    val fillGap: Double? get() = TouchTurnQuoteStripFormat.fillGap(
        entryPrice = entryPrice,
        entrySide = entrySide,
        bid = bid,
        ask = ask
    )

    val fillGapLabel: String? get() = fillGap?.let {
        TouchTurnQuoteStripFormat.gapLabel(it, currencyCode)
    }

    val isFillable: Boolean get() = fillGap?.let { it <= 0.0 } == true
}

object TouchTurnQuoteStripUiMapper {
    fun from(
        quote: LiveQuote?,
        currencyCode: String,
        bracketSetup: TouchTurnBracketSetup?,
        levels: List<TouchTurnOrderLevelUi> = emptyList(),
        closestApproach: TouchTurnClosestApproachUi? = null
    ): TouchTurnQuoteStripUi? {
        val entryPrice = bracketSetup?.entry
            ?: levels.firstOrNull { it.kind == TouchTurnOrderLevelKind.ENTRY }?.price
        val hasQuote = quote?.bid?.let { it > 0.0 } == true ||
            quote?.ask?.let { it > 0.0 } == true ||
            quote?.last?.let { it > 0.0 } == true
        if (!hasQuote && entryPrice == null) return null
        return TouchTurnQuoteStripUi(
            bid = quote?.bid?.takeIf { it > 0.0 },
            ask = quote?.ask?.takeIf { it > 0.0 },
            last = quote?.last?.takeIf { it > 0.0 },
            currencyCode = currencyCode,
            entryPrice = entryPrice,
            entrySide = bracketSetup?.side,
            closestApproach = closestApproach
        )
    }

    fun fillPriceForGap(
        entrySide: TouchTurnTradeSide?,
        bid: Double?,
        ask: Double?
    ): Double? = when (entrySide) {
        TouchTurnTradeSide.LONG -> ask?.takeIf { it > 0.0 }
        TouchTurnTradeSide.SHORT -> bid?.takeIf { it > 0.0 }
        null -> ask?.takeIf { it > 0.0 } ?: bid?.takeIf { it > 0.0 }
    }
}

object TouchTurnQuoteStripFormat {
    fun fillGap(
        entryPrice: Double?,
        entrySide: TouchTurnTradeSide?,
        bid: Double?,
        ask: Double?
    ): Double? {
        val entry = entryPrice?.takeIf { it > 0.0 } ?: return null
        return when (entrySide) {
            TouchTurnTradeSide.LONG -> ask?.let { it - entry }
            TouchTurnTradeSide.SHORT -> bid?.let { entry - it }
            null -> ask?.let { it - entry } ?: bid?.let { entry - it }
        }
    }

    fun gapLabel(gap: Double, currencyCode: String): String {
        if (kotlin.math.abs(gap) < 0.000_05) return "at entry"
        val sign = if (gap > 0) "+" else "-"
        val magnitude = kotlin.math.abs(gap)
        return when (Formatters.normalizeDisplayCurrency(currencyCode)) {
            "GBP" -> "$sign${(magnitude * 100).roundToInt()}p"
            else -> "$sign${String.format("%.2f", magnitude)}"
        }
    }
}
