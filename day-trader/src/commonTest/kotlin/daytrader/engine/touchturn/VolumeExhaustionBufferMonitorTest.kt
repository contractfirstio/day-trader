package daytrader.engine.touchturn

import daytrader.domain.TouchTurnDefaults
import daytrader.execution.ExecutionManager
import daytrader.gateway.LiveQuote
import daytrader.marketdata.MarketDataProvider
import daytrader.marketdata.VolumeTick
import daytrader.replay.ReplayClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

class VolumeExhaustionBufferMonitorTest {

    @Test
    fun bufferMonitor_virtualClock_exitsWhenNowPassesObservationDeadline() = runBlocking {
        val clock = ReplayClock(initialEpochMs = 1_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val volumeTicks = MutableSharedFlow<VolumeTick>(extraBufferCapacity = 8)
        val marketData = FakeMarketData(volumeTicks)
        val execution = RecordingExecution()
        val monitor = VolumeExhaustionBufferMonitor(
            marketData = marketData,
            execution = execution,
            scope = scope,
            nowEpochMillis = clock::now,
            delayMillis = { delay(1) }
        )

        monitor.start(
            instanceId = "dep-1",
            symbol = "AAPL",
            entryOrderId = 42,
            volumeThreshold = 1_000_000.0
        )
        clock.advanceBy(TouchTurnDefaults.VOLUME_BUFFER_OBSERVATION_MS + 1)
        delay(100)

        assertTrue(execution.cancelledOrderIds.isEmpty(), "low volume should not cancel entry")
        monitor.stopAll()
    }

    @Test
    fun bufferMonitor_virtualClock_cancelsWhenVolumeThresholdExceeded() = runBlocking {
        val clock = ReplayClock(initialEpochMs = 1_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val volumeTicks = MutableSharedFlow<VolumeTick>(extraBufferCapacity = 8)
        val marketData = FakeMarketData(volumeTicks)
        val execution = RecordingExecution()
        val monitor = VolumeExhaustionBufferMonitor(
            marketData = marketData,
            execution = execution,
            scope = scope,
            nowEpochMillis = clock::now,
            delayMillis = { delay(1) }
        )

        monitor.start(
            instanceId = "dep-1",
            symbol = "AAPL",
            entryOrderId = 7,
            volumeThreshold = 100.0
        )
        delay(50)
        volumeTicks.emit(VolumeTick(symbol = "AAPL", volumeDelta = 150.0, epochMillis = clock.now()))
        delay(200)

        assertEquals(listOf(7), execution.cancelledOrderIds)
        monitor.stopAll()
    }

    private class FakeMarketData(
        private val volumeTicks: Flow<VolumeTick>
    ) : MarketDataProvider {
        override val quotes: StateFlow<Map<String, LiveQuote>> = MutableStateFlow(emptyMap())

        override suspend fun fetchTouchTurnSignalContext(
            symbol: String,
            instrument: daytrader.domain.InstrumentIdentity?,
            isClosedBarRefetch: Boolean,
            marketZoneId: String?,
            allowMissingTodayOpeningBar: Boolean
        ) = error("not used")

        override fun observeVolumeTicks(symbol: String): Flow<VolumeTick> = volumeTicks

        override fun ensureStreaming(symbol: String, instrument: daytrader.domain.InstrumentIdentity?) = Unit

        override fun releaseStreaming(symbol: String, instrument: daytrader.domain.InstrumentIdentity?) = Unit
    }

    private class RecordingExecution : ExecutionManager {
        val cancelledOrderIds = mutableListOf<Int>()
        override val openOrders = MutableStateFlow<List<daytrader.gateway.WorkingOrder>>(emptyList())
        override val positions = MutableStateFlow<List<daytrader.gateway.AccountPosition>>(emptyList())
        override val fills = MutableStateFlow<List<daytrader.gateway.BrokerFill>>(emptyList())

        override suspend fun cancelOrder(orderId: Int): Boolean {
            cancelledOrderIds += orderId
            return true
        }

        override suspend fun awaitOrderSubmitted(orderId: Int, timeoutMs: Long): Boolean = true

        override fun cancelOpenOrdersForSymbol(symbol: String) = Unit

        override fun flattenSymbolForSymbol(symbol: String) = Unit

        override fun placeTouchTurnBracket(plan: daytrader.domain.TouchTurnOrderPlan) =
            daytrader.execution.BracketPlacementResult(entryOrderId = null, plan = plan)

        override fun refreshFills() = Unit
    }
}
