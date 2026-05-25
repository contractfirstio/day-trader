package daytrader.broker.emulator

actual fun emulatorFirstCandleCloseSecEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_CANDLE_CLOSE_SEC")
