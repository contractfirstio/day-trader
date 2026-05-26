package daytrader.presentation.markets

import daytrader.domain.RthMarketSessions
import daytrader.domain.TouchTurnLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory US/UK/HK filter for deployments and session history (not persisted). */
class MarketFilterState {
    private val _selectedZoneId = MutableStateFlow<String?>(null)
    val selectedZoneId: StateFlow<String?> = _selectedZoneId.asStateFlow()
    private var startupDefaultApplied = false
    private var autoFilterSuppressed = false

    fun select(zoneId: String) {
        _selectedZoneId.value = zoneId
    }

    fun toggle(zoneId: String) {
        _selectedZoneId.update { current ->
            if (current == zoneId) {
                autoFilterSuppressed = true
                null
            } else {
                zoneId
            }
        }
    }

    fun clear() {
        _selectedZoneId.value = null
        autoFilterSuppressed = true
    }

    /** Applies a LIVE market filter once per app session unless the user has cleared filters. */
    fun applyStartupDefaultIfNeeded(nowEpochMillis: Long = System.currentTimeMillis()) {
        if (startupDefaultApplied || autoFilterSuppressed) return
        startupDefaultApplied = true
        val liveZoneId = RthMarketSessions.all
            .firstOrNull { TouchTurnLogic.isRthMarketOpen(it, nowEpochMillis) }
            ?.zoneId
        if (liveZoneId != null) {
            select(liveZoneId)
        }
    }
}

fun marketLabelForZone(zoneId: String): String =
    RthMarketSessions.all.find { it.zoneId == zoneId }?.label ?: zoneId
