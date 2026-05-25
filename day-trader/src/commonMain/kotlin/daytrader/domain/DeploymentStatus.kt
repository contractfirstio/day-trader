package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DeploymentStatus {
    RUNNING,
    STOPPED,
    ERROR
}
