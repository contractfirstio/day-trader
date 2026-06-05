package daytrader.replay

import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ReplaySourceValidationTest {

    @Test
    fun isReplayCapture_detectsReplayBrokerKind() {
        assertTrue(ReplaySourceValidation.isReplayCapture("REPLAY"))
        assertTrue(ReplaySourceValidation.isReplayCapture("replay"))
        assertFalse(ReplaySourceValidation.isReplayCapture("EMULATOR"))
        assertFalse(ReplaySourceValidation.isReplayCapture("EMULATOR_LIVE_IB_MARKET_DATA"))
    }

    @Test
    fun isEmulatorCapture_detectsOfflineEmulatorBrokerKind() {
        assertTrue(ReplaySourceValidation.isEmulatorCapture("EMULATOR"))
        assertTrue(ReplaySourceValidation.isEmulatorCapture("emulator"))
        assertFalse(ReplaySourceValidation.isEmulatorCapture("EMULATOR_LIVE_IB_MARKET_DATA"))
    }

    @Test
    fun isSupportedReplayCapture_allowsHybridAndIb() {
        assertTrue(ReplaySourceValidation.isSupportedReplayCapture("EMULATOR_LIVE_IB_MARKET_DATA"))
        assertTrue(ReplaySourceValidation.isSupportedReplayCapture("INTERACTIVE_BROKERS"))
        assertFalse(ReplaySourceValidation.isSupportedReplayCapture("EMULATOR"))
        assertFalse(ReplaySourceValidation.isSupportedReplayCapture("REPLAY"))
    }

    @Test
    fun requireReplayable_rejectsReplayCaptureBundle() {
        val contents = ReplaySessionFixtures.minimalContents().copy(
            manifestJson = ReplaySessionFixtures.minimalContents().manifestJson!!.replace(
                "\"brokerKind\": \"EMULATOR\"",
                "\"brokerKind\": \"REPLAY\""
            )
        )
        val bundle = SessionBundleLoader.load(contents).getOrThrow()

        assertFailsWith<IllegalArgumentException> {
            ReplaySourceValidation.requireReplayable(bundle)
        }
    }

    @Test
    fun requireReplayable_rejectsEmulatorCaptureBundle() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()

        assertFailsWith<IllegalArgumentException> {
            ReplaySourceValidation.requireReplayable(bundle)
        }
    }
}
