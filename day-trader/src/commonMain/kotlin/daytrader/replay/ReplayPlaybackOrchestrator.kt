package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.data.StrategyDeploymentRepository
import daytrader.diagnostics.SessionTrace
import daytrader.domain.DeploymentStatus
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
 * Drives interactive replay per deployment: fast-forward the opening bar, then arm merged
 * per-symbol quote drip (hybrid-style parallel streaming).
 */
class ReplayPlaybackOrchestrator(
    private val clock: MutableTradingClock,
    private val quoteFeeder: MultiSymbolQuoteFeeder,
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
        set(value) {
            field = value
            quoteFeeder.quoteIntervalMs = value
        }

    private val playbackJobs = mutableMapOf<String, Job>()
    private var quotesPublishedSinceLiquidityNudge = 0

    init {
        quoteFeeder.quoteIntervalMs = quoteIntervalMs
        quoteFeeder.onQuotePublished = { symbol -> onMergedQuotePublished(symbol) }
        quoteFeeder.onOpeningBarQuotesReady = { symbol -> onOpeningBarQuotesReady(symbol) }
    }

    fun attach(engine: TouchTurnEnginePort, repository: StrategyDeploymentRepository) {
        this.engine = engine
        this.repository = repository
    }

    fun isPlaying(): Boolean = playbackJobs.values.any { it.isActive }

    /**
     * Refcounted subscription for [symbol]; quotes drip only after [enableDrip] post fast-forward.
     */
    fun ensureQuotesFlowing(symbol: String) {
        quoteFeeder.ensureStreaming(symbol)
    }

    fun onSessionStarted(instanceId: String) {
        if (!interactiveAutoStartEnabled) {
            trace(
                instanceId,
                "auto_start_skipped",
                extra = mapOf("interactiveAutoStartEnabled" to "false")
            )
            return
        }
        playbackJobs[instanceId]?.cancel()
        trace(instanceId, "playback_scheduled")
        playbackJobs[instanceId] = scope.launch {
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
            } finally {
                playbackJobs.remove(instanceId)
            }
        }
    }

    fun stop(instanceId: String) {
        playbackJobs.remove(instanceId)?.cancel()
        deploymentSymbol(instanceId)?.let { symbol ->
            quoteFeeder.releaseStreaming(symbol)
        }
        if (playbackJobs.isEmpty()) {
            _state.value = ReplayPlaybackState.Idle
        }
        trace(instanceId, "playback_stopped")
    }

    fun stopAll() {
        playbackJobs.keys.toList().forEach { stop(it) }
        playbackJobs.clear()
        quoteFeeder.stopDrip()
        _state.value = ReplayPlaybackState.Idle
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
        val symbol = instance.symbol
        val feeder = quoteFeeder.feederForSymbol(symbol) ?: run {
            traceAbort(instanceId, "quote_feeder_missing", instance, mapOf("symbol" to symbol))
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
        val quotesBefore = feeder.publishedQuoteCount

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
                "capturedQuoteCount" to feeder.totalQuoteCount.toString(),
                "symbol" to symbol
            )
        )

        val steps = ReplayPlaybackConfig.FORMING_STEPS.coerceAtLeast(1)
        if (formingWallDurationMs <= 0L) {
            clock.advanceTo(targetMs)
            quoteFeeder.publishUpTo(symbol, targetMs)
        } else {
            val wallStep = (formingWallDurationMs / steps).coerceAtLeast(1L)
            val clockAtFormingStart = clock.nowEpochMillis()
            for (step in 1..steps) {
                val quoteEpochMs = openMs + (barEnd - openMs) * step / steps
                quoteFeeder.publishUpTo(symbol, quoteEpochMs)
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
            quoteFeeder.publishUpTo(symbol, targetMs)
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
                "quotesPublishedDuringFastForward" to (feeder.publishedQuoteCount - quotesBefore).toString(),
                "quotesPublishedTotal" to feeder.publishedQuoteCount.toString()
            )
        )

        quoteFeeder.markOpeningBarQuotesReady(symbol)

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

    private suspend fun runInteractivePlayback(instanceId: String) {
        val instance = repository?.deployments?.value?.find { it.id == instanceId }
        val symbol = instance?.symbol ?: return
        trace(instanceId, "playback_started", instance)
        fastForwardOpeningBar(instanceId)
        val feeder = quoteFeeder.feederForSymbol(symbol)
        val total = feeder?.totalQuoteCount ?: 0
        val published = feeder?.publishedQuoteCount ?: 0
        _state.value = ReplayPlaybackState.DrippingQuotes(published, total)
        trace(
            instanceId,
            "quote_drip_started",
            instance,
            mapOf(
                "totalQuotes" to total.toString(),
                "alreadyPublished" to published.toString(),
                "quoteIntervalMs" to quoteIntervalMs().toString(),
                "clockEpochMs" to clock.nowEpochMillis().toString(),
                "symbol" to symbol
            )
        )
        quoteFeeder.ensureStreaming(symbol)
        quoteFeeder.enableDrip(symbol)
        trace(instanceId, "playback_completed", instance)
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

    private fun onOpeningBarQuotesReady(symbol: String) {
        val engine = engine ?: return
        val repository = repository ?: return
        repository.deployments.value
            .filter { it.status == DeploymentStatus.RUNNING && SymbolMarkets.symbolsMatch(it.symbol, symbol) }
            .forEach { deployment ->
                engine.dispatch(TouchTurnCommand.PollLiquidity(deployment.id))
            }
    }

    private fun onMergedQuotePublished(symbol: String) {
        val engine = engine ?: return
        val repository = repository ?: return
        quotesPublishedSinceLiquidityNudge++
        updateDrippingState()
        if (quotesPublishedSinceLiquidityNudge % ReplayPlaybackConfig.LIQUIDITY_NUDGE_EVERY_N_QUOTES == 0) {
            repository.deployments.value
                .filter { it.status == DeploymentStatus.RUNNING && SymbolMarkets.symbolsMatch(it.symbol, symbol) }
                .forEach { deployment ->
                    engine.dispatch(TouchTurnCommand.PollLiquidity(deployment.id))
                }
        }
    }

    private fun updateDrippingState() {
        var published = 0
        var total = 0
        repository?.deployments?.value
            ?.filter { it.status == DeploymentStatus.RUNNING }
            ?.forEach { deployment ->
                val feeder = quoteFeeder.feederForSymbol(deployment.symbol) ?: return@forEach
                published += feeder.publishedQuoteCount
                total += feeder.totalQuoteCount
            }
        if (total > 0) {
            _state.value = ReplayPlaybackState.DrippingQuotes(published, total)
        }
    }

    private fun deploymentSymbol(instanceId: String): String? =
        repository?.deployments?.value?.find { it.id == instanceId }?.symbol

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
