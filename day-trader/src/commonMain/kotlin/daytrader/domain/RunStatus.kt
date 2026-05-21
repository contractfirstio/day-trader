package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RunStatus {
    IN_PROGRESS,
    CLOSED
}
