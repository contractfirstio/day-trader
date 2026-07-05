package daytrader.e2e.steps

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorPricingSource
import daytrader.data.SessionMarketDataCapture
import daytrader.e2e.support.E2EProcessCleanup
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPrepareStatus
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.domain.inProgressSession
import daytrader.e2e.E2EWorld
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2EEngineLiquidityHelper
import daytrader.e2e.support.E2ESessionDriver
import daytrader.e2e.support.E2EStartBlockedHelper
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
import daytrader.e2e.support.TouchTurnMarketFixtures
import daytrader.e2e.support.TouchTurnMarketScenarioId
import daytrader.engine.TouchTurnCommand
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayConnectionState
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class BrokerModeSteps {
    private val world = E2EWorld()

    private val liquidityEvalNoBracketOutcomes = setOf(
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
        TouchTurnSessionOutcome.NO_TRADE_DOJI,
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_ENTRY_MARKETABLE,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
    )

    @Before
    fun resetWorld() {
        E2EProcessCleanup.resetAll()
        world.reset()
        E2EProcessCleanup.requireClean("Cucumber @Before")
    }

    @After
    fun shutdownWorld() {
        world.reset()
        E2EProcessCleanup.requireClean("Cucumber @After")
    }

    @Given("the broker mode is {string}")
    fun theBrokerModeIs(mode: String) {
        world.brokerMode = mode.lowercase()
    }

    @Given("a running Touch Turn deployment for {string}")
    fun aRunningDeployment(symbol: String) {
        world.symbol = symbol
        world.repository.add(E2ETestFixtures.runningDeployment(symbol = symbol))
    }

    @Given("a stopped Touch Turn deployment for {string}")
    fun aStoppedDeployment(symbol: String) {
        world.symbol = symbol
        world.repository.deployments.value.toList().forEach { world.repository.remove(it.id) }
        world.repository.add(E2ETestFixtures.stoppedDeployment(symbol = symbol))
    }

    @Given("the deployment has liquidity evaluation enabled")
    fun deploymentLiquidityEvaluationEnabled() {
        world.repository.update(activeDeploymentId()) { current ->
            current.copy(
                touchTurnRules = (current.touchTurnRules ?: TouchTurnRuleConfig.DEFAULT).copy(
                    enables = (current.touchTurnRules?.enables ?: TouchTurnRuleEnables.DEFAULT)
                        .copy(liquidityRangeDailyAtr = true)
                )
            )
        }
    }

    @Given("the IB gateway returns canonical scenario {string}")
    fun ibGatewayCanonicalScenario(scenarioId: String) {
        val scenario = TouchTurnMarketFixtures.scenario(parseCanonicalScenarioId(scenarioId))
        world.activeIbHarness().applyMarketScenario(scenario)
        world.activeIbHarness().gateway.resetRefetchIndex()
    }

    @Given("the deployment has canonical scenario {string} loaded")
    fun deploymentCanonicalScenarioLoaded(scenarioId: String) {
        seedCanonicalClosedBar(parseCanonicalScenarioId(scenarioId))
    }

    @Given("the IB gateway is disconnected")
    fun ibGatewayDisconnected() {
        world.activeIbHarness().gateway.disconnect()
    }

    @Given("the IB gateway reports an open position for {string}")
    fun ibGatewayOpenPosition(symbol: String) {
        world.activeIbHarness().gateway.setPositions(
            listOf(E2EStartBlockedHelper.openPosition(symbol = symbol))
        )
    }

    @Given("the IB gateway rejects the next bracket placement")
    fun ibGatewayRejectsBracketPlacement() {
        world.activeIbHarness().gateway.bracketPlacementAckResult =
            Result.failure(IllegalStateException("parent_open_order_timeout"))
    }

    @Given("session market data capture is active for the deployment")
    fun sessionMarketDataCaptureActive() {
        val deployment = world.repository.deployments.value.first()
        val sessionId = deployment.inProgressSession()?.id ?: error("expected in-progress session")
        E2EProcessCleanup.resetAll()
        SessionMarketDataCapture.start(
            deploymentId = deployment.id,
            sessionId = sessionId,
            symbol = deployment.symbol,
            instrument = deployment.instrument
        )
    }

    @Given("the emulator entry scenario is immediate fill")
    fun emulatorImmediateFill() {
        world.configureEmulatorHarness { EmulatorModeTestHarness.immediateEntry(it) }
    }

    @Given("the emulator entry scenario is never fill")
    fun emulatorNeverFill() {
        world.configureEmulatorHarness { EmulatorModeTestHarness.neverFillEntry(it) }
    }

    @Given("the emulator uses synthetic pricing")
    fun emulatorSyntheticPricing() {
        world.configureEmulatorHarness {
            EmulatorModeTestHarness(
                scope = it,
                config = BrokerEmulatorConfig(
                    connectDelayMs = 1,
                    marketTickIntervalMs = 50,
                    pricingSource = EmulatorPricingSource.SYNTHETIC
                )
            )
        }
    }

    @Given("the mocked IB bootstrap context is non-liquidity")
    fun mockedIbBootstrapNonLiquidity() {
        val harness = world.activeHybridHarness()
        harness.ibGateway.bootstrapContext = E2ETestFixtures.bootstrapContext(E2ETestFixtures.nonLiquidityOpeningBar())
    }

    @Given("the mocked IB bootstrap context is liquidity")
    fun mockedIbBootstrapLiquidity() {
        val harness = world.activeHybridHarness()
        harness.ibGateway.bootstrapContext = E2ETestFixtures.bootstrapContext(E2ETestFixtures.liquidityOpeningBar())
    }

    @Given("the mocked IB closed-bar refetch is non-liquidity")
    fun mockedIbRefetchNonLiquidity() {
        val harness = world.activeHybridHarness()
        harness.ibGateway.refetchContexts = listOf(
            E2ETestFixtures.bootstrapContext(E2ETestFixtures.nonLiquidityOpeningBar())
        )
    }

    @Given("the mocked IB closed-bar refetch is liquidity")
    fun mockedIbRefetchLiquidity() {
        val harness = world.activeHybridHarness()
        harness.ibGateway.refetchContexts = listOf(
            E2ETestFixtures.bootstrapContext(E2ETestFixtures.liquidityOpeningBar())
        )
    }

    @Given("the IB gateway returns a non-liquidity bootstrap context")
    fun ibGatewayBootstrapNonLiquidity() {
        val harness = world.activeIbHarness()
        harness.gateway.signalContextFetchResult = Result.success(
            E2ETestFixtures.bootstrapContext(E2ETestFixtures.nonLiquidityOpeningBar())
        )
        harness.gateway.candleFetchResult = Result.success(E2ETestFixtures.nonLiquidityOpeningBar())
    }

    @Given("the IB gateway returns a liquidity bootstrap context")
    fun ibGatewayBootstrapLiquidity() {
        val harness = world.activeIbHarness()
        val bar = E2ETestFixtures.liquidityOpeningBar()
        harness.gateway.signalContextFetchResult = Result.success(E2ETestFixtures.bootstrapContext(bar))
        harness.gateway.candleFetchResult = Result.success(bar)
    }

    @Given("the IB gateway closed-bar refetch is non-liquidity")
    fun ibGatewayRefetchNonLiquidity() {
        val harness = world.activeIbHarness()
        harness.gateway.refetchSignalContexts = listOf(
            E2ETestFixtures.bootstrapContext(E2ETestFixtures.nonLiquidityOpeningBar())
        )
        harness.gateway.resetRefetchIndex()
    }

    @Given("the IB gateway closed-bar refetch is liquidity")
    fun ibGatewayRefetchLiquidity() {
        val bar = E2ETestFixtures.liquidityOpeningBar()
        val harness = world.activeIbHarness()
        harness.gateway.refetchSignalContexts = listOf(E2ETestFixtures.bootstrapContext(bar))
        harness.gateway.resetRefetchIndex()
    }

    @Given("the deployment has a closed non-liquidity bar loaded")
    fun deploymentHasClosedNonLiquidityBar() {
        seedClosedBar(E2ETestFixtures.nonLiquidityOpeningBar())
    }

    @Given("the deployment has a closed liquidity bar loaded")
    fun deploymentHasClosedLiquidityBar() {
        seedClosedBar(E2ETestFixtures.liquidityOpeningBar())
    }

    private fun seedClosedBar(bar: daytrader.domain.OhlcBar) {
        world.repository.update(activeDeploymentId()) { current ->
            val rules = (current.touchTurnRules ?: TouchTurnRuleConfig.DEFAULT).copy(
                enables = (current.touchTurnRules?.enables ?: TouchTurnRuleConfig.DEFAULT.enables)
                    .copy(liquidityRangeDailyAtr = true)
            )
            current.copy(touchTurnRules = rules)
                .withFirstFifteenMinuteCandle(
                    sessionDate = E2ETestFixtures.SESSION_DATE,
                    candle = bar,
                    atr14 = E2ETestFixtures.ATR14,
                    dailyAtr14 = E2ETestFixtures.ATR14,
                    volumeSma20 = E2ETestFixtures.VOLUME_SMA20
                )
                .withOpeningBarClosedMilestone()
                .withClosedFirstFifteenMinuteCandle(bar)
        }
    }

    private fun seedCanonicalClosedBar(scenarioId: TouchTurnMarketScenarioId) {
        val scenario = TouchTurnMarketFixtures.scenario(scenarioId)
        seedClosedBar(scenario.openingBar)
    }

    private fun parseCanonicalScenarioId(raw: String): TouchTurnMarketScenarioId =
        TouchTurnMarketScenarioId.valueOf(raw.trim())

    private fun seedHybridLiveQuote() {
        val harness = world.activeHybridHarness()
        val close = harness.ibGateway.bootstrapContext.firstCandle.close
        harness.publishIbQuote(
            world.symbol,
            E2ETestFixtures.liveQuote(
                symbol = world.symbol,
                bid = close - 0.01,
                ask = close + 0.01,
                last = close
            )
        )
    }

    @When("the broker runtime starts")
    fun brokerRuntimeStarts() = runBlocking {
        when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().start()
            "hybrid" -> world.activeHybridHarness().start()
            "ib" -> world.activeIbHarness().start()
            else -> error("Unknown broker mode: ${world.brokerMode}")
        }
        delay(200)
    }

    @When("the broker runtime shuts down")
    fun brokerRuntimeShutsDown() {
        when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().shutdown()
            "hybrid" -> world.activeHybridHarness().shutdown()
            "ib" -> world.activeIbHarness().shutdown()
            else -> error("Unknown broker mode: ${world.brokerMode}")
        }
    }

    @When("the Touch Turn engine starts")
    fun touchTurnEngineStarts() = runBlocking {
        val engine = when (world.brokerMode) {
            "emulator" -> {
                val harness = world.activeEmulatorHarness()
                harness.createEngine(world.repository).also { world.engine = it }
            }
            "hybrid" -> {
                val harness = world.activeHybridHarness()
                harness.createEngine(world.repository).also { world.engine = it }
            }
            "ib" -> {
                val harness = world.activeIbHarness()
                harness.createEngine(world.repository, world.scope).also { world.engine = it }
            }
            else -> error("Unknown broker mode: ${world.brokerMode}")
        }
        world.driver = E2ESessionDriver(engine, world.repository)
        if (world.brokerMode == "hybrid") {
            seedHybridLiveQuote()
        }
        world.driver!!.startEngine()
        when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().start()
            "hybrid" -> world.activeHybridHarness().start()
            "ib" -> world.activeIbHarness().start()
            else -> error("Unknown broker mode: ${world.brokerMode}")
        }
        delay(100)
    }

    private fun activeDeploymentId(): String =
        world.repository.deployments.value.firstOrNull()?.id ?: E2ETestFixtures.DEPLOYMENT_ID

    @When("the engine evaluates liquidity for the session")
    fun engineEvaluatesLiquidityForSession() = runBlocking {
        val engine = world.engine ?: error("Touch Turn engine not started")
        if (world.brokerMode == "ib") {
            world.activeIbHarness().start()
        }
        E2EEngineLiquidityHelper.bootstrapAndAwaitLiquidity(
            engine = engine,
            repository = world.repository,
            deploymentId = activeDeploymentId(),
            startEngine = false,
        )
    }

    @When("session prepare runs on IB")
    fun sessionPrepareRunsOnIb() = runBlocking {
        val harness = world.activeIbHarness()
        if (harness.gateway.connectionState.value == GatewayConnectionState.Connected) {
            harness.start()
        }
        val engine = harness.createEngine(world.repository, world.scope).also { world.engine = it }
        engine.start()
        engine.dispatch(TouchTurnCommand.PrepareSession(activeDeploymentId()))
        delay(500)
    }

    @When("the Touch Turn IB session starts")
    fun touchTurnIbSessionStarts() = runBlocking {
        world.activeIbHarness().start()
        val engine = world.activeIbHarness()
            .createEngine(world.repository, world.scope)
            .also { world.engine = it }
        world.driver = E2ESessionDriver(engine, world.repository)
        engine.start()
        engine.dispatch(
            TouchTurnCommand.StartSession(
                instanceId = activeDeploymentId(),
                sessionDate = E2ETestFixtures.SESSION_DATE
            )
        )
        delay(300)
    }

    @When("the session loads the first fifteen minute candle")
    fun sessionLoadsFirstCandle() = runBlocking {
        world.driver!!.loadFirstCandle()
    }

    @When("liquidity is evaluated for the session")
    fun liquidityEvaluated() = runBlocking {
        if (world.brokerMode == "hybrid" && world.hybridHarness == null) {
            world.activeHybridHarness().start()
        }
        if (world.brokerMode == "ib" && world.ibHarness == null) {
            world.activeIbHarness().start()
        }
        val requireLive = world.brokerMode == "hybrid"
        if (requireLive) seedHybridLiveQuote()
        val liveQuote = if (requireLive) {
            world.activeHybridHarness().ibGateway.quotes.value[world.symbol.uppercase()]
        } else null

        world.repository.update(activeDeploymentId()) { current ->
            current.withLiquidityEvaluatedIfClosed(
                enforceCloseConfirmation = false,
                nowEpochMillis = E2ETestFixtures.BAR_CLOSE_EPOCH_MS
            )
        }

        val deployment = world.repository.deployments.value.first()
        val session = deployment.touchTurnSession
        val mayPlaceBracket = session?.entryOrdersPermitted == true &&
            session.setup != null &&
            session.decisionOutcome !in liquidityEvalNoBracketOutcomes
        if (mayPlaceBracket) {
            val plan = TouchTurnOrderPlanner.buildOrderPlan(
                symbol = deployment.symbol,
                setup = session.setup,
                maxDollars = deployment.maxDollars,
                currencyCode = session.currencyCode,
                openingBarClose = session.candle?.close
            )
            if (plan != null) {
                when (world.brokerMode) {
                    "hybrid" -> {
                        world.activeHybridHarness().executionGateway.placeTouchTurnBracket(plan)
                        world.repository.update(activeDeploymentId()) { it.withOrdersPlacedForSession() }
                    }
                    "ib" -> {
                        world.activeIbHarness().start()
                        world.activeIbHarness().gateway.placeTouchTurnBracket(plan)
                    }
                }
            }
        }
        if (requireLive) {
            world.activeHybridHarness().ibGateway.ensureStreaming(world.symbol)
        }
        delay(300)
    }

    @When("the session is stopped manually")
    fun sessionStoppedManually() = runBlocking {
        if (world.engine == null || world.driver == null) {
            touchTurnEngineStarts()
        }
        val deployment = world.repository.deployments.value.first()
        if (deployment.touchTurnSession == null) {
            world.driver!!.loadFirstCandle()
        }
        world.driver!!.stopSession()
    }

    @When("a liquidity bracket is placed on the emulator for {string}")
    fun placeLiquidityBracket(symbol: String) = runBlocking {
        val gateway = when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().gateway
            "hybrid" -> world.activeHybridHarness().executionGateway
            else -> error("Bracket placement on emulator requires emulator or hybrid mode")
        }
        if (world.engine == null) {
            when (world.brokerMode) {
                "emulator" -> world.activeEmulatorHarness().start()
                "hybrid" -> world.activeHybridHarness().start()
            }
        }
        if (world.brokerMode == "emulator") {
            world.activeEmulatorHarness().adapter.ensureStreamingMarketData(symbol)
        }
        gateway.placeTouchTurnBracket(E2EBracketHelper.liquidityPlan(symbol))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (gateway.openOrders.value.any { it.symbol == symbol.uppercase() && it.remaining > 0 }) {
                return@runBlocking
            }
            delay(50)
        }
    }

    @When("an external quote is published for {string}")
    fun publishExternalQuote(symbol: String) = runBlocking {
        val quote = E2ETestFixtures.liveQuote(symbol, bid = 99.0, ask = 99.2, last = 99.1)
        when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().adapter.ingestExternalQuote(symbol, quote, null)
            "hybrid" -> world.activeHybridHarness().publishIbQuote(symbol, quote)
            else -> error("External quote publishing requires emulator or hybrid mode")
        }
        delay(150)
    }

    @When("a live IB quote crosses the entry price for {string}")
    fun liveIbQuoteCrossesEntry(symbol: String) = runBlocking {
        val harness = world.activeHybridHarness()
        val entry = world.repository.deployments.value.first()
            .touchTurnSession?.setup?.entry ?: 100.0
        val approach = E2ETestFixtures.liveQuote(
            symbol = symbol,
            bid = entry,
            ask = entry + 0.10,
            last = entry + 0.05
        )
        val fill = E2ETestFixtures.liveQuote(
            symbol = symbol,
            bid = entry - 0.01,
            ask = entry,
            last = entry
        )
        repeat(3) {
            harness.publishIbQuote(symbol, approach)
            harness.ingestLiveQuote(symbol, approach)
            delay(80)
        }
        repeat(5) {
            harness.publishIbQuote(symbol, fill)
            harness.ingestLiveQuote(symbol, fill)
            delay(100)
        }
    }

    @When("the emulator advances synthetic market ticks")
    fun emulatorAdvancesTicks() = runBlocking {
        delay(300)
    }

    @When("the IB gateway resolves instrument {string}")
    fun ibResolvesInstrument(symbol: String) = runBlocking {
        val result = world.activeIbHarness().gateway.resolveInstrument(symbol)
        world.lastInstrumentResolution = result.isSuccess
    }
    @Then("the execution gateway should be connected")
    fun executionGatewayConnected() {
        val gateway = world.activeEmulatorHarness().gateway
        assertEquals(GatewayConnectionState.Connected, gateway.connectionState.value)
    }

    @Then("the gateway should be connected")
    fun gatewayConnected() {
        when (world.brokerMode) {
            "ib" -> assertEquals(
                GatewayConnectionState.Connected,
                world.activeIbHarness().gateway.connectionState.value
            )
            else -> executionGatewayConnected()
        }
    }

    @Then("the execution gateway broker id should be {string}")
    fun executionGatewayBrokerId(expected: String) {
        assertEquals(BrokerId.valueOf(expected), world.activeEmulatorHarness().gateway.brokerId)
    }

    @Then("the market data gateway broker id should be {string}")
    fun marketDataGatewayBrokerId(expected: String) {
        assertEquals(BrokerId.valueOf(expected), world.activeHybridHarness().ibGateway.brokerId)
    }

    @Then("the gateway broker id should be {string}")
    fun gatewayBrokerId(expected: String) {
        assertEquals(BrokerId.valueOf(expected), world.activeIbHarness().gateway.brokerId)
    }

    @Then("the deployment candle status should be {string}")
    fun deploymentCandleStatus(expected: String) {
        val status = world.repository.deployments.value.first().touchTurnSession?.status
        assertEquals(TouchTurnCandleStatus.valueOf(expected), status)
    }

    @Then("the deployment should have ATR14 populated")
    fun deploymentHasAtr() {
        assertNotNull(world.repository.deployments.value.first().touchTurnSession?.atr14)
    }

    @Then("the session decision outcome should be {string}")
    fun sessionDecisionOutcome(expected: String) {
        val deployment = world.repository.deployments.value.first()
        val session = deployment.touchTurnSession
        val actual = session?.decisionOutcome
            ?: E2EEngineLiquidityHelper.decisionOutcome(deployment)
        assertNotNull(actual, "decision outcome missing (session cleared=${session == null})")
        assertEquals(
            TouchTurnSessionOutcome.valueOf(expected),
            actual,
            "actual decisionOutcome=$actual"
        )
    }

    @Then("the emulator should report an open position for {string}")
    fun emulatorHasOpenPosition(symbol: String) = runBlocking {
        awaitEmulatorPosition(symbol, open = true)
    }

    @Then("the emulator should have no open position for {string}")
    fun emulatorHasNoPosition(symbol: String) = runBlocking {
        awaitEmulatorPosition(symbol, open = false)
    }

    @Then("the emulator should have a working entry order for {string}")
    fun emulatorHasWorkingEntry(symbol: String) = runBlocking {
        val gateway = when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().gateway
            "hybrid" -> world.activeHybridHarness().executionGateway
            else -> error("Requires emulator or hybrid mode")
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val hasWorkingEntry = gateway.openOrders.value.any {
                it.symbol == symbol.uppercase() && it.remaining > 0
            }
            if (hasWorkingEntry) return@runBlocking
            delay(50)
        }
        assertTrue(
            gateway.openOrders.value.any { it.symbol == symbol.uppercase() && it.remaining > 0 },
            "expected working entry for $symbol orders=${gateway.openOrders.value}"
        )
    }

    @Then("the emulator should have flattened {string}")
    fun emulatorFlattened(symbol: String) {
        val gateway = when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().gateway
            "hybrid" -> world.activeHybridHarness().executionGateway
            else -> error("Requires emulator or hybrid mode")
        }
        assertTrue(
            gateway.positions.value.none { it.symbol == symbol.uppercase() && it.quantity != 0 },
            "expected flat position for $symbol"
        )
    }

    @Then("the mocked IB gateway should reject order placement")
    fun mockedIbRejectsOrders() {
        assertFailsWith<IllegalStateException> {
            world.activeHybridHarness().ibGateway.placeTouchTurnBracket(E2EBracketHelper.liquidityPlan())
        }
    }

    @Then("the emulator should have received a bracket for {string}")
    fun emulatorReceivedBracket(symbol: String) = runBlocking {
        val gateway = world.activeHybridHarness().executionGateway
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val hasBracket = gateway.openOrders.value.any { it.symbol == symbol.uppercase() } ||
                world.repository.deployments.value.first().touchTurnSession?.ordersPlacedForSession == true
            if (hasBracket) return@runBlocking
            delay(50)
        }
        val session = world.repository.deployments.value.first().touchTurnSession
        assertTrue(
            session?.ordersPlacedForSession == true || gateway.openOrders.value.isNotEmpty(),
            "expected bracket on emulator for $symbol"
        )
    }

    @Then("the mocked IB should have subscribed to live quotes for {string}")
    fun mockedIbSubscribed(symbol: String) {
        val calls = world.activeHybridHarness().ibGateway.ensureLiveMarketDataCalls
        assertTrue(calls.any { it.equals(symbol, ignoreCase = true) }, "expected IB subscribe for $symbol")
    }

    @When("synthetic quote streaming is ensured for {string}")
    fun ensureSyntheticQuoteStreaming(symbol: String) {
        world.activeEmulatorHarness().adapter.ensureStreamingMarketData(symbol)
    }

    @Then("the emulator should be streaming quotes for {string}")
    fun emulatorStreamingQuotes(symbol: String) = runBlocking {
        val gateway = world.activeEmulatorHarness().gateway
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (gateway.quotes.value.containsKey(symbol.uppercase())) return@runBlocking
            delay(50)
        }
        assertTrue(
            gateway.quotes.value.containsKey(symbol.uppercase()),
            "expected streaming quotes for $symbol, had ${gateway.quotes.value.keys}"
        )
    }

    @Then("the emulator should have released quotes for {string}")
    fun emulatorReleasedQuotes(symbol: String) = runBlocking {
        val gateway = world.activeEmulatorHarness().gateway
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!gateway.quotes.value.containsKey(symbol.uppercase())) return@runBlocking
            delay(50)
        }
        assertTrue(
            !gateway.quotes.value.containsKey(symbol.uppercase()),
            "expected quotes released for $symbol, still had ${gateway.quotes.value.keys}"
        )
    }

    @Then("the mocked IB gateway should remain market-data-only")
    fun mockedIbRemainsMarketDataOnly() {
        mockedIbRejectsOrders()
    }

    @Then("the IB gateway should have placed a bracket for {string}")
    fun ibGatewayPlacedBracket(symbol: String) = runBlocking {
        val gateway = world.activeIbHarness().gateway
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (gateway.placedBrackets.any { it.symbol == symbol.uppercase() }) return@runBlocking
            delay(50)
        }
        assertTrue(
            gateway.placedBrackets.any { it.symbol == symbol.uppercase() },
            "expected IB bracket for $symbol"
        )
    }

    @Then("the IB gateway should have flattened {string}")
    fun ibGatewayFlattened(symbol: String) {
        assertTrue(world.activeIbHarness().gateway.flattenedSymbols.contains(symbol.uppercase()))
    }

    @Then("the gateway connection state should be {string}")
    fun gatewayConnectionState(expected: String) {
        val state = when (world.brokerMode) {
            "ib" -> world.activeIbHarness().gateway.connectionState.value
            "emulator" -> world.activeEmulatorHarness().gateway.connectionState.value
            "hybrid" -> world.activeHybridHarness().ibGateway.connectionState.value
            else -> error("Unknown mode")
        }
        val expectedState = when (expected) {
            "Connected" -> GatewayConnectionState.Connected
            "Disconnected" -> GatewayConnectionState.Disconnected
            "Connecting" -> GatewayConnectionState.Connecting
            else -> error("Unknown connection state: $expected")
        }
        assertEquals(expectedState, state)
    }

    @Then("the session should have orders placed for the session")
    fun sessionOrdersPlacedForSession() = runBlocking {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val placed = world.repository.deployments.value.first()
                .touchTurnSession
                ?.ordersPlacedForSession
            if (placed == true) return@runBlocking
            delay(50)
        }
        assertEquals(
            true,
            world.repository.deployments.value.first().touchTurnSession?.ordersPlacedForSession,
            "ordersPlacedForSession never became true"
        )
    }

    @Then("the IB bracket entry should match canonical scenario {string}")
    fun ibBracketEntryMatchesCanonicalScenario(scenarioId: String) {
        val scenario = TouchTurnMarketFixtures.scenario(parseCanonicalScenarioId(scenarioId))
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val threshold = TouchTurnLogic.liquidityRangeThreshold(scenario.signalContext.atr14, rules)
        val setup = TouchTurnLogic.computeBracketSetup(scenario.openingBar, threshold, rules)
        assertTrue(setup.isLiquidityCandle, "expected liquidity candle for $scenarioId")
        val plan = world.activeIbHarness().gateway.placedBrackets.last()
        val entryOrder = plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }
        assertEquals(setup.entry, entryOrder.price, 0.0001)
    }

    @Then("the prepare check {string} should fail")
    fun prepareCheckShouldFail(checkId: String) {
        val prepare = world.repository.deployments.value.first().touchTurnPrepare
        assertNotNull(prepare, "touchTurnPrepare missing")
        val check = prepare.checks.firstOrNull { it.id == checkId }
        assertNotNull(check, "prepare check $checkId missing; had ${prepare.checks.map { it.id }}")
        assertEquals(TouchTurnPrepareStatus.FAIL.name, check.status)
    }

    @Then("session market data capture should be active")
    fun sessionMarketDataCaptureShouldBeActive() {
        assertNotNull(SessionMarketDataCapture.activeForDeployment(activeDeploymentId()))
    }

    @Then("session market data capture should be inactive")
    fun sessionMarketDataCaptureShouldBeInactive() {
        assertNull(SessionMarketDataCapture.activeForDeployment(activeDeploymentId()))
    }

    @Then("the instrument resolution should succeed")
    fun instrumentResolutionSucceeded() {
        assertTrue(world.lastInstrumentResolution)
    }

    private suspend fun awaitEmulatorPosition(symbol: String, open: Boolean) {
        val gateway = when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().gateway
            "hybrid" -> world.activeHybridHarness().executionGateway
            else -> error("Requires emulator or hybrid mode")
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val position = gateway.positions.value.firstOrNull {
                it.symbol == symbol.uppercase() && it.quantity != 0
            }
            if (open && position != null) return
            if (!open && position == null) return
            delay(50)
        }
        val positions = gateway.positions.value.filter { it.symbol == symbol.uppercase() }
        if (open) {
            assertTrue(positions.any { it.quantity != 0 }, "expected open position for $symbol")
        } else {
            assertTrue(positions.none { it.quantity != 0 }, "expected no open position for $symbol")
        }
    }
}
