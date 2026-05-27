package daytrader.broker.emulator

/** How the emulator simulates Touch Turn entry limit fills before the bracket walk. */
enum class TouchTurnEntryScenario {
    /** Price starts away from entry and walks toward the limit over several ticks. */
    APPROACH_AND_FILL,
    /** Price drifts away; entry limit is never touched. */
    NEVER_FILL,
    /** Instant fill on bracket submit (legacy / fast tests). */
    IMMEDIATE
}

/**
 * Pre-entry price path while the entry limit is working.
 * Cleared once the entry order fills or the bracket is cancelled.
 */
internal class BracketEntryPending(
    val entryOrderId: Int,
    val entryPrice: Double,
    val openingBarClose: Double,
    /** True when entry is a buy limit (long); false for sell limit (short). */
    val isBuyEntry: Boolean,
    val scenario: TouchTurnEntryScenario,
    val range: Double,
    var ticksElapsed: Int = 0
)
