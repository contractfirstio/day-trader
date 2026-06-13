package daytrader.data.persistence

import daytrader.replay.ReplayPlaybackConfig
import kotlinx.serialization.Serializable

@Serializable
data class ReplaySettingsDocument(
    val quoteIntervalMs: Long = ReplayPlaybackConfig.DEFAULT_QUOTE_INTERVAL_MS
)
