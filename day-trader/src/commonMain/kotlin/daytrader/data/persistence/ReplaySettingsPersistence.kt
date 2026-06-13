package daytrader.data.persistence

import daytrader.replay.ReplaySettings

object ReplaySettingsPersistence {
    fun fromDocument(document: ReplaySettingsDocument): ReplaySettings =
        ReplaySettings(quoteIntervalMs = document.quoteIntervalMs.coerceAtLeast(0L))

    fun toDocument(settings: ReplaySettings): ReplaySettingsDocument =
        ReplaySettingsDocument(quoteIntervalMs = settings.quoteIntervalMs)
}
