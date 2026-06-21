package daytrader.broker

import com.ib.client.ContractDetails
import com.ib.client.Decimal
import daytrader.domain.InstrumentOrderSizeRules
import kotlin.math.roundToInt

/** IB [ContractDetails] order-size constraints for stocks and other instruments. */
internal object IbOrderSizeRules {
    fun fromContractDetails(details: ContractDetails): InstrumentOrderSizeRules =
        InstrumentOrderSizeRules(
            minOrderSize = positiveQuantity(details.minSize()),
            orderSizeIncrement = positiveQuantity(details.sizeIncrement())
                ?: positiveQuantity(details.suggestedSizeIncrement())
        )

    private fun positiveQuantity(value: Decimal?): Int? {
        if (value == null || !Decimal.isValid(value)) return null
        val quantity = value.value().toDouble().roundToInt()
        return quantity.takeIf { it > 0 }
    }
}
