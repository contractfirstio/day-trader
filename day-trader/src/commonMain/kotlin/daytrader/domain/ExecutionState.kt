package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionState {
    FLAT,
    WORKING,
    FILLED
}
