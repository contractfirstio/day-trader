package daytrader.e2e

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Tags

object E2EBrokerModeTags {
    const val E2E = "e2e"
    const val EMULATOR = "e2e-emulator"
    /** Paper trading: emulator execution with live IB market data (hybrid). */
    const val PAPER = "e2e-paper"
    const val IB = "e2e-ib"
    const val REPLAY = "e2e-replay"
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tags(Tag(E2EBrokerModeTags.E2E), Tag(E2EBrokerModeTags.EMULATOR))
annotation class E2EEmulatorTest

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tags(Tag(E2EBrokerModeTags.E2E), Tag(E2EBrokerModeTags.PAPER))
annotation class E2EPaperTest

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tags(Tag(E2EBrokerModeTags.E2E), Tag(E2EBrokerModeTags.IB))
annotation class E2EIbTest

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tags(Tag(E2EBrokerModeTags.E2E), Tag(E2EBrokerModeTags.REPLAY))
annotation class E2EReplayTest
