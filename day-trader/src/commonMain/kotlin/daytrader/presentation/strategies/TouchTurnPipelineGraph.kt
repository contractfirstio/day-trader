package daytrader.presentation.strategies

/**
 * Touch Turn session lifecycle as a fixed DAG (trunk + trade/no-trade branches).
 * Built from [TouchTurnStatusBreadcrumbMapper] step states.
 */
enum class TouchTurnPipelineNodeId {
    Start,
    Data,
    Bar,
    Liquidity,
    Confirmation,
    Orders,
    Position,
    NoTrade,
    Close
}

enum class TouchTurnPipelineEdgeState {
    /** On the active path, already passed. */
    Taken,
    /** On the active path, leading into the current node. */
    Active,
    /** Valid branch that was not taken after a decision. */
    Dimmed,
    /** Not relevant yet or unreachable. */
    Unreachable
}

data class TouchTurnPipelineNode(
    val id: TouchTurnPipelineNodeId,
    val label: String,
    val shortLabel: String,
    val state: TouchTurnBreadcrumbStepState,
    val timestamp: String?,
    /** Normalized layout X in 0..1. */
    val x: Float,
    /** Normalized layout Y in 0..1. */
    val y: Float,
    /** Diamond shape for decision nodes. */
    val isDecision: Boolean = false
)

data class TouchTurnPipelineEdge(
    val from: TouchTurnPipelineNodeId,
    val to: TouchTurnPipelineNodeId,
    val label: String? = null,
    val state: TouchTurnPipelineEdgeState
)

data class TouchTurnPipelineGraph(
    val nodes: List<TouchTurnPipelineNode>,
    val edges: List<TouchTurnPipelineEdge>,
    val activePath: List<TouchTurnPipelineNodeId>,
    val caption: String
) {
    fun currentNodeId(): TouchTurnPipelineNodeId? =
        nodes.firstOrNull { it.state == TouchTurnBreadcrumbStepState.CURRENT }?.id

    /** Best node to show when nothing is selected yet. */
    fun defaultSelectedNode(): TouchTurnPipelineNodeId? =
        currentNodeId()
            ?: activePath.lastOrNull { id ->
                nodes.firstOrNull { it.id == id }?.state == TouchTurnBreadcrumbStepState.COMPLETED
            }
            ?: nodes.firstOrNull { it.isSelectable() }?.id

    fun node(id: TouchTurnPipelineNodeId): TouchTurnPipelineNode? =
        nodes.firstOrNull { it.id == id }
}

fun TouchTurnPipelineNode.isSelectable(): Boolean =
    state != TouchTurnBreadcrumbStepState.SKIPPED &&
        state != TouchTurnBreadcrumbStepState.UPCOMING

fun TouchTurnPipelineNodeId.detailTitle(): String = when (this) {
    TouchTurnPipelineNodeId.Start -> "Starting session"
    TouchTurnPipelineNodeId.Data -> "Session data"
    TouchTurnPipelineNodeId.Bar -> "Opening 15-minute bar"
    TouchTurnPipelineNodeId.Liquidity -> "Liquidity check"
    TouchTurnPipelineNodeId.Confirmation -> "Close confirmation"
    TouchTurnPipelineNodeId.Orders -> "Orders"
    TouchTurnPipelineNodeId.Position -> "Position"
    TouchTurnPipelineNodeId.NoTrade -> "No trade path"
    TouchTurnPipelineNodeId.Close -> "Closing session"
}

object TouchTurnPipelineLayout {
    fun position(id: TouchTurnPipelineNodeId): Pair<Float, Float> = when (id) {
        TouchTurnPipelineNodeId.Start -> 0.05f to 0.45f
        TouchTurnPipelineNodeId.Data -> 0.20f to 0.45f
        TouchTurnPipelineNodeId.Bar -> 0.35f to 0.45f
        TouchTurnPipelineNodeId.Liquidity -> 0.50f to 0.45f
        TouchTurnPipelineNodeId.Confirmation -> 0.64f to 0.45f
        TouchTurnPipelineNodeId.Orders -> 0.77f to 0.16f
        TouchTurnPipelineNodeId.Position -> 0.88f to 0.16f
        TouchTurnPipelineNodeId.NoTrade -> 0.77f to 0.74f
        TouchTurnPipelineNodeId.Close -> 0.93f to 0.45f
    }

    val edgeDefinitions: List<Triple<TouchTurnPipelineNodeId, TouchTurnPipelineNodeId, String?>> = listOf(
        Triple(TouchTurnPipelineNodeId.Start, TouchTurnPipelineNodeId.Data, null),
        Triple(TouchTurnPipelineNodeId.Data, TouchTurnPipelineNodeId.Bar, null),
        Triple(TouchTurnPipelineNodeId.Bar, TouchTurnPipelineNodeId.Liquidity, null),
        Triple(TouchTurnPipelineNodeId.Liquidity, TouchTurnPipelineNodeId.Confirmation, "yes"),
        Triple(TouchTurnPipelineNodeId.Liquidity, TouchTurnPipelineNodeId.NoTrade, "no"),
        Triple(TouchTurnPipelineNodeId.Confirmation, TouchTurnPipelineNodeId.Orders, "yes"),
        Triple(TouchTurnPipelineNodeId.Confirmation, TouchTurnPipelineNodeId.NoTrade, "no"),
        Triple(TouchTurnPipelineNodeId.Orders, TouchTurnPipelineNodeId.Position, "yes"),
        Triple(TouchTurnPipelineNodeId.Orders, TouchTurnPipelineNodeId.NoTrade, "no"),
        Triple(TouchTurnPipelineNodeId.Position, TouchTurnPipelineNodeId.Close, null),
        Triple(TouchTurnPipelineNodeId.NoTrade, TouchTurnPipelineNodeId.Close, null)
    )
}
