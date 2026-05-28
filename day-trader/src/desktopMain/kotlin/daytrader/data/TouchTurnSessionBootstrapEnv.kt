package daytrader.data

actual fun emulatorRequireCloseConfirmationEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_REQUIRE_CLOSE_CONFIRMATION")
