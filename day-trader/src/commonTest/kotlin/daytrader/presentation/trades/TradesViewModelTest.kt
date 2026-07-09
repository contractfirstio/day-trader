package daytrader.presentation.trades

import daytrader.data.FillsRepository
import daytrader.data.HistoricalTradeSync
import daytrader.data.persistence.MergeFillsResult
import daytrader.engine.support.FakeBrokerGateway
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.platform.TradingClock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

class TradesViewModelTest {
    @Test
    fun symbolFilterNarrowsRowsAndAggregatesPnL() = runBlocking {
        val repository = FakeFillsRepository(
            listOf(
                sampleFill(execId = "aapl-1", symbol = "AAPL", time = "2026-07-07", realizedPnL = 50.0),
                sampleFill(execId = "msft-1", symbol = "MSFT", time = "2026-07-07", realizedPnL = -10.0),
                sampleFill(execId = "aapl-2", symbol = "AAPL", time = "2026-07-07", realizedPnL = 25.0),
            )
        )
        val viewModel = TradesViewModel(repository = repository, tradingClock = fixedJulyClock())
        delay(50)
        viewModel.onDatePresetSelected(TradeDatePreset.ALL)
        delay(25)
        assertEquals(listOf("AAPL", "MSFT"), viewModel.uiState.value.availableSymbols)
        viewModel.onSymbolFilterSelected("AAPL")
        delay(25)
        assertEquals(2, viewModel.uiState.value.totalFillCount)
        assertEquals(setOf("aapl-1", "aapl-2"), viewModel.uiState.value.rows.map { it.execId }.toSet())
        assertEquals("2 trades · AAPL", viewModel.uiState.value.filterSummary?.tradeCountLabel)
        assertEquals("+$75.00", viewModel.uiState.value.filterSummary?.formattedRealizedPnL)
    }

    @Test
    fun customDateFilterNarrowsRowsAndAggregatesPnL() = runBlocking {
        val repository = FakeFillsRepository(
            listOf(
                sampleFill(execId = "day1", time = "2026-07-07", realizedPnL = 100.0),
                sampleFill(execId = "day2", time = "2026-07-08", realizedPnL = -40.0),
            )
        )
        val viewModel = TradesViewModel(repository = repository)
        delay(50)
        viewModel.onFilterFromDateChanged("2026-07-07")
        viewModel.onFilterToDateChanged("2026-07-07")
        delay(25)
        assertEquals(1, viewModel.uiState.value.totalFillCount)
        assertEquals("day1", viewModel.uiState.value.rows.single().execId)
        assertEquals("1 trade", viewModel.uiState.value.filterSummary?.tradeCountLabel)
        assertEquals("+$100.00", viewModel.uiState.value.filterSummary?.formattedRealizedPnL)
        assertEquals("Jul 7, 2026", viewModel.uiState.value.rows.single().formattedTime)
    }

    @Test
    fun allPresetShowsEveryStoredTrade() = runBlocking {
        val recentTime = LocalDateTime.now().minusDays(5).format(IB_TIME_FORMAT)
        val oldTime = LocalDateTime.now().minusDays(45).format(IB_TIME_FORMAT)
        val repository = FakeFillsRepository(
            listOf(
                sampleFill(execId = "recent", time = recentTime),
                sampleFill(execId = "old", time = oldTime)
            )
        )
        val viewModel = TradesViewModel(repository = repository)
        delay(50)
        viewModel.onDatePresetSelected(TradeDatePreset.ALL)
        delay(25)
        assertEquals(2, viewModel.uiState.value.totalFillCount)
    }

    @Test
    fun filtersFillsToLastThirtyDays() = runBlocking {
        val recentTime = LocalDateTime.now().minusDays(5).format(IB_TIME_FORMAT)
        val oldTime = LocalDateTime.now().minusDays(45).format(IB_TIME_FORMAT)
        val repository = FakeFillsRepository(
            listOf(
                sampleFill(execId = "recent", time = recentTime),
                sampleFill(execId = "old", time = oldTime)
            )
        )
        val viewModel = TradesViewModel(repository = repository)
        delay(50)
        assertEquals(1, viewModel.uiState.value.totalFillCount)
        assertEquals("recent", viewModel.uiState.value.rows.single().execId)
    }

    @Test
    fun sortsByTimeDescendingByDefault() = runBlocking {
        val earlier = LocalDateTime.now().minusDays(1).withHour(9).format(IB_TIME_FORMAT)
        val later = LocalDateTime.now().minusDays(1).withHour(15).format(IB_TIME_FORMAT)
        val repository = FakeFillsRepository(
            listOf(
                sampleFill(execId = "earlier", time = earlier),
                sampleFill(execId = "later", time = later)
            )
        )
        val viewModel = TradesViewModel(repository = repository)
        delay(50)
        assertEquals("later", viewModel.uiState.value.rows.first().execId)
        assertEquals("earlier", viewModel.uiState.value.rows.last().execId)
    }

    @Test
    fun syncFromIbStartsWhileConnected() = runBlocking {
        val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
        val repository = FakeFillsRepository(emptyList())
        val viewModel = TradesViewModel(
            repository = repository,
            executionGateway = gateway,
            brokerKind = BrokerKind.INTERACTIVE_BROKERS
        )
        delay(50)
        assertTrue(viewModel.uiState.value.canSync)
        viewModel.onSyncClick()
        delay(25)
        assertTrue(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun syncReportsFlexSetupHintWhenIbAndNoFlexConfigured() = runBlocking {
        val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
        val repository = FakeFillsRepository(emptyList())
        val viewModel = TradesViewModel(
            repository = repository,
            executionGateway = gateway,
            brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            syncTimeoutMs = 300L
        )
        delay(50)
        viewModel.onSyncClick()
        delay(500)
        assertEquals(
            "No trades stored yet. Open IB Settings and add Flex token + trades query ID (live account only), then sync.",
            viewModel.uiState.value.syncMessage
        )
    }

    @Test
    fun syncMergesFlexTradesIntoRepository() = runBlocking {
        val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
        val repository = FakeFillsRepository(emptyList())
        val flexTrade = sampleFill(execId = "flex-1", time = "20260601  10:00:00")
        val viewModel = TradesViewModel(
            repository = repository,
            executionGateway = gateway,
            historicalTradeSync = HistoricalTradeSync { Result.success(listOf(flexTrade)) },
            brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            syncTimeoutMs = 300L
        )
        delay(50)
        viewModel.onSyncClick()
        delay(500)
        assertEquals(1, repository.fills.value.size)
        assertEquals("Added 1 trade(s) (1 from Flex) — 1 stored in total.", viewModel.uiState.value.syncMessage)
    }

    @Test
    fun syncRefreshesFlexTradeDatesWhenExecIdsAlreadyStored() = runBlocking {
        val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
        val repository = FakeFillsRepository(
            listOf(sampleFill(execId = "flex-1", time = "2026-07-07"))
        )
        val viewModel = TradesViewModel(
            repository = repository,
            executionGateway = gateway,
            historicalTradeSync = HistoricalTradeSync {
                Result.success(listOf(sampleFill(execId = "flex-1", time = "2026-07-06")))
            },
            brokerKind = BrokerKind.INTERACTIVE_BROKERS,
            syncTimeoutMs = 300L
        )
        delay(50)
        viewModel.onSyncClick()
        delay(500)
        assertEquals("2026-07-06", repository.fills.value.single().time)
        assertEquals(
            "Refreshed 1 Flex trade(s) from IB — 1 stored in total.",
            viewModel.uiState.value.syncMessage
        )
    }

    @Test
    fun syncDisabledWhenDisconnected() = runBlocking {
        val gateway = FakeBrokerGateway(brokerId = BrokerId.INTERACTIVE_BROKERS)
        gateway.disconnect()
        val repository = FakeFillsRepository(emptyList())
        val viewModel = TradesViewModel(
            repository = repository,
            executionGateway = gateway,
            brokerKind = BrokerKind.INTERACTIVE_BROKERS
        )
        delay(50)
        assertFalse(viewModel.uiState.value.canSync)
        viewModel.onSyncClick()
        delay(25)
        assertEquals(
            "Open IB Settings (or menu Settings → Interactive Brokers) and add Flex token + query ID. Flex requires a live IB account.",
            viewModel.uiState.value.syncMessage
        )
    }

    @Test
    fun respectsInjectedTradingClockCutoff() = runBlocking {
        val fixedNow = LocalDateTime.of(2026, 6, 15, 12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val clock = object : TradingClock {
            override fun nowEpochMillis(): Long = fixedNow
            override suspend fun delayMillis(ms: Long) = delay(ms)
        }
        val insideWindow = LocalDateTime.of(2026, 6, 1, 10, 0).format(IB_TIME_FORMAT)
        val outsideWindow = LocalDateTime.of(2026, 4, 1, 10, 0).format(IB_TIME_FORMAT)
        val repository = FakeFillsRepository(
            listOf(
                sampleFill(execId = "inside", time = insideWindow),
                sampleFill(execId = "outside", time = outsideWindow)
            )
        )
        val viewModel = TradesViewModel(repository = repository, tradingClock = clock)
        delay(50)
        assertEquals(1, viewModel.uiState.value.totalFillCount)
        assertEquals("inside", viewModel.uiState.value.rows.single().execId)
    }

    private fun fixedJulyClock(): TradingClock {
        val fixedNow = LocalDateTime.of(2026, 7, 9, 12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return object : TradingClock {
            override fun nowEpochMillis(): Long = fixedNow
            override suspend fun delayMillis(ms: Long) = delay(ms)
        }
    }

    private fun sampleFill(
        execId: String,
        time: String,
        symbol: String = "AAPL",
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
        time = time,
        commission = 0.35,
        realizedPnL = realizedPnL
    )

    companion object {
        private val IB_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd  HH:mm:ss")
    }
}

private class FakeFillsRepository(
    initial: List<BrokerFill>
) : FillsRepository {
    private val _fills = MutableStateFlow(initial)
    override val fills: StateFlow<List<BrokerFill>> = _fills.asStateFlow()

    override suspend fun awaitHydrated() = Unit

    override fun mergeFills(incoming: List<BrokerFill>): MergeFillsResult {
        if (incoming.isEmpty()) return MergeFillsResult(_fills.value, added = 0, updated = 0)
        val merged = daytrader.data.persistence.TradesPersistence.mergeFills(_fills.value, incoming)
        if (merged.added == 0 && merged.updated == 0) return merged
        _fills.value = merged.fills
        return merged
    }

    override fun mergeFlexFills(incoming: List<BrokerFill>): MergeFillsResult {
        if (incoming.isEmpty()) return MergeFillsResult(_fills.value, added = 0, updated = 0)
        val merged = daytrader.data.persistence.TradesPersistence.mergeFlexFills(_fills.value, incoming)
        if (merged.added == 0 && merged.updated == 0) return merged
        _fills.value = merged.fills
        return merged
    }

    override fun flushPersistenceBlocking() = Unit
}
