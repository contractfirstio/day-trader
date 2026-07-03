package daytrader.domain

import daytrader.broker.SymbolMarkets
import kotlinx.serialization.Serializable

/** IB contract fields used for market data, historical bars, and order placement. */
@Serializable
data class InstrumentIdentity(
    val symbol: String,
    val secType: String = "STK",
    val exchange: String,
    val primaryExch: String? = null,
    val currency: String,
    val conId: Long? = null,
    val localSymbol: String? = null,
    val tradingClass: String? = null,
    /** IB [ContractDetails.minSize] when resolved via reqContractDetails; 1 for US unit lots. */
    val minOrderSize: Int = 1,
    /** IB [ContractDetails.sizeIncrement] (or suggested increment); 1 for US unit lots. */
    val orderSizeIncrement: Int = 1,
    /** IB [ContractDetails.minTick] when resolved via reqContractDetails. */
    val minPriceTick: Double? = null
) {
    /** Stable key for deduplicating contract-detail rows. */
    fun dedupeKey(): String =
        conId?.takeIf { it > 0 }?.toString()
            ?: listOf(symbol, exchange, primaryExch.orEmpty(), currency).joinToString("|")

    companion object {
        fun fromContractSnapshot(
            snapshot: InstrumentMarketResolver.ContractSnapshot,
            conId: Long? = null
        ): InstrumentIdentity {
            val symbol = snapshot.symbol.trim().uppercase()
            val exchange = snapshot.exchange?.trim()?.uppercase().orEmpty().ifBlank { "SMART" }
            val primary = snapshot.primaryExch?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val currency = snapshot.currency?.trim()?.uppercase().orEmpty().ifBlank {
                DeploymentMarket.currencyForZone(
                    InstrumentMarketResolver.fromIbContract(snapshot).marketZoneId
                )
            }
            val orderSizeRules = InstrumentOrderSizeRules.fromIbValues(
                minOrderSize = snapshot.minOrderSize,
                orderSizeIncrement = snapshot.orderSizeIncrement
            )
            return InstrumentIdentity(
                symbol = symbol,
                exchange = exchange,
                primaryExch = primary,
                currency = currency,
                conId = conId?.takeIf { it > 0 },
                minOrderSize = orderSizeRules.minOrderSize,
                orderSizeIncrement = orderSizeRules.orderSizeIncrement,
                minPriceTick = snapshot.minPriceTick?.takeIf { it > 0.0 }
            )
        }

        /** Legacy deployments and offline emulator resolution. */
        fun heuristic(symbol: String, currencyCode: String = "USD"): InstrumentIdentity {
            val norm = SymbolMarkets.normalizeSymbol(symbol)
            return if (SymbolMarkets.isHongKong(symbol)) {
                InstrumentIdentity(
                    symbol = norm,
                    exchange = "SEHK",
                    primaryExch = "SEHK",
                    currency = "HKD"
                )
            } else {
                InstrumentIdentity(
                    symbol = norm,
                    exchange = "SMART",
                    currency = currencyCode.ifBlank { "USD" }.uppercase()
                )
            }
        }
    }
}
