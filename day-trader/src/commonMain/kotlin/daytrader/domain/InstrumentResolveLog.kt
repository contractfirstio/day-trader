package daytrader.domain

/**
 * Console logging for symbol → venue listing resolution.
 * Enabled by default; set DAY_TRADER_INSTRUMENT_RESOLVE_LOGS=false to disable.
 */
object InstrumentResolveLog {
    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_INSTRUMENT_RESOLVE_LOGS")
            ?.equals("false", ignoreCase = true) != true

    fun line(message: String) {
        if (enabled) println("[InstrumentResolve] $message")
    }

    fun resolveStarted(symbol: String, source: String) {
        line("resolve started symbol=$symbol source=$source")
    }

    fun resolveFinished(
        symbol: String,
        success: Boolean,
        rawCount: Int,
        uiCount: Int,
        listings: List<String>,
        error: String? = null
    ) {
        val listingText = listings.joinToString(", ").ifBlank { "—" }
        if (success) {
            line(
                "resolve finished symbol=$symbol rawCandidates=$rawCount uiCandidates=$uiCount " +
                    "showVenueDropdown=${uiCount > 1} listings=[$listingText]"
            )
        } else {
            line(
                "resolve failed symbol=$symbol error=${error ?: "unknown"} " +
                    "rawCandidates=$rawCount uiCandidates=$uiCount"
            )
        }
    }

    fun uiReceived(symbol: String, uiCount: Int, selected: String?) {
        line(
            "ui updated symbol=$symbol uiCandidates=$uiCount showVenueDropdown=${uiCount > 1} " +
                "selected=${selected ?: "none"}"
        )
    }
}
