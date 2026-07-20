package daytrader.domain

/**
 * Full-session 5-minute bar accumulation for analysis — independent of the 5m confirmation toggle.
 * Bars are deduped by IB bar [OhlcBar.time] and appended in arrival order.
 */
object SessionFiveMinuteBarsLogic {
    fun appendClosedBars(existing: List<OhlcBar>, incoming: List<OhlcBar>): List<OhlcBar> {
        if (incoming.isEmpty()) return existing
        val seen = existing.mapNotNullTo(mutableSetOf()) { it.time }
        val appended = ArrayList<OhlcBar>(existing.size + incoming.size)
        appended.addAll(existing)
        for (bar in incoming) {
            val time = bar.time ?: continue
            if (time in seen) continue
            seen += time
            appended += bar
        }
        return if (appended.size == existing.size) existing else appended
    }
}
