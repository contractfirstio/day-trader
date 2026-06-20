package daytrader.e2e

import daytrader.domain.DeploymentSymbolResolver
import daytrader.domain.StrategyType
import daytrader.domain.SymbolImportCsvParser
import daytrader.domain.defaultStrategyDeployment
import daytrader.e2e.support.E2EStrategiesViewModelHarness
import daytrader.e2e.support.closeE2EHarness
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * End-to-end: CSV parse + IB resolve + deployment creation surfaces in Strategies list.
 */
class E2ESymbolImportTest {
    @Test
    fun viewModel_csvParsedSymbol_resolvesAndAppearsInDeploymentList() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var harness: E2EStrategiesViewModelHarness? = null
        try {
            val repository = InMemoryStrategyDeploymentRepository()
            val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
            harness = E2EStrategiesViewModelHarness.createWithGateway(
                scope = scope,
                repository = repository,
                gateway = gateway,
                brokerKind = BrokerKind.EMULATOR,
            )
            harness.start()

            val parsed = SymbolImportCsvParser.parse("symbol,exchange\nNVDA,US")
            assertTrue(parsed.errors.isEmpty())
            val row = parsed.rows.single()

            val resolved = DeploymentSymbolResolver.resolveForImport(
                symbol = row.symbol,
                expectedZoneId = "America/New_York",
                gateway = gateway,
                connected = true
            ).getOrThrow()

            val deployment = defaultStrategyDeployment(
                strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                symbol = row.symbol,
                maxDollars = 500,
                marketZoneId = resolved.marketZoneId,
                currencyCode = resolved.currencyCode,
                marketSource = resolved.source,
                companyName = resolved.companyName,
                instrument = resolved.identity,
                brokerKind = harness.brokerKind,
            )
            repository.add(deployment)
            harness.selectDeployment(deployment.id)
            kotlinx.coroutines.delay(50)

            assertEquals(1, harness.viewModel.listState.value.totalCount)
            val listRow = harness.viewModel.listState.value.filteredRows.single {
                it.name.contains("NVDA", ignoreCase = true)
            }
            assertTrue(listRow.name.contains("NVDA", ignoreCase = true))
            assertEquals(1, harness.viewModel.listState.value.totalCount)
        } finally {
            harness.closeE2EHarness()
            scope.cancel()
        }
    }
}
