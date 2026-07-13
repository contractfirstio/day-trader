package daytrader.domain

import daytrader.gateway.WorkingOrder

data class WatchlistPlanOrderContext(
    val watchlistId: String,
    val entry: WatchlistEntry,
    val plan: WatchlistTradePlan,
)

object WatchlistPlanOrderLinks {
    fun planLabelForOrder(orderId: Int, watchlists: List<Watchlist>): String? =
        planContextForOrder(orderId, watchlists)?.let { context ->
            "${context.entry.symbol} · ${context.plan.label}"
        }

    fun planContextForOrder(orderId: Int, watchlists: List<Watchlist>): WatchlistPlanOrderContext? =
        watchlists.asSequence()
            .flatMap { watchlist ->
                watchlist.entries.asSequence().flatMap { entry ->
                    entry.tradePlans.asSequence().map { plan ->
                        WatchlistPlanOrderContext(
                            watchlistId = watchlist.id,
                            entry = entry,
                            plan = plan,
                        )
                    }
                }
            }
            .firstOrNull { context -> orderId in context.plan.placedOrderIds }

    fun planContextForOrderGroup(
        groupOrders: List<WorkingOrder>,
        watchlists: List<Watchlist>,
    ): WatchlistPlanOrderContext? {
        if (watchlists.isEmpty() || groupOrders.isEmpty()) return null
        val entryOrderId = groupOrders.firstOrNull { it.parentOrderId == 0 }?.orderId ?: return null
        val groupOrderIds = groupOrders.map { it.orderId }.toSet()
        return watchlists.asSequence()
            .flatMap { watchlist ->
                watchlist.entries.asSequence().flatMap { entry ->
                    entry.tradePlans.asSequence().map { plan ->
                        WatchlistPlanOrderContext(
                            watchlistId = watchlist.id,
                            entry = entry,
                            plan = plan,
                        )
                    }
                }
            }
            .firstOrNull { context ->
                val plan = context.plan
                plan.hasPlacedOrder &&
                    plan.placedOrderIds.isNotEmpty() &&
                    (entryOrderId in plan.placedOrderIds || plan.placedOrderIds.any { it in groupOrderIds })
            }
    }

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
