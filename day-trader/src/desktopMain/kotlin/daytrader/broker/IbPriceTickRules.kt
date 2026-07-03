package daytrader.broker

import com.ib.client.ContractDetails

/** IB [ContractDetails.minTick] for order price quantization. */
internal object IbPriceTickRules {
    fun fromContractDetails(details: ContractDetails): Double? =
        details.minTick().takeIf { it > 0.0 && it.isFinite() }
}
