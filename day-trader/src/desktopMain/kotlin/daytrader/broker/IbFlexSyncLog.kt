package daytrader.broker

import daytrader.diagnostics.TimestampedConsoleLog

internal object IbFlexSyncLog {
    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_IB_FLEX_LOGS")?.equals("false", ignoreCase = true) != true

    fun info(message: String) {
        if (enabled) TimestampedConsoleLog.line("FlexSync", message)
    }

    fun error(message: String) {
        TimestampedConsoleLog.line("FlexSync", "ERROR $message")
    }
}
