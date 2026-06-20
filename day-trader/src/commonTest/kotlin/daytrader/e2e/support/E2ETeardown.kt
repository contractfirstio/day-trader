package daytrader.e2e.support

/** Stops ViewModel collectors, engine, and broker hooks — safe to call from test `finally`. */
fun E2EStrategiesViewModelHarness?.closeE2EHarness() {
    this?.runCatching { close() }
}

/** Stops emulator adapter + gateway — safe to call from test `finally`. */
fun EmulatorModeTestHarness?.shutdownEmulatorHarness() {
    this?.runCatching { shutdown() }
}

/** Stops IB gateway wiring — safe to call from test `finally`. */
fun IbModeTestHarness?.shutdownIbHarness() {
    this?.runCatching { shutdown() }
}

/** Stops a Touch Turn engine command loop — safe to call from test `finally`. */
fun daytrader.engine.TouchTurnEnginePort?.shutdownEngine() {
    this?.runCatching { shutdown() }
}
