package daytrader.broker.emulator

actual fun emulatorFirstCandleCloseSecEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_CANDLE_CLOSE_SEC")

actual fun emulatorFirstCandleColorEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_FIRST_CANDLE_COLOR")

actual fun emulatorFirstCandleAlternateEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_FIRST_CANDLE_ALTERNATE")
