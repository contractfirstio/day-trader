package daytrader.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class ProximityThresholdMode {
    PERCENT,
    ABSOLUTE
}

enum class WatchlistProximityStatus {
    NOT_SCANNED,
    CLEAR,
    NEAR,
    NO_DATA
}

data class WatchlistProximityEvaluation(
    val planId: String,
    val planLabel: String,
    val entryPrice: Double,
    val scannedPrice: Double,
    val distance: Double,
    val threshold: Double,
    val isNear: Boolean
)

object WatchlistEntryProximityEvaluator {
    fun thresholdDistance(plan: WatchlistTradePlan, entryPrice: Double): Double? {
        if (!plan.proximityAlertEnabled) return null
        val value = plan.proximityThresholdValue ?: return null
        if (value <= 0.0) return null
        return when (plan.proximityThresholdMode) {
            ProximityThresholdMode.PERCENT -> entryPrice * (value / 100.0)
            ProximityThresholdMode.ABSOLUTE -> value
        }
    }

    fun evaluatePlan(plan: WatchlistTradePlan, scannedPrice: Double): WatchlistProximityEvaluation? {
        if (plan.hasPlacedOrder) return null
        if (!plan.proximityAlertEnabled) return null
        val entry = plan.entryPrice ?: return null
        if (entry <= 0.0 || scannedPrice <= 0.0) return null
        val threshold = thresholdDistance(plan, entry) ?: return null
        val distance = abs(scannedPrice - entry)
        return WatchlistProximityEvaluation(
            planId = plan.id,
            planLabel = plan.label,
            entryPrice = entry,
            scannedPrice = scannedPrice,
            distance = distance,
            threshold = threshold,
            isNear = distance <= threshold
        )
    }

    fun evaluateEntry(entry: WatchlistEntry, scannedPrice: Double): List<WatchlistProximityEvaluation> =
        entry.tradePlans.mapNotNull { evaluatePlan(it, scannedPrice) }

    fun entryStatus(entry: WatchlistEntry, scannedPrice: Double?): WatchlistProximityStatus {
        if (scannedPrice == null || scannedPrice <= 0.0) {
            return if (entry.lastScannedAtEpochMs != null) WatchlistProximityStatus.NO_DATA else WatchlistProximityStatus.NOT_SCANNED
        }
        val evaluations = evaluateEntry(entry, scannedPrice)
        if (evaluations.isEmpty()) return WatchlistProximityStatus.CLEAR
        return if (evaluations.any { it.isNear }) WatchlistProximityStatus.NEAR else WatchlistProximityStatus.CLEAR
    }
}
