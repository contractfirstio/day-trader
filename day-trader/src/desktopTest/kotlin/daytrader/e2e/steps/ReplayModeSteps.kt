package daytrader.e2e.steps

import daytrader.e2e.E2EWorld
import daytrader.e2e.support.E2EProcessCleanup
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerKind
import daytrader.replay.ReplayComparison
import daytrader.replay.ReplaySessionRunner
import daytrader.replay.SessionReplayCatalog
import daytrader.replay.support.ReplaySessionFixtures
import daytrader.replay.support.SessionBundleTestWriter
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class ReplayModeSteps {
    private val world = E2EWorld()
    private var scopeRoot: java.nio.file.Path? = null
    private var catalogEntries: List<daytrader.replay.SessionReplayEntry> = emptyList()
    private var replayScope: CoroutineScope? = null
    private var replayComparison: ReplayComparison? = null

    @Before
    fun resetWorld() {
        E2EProcessCleanup.resetAll()
        cleanupReplayResources()
        world.reset()
        E2EProcessCleanup.requireClean("Replay Cucumber @Before")
    }

    @After
    fun shutdownWorld() {
        cleanupReplayResources()
        world.reset()
        E2EProcessCleanup.requireClean("Replay Cucumber @After")
    }

    @Given("a hybrid session capture on disk for {string}")
    fun hybridSessionCaptureOnDisk(symbol: String) {
        val root = Files.createTempDirectory("replay-bdd-catalog")
        scopeRoot = root
        val scopeDir = root.resolve(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.dataDirectorySegment)
        val contents = ReplaySessionFixtures.asHybridReplayable(ReplaySessionFixtures.minimalContents())
        SessionBundleTestWriter.writeSessionDirectory(
            scopeRoot = scopeDir,
            deploymentId = "dep-replay-catalog",
            sessionId = "sess-replay-catalog",
            contents = contents,
        )
        world.symbol = symbol
    }

    @When("the replay catalog is discovered under paper-live-ib scope")
    fun discoverReplayCatalog() {
        val root = requireNotNull(scopeRoot) { "replay scope root not seeded" }
        val scopeDir = root.resolve(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.dataDirectorySegment)
        catalogEntries = SessionReplayCatalog.discoverUnderScope(
            scopeRoot = scopeDir,
            brokerScope = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.dataDirectorySegment,
        )
    }

    @Then("the replay catalog should list {string} for session date {string}")
    fun replayCatalogListsSymbol(symbol: String, sessionDate: String) {
        assertTrue(catalogEntries.isNotEmpty(), "expected catalog entries, got none")
        val match = catalogEntries.singleOrNull { it.symbol == symbol && it.sessionDate == sessionDate }
        assertNotNull(match, "catalog=$catalogEntries")
    }

    @Given("a minimal hybrid session capture on disk")
    fun minimalHybridSessionCaptureOnDisk() {
        val root = Files.createTempDirectory("replay-bdd-runner")
        scopeRoot = root
        val scopeDir = root.resolve(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.dataDirectorySegment)
        SessionBundleTestWriter.writeSessionDirectory(
            scopeRoot = scopeDir,
            deploymentId = "dep-replay-minimal",
            sessionId = "sess-replay-minimal",
            contents = ReplaySessionFixtures.asHybridReplayable(ReplaySessionFixtures.minimalContents()),
        )
    }

    @When("the capture replays through the session runner")
    fun captureReplaysThroughSessionRunner() = runBlocking {
        val root = requireNotNull(scopeRoot) { "replay scope root not seeded" }
        val scopeDir = root.resolve(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA.dataDirectorySegment)
        val sessionDir = scopeDir.resolve("sessions/dep-replay-minimal/sess-replay-minimal")
        val bundle = daytrader.replay.SessionBundleDirectoryReader
            .loadReplayableFromDirectory(sessionDir.toString())
            .getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        replayScope = scope
        val repository = InMemoryStrategyDeploymentRepository()
        replayComparison = ReplaySessionRunner(bundle, repository, scope).run()
    }

    @Then("the replay comparison outcome should match ground truth")
    fun replayComparisonOutcomeMatchesGroundTruth() {
        val comparison = assertNotNull(replayComparison)
        assertTrue(
            comparison.outcomeMatches,
            "expected=${comparison.expectedOutcome} actual=${comparison.actualOutcome}",
        )
    }

    private fun cleanupReplayResources() {
        replayScope?.cancel()
        replayScope = null
        replayComparison = null
        scopeRoot?.toFile()?.deleteRecursively()
        scopeRoot = null
        catalogEntries = emptyList()
    }
}
