package daytrader.domain

import daytrader.gateway.WorkingOrder

object WatchlistPlanOrderLinks {
    fun planLabelForOrder(orderId: Int, watchlists: List<Watchlist>): String? =
        watchlists.asSequence()
            .flatMap { watchlist -> watchlist.entries.asSequence() }
            .flatMap { entry ->
                entry.tradePlans.asSequence().map { plan -> entry to plan }
            }
            .firstOrNull { (_, plan) -> orderId in plan.placedOrderIds }
            ?.let { (entry, plan) -> "${entry.symbol} · ${plan.label}" }

    fun enrichWithPlanLabels(
        orders: List<WorkingOrder>,
        watchlists: List<Watchlist>
    ): Map<Int, String> {
        if (watchlists.isEmpty()) return emptyMap()
        return orders.mapNotNull { order ->
            planLabelForOrder(order.orderId, watchlists)?.let { order.orderId to it }
        }.toMap()
    }
}
