package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SessionStatus {
    IN_PROGRESS,
    CLOSED
}
