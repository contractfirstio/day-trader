package daytrader.presentation.strategies

/**
 * Touch Turn session lifecycle as a linear pipeline with decision branches at Rules and Orders.
 * Built from [TouchTurnStatusBreadcrumbMapper] step states.
 */
enum class TouchTurnPipelineNodeId {
    Readiness,
    Data,
    Rules,
    /** Post-sweep 5-minute hammer confirmation (when enabled). */
    FiveMinConfirmation,
    Orders,
    Position,
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
    val caption: String,
    /** Prominent explanation when trade was blocked, data failed, or session is stopping. */
    val statusBanner: TouchTurnSessionStatusUi? = null
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
    TouchTurnPipelineNodeId.Readiness -> "Session start"
    TouchTurnPipelineNodeId.Data -> "Market data"
    TouchTurnPipelineNodeId.Rules -> "Entry rules"
    TouchTurnPipelineNodeId.FiveMinConfirmation -> "5m confirmation"
    TouchTurnPipelineNodeId.Orders -> "Orders"
    TouchTurnPipelineNodeId.Position -> "Position"
    TouchTurnPipelineNodeId.Close -> "Closing session"
}

object TouchTurnPipelineLayout {
    private const val Y = 0.45f

    private val xWithFiveMin = mapOf(
        TouchTurnPipelineNodeId.Readiness to 0.05f,
        TouchTurnPipelineNodeId.Data to 0.19f,
        TouchTurnPipelineNodeId.Rules to 0.33f,
        TouchTurnPipelineNodeId.FiveMinConfirmation to 0.47f,
        TouchTurnPipelineNodeId.Orders to 0.61f,
        TouchTurnPipelineNodeId.Position to 0.75f,
        TouchTurnPipelineNodeId.Close to 0.89f
    )

    private val xWithoutFiveMin = mapOf(
        TouchTurnPipelineNodeId.Readiness to 0.05f,
        TouchTurnPipelineNodeId.Data to 0.22f,
        TouchTurnPipelineNodeId.Rules to 0.39f,
        TouchTurnPipelineNodeId.Orders to 0.56f,
        TouchTurnPipelineNodeId.Position to 0.73f,
        TouchTurnPipelineNodeId.Close to 0.89f
    )

    fun position(id: TouchTurnPipelineNodeId, includeFiveMin: Boolean = true): Pair<Float, Float> {
        val xMap = if (includeFiveMin) xWithFiveMin else xWithoutFiveMin
        return (xMap[id] ?: error("No layout for $id when includeFiveMin=$includeFiveMin")) to Y
    }

    private val allEdgeDefinitions: List<Triple<TouchTurnPipelineNodeId, TouchTurnPipelineNodeId, String?>> = listOf(
        Triple(TouchTurnPipelineNodeId.Readiness, TouchTurnPipelineNodeId.Data, null),
        Triple(TouchTurnPipelineNodeId.Data, TouchTurnPipelineNodeId.Rules, null),
        Triple(TouchTurnPipelineNodeId.Rules, TouchTurnPipelineNodeId.FiveMinConfirmation, "sweep"),
        Triple(TouchTurnPipelineNodeId.Rules, TouchTurnPipelineNodeId.Orders, "pass"),
        Triple(TouchTurnPipelineNodeId.Rules, TouchTurnPipelineNodeId.Close, "no"),
        Triple(TouchTurnPipelineNodeId.FiveMinConfirmation, TouchTurnPipelineNodeId.Orders, "hammer"),
        Triple(TouchTurnPipelineNodeId.FiveMinConfirmation, TouchTurnPipelineNodeId.Close, "fail"),
        Triple(TouchTurnPipelineNodeId.Orders, TouchTurnPipelineNodeId.Position, "fill"),
        Triple(TouchTurnPipelineNodeId.Orders, TouchTurnPipelineNodeId.Close, "skip"),
        Triple(TouchTurnPipelineNodeId.Position, TouchTurnPipelineNodeId.Close, null)
    )

    fun edgeDefinitions(includeFiveMin: Boolean): List<Triple<TouchTurnPipelineNodeId, TouchTurnPipelineNodeId, String?>> =
        if (includeFiveMin) {
            allEdgeDefinitions
        } else {
            allEdgeDefinitions.filter { (from, to, _) ->
                from != TouchTurnPipelineNodeId.FiveMinConfirmation &&
                    to != TouchTurnPipelineNodeId.FiveMinConfirmation
            }
        }
}
