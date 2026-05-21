package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
enum class InstanceStatus {
    RUNNING,
    STOPPED,
    ERROR
}
