package daytrader.replay

import daytrader.gateway.BrokerKind

object ReplaySourceValidation {
    fun isReplayCapture(brokerKind: String?): Boolean =
        brokerKind?.trim()?.equals(BrokerKind.REPLAY.name, ignoreCase = true) == true ||
            brokerKind?.trim()?.equals(BrokerKind.REPLAY.dataDirectorySegment, ignoreCase = true) == true

    fun isEmulatorCapture(brokerKind: String?): Boolean =
        brokerKind?.trim()?.equals(BrokerKind.EMULATOR.name, ignoreCase = true) == true ||
            brokerKind?.trim()?.equals(BrokerKind.EMULATOR.dataDirectorySegment, ignoreCase = true) == true

    fun isSupportedReplayCapture(brokerKind: String?): Boolean =
        !isReplayCapture(brokerKind) && !isEmulatorCapture(brokerKind)

    fun requireReplayable(bundle: SessionBundle) {
        when {
            isReplayCapture(bundle.brokerKind) ->
                require(false) {
                    "Sessions captured in replay mode cannot be replayed again. " +
                        "Use the original hybrid (paper-live-ib) capture."
                }
            isEmulatorCapture(bundle.brokerKind) ->
                require(false) {
                    "Offline emulator sessions cannot be replayed. " +
                        "Use a hybrid (paper-live-ib) session capture with live IB market data."
                }
        }
    }
}
