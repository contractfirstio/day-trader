package daytrader.replay

import daytrader.domain.DeploymentStatus
import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorBrokerAdapter
import daytrader.data.StrategyDeploymentRepository
import daytrader.engine.TouchTurnEngine
import daytrader.engine.TouchTurnEnginePort
import daytrader.execution.BrokerGatewayExecutionManager
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import daytrader.gateway.QueuedBrokerGateway
import daytrader.marketdata.BrokerGatewayMarketDataProvider
import daytrader.marketdata.MarketQuoteBus
import daytrader.platform.MutableTradingClock
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope

/**
 * Hybrid replay wiring: emulator execution + captured IB market data from [SessionBundle]s.
 * Supports parallel sessions via per-symbol capture registry and [MultiSymbolQuoteFeeder].
 */
class ReplayHybridRuntime(
    bundle: SessionBundle,
    val clock: MutableTradingClock,
    private val scope: CoroutineScope
) {
    val captureRegistry = ReplayCaptureRegistry(bundle)
    val bundle: SessionBundle
        get() = captureRegistry.primaryBundle ?: bundleSeed
    private val bundleSeed = bundle

    val quoteBus = MarketQuoteBus()
    val marketDataGateway = ReplayMarketDataGateway(captureRegistry)
    private val inbound = LinkedBlockingQueue<GatewayEvent>()
    private val outbound = LinkedBlockingQueue<GatewayCommand>()

    private val emulator = EmulatorBrokerAdapter(
        emit = { event -> inbound.offer(event) },
        receiveCommand = { outbound.take() },
        config = BrokerEmulatorConfig.forReplayBacktest(),
        quoteBus = quoteBus,
        scope = scope
    )

    val executionGateway = QueuedBrokerGateway(
        sendCommand = { command -> outbound.offer(command) },
        receiveEventBlocking = { inbound.take() },
        brokerId = BrokerId.EMULATOR,
        scope = scope
    )

    val quoteFeeder = MultiSymbolQuoteFeeder(
        registry = captureRegistry,
        quoteBus = quoteBus,
        marketDataGateway = marketDataGateway,
        clock = clock,
        scope = scope
    )

    val playbackOrchestrator = ReplayPlaybackOrchestrator(
        clock = clock,
        quoteFeeder = quoteFeeder,
        scope = scope
    )

    init {
        wireInteractiveQuoteIngest()
        quoteFeeder.backtestQuoteIngest = { event ->
            emulator.ingestExternalQuoteFromReplay(
                symbol = event.symbol,
                quote = event.quote,
                priorClose = null
            )
        }
        playbackOrchestrator.bindRuntime(this)
    }

    private var backtestFastPathEnabled = false

    /** When set, session-start hooks must not reset runtime or swap captures (headless batch item). */
    @Volatile
    var headlessBacktestDeploymentId: String? = null
        private set

    private var headlessBacktestBundle: SessionBundle? = null

    fun beginHeadlessBacktest(deploymentId: String, bundle: SessionBundle) {
        headlessBacktestDeploymentId = deploymentId
        headlessBacktestBundle = bundle
    }

    fun endHeadlessBacktest() {
        headlessBacktestDeploymentId = null
        headlessBacktestBundle = null
    }

    /**
     * During headless batch replay, [TouchTurnEngine] must use the bundle already registered by
     * [ReplaySessionController.runBacktestReplay] — not re-resolve from the catalog on StartSession.
     */
    fun headlessBacktestSessionDate(deploymentId: String): String? {
        if (headlessBacktestDeploymentId != deploymentId) return null
        return headlessBacktestBundle?.sessionDate
    }

    private fun wireInteractiveQuoteIngest() {
        quoteFeeder.onCapturedQuotePublished = { event ->
            emulator.ingestExternalQuoteSynchronously(
                symbol = event.symbol,
                quote = event.quote,
                priorClose = null
            )
        }
    }

    /** Headless backtest and max-speed interactive replay: no wall-clock sleeps on virtual time. */
    fun enableBacktestFastPath() {
        backtestFastPathEnabled = true
        (clock as? ReplayClock)?.useWallClockDelays = false
        quoteFeeder.onCapturedQuotePublished = null
    }

    fun disableBacktestFastPath() {
        backtestFastPathEnabled = false
        (clock as? ReplayClock)?.useWallClockDelays = true
        wireInteractiveQuoteIngest()
    }

    suspend fun publishBacktestQuotesUpTo(symbol: String, epochMs: Long) {
        quoteFeeder.publishUpToForBacktest(symbol, epochMs) { event ->
            emulator.ingestExternalQuoteFromReplay(
                symbol = event.symbol,
                quote = event.quote,
                priorClose = null
            )
        }
    }

    /** Lets the emulator order actor run between headless replay ticks. */
    suspend fun drainEmulatorPipeline(maxSpins: Int = 8) {
        if (backtestFastPathEnabled) {
            emulator.drainOrderActorQueue(maxRounds = maxSpins)
        } else {
            emulator.yieldOrderActor(maxSpins)
        }
    }

    /** Waits for async bracket placement to finish before replay quotes drive fills. */
    suspend fun awaitEmulatorBracketPipeline(maxSpins: Int = ReplayBacktestFastPath.BRACKET_ACK_MAX_YIELDS) {
        if (backtestFastPathEnabled) {
            emulator.drainOrderActorQueue(maxRounds = maxSpins)
        } else {
            emulator.awaitIdleForReplay(maxSpins)
        }
    }

    /** Opening-bar publish with no forming-bar wall-clock animation (backtest / max speed). */
    suspend fun fastForwardOpeningBarForBacktest(symbol: String, targetEpochMs: Long) {
        clock.advanceTo(targetEpochMs)
        if (backtestFastPathEnabled) {
            quoteFeeder.seedGatewayQuotesUpTo(symbol, targetEpochMs)
        } else {
            quoteFeeder.publishUpTo(symbol, targetEpochMs)
        }
    }

    private var sessionEngine: TouchTurnEnginePort? = null

    fun attachSessionEngine(engine: TouchTurnEnginePort) {
        sessionEngine = engine
    }

    fun start() {
        marketDataGateway.resetRefetchIndex()
        quoteFeeder.resetAll()
        emulator.start()
        executionGateway.connect()
        marketDataGateway.connect()
    }

    fun registerBundle(bundle: SessionBundle) {
        quoteFeeder.registerBundle(bundle)
    }

    fun isOpeningBarQuotesReady(symbol: String): Boolean =
        quoteFeeder.isOpeningBarQuotesReady(symbol)

    fun ensureStreamingMarketData(symbol: String) {
        playbackOrchestrator.ensureQuotesFlowing(symbol)
    }

    fun releaseStreamingMarketData(symbol: String) {
        if (quoteFeeder.releaseStreaming(symbol)) {
            marketDataGateway.clearLiveStateForSymbol(symbol)
            captureRegistry.evictSymbol(symbol)
            executionGateway.requestSymbolSessionPrune(symbol)
        }
    }

    /**
     * Prepares replay state for a session. When other deployments are already running, only
     * resets that symbol/instance so parallel sessions are not torn down.
     */
    fun prepareForSession(instanceId: String, symbol: String, otherSessionsRunning: Boolean) {
        if (headlessBacktestDeploymentId == instanceId) return
        if (otherSessionsRunning) {
            playbackOrchestrator.stop(instanceId)
            marketDataGateway.resetRefetchIndex(symbol)
            quoteFeeder.resetSymbol(symbol)
            sessionEngine?.resetSessionMemory(instanceId)
        } else {
            resetExecutionState(sessionEngine)
        }
    }

    /**
     * Clears replay/runtime memory retained across session boundaries: quote cursors, gateway
     * snapshots, emulator fills/orders, engine tracking, and stale queue events.
     */
    fun reseedBacktestRandom(seed: Long) {
        emulator.reseedRandom(seed)
    }

    fun resetExecutionState(engine: TouchTurnEnginePort? = sessionEngine) {
        playbackOrchestrator.stopAll()
        marketDataGateway.resetRefetchIndex()
        marketDataGateway.clearLiveState()
        quoteFeeder.resetAll()
        drainGatewayQueues()
        emulator.resetSessionState()
        executionGateway.resetSessionLiveState()
        engine?.resetSessionMemory()
    }

    /** @deprecated Use [registerBundle]; kept for callers that switch the primary capture label. */
    fun swapBundle(newBundle: SessionBundle) {
        registerBundle(newBundle)
    }

    fun shutdown() {
        playbackOrchestrator.stopAll()
        emulator.shutdown()
        marketDataGateway.disconnect()
        drainGatewayQueues()
    }

    private fun drainGatewayQueues() {
        while (inbound.poll() != null) { }
        while (outbound.poll() != null) { }
    }

    fun createEngine(repository: StrategyDeploymentRepository): TouchTurnEngine {
        val marketData = BrokerGatewayMarketDataProvider(
            gateway = marketDataGateway,
            ensureLiveMarketData = { symbol, _ -> ensureStreamingMarketData(symbol) },
            releaseLiveMarketData = { symbol, _ -> releaseStreamingMarketData(symbol) }
        )
        return TouchTurnEngine(
            marketData = marketData,
            execution = BrokerGatewayExecutionManager(executionGateway),
            repository = repository,
            scope = scope,
            brokerKind = BrokerKind.REPLAY,
            nowEpochMillis = clock::nowEpochMillis,
            delayMillis = clock::delayMillis,
            onReplaySessionStarting = { deployment, _ ->
                if (headlessBacktestDeploymentId == deployment.id) return@TouchTurnEngine
                val othersRunning = repository.deployments.value.any {
                    it.status == DeploymentStatus.RUNNING && it.id != deployment.id
                }
                prepareForSession(deployment.id, deployment.symbol, othersRunning)
            },
            activateReplayCapture = { deployment ->
                headlessBacktestSessionDate(deployment.id)
                    ?: captureRegistry.bundleFor(deployment.symbol)?.sessionDate
            },
            isReplayOpeningBarQuotesReady = { symbol -> isOpeningBarQuotesReady(symbol) },
            sessionGateway = marketDataGateway,
            executionGateway = executionGateway
        )
    }
}
