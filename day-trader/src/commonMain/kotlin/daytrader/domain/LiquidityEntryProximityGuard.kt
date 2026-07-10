package daytrader.domain

/**
 * Skip liquidity upsize when price has reached the entry band — resizing would disrupt the working order.
 */
object LiquidityEntryProximityGuard {
    fun shouldSkipResize(entryTouchable: Boolean?): Boolean = entryTouchable == true
}
