package daytrader.replay

sealed interface ReplayPlaybackState {
    data object Idle : ReplayPlaybackState

    data class FastForming(
        val step: Int,
        val totalSteps: Int
    ) : ReplayPlaybackState

    data object AwaitingClosedBar : ReplayPlaybackState

    data class DrippingQuotes(
        val published: Int,
        val total: Int
    ) : ReplayPlaybackState
}
