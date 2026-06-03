package daytrader.presentation.strategies

import daytrader.domain.TouchTurnSessionContext

/**
 * Broker-agnostic order lifecycle for Touch Turn live UI.
 *
 * Combines session commit state (engine/domain) with abstract broker activity signals
 * ([hasOpenPosition], [hasOpenOrders]) so presentation code does not branch on emulator vs hybrid.
 */
enum class TouchTurnOrderLifecyclePhase {
    NOT_PLACED,
    SUBMITTED_PENDING_BROKER_VISIBILITY,
    AWAITING_ENTRY,
    IN_POSITION,
    CLOSED_NO_FILL,
    CLOSED
}

data class TouchTurnOrderLifecycleUi(
    val phase: TouchTurnOrderLifecyclePhase,
    val showLiveOrdersPanel: Boolean,
    val showOrdersPreview: Boolean,
    val showLiveOrderChart: Boolean,
    val statusMessage: String?
)

object TouchTurnOrderLifecycleResolver {
    fun resolve(
        session: TouchTurnSessionContext?,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        inActiveTrade: Boolean,
        sessionEnded: Boolean,
        hasSessionTrades: Boolean
    ): TouchTurnOrderLifecycleUi {
        val ordersCommitted = session?.ordersPlacedForSession == true
        val positionOpened = session?.milestones?.positionOpenedAt != null

        if (sessionEnded) {
            return when {
                hasSessionTrades || positionOpened || hasOpenPosition ->
                    closed(showOrdersPreview = true)
                ordersCommitted ->
                    TouchTurnOrderLifecycleUi(
                        phase = TouchTurnOrderLifecyclePhase.CLOSED_NO_FILL,
                        showLiveOrdersPanel = false,
                        showOrdersPreview = true,
                        showLiveOrderChart = false,
                        statusMessage = TouchTurnSessionReasonUi.orderLifecycleMessage(
                            TouchTurnOrderLifecyclePhase.CLOSED_NO_FILL,
                            session
                        )
                    )
                else -> notPlaced(previewWhenIdle = true, session = session)
            }
        }

        val phase = when {
            inActiveTrade || hasOpenPosition -> TouchTurnOrderLifecyclePhase.IN_POSITION
            hasOpenOrders && ordersCommitted -> TouchTurnOrderLifecyclePhase.AWAITING_ENTRY
            ordersCommitted -> TouchTurnOrderLifecyclePhase.SUBMITTED_PENDING_BROKER_VISIBILITY
            else -> TouchTurnOrderLifecyclePhase.NOT_PLACED
        }
        return livePhase(phase, session)
    }

    private fun notPlaced(
        previewWhenIdle: Boolean,
        session: TouchTurnSessionContext? = null
    ): TouchTurnOrderLifecycleUi =
        TouchTurnOrderLifecycleUi(
            phase = TouchTurnOrderLifecyclePhase.NOT_PLACED,
            showLiveOrdersPanel = false,
            showOrdersPreview = previewWhenIdle,
            showLiveOrderChart = false,
            statusMessage = TouchTurnSessionReasonUi.orderLifecycleMessage(
                TouchTurnOrderLifecyclePhase.NOT_PLACED,
                session
            )
        )

    private fun closed(showOrdersPreview: Boolean): TouchTurnOrderLifecycleUi =
        TouchTurnOrderLifecycleUi(
            phase = TouchTurnOrderLifecyclePhase.CLOSED,
            showLiveOrdersPanel = false,
            showOrdersPreview = showOrdersPreview,
            showLiveOrderChart = false,
            statusMessage = null
        )

    private fun livePhase(
        phase: TouchTurnOrderLifecyclePhase,
        session: TouchTurnSessionContext?
    ): TouchTurnOrderLifecycleUi {
        val showLiveOrdersPanel = phase != TouchTurnOrderLifecyclePhase.NOT_PLACED
        return TouchTurnOrderLifecycleUi(
            phase = phase,
            showLiveOrdersPanel = showLiveOrdersPanel,
            showOrdersPreview = phase == TouchTurnOrderLifecyclePhase.NOT_PLACED,
            showLiveOrderChart = showLiveOrdersPanel,
            statusMessage = TouchTurnSessionReasonUi.orderLifecycleMessage(phase, session)
        )
    }
}
