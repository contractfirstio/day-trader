package daytrader.platform

/**
 * Plays an audible 10 → 1 countdown (one tick per second), then "[marketLabel] Market Open".
 */
expect object CountdownAudio {
    suspend fun playTenSecondCountdown(marketLabel: String)
}
