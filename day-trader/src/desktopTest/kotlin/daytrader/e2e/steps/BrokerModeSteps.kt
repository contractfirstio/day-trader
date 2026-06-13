package daytrader.e2e.steps

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.EmulatorPricingSource
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.e2e.E2EWorld
import daytrader.e2e.support.E2EBracketHelper
import daytrader.e2e.support.E2ESessionDriver
import daytrader.e2e.support.E2ETestFixtures
import daytrader.e2e.support.EmulatorModeTestHarness
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
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class BrokerModeSteps {
    private val world = E2EWorld()

    @Before
    fun resetWorld() {
        world.reset()
    }

    @After
    fun shutdownWorld() {
        world.reset()
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
            current
                .withFirstFifteenMinuteCandle(
                    sessionDate = E2ETestFixtures.SESSION_DATE,
                    candle = bar,
                    atr14 = E2ETestFixtures.ATR14,
                    volumeSma20 = E2ETestFixtures.VOLUME_SMA20
                )
                .withOpeningBarClosedMilestone()
                .withClosedFirstFifteenMinuteCandle(bar)
        }
    }

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
            "emulator" -> world.activeEmulatorHarness().createEngine(world.repository)
            "hybrid" -> world.activeHybridHarness().createEngine(world.repository)
            "ib" -> world.activeIbHarness().createEngine(world.repository, world.scope)
            else -> error("Unknown broker mode: ${world.brokerMode}")
        }
        world.engine = engine
        world.driver = E2ESessionDriver(engine, world.repository)
        when (world.brokerMode) {
            "emulator" -> world.activeEmulatorHarness().start()
            "hybrid" -> {
                world.activeHybridHarness().start()
                seedHybridLiveQuote()
            }
            "ib" -> world.activeIbHarness().start()
        }
        world.driver!!.startEngine()
        // Avoid BrokerConnected bootstrap retry clobbering pre-seeded READY sessions.
        delay(100)
    }

    private fun activeDeploymentId(): String =
        world.repository.deployments.value.firstOrNull()?.id ?: E2ETestFixtures.DEPLOYMENT_ID

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
        if (session?.entryOrdersPermitted == true && session.setup != null) {
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
        gateway.placeTouchTurnBracket(E2EBracketHelper.liquidityPlan(symbol))
        delay(200)
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
        val session = world.repository.deployments.value.first().touchTurnSession
        assertNotNull(session, "touchTurnSession missing")
        assertEquals(
            TouchTurnSessionOutcome.valueOf(expected),
            session.decisionOutcome,
            "actual decisionOutcome=${session.decisionOutcome}"
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
