package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TradeSide {
    LONG,
    SHORT;

    fun label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}
