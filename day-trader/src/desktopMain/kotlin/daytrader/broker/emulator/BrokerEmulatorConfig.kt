package daytrader.broker.emulator

actual fun emulatorFirstCandleCloseSecEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_CANDLE_CLOSE_SEC")

actual fun emulatorFirstCandleColorEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_FIRST_CANDLE_COLOR")

actual fun emulatorFirstCandleAlternateEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_FIRST_CANDLE_ALTERNATE")

actual fun emulatorEntryFillImmediatelyEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_ENTRY_FILL_IMMEDIATELY")

actual fun emulatorEntryNeverFillProbEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_ENTRY_NEVER_FILL_PROB")

actual fun emulatorTouchTurnScenarioEnv(): String? =
    System.getenv("DAY_TRADER_EMULATOR_TOUCH_TURN_SCENARIO")
