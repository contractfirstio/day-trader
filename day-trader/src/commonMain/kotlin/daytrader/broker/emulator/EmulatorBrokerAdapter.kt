package daytrader.broker.emulator

import daytrader.gateway.BrokerAdapter
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayCommand
import daytrader.gateway.GatewayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EmulatorBrokerAdapter(
    private val emit: (GatewayEvent) -> Unit,
    private val receiveCommand: suspend () -> GatewayCommand,
    private val config: BrokerEmulatorConfig = BrokerEmulatorConfig.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : BrokerAdapter {

    override val brokerId: BrokerId = BrokerId.EMULATOR

    private val engine = BrokerEmulatorEngine(config = config, emit = emit)
    private var commandLoopJob: Job? = null
    private var marketJob: Job? = null
    private var orderJob: Job? = null

    override fun start() {
        emit(GatewayEvent.ConnectionStateChanged(daytrader.gateway.GatewayConnectionState.Disconnected))
        commandLoopJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                when (val command = receiveCommand()) {
                    GatewayCommand.Connect -> {
                        engine.handleConnect()
                        engine.finishConnect()
                    }
                    GatewayCommand.Disconnect -> engine.handleDisconnect()
                    GatewayCommand.Reconnect -> engine.handleReconnect()
                    GatewayCommand.Shutdown -> {
                        engine.handleShutdown()
                        return@launch
                    }
                    is GatewayCommand.FetchFirstFifteenMinuteCandle ->
                        launch { engine.fetchFirstFifteenMinuteCandle(command.requestId, command.symbol) }
                    is GatewayCommand.FetchFourteenDayAdr ->
                        launch { engine.fetchFourteenDayAdr(command.requestId, command.symbol) }
                }
            }
        }
        marketJob = scope.launch {
            while (isActive) {
                delay(config.marketTickIntervalMs)
                if (engine.shouldRunMarketTicks()) {
                    engine.runMarketTick()
                }
            }
        }
        orderJob = scope.launch {
            while (isActive) {
                delay(config.orderProgressIntervalMs)
                if (engine.shouldRunOrderSim()) {
                    engine.runOrderProgressStep()
                }
            }
        }
    }

    override fun shutdown() {
        engine.handleShutdown()
        commandLoopJob?.cancel()
        marketJob?.cancel()
        orderJob?.cancel()
    }
}
