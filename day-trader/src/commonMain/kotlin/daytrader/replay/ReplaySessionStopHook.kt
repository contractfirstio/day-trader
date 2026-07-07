package daytrader.replay

/**
 * Replay session stop: publish quotes, synchronously flatten on the emulator, and drain inbound
 * snapshots before [daytrader.engine.TouchTurnEngine] reads broker state.
 */
fun interface ReplaySessionStopHook {
    suspend fun flattenAndDrain(symbol: String)
}
