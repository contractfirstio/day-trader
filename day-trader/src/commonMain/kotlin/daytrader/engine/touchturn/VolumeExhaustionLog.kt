package daytrader.engine.touchturn

import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.gateway.BrokerId

/** Granular Touch Turn volume-exhaustion / buffer-zone state transitions. */
object VolumeExhaustionLog {
    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_TOUCH_TURN_CANDLE_LOGS")
            ?.equals("false", ignoreCase = true) != true

    fun signalDetected(instanceId: String, symbol: String, detail: String) {
        line("Signal Detected instance=$instanceId symbol=$symbol $detail")
    }

    fun filterPassed(instanceId: String, symbol: String, detail: String) {
        line("Filter Passed instance=$instanceId symbol=$symbol $detail")
    }

    fun filterAborted(instanceId: String, symbol: String, reason: String) {
        line("Filter Aborted instance=$instanceId symbol=$symbol reason=$reason")
    }

    fun bufferActive(instanceId: String, symbol: String, entryOrderId: Int?, threshold: Double) {
        line(
            "Buffer Active instance=$instanceId symbol=$symbol entryOrderId=${entryOrderId ?: "n/a"} " +
                "volumeThreshold=$threshold"
        )
    }

    fun orderCancelled(instanceId: String, symbol: String, entryOrderId: Int, accumulatedVolume: Double) {
        line(
            "Order Cancelled instance=$instanceId symbol=$symbol entryOrderId=$entryOrderId " +
                "accumulatedVolume=$accumulatedVolume"
        )
    }

    fun bufferCompleted(instanceId: String, symbol: String, accumulatedVolume: Double) {
        line("Buffer Completed instance=$instanceId symbol=$symbol accumulatedVolume=$accumulatedVolume")
    }

    fun executionAttempt(brokerId: BrokerId, symbol: String? = null, action: String, orderId: Int? = null) {
        if (brokerId == BrokerId.INTERACTIVE_BROKERS) return
        line("Execution mock broker=${brokerId.name} symbol=${symbol ?: "-"} orderId=${orderId ?: "-"} $action")
    }

    fun executionResult(brokerId: BrokerId, symbol: String? = null, detail: String, orderId: Int? = null) {
        if (brokerId == BrokerId.INTERACTIVE_BROKERS) return
        line("Execution result broker=${brokerId.name} symbol=${symbol ?: orderId.toString()} $detail")
    }

    private fun line(message: String) {
        if (!enabled) return
        TimestampedConsoleLog.line("TouchTurnVolume", message)
    }
}
