package daytrader.data

expect fun emulatorRequireCloseConfirmationEnv(): String?

internal fun emulatorRequireCloseConfirmation(): Boolean =
    when (emulatorRequireCloseConfirmationEnv()?.trim()?.lowercase()) {
        "true", "1", "on", "yes" -> true
        else -> false
    }
