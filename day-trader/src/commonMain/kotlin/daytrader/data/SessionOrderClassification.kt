package daytrader.data

import daytrader.gateway.WorkingOrder

/** Classifies working orders for session-stop cleanup (open deadline retains stop-loss legs). */
object SessionOrderClassification {
    fun isProtectiveStopLoss(order: WorkingOrder): Boolean {
        val type = order.orderType.uppercase()
        return type == "STP" ||
            type.startsWith("STP ") ||
            type == "TRAIL" ||
            order.isTrailAdjustment
    }
}
