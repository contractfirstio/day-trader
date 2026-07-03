package daytrader.broker

import com.ib.client.ContractDetails
import com.ib.client.PriceIncrement
import daytrader.domain.InstrumentPriceIncrement

/** IB [ContractDetails.marketRuleIds] + [reqMarketRule] price ladders. */
internal object IbMarketRuleRules {
    fun marketRuleIdForContract(details: ContractDetails): Int? {
        val ids = details.marketRuleIds()
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        if (ids.isEmpty()) return null
        if (ids.size == 1) return ids.first()
        val exchanges = details.validExchanges()
            ?.split(",")
            ?.map { it.trim().uppercase() }
            .orEmpty()
        val contract = details.contract()
        val target = listOfNotNull(
            contract.primaryExch()?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
            contract.exchange()?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        )
        for (exch in target) {
            val index = exchanges.indexOf(exch)
            if (index in ids.indices) return ids[index]
        }
        return ids.first()
    }

    fun toDomainIncrements(increments: Array<PriceIncrement>): List<InstrumentPriceIncrement> =
        increments
            .mapNotNull { band ->
                val increment = band.increment()
                val lowEdge = band.lowEdge()
                if (increment > 0.0 && increment.isFinite() && lowEdge.isFinite()) {
                    InstrumentPriceIncrement(lowEdge = lowEdge, increment = increment)
                } else {
                    null
                }
            }
            .sortedBy { it.lowEdge }
}
