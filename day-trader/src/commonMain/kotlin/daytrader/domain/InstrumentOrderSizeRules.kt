package daytrader.domain

import kotlinx.serialization.Serializable

/** Minimum quantity and lot step for order placement; defaults suit US whole-share trading. */
@Serializable
data class InstrumentOrderSizeRules(
    val minOrderSize: Int = 1,
    val orderSizeIncrement: Int = 1
) {
    init {
        require(minOrderSize > 0) { "minOrderSize must be positive" }
        require(orderSizeIncrement > 0) { "orderSizeIncrement must be positive" }
    }

    /** True when both fields are the unit-lot default (typical US equity). */
    fun isUnitLot(): Boolean = minOrderSize == 1 && orderSizeIncrement == 1

    fun isValidQuantity(quantity: Int): Boolean =
        quantity >= minOrderSize &&
            (quantity - minOrderSize) % orderSizeIncrement == 0

    fun validateQuantity(quantity: Int): String? = when {
        quantity < minOrderSize ->
            "Quantity must be at least $minOrderSize shares (minimum board lot)"
        !isValidQuantity(quantity) ->
            "Quantity must increase in steps of $orderSizeIncrement above the minimum lot"
        else -> null
    }

    /** Floor [rawQuantity] to the nearest valid lot at or below the requested size. */
    fun snapQuantityDown(rawQuantity: Int): SnapOrderSizeResult {
        if (rawQuantity < minOrderSize) {
            return SnapOrderSizeResult.BelowMinimum(minimum = minOrderSize)
        }
        val excess = rawQuantity - minOrderSize
        val steps = excess / orderSizeIncrement
        return SnapOrderSizeResult.Ok(minOrderSize + steps * orderSizeIncrement)
    }

    /** Share count for one upsize step on a working order that already meets the minimum lot. */
    fun additionalLotShares(currentQuantity: Int): Int =
        if (currentQuantity >= minOrderSize) orderSizeIncrement else minOrderSize

    /** Notional for one upsize lot at [entryPrice] given [currentQuantity] on the working order. */
    fun additionalLotNotional(entryPrice: Double, currentQuantity: Int): Int {
        if (entryPrice <= 0.0) return 0
        return kotlin.math.ceil(additionalLotShares(currentQuantity) * entryPrice).toInt().coerceAtLeast(1)
    }

    /** Floor [rawAdditional] to a valid upsize step for a working order at [currentQuantity]. */
    fun snapAdditionalQuantityDown(rawAdditional: Int, currentQuantity: Int): SnapOrderSizeResult {
        if (rawAdditional <= 0) {
            return SnapOrderSizeResult.BelowMinimum(minimum = additionalLotShares(currentQuantity))
        }
        val snapped = (rawAdditional / orderSizeIncrement) * orderSizeIncrement
        if (snapped <= 0) {
            return SnapOrderSizeResult.BelowMinimum(minimum = additionalLotShares(currentQuantity))
        }
        return SnapOrderSizeResult.Ok(snapped)
    }

    companion object {
        val DEFAULT = InstrumentOrderSizeRules()

        /** Applies IB contract-detail values; missing or invalid fields keep unit-lot defaults. */
        fun fromIbValues(minOrderSize: Int?, orderSizeIncrement: Int?): InstrumentOrderSizeRules {
            val min = minOrderSize?.takeIf { it > 0 } ?: DEFAULT.minOrderSize
            val increment = orderSizeIncrement?.takeIf { it > 0 } ?: min
            // US unit-lot (min 1): IB sizeIncrement is a suggested order step, not a board lot.
            val normalizedIncrement = if (min == DEFAULT.minOrderSize) DEFAULT.orderSizeIncrement else increment
            return InstrumentOrderSizeRules(minOrderSize = min, orderSizeIncrement = normalizedIncrement)
        }
    }
}

sealed interface SnapOrderSizeResult {
    data class Ok(val quantity: Int) : SnapOrderSizeResult
    data class BelowMinimum(val minimum: Int) : SnapOrderSizeResult
}

fun InstrumentIdentity.orderSizeRules(): InstrumentOrderSizeRules =
    InstrumentOrderSizeRules(minOrderSize = minOrderSize, orderSizeIncrement = orderSizeIncrement)
