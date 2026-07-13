package daytrader.domain

/** Identifies a working bracket that can be resized at the broker. */
sealed interface BracketAmendTarget {
    val amendKey: String

    data class Deployment(val deploymentId: String) : BracketAmendTarget {
        override val amendKey: String = "deployment:$deploymentId"
    }

    data class WatchlistPlan(
        val watchlistId: String,
        val entryId: String,
        val planId: String,
    ) : BracketAmendTarget {
        override val amendKey: String = "watchlist:$watchlistId:$entryId:$planId"
    }

    /** Working bracket located only from open orders (e.g. session stopped, no watchlist link). */
    data class OpenBracket(
        val symbolKey: String,
        val parentOrderId: Int,
    ) : BracketAmendTarget {
        override val amendKey: String = "open-bracket:$symbolKey:$parentOrderId"
    }
}
