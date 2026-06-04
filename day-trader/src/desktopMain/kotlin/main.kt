import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import daytrader.data.GatewayPositionRepository
import daytrader.gateway.BrokerKind
import daytrader.gateway.BrokerRuntime
import daytrader.platform.AppFileSystem
import daytrader.platform.CrashLogging
import daytrader.platform.DesktopFolderPicker
import daytrader.replay.SessionBundleDirectoryReader
import daytrader.replay.SessionReplayCatalog
import daytrader.ui.App
import daytrader.ui.ApplicationQuitConfirmDialog
import daytrader.ui.ApplicationQuitCoordinator
import daytrader.ui.BrokerSelectionScreen
import daytrader.ui.SessionReplayPickerScreen

private sealed interface StartupPhase {
    data object ChooseBroker : StartupPhase
    data object PickSession : StartupPhase
    data class Running(val runtime: BrokerRuntime) : StartupPhase
}

fun main() {
    CrashLogging.installDefaultHandlers()
    application {
        var phase by remember { mutableStateOf<StartupPhase>(StartupPhase.ChooseBroker) }
        var pendingSelection by remember { mutableStateOf(BrokerKind.fromEnvironment()) }
        var applicationQuit by remember { mutableStateOf<ApplicationQuitCoordinator?>(null) }
        var showQuitConfirm by remember { mutableStateOf(false) }

        fun performApplicationQuit() {
            if (phase is StartupPhase.Running) {
                applicationQuit?.stopRunningSessions?.invoke()
                (phase as StartupPhase.Running).runtime.shutdown()
            }
            exitApplication()
        }

        val windowTitle = when (val current = phase) {
            StartupPhase.ChooseBroker -> "Day Trader — Choose Broker"
            StartupPhase.PickSession -> "Day Trader — Choose Session"
            is StartupPhase.Running -> when (current.runtime.kind) {
                BrokerKind.EMULATOR -> "Day Trader (Broker Emulator)"
                BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Day Trader (Paper · Live IB Data)"
                BrokerKind.REPLAY -> "Day Trader (Session Replay)"
                BrokerKind.INTERACTIVE_BROKERS -> "Day Trader"
            }
        }

        val windowState = rememberWindowState(size = DpSize(680.dp, 620.dp))
        LaunchedEffect(phase) {
            windowState.size = when (phase) {
                StartupPhase.ChooseBroker, StartupPhase.PickSession -> DpSize(680.dp, 620.dp)
                is StartupPhase.Running -> DpSize(2234.dp, 1357.dp)
            }
        }

        Window(
            onCloseRequest = {
                val quit = applicationQuit
                if (phase is StartupPhase.Running && quit != null && quit.hasRunningSessions()) {
                    showQuitConfirm = true
                } else {
                    performApplicationQuit()
                }
            },
            title = windowTitle,
            state = windowState
        ) {
            MaterialTheme {
                if (showQuitConfirm) {
                    ApplicationQuitConfirmDialog(
                        runningSymbols = applicationQuit?.runningSymbols?.invoke().orEmpty(),
                        onConfirmQuit = {
                            showQuitConfirm = false
                            performApplicationQuit()
                        },
                        onDismiss = { showQuitConfirm = false }
                    )
                }
                when (val current = phase) {
                    StartupPhase.ChooseBroker -> BrokerSelectionScreen(
                        selected = pendingSelection,
                        onSelect = { pendingSelection = it },
                        onContinue = {
                            if (pendingSelection == BrokerKind.REPLAY) {
                                phase = StartupPhase.PickSession
                            } else {
                                AppFileSystem.configureDataScope(pendingSelection)
                                val runtime = BrokerRuntime.create(pendingSelection)
                                runtime.start()
                                phase = StartupPhase.Running(runtime)
                            }
                        }
                    )

                    StartupPhase.PickSession -> {
                        val catalogEntries = remember {
                            SessionReplayCatalog.discover(AppFileSystem.applicationDataRoot())
                        }
                        SessionReplayPickerScreen(
                            entries = catalogEntries,
                            onBrowseFolder = { DesktopFolderPicker.pickDirectory("Select captured session folder") },
                            onContinue = { entry ->
                                val bundle = SessionBundleDirectoryReader
                                    .loadFromDirectory(entry.directoryPath)
                                    .getOrElse { error(it.message ?: "Failed to load session bundle") }
                                AppFileSystem.configureDataScope(BrokerKind.REPLAY)
                                val runtime = BrokerRuntime.createReplay(bundle)
                                phase = StartupPhase.Running(runtime)
                            },
                            onBack = { phase = StartupPhase.ChooseBroker }
                        )
                    }

                    is StartupPhase.Running -> {
                        val positionRepository = remember(current.runtime) {
                            GatewayPositionRepository(current.runtime.gateway)
                        }
                        App(
                            brokerGateway = current.runtime.gateway,
                            positionRepository = positionRepository,
                            brokerKind = current.runtime.kind,
                            touchTurnSessionGateway = current.runtime.marketDataGateway
                                ?: current.runtime.gateway,
                            ensureLiveMarketData = current.runtime.ensureLiveMarketData,
                            releaseLiveMarketData = current.runtime.releaseLiveMarketData,
                            replayHybridRuntime = current.runtime.replayHybridRuntime,
                            replayBundle = current.runtime.replayBundle,
                            onRegisterApplicationQuit = { applicationQuit = it }
                        )
                    }
                }
            }
        }
    }
}
