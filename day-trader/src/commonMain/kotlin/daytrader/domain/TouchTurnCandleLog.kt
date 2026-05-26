package daytrader.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Console diagnostics for the first 15-minute Touch Turn bar (close timing / timezone).
 * Enabled by default; set `DAY_TRADER_TOUCH_TURN_CANDLE_LOGS=false` to disable.
 */
object TouchTurnCandleLog {
    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_TOUCH_TURN_CANDLE_LOGS")
            ?.equals("false", ignoreCase = true) != true

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun candleLoaded(
        instanceId: String,
        symbol: String,
        sessionDate: String,
        deploymentMarketZoneId: String,
        session: TouchTurnSessionContext,
        ibSessionDayYyyyMmDd: String? = null
    ) {
        line("candle loaded instance=$instanceId symbol=$symbol sessionDate=$sessionDate")
        emitSnapshot(
            deploymentMarketZoneId = deploymentMarketZoneId,
            session = session,
            ibSessionDayYyyyMmDd = ibSessionDayYyyyMmDd,
            context = "load"
        )
    }

    fun ibHistoricalBar(
        symbol: String,
        marketZoneId: String,
        sessionDayYyyyMmDd: String,
        rawBarTime: String?,
        selectedBarTime: String?,
        totalBars: Int,
        sessionDayBars: Int
    ) {
        val normalizedNote = when {
            rawBarTime == null -> ""
            rawBarTime == selectedBarTime -> ""
            else -> " normalizedFrom=$rawBarTime"
        }
        line(
            "IB historical 15m symbol=$symbol zone=$marketZoneId sessionDay=$sessionDayYyyyMmDd " +
                "bars=$totalBars sessionDayBars=$sessionDayBars selectedBarTime=${selectedBarTime ?: "null"}" +
                normalizedNote
        )
    }

    /** Logged once per instance when RTH open is >15m ago but the bar is still FORMING. */
    fun stuckFormingAfterRthOpen(
        instanceId: String,
        symbol: String,
        deploymentMarketZoneId: String,
        session: TouchTurnSessionContext
    ) {
        line(
            "STUCK FORMING (>15m since RTH open, bar still not closed) " +
                "instance=$instanceId symbol=$symbol — check bar time vs marketZoneId / TWS API timezone"
        )
        emitSnapshot(
            deploymentMarketZoneId = deploymentMarketZoneId,
            session = session,
            context = "stuck"
        )
    }

    fun candleClosed(
        instanceId: String,
        symbol: String,
        session: TouchTurnSessionContext
    ) {
        val barTime = session.candle?.time ?: return
        line(
            "candle closed instance=$instanceId symbol=$symbol barTime=$barTime " +
                "zone=${session.marketZoneId} status=${session.candleCloseStatus()}"
        )
    }

    private fun emitSnapshot(
        deploymentMarketZoneId: String,
        session: TouchTurnSessionContext,
        ibSessionDayYyyyMmDd: String? = null,
        context: String
    ) {
        val now = System.currentTimeMillis()
        val candle = session.candle
        val barTime = candle?.time
        val sessionZone = session.marketZoneId
        val closeStatus = session.candleCloseStatus(now)
        val rthSession = RthMarketSessions.forZoneId(sessionZone)
        val rthOpenToday = TouchTurnLogic.marketOpenEpochMillis(session.sessionDate, sessionZone, barTime)
        val elapsedRth = TouchTurnLogic.millisSinceLastMarketOpenWallClock(sessionZone, now)
        val barStart = barTime?.let { TouchTurnLogic.barStartEpochMillis(it, sessionZone) }
        val barEnd = barTime?.let { TouchTurnLogic.barEndEpochMillis(it, sessionZone) }
        val millisUntilBarEnd = barEnd?.let { (it - now).coerceAtLeast(0) }
        val overdueMs = if (barEnd != null && now > barEnd) now - barEnd else 0L

        detail("  context=$context sessionZone=$sessionZone deploymentZone=$deploymentMarketZoneId")
        if (deploymentMarketZoneId != sessionZone) {
            detail("  WARNING deploymentZone != sessionZone")
        }
        ibSessionDayYyyyMmDd?.let {
            detail("  ibSessionDayFilter=$it systemDefaultZone=${ZoneId.systemDefault().id}")
        }
        detail(
            "  currency=${session.currencyCode} " +
                "expectedRthOpen=${rthSession.openHour}:${pad(rthSession.openMinute)} ${rthSession.label}"
        )
        detail("  ibBarTime=${barTime ?: "null"} closeStatus=$closeStatus")
        barStart?.let { detail("  parsedBarOpen=${formatInstant(it, sessionZone)}") }
        barEnd?.let { detail("  parsedBarEnd=${formatInstant(it, sessionZone)}") }
        detail("  now=${formatInstant(now, sessionZone)}")
        rthOpenToday?.let { detail("  rthOpenToday=${formatInstant(it, sessionZone)}") }
        detail("  millisSinceRthOpen=${formatDuration(elapsedRth)}")
        when {
            barEnd == null -> detail("  millisUntilBarClose=unknown (could not parse bar time)")
            overdueMs > 0 -> detail("  barCloseOverdueBy=${formatDuration(overdueMs)} (should be CLOSED)")
            millisUntilBarEnd != null -> detail("  millisUntilBarClose=${formatDuration(millisUntilBarEnd)}")
        }
        if (elapsedRth > TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS &&
            closeStatus == FirstCandleCloseStatus.FORMING
        ) {
            detail(
                "  HINT: >15m since RTH open but bar still FORMING — " +
                    "IB bar timestamp timezone may not match sessionZone, or deployment market is wrong " +
                    "(UK/LSE needs Europe/London and bar open ~08:00 local)"
            )
        }
    }

    private fun line(message: String) {
        if (!enabled) return
        println("[TouchTurnCandle] $message")
    }

    private fun detail(message: String) {
        if (!enabled) return
        println("[TouchTurnCandle]$message")
    }

    private fun formatInstant(epochMillis: Long, zoneId: String): String {
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zoneId))
        return "${zdt.format(timeFormatter)} ($zoneId)"
    }

    private fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s (${millis}ms)"
            m > 0 -> "${m}m ${s}s (${millis}ms)"
            else -> "${s}s (${millis}ms)"
        }
    }

    private fun pad(minute: Int): String = minute.toString().padStart(2, '0')
}
