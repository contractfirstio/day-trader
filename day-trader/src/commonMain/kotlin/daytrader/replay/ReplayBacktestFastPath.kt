package daytrader.replay

/**
 * Headless backtest replay runs virtual time as fast as the CPU allows — no UI pacing or
 * poll sleeps. Fill accuracy is unchanged: every captured tick still reaches the emulator in order.
 */
object ReplayBacktestFastPath {
    /** Yield-only engine drain per quote (replaces 4× 1ms wall-clock sleeps). */
    const val ENGINE_DRAIN_YIELD_ROUNDS = 32

    const val BOOTSTRAP_MAX_YIELDS = 1_600
    const val STOP_MAX_YIELDS = 800

    /** Spins for [daytrader.engine.TouchTurnEnginePort.drainUntilIdle]. */
    const val ENGINE_IDLE_MAX_SPINS = 512

    /** Headless backtest: wait for emulator bracket ack after async gateway queueing. */
    const val BRACKET_ACK_MAX_YIELDS = 64
}
