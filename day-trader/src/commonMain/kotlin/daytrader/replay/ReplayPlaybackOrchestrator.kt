package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.data.StrategyDeploymentRepository
import daytrader.diagnostics.SessionTrace
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.inProgressSession
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEnginePort
import daytrader.platform.MutableTradingClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives interactive replay: fast-forward the opening bar, then drip captured quotes on a wall-clock cadence.
 */
class ReplayPlaybackOrchestrator(
    private val clock: MutableTradingClock,
    private val quoteFeeder: QuoteFeeder,
    private val scope: CoroutineScope,
    defaultQuoteIntervalMs: () -> Long = { ReplayPlaybackConfig.DEFAULT_QUOTE_INTERVAL_MS }
) {
    private var engine: TouchTurnEnginePort? = null
    private var repository: StrategyDeploymentRepository? = null

    private val _state = MutableStateFlow<ReplayPlaybackState>(ReplayPlaybackState.Idle)
    val state: StateFlow<ReplayPlaybackState> = _state.asStateFlow()

    @Volatile
    var interactiveAutoStartEnabled: Boolean = true

    @Volatile
    var quoteIntervalMs: () -> Long = defaultQuoteIntervalMs

    private var playbackJob: Job? = null
    private var activeInstanceId: String? = null

    fun attach(engine: TouchTurnEnginePort, repository: StrategyDeploymentRepository) {
        this.engine = engine
        this.repository = repository
    }

    fun isPlaying(): Boolean = playbackJob?.isActive == true

    /**
     * Satisfies [ensureLiveMarketData] during replay bootstrap without publishing captured quotes.
     * [QuoteFeeder] is driven only by [fastForwardOpeningBar] and [dripQuotes] so prices stream
     * on the replay cadence instead of being bulk-flushed ahead of virtual time.
     */
    fun ensureQuotesFlowing() = Unit

    fun onSessionStarted(instanceId: String) {
        bindQuotePublishSymbol(instanceId)
        if (!interactiveAutoStartEnabled) {
            trace(
                instanceId,
                "auto_start_skipped",
                extra = mapOf("interactiveAutoStartEnabled" to "false")
            )
            return
        }
        playbackJob?.cancel()
        trace(instanceId, "playback_scheduled")
        playbackJob = scope.launch {
            try {
                awaitBootstrap(instanceId)
                runInteractivePlayback(instanceId)
            } catch (cancelled: CancellationException) {
                trace(instanceId, "playback_cancelled")
                throw cancelled
            } catch (error: Throwable) {
                trace(
                    instanceId,
                    "playback_failed",
                    extra = mapOf("error" to (error.message ?: error::class.simpleName ?: "unknown"))
                )
                throw error
            }
        }
    }

    fun stop() {
        val instanceId = activeInstanceId
        playbackJob?.cancel()
        playbackJob = null
        activeInstanceId = null
        quoteFeeder.publishSymbolOverride = null
        _state.value = ReplayPlaybackState.Idle
        instanceId?.let { trace(it, "playback_stopped") }
    }

    suspend fun fastForwardOpeningBar(
        instanceId: String,
        formingWallDurationMs: Long = ReplayPlaybackConfig.FORMING_WALL_DURATION_MS
    ) {
        val engine = engine ?: run {
            traceAbort(instanceId, "engine_not_attached")
            return
        }
        val repository = repository ?: run {
            traceAbort(instanceId, "repository_not_attached")
            return
        }
        val instance = repository.deployments.value.find { it.id == instanceId } ?: run {
            traceAbort(instanceId, "deployment_not_found")
            return
        }
        val session = instance.touchTurnSession ?: run {
            traceAbort(instanceId, "touch_turn_session_missing", instance)
            return
        }
        val openingBarTime = session.resolvedOpeningBarTime() ?: run {
            traceAbort(
                instanceId,
                "opening_bar_time_missing",
                instance,
                mapOf("sessionStatus" to session.status.name)
            )
            return
        }
        val zoneId = session.marketZoneId
        val barEnd = TouchTurnLogic.barEndEpochMillis(openingBarTime, zoneId) ?: run {
            traceAbort(instanceId, "bar_end_unresolved", instance, mapOf("openingBarTime" to openingBarTime))
            return
        }
        val openMs = ReplaySessionTiming.sessionOpenEpochMillis(instance, session.sessionDate)
            ?: TouchTurnLogic.barStartEpochMillis(openingBarTime, zoneId)
            ?: clock.nowEpochMillis()
        val settleMs = TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS
        val targetMs = barEnd + settleMs + 1
        val quotesBefore = quoteFeeder.publishedQuoteCount

        activeInstanceId = instanceId
        trace(
            instanceId,
            "fast_forward_started",
            instance,
            mapOf(
                "formingWallDurationMs" to formingWallDurationMs.toString(),
                "openEpochMs" to openMs.toString(),
                "barEndEpochMs" to barEnd.toString(),
                "targetEpochMs" to targetMs.toString(),
                "openingBarTime" to openingBarTime,
                "clockBeforeEpochMs" to clock.nowEpochMillis().toString(),
                "capturedQuoteCount" to quoteFeeder.totalQuoteCount.toString()
            )
        )

        val steps = ReplayPlaybackConfig.FORMING_STEPS.coerceAtLeast(1)
        if (formingWallDurationMs <= 0L) {
            clock.advanceTo(targetMs)
            quoteFeeder.publishUpTo(targetMs)
        } else {
            val wallStep = (formingWallDurationMs / steps).coerceAtLeast(1L)
            val clockAtFormingStart = clock.nowEpochMillis()
            for (step in 1..steps) {
                val quoteEpochMs = openMs + (barEnd - openMs) * step / steps
                quoteFeeder.publishUpTo(quoteEpochMs)
                _state.value = ReplayPlaybackState.FastForming(step, steps)
                if (step == 1 || step == steps) {
                    trace(
                        instanceId,
                        "fast_forward_step",
                        instance,
                        mapOf(
                            "step" to step.toString(),
                            "totalSteps" to steps.toString(),
                            "quoteEpochMs" to quoteEpochMs.toString(),
                            "clockEpochMs" to clock.nowEpochMillis().toString()
                        )
                    )
                }
                delay(wallStep)
            }
            clock.advanceTo(targetMs)
            quoteFeeder.publishUpTo(targetMs)
            trace(
                instanceId,
                "fast_forward_clock_jump",
                instance,
                mapOf(
                    "clockBeforeEpochMs" to clockAtFormingStart.toString(),
                    "clockAfterEpochMs" to clock.nowEpochMillis().toString(),
                    "targetEpochMs" to targetMs.toString()
                )
            )
        }

        trace(
            instanceId,
            "fast_forward_completed",
            instance,
            mapOf(
                "clockAfterEpochMs" to clock.nowEpochMillis().toString(),
                "quotesPublishedDuringFastForward" to (quoteFeeder.publishedQuoteCount - quotesBefore).toString(),
                "quotesPublishedTotal" to quoteFeeder.publishedQuoteCount.toString()
            )
        )

        _state.value = ReplayPlaybackState.AwaitingClosedBar
        engine.dispatch(TouchTurnCommand.PollLiquidity(instanceId))
        var polls = 0
        repeat(ReplayPlaybackConfig.CLOSED_BAR_WAIT_POLLS) {
            polls++
            delay(ReplayPlaybackConfig.CLOSED_BAR_WAIT_POLL_MS)
            val touchTurn = repository.deployments.value.find { it.id == instanceId }?.touchTurnSession
            val barClosed = touchTurn?.milestones?.barClosedAt != null
            val hasCandle = touchTurn?.candle != null
            if (barClosed && hasCandle) {
                trace(
                    instanceId,
                    "closed_bar_ready",
                    instance,
                    mapOf(
                        "polls" to polls.toString(),
                        "barClosedAt" to (touchTurn?.milestones?.barClosedAt ?: "null"),
                        "candleClose" to (touchTurn?.candle?.close?.toString() ?: "null")
                    )
                )
                return
            }
            engine.dispatch(TouchTurnCommand.PollLiquidity(instanceId))
        }
        val touchTurn = repository.deployments.value.find { it.id == instanceId }?.touchTurnSession
        trace(
            instanceId,
            "closed_bar_wait_timeout",
            instance,
            mapOf(
                "polls" to polls.toString(),
                "barClosedAt" to (touchTurn?.milestones?.barClosedAt ?: "null"),
                "hasCandle" to (touchTurn?.candle != null).toString(),
                "candleCloseStatus" to (touchTurn?.candleCloseStatus(clock.nowEpochMillis())?.name ?: "null")
            )
        )
    }

    suspend fun dripQuotes(instanceId: String) {
        val engine = engine ?: run {
            traceAbort(instanceId, "engine_not_attached")
            return
        }
        val instance = repository?.deployments?.value?.find { it.id == instanceId }
        val total = quoteFeeder.totalQuoteCount
        var published = quoteFeeder.publishedQuoteCount
        _state.value = ReplayPlaybackState.DrippingQuotes(published, total)
        trace(
            instanceId,
            "quote_drip_started",
            instance,
            mapOf(
                "totalQuotes" to total.toString(),
                "alreadyPublished" to published.toString(),
                "quoteIntervalMs" to quoteIntervalMs().toString(),
                "clockEpochMs" to clock.nowEpochMillis().toString()
            )
        )

        while (true) {
            val event = quoteFeeder.publishNext() ?: break
            clock.advanceTo(event.epochMs)
            published++
            _state.value = ReplayPlaybackState.DrippingQuotes(published, total)
            if (published == 1 || published == total || published % 500 == 0) {
                trace(
                    instanceId,
                    "quote_drip_progress",
                    instance,
                    mapOf(
                        "published" to published.toString(),
                        "total" to total.toString(),
                        "quoteEpochMs" to event.epochMs.toString(),
                        "clockEpochMs" to clock.nowEpochMillis().toString()
                    )
                )
            }
            if (published % ReplayPlaybackConfig.LIQUIDITY_NUDGE_EVERY_N_QUOTES == 0) {
                engine.dispatch(TouchTurnCommand.PollLiquidity(instanceId))
            }
            val intervalMs = quoteIntervalMs()
            if (intervalMs > 0L) {
                delay(intervalMs)
            }
        }
        trace(
            instanceId,
            "quote_drip_completed",
            instance,
            mapOf(
                "published" to published.toString(),
                "total" to total.toString(),
                "clockEpochMs" to clock.nowEpochMillis().toString()
            )
        )
        if (activeInstanceId == instanceId) {
            _state.value = ReplayPlaybackState.Idle
        }
    }

    private suspend fun runInteractivePlayback(instanceId: String) {
        val instance = repository?.deployments?.value?.find { it.id == instanceId }
        trace(instanceId, "playback_started", instance)
        try {
            fastForwardOpeningBar(instanceId)
            dripQuotes(instanceId)
            trace(instanceId, "playback_completed", instance)
        } finally {
            if (activeInstanceId == instanceId) {
                activeInstanceId = null
                _state.value = ReplayPlaybackState.Idle
            }
        }
    }

    private suspend fun awaitBootstrap(instanceId: String) {
        val repository = repository ?: run {
            traceAbort(instanceId, "repository_not_attached")
            return
        }
        trace(instanceId, "bootstrap_wait_started")
        repeat(400) { poll ->
            delay(15L)
            val deployment = repository.deployments.value.find { it.id == instanceId }
            val session = deployment?.touchTurnSession
            if (session?.openingBarTime != null && session.status == TouchTurnCandleStatus.READY) {
                trace(
                    instanceId,
                    "bootstrap_ready",
                    deployment,
                    mapOf(
                        "polls" to (poll + 1).toString(),
                        "openingBarTime" to session.openingBarTime!!,
                        "sessionStatus" to session.status.name
                    )
                )
                return
            }
        }
        val session = repository.deployments.value.find { it.id == instanceId }?.touchTurnSession
        trace(
            instanceId,
            "bootstrap_wait_timeout",
            repository.deployments.value.find { it.id == instanceId },
            mapOf(
                "openingBarTime" to (session?.openingBarTime ?: "null"),
                "sessionStatus" to (session?.status?.name ?: "null")
            )
        )
    }

    private fun bindQuotePublishSymbol(instanceId: String) {
        val symbol = repository?.deployments?.value
            ?.find { it.id == instanceId }
            ?.symbol
            ?.let(SymbolMarkets::normalizeSymbol)
        quoteFeeder.publishSymbolOverride = symbol
    }

    private fun traceAbort(
        instanceId: String,
        reason: String,
        instance: StrategyDeployment? = repository?.deployments?.value?.find { it.id == instanceId },
        extra: Map<String, String> = emptyMap()
    ) {
        trace(instanceId, "aborted", instance, mapOf("reason" to reason) + extra)
    }

    private fun trace(
        instanceId: String,
        event: String,
        instance: StrategyDeployment? = repository?.deployments?.value?.find { it.id == instanceId },
        extra: Map<String, String> = emptyMap()
    ) {
        SessionTrace.replayPlayback(
            deploymentId = instanceId,
            sessionId = instance?.inProgressSession()?.id,
            symbol = instance?.symbol,
            event = event,
            details = extra
        )
    }
}
