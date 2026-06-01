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
        val ordersCommitted = session?.sessionOrdersPlaced() == true
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
                        statusMessage = null
                    )
                else -> notPlaced(previewWhenIdle = true)
            }
        }

        val phase = when {
            inActiveTrade || hasOpenPosition -> TouchTurnOrderLifecyclePhase.IN_POSITION
            hasOpenOrders -> TouchTurnOrderLifecyclePhase.AWAITING_ENTRY
            ordersCommitted -> TouchTurnOrderLifecyclePhase.SUBMITTED_PENDING_BROKER_VISIBILITY
            else -> TouchTurnOrderLifecyclePhase.NOT_PLACED
        }
        return livePhase(phase)
    }

    private fun notPlaced(previewWhenIdle: Boolean): TouchTurnOrderLifecycleUi =
        TouchTurnOrderLifecycleUi(
            phase = TouchTurnOrderLifecyclePhase.NOT_PLACED,
            showLiveOrdersPanel = false,
            showOrdersPreview = previewWhenIdle,
            showLiveOrderChart = false,
            statusMessage = null
        )

    private fun closed(showOrdersPreview: Boolean): TouchTurnOrderLifecycleUi =
        TouchTurnOrderLifecycleUi(
            phase = TouchTurnOrderLifecyclePhase.CLOSED,
            showLiveOrdersPanel = false,
            showOrdersPreview = showOrdersPreview,
            showLiveOrderChart = false,
            statusMessage = null
        )

    private fun livePhase(phase: TouchTurnOrderLifecyclePhase): TouchTurnOrderLifecycleUi {
        val showLiveOrdersPanel = phase != TouchTurnOrderLifecyclePhase.NOT_PLACED
        return TouchTurnOrderLifecycleUi(
            phase = phase,
            showLiveOrdersPanel = showLiveOrdersPanel,
            showOrdersPreview = phase == TouchTurnOrderLifecyclePhase.NOT_PLACED,
            showLiveOrderChart = showLiveOrdersPanel,
            statusMessage = when (phase) {
                TouchTurnOrderLifecyclePhase.SUBMITTED_PENDING_BROKER_VISIBILITY ->
                    "Orders submitted for this session; awaiting broker open-order visibility or fill."
                else -> null
            }
        )
    }
}
