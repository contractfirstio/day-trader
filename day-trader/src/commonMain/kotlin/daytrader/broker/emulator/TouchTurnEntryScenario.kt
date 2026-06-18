package daytrader.broker.emulator

/** How the emulator simulates Touch Turn entry fills before the bracket walk. */
enum class TouchTurnEntryScenario {
    /** Price starts away from entry and walks toward the trigger level over several ticks. */
    APPROACH_AND_FILL,
    /** Price drifts away; entry order is never triggered. */
    NEVER_FILL,
    /** Instant fill on bracket submit (legacy / fast tests). */
    IMMEDIATE
}

/**
 * Pre-entry price path while the entry order is working.
 * Cleared once the entry order fills or the bracket is cancelled.
 */
internal class BracketEntryPending(
    val entryOrderId: Int,
    val entryPrice: Double,
    val openingBarClose: Double,
    /** True when entry is a buy (long); false for sell (short). */
    val isBuyEntry: Boolean,
    /** True for invert stop entry; false for reversal limit entry. */
    val isStopEntry: Boolean,
    val scenario: TouchTurnEntryScenario,
    val range: Double,
    var ticksElapsed: Int = 0
)
