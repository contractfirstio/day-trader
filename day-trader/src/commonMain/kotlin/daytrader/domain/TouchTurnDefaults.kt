package daytrader.domain

object TouchTurnDefaults {
    const val ADR_LOOKBACK_DAYS = 14
    const val ATR_LOOKBACK_PERIODS = 14
    /** Daily bars used for ProReal-style daily ATR(14) liquidity threshold. */
    const val DAILY_ATR_LOOKBACK_PERIODS = 14
    const val VOLUME_SMA_PERIODS = 20
    /**
     * IB [reqHistoricalData] duration for Touch Turn 15m history. Needs ~20 prior RTH session
     * opening bars plus today's bar and an ATR window (~35+ trading days); 1 M is often too short.
     */
    const val TOUCH_TURN_15M_HISTORY_DURATION = "2 M"
    /** Shallow 15m history when only today's opening bar is needed (liquidity/volume rules off). */
    const val TOUCH_TURN_OPENING_BAR_HISTORY_DURATION = "5 D"
    /**
     * Composite Touch Turn bootstrap (15m + optional daily). Slightly above IB historical timeouts (45s)
     * so IB can return a leg-specific error before the gateway gives up.
     */
    const val SIGNAL_CONTEXT_REQUEST_TIMEOUT_MS = 50_000L
    /** Liquidity when first 15m range is at least this fraction of 14-period ATR. */
    const val ATR_LIQUIDITY_RATIO = 0.25
    /** Opening-bar volume above this multiple of the 20 prior session-open volume SMA aborts entry. */
    const val VOLUME_EXHAUSTION_RATIO = 1.5
    /** Post-entry observation window before resting bracket is left working unchecked. */
    const val VOLUME_BUFFER_OBSERVATION_MS = 60_000L
    /** Green (short) liquidity bar: take-profit distance as fraction of bar range. */
    const val TAKE_PROFIT_FIB_RATIO_GREEN = 0.382
    /** Red (long) liquidity bar: take-profit distance as fraction of bar range. */
    const val TAKE_PROFIT_FIB_RATIO_RED = 0.382
    /**
     * Stop distance = entry-to-target distance ÷ this ratio (reward:risk).
     * Default 2.0 → stop at half the take-profit distance (2:1 TP:SL).
     */
    const val TAKE_PROFIT_TO_STOP_LOSS_RATIO = 2.0
    /** Max time after 15m bar close to pass close confirmation and place entry orders. */
    const val CLOSE_CONFIRMATION_AFTER_CLOSE_MS = 60_000L
    /** Wait after 15m bar end before trusting IB historical refetch (bar-not-final race). */
    const val CLOSED_BAR_REFETCH_SETTLE_MS = 3_000L
    /** Long: skip entry when ask is more than this fraction of bar range below entry (and vice versa for short). */
    const val ENTRY_TOUCH_BUFFER_RATIO_OF_RANGE = 0.05
    /** Nudge entry limit inward from bar extreme: long up from low, short down from high (fraction of bar range). */
    const val ENTRY_INWARD_OFFSET_RATIO_OF_RANGE = 0.02
    /** Paper / emulator / replay: entry at bar extreme (no inward nudge). */
    const val ENTRY_INWARD_OFFSET_RATIO_OF_RANGE_SIMULATED = 0.0
    /** Max |bar.close − liveMid| as a fraction of bar range before hybrid mode rejects the setup. */
    const val BAR_LIVE_DIVERGENCE_MAX_RATIO_OF_RANGE = 0.25
    /** For short setups (green liquidity candle), require close in the lower X of range. */
    const val CLOSE_POSITION_SHORT_MAX = 0.45
    /** For long setups (red liquidity candle), require close in the upper X of range. */
    const val CLOSE_POSITION_LONG_MIN = 0.55
    /** Maximum minutes after RTH open before auto-stop when [openDeadline] is enabled. */
    const val STOP_AFTER_OPEN_MINUTES = 90
    /** Favorable move (fraction of entry→take-profit) before stop converts to trailing. */
    const val TRAILING_STOP_TRIGGER_FRACTION_OF_ENTRY_TO_TP = 0.5
    /** Cushion below entry (long) / above entry (short) when trailing arms, as fraction of bar range. 0 = at entry. */
    const val TRAILING_STOP_ARM_OFFSET_FRACTION_OF_BAR_RANGE = 0.0
    const val RTH_SESSION_OPEN_HOUR = 9
    const val RTH_SESSION_OPEN_MINUTE = 30
}
