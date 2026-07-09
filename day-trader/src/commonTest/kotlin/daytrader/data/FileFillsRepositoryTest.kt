package daytrader.data

import daytrader.engine.support.FakeBrokerGateway
import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class FileFillsRepositoryTest {
    @Test
    fun liveGatewaySnapshot_mergesWithoutClearingOnEmptySnapshot() = runBlocking {
        val gateway = FakeBrokerGateway()
        val repository = FileFillsRepository(gateway)
        repository.awaitHydrated()

        gateway.setFills(
            listOf(
                sampleFill(execId = "live-1"),
                sampleFill(execId = "live-2", symbol = "MSFT")
            )
        )
        delay(50)
        assertEquals(2, repository.fills.value.size)

        gateway.setFills(emptyList())
        delay(50)
        assertEquals(2, repository.fills.value.size, "disconnect clear must not wipe stored fills")
    }

    @Test
    fun liveGatewaySnapshot_enrichesExistingFill() = runBlocking {
        val gateway = FakeBrokerGateway()
        val repository = FileFillsRepository(gateway)
        repository.awaitHydrated()

        gateway.setFills(listOf(sampleFill(execId = "live-1")))
        delay(50)
        gateway.setFills(
            listOf(
                sampleFill(execId = "live-1", commission = 0.42, realizedPnL = 4.0)
            )
        )
        delay(50)
        val fill = repository.fills.value.single()
        assertEquals(0.42, fill.commission)
        assertEquals(4.0, fill.realizedPnL)
    }

    private fun sampleFill(
        execId: String,
        symbol: String = "AAPL",
        commission: Double? = null,
        realizedPnL: Double? = null,
    ) = BrokerFill(
        execId = execId,
        orderId = 1,
        permId = 100L,
        parentOrderId = 0,
        symbol = symbol,
        side = "BOT",
        quantity = 10,
        price = 150.0,
        time = "20260601  10:00:00",
        commission = commission,
        realizedPnL = realizedPnL,
    )
}
