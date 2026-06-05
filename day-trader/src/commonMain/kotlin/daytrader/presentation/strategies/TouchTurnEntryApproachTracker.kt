package daytrader.presentation.strategies

/**
 * Tracks the closest fill-side price to entry for the current Touch Turn session.
 * Long → minimum (ask − entry); short → minimum (entry − bid).
 */
class TouchTurnEntryApproachTracker {
    private var boundSessionId: String? = null
    private var minFillGap: Double? = null
    private var fillPriceAtMinGap: Double? = null

    fun bindSession(sessionId: String?) {
        if (sessionId == null) return
        if (boundSessionId != sessionId) {
            boundSessionId = sessionId
            clear()
        }
    }

    fun clear() {
        minFillGap = null
        fillPriceAtMinGap = null
    }

    fun record(fillGap: Double, fillPrice: Double) {
        if (fillPrice <= 0.0) return
        if (minFillGap == null || fillGap < minFillGap!!) {
            minFillGap = fillGap
            fillPriceAtMinGap = fillPrice
        }
    }

    fun snapshot(): TouchTurnClosestApproachUi? {
        val gap = minFillGap ?: return null
        val price = fillPriceAtMinGap ?: return null
        return TouchTurnClosestApproachUi(gap = gap, fillPrice = price)
    }
}

data class TouchTurnClosestApproachUi(
    val gap: Double,
    val fillPrice: Double
) {
    fun gapLabel(currencyCode: String): String = TouchTurnQuoteStripFormat.gapLabel(gap, currencyCode)
}
