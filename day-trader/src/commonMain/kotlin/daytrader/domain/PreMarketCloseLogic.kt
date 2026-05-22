package daytrader.domain

object PreMarketCloseLogic {
    fun positionCloseDeadlineEpochMillis(
        marketCloseEpochMillis: Long,
        minutesBeforeClose: Int
    ): Long = marketCloseEpochMillis - minutesBeforeClose * 60_000L

    /**
     * True from [minutesBeforeClose] before RTH close until the close instant (exclusive of after-hours).
     */
    fun isWithinPreCloseExitWindow(
        nowEpochMillis: Long,
        marketCloseEpochMillis: Long,
        minutesBeforeClose: Int
    ): Boolean {
        val deadline = positionCloseDeadlineEpochMillis(marketCloseEpochMillis, minutesBeforeClose)
        return nowEpochMillis >= deadline && nowEpochMillis < marketCloseEpochMillis
    }
}
