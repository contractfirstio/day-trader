package daytrader.data.persistence

import daytrader.domain.InstrumentIdentity
import kotlinx.serialization.Serializable

@Serializable
data class InstrumentIdentityRecord(
    val symbol: String,
    val secType: String = "STK",
    val exchange: String,
    val primaryExch: String? = null,
    val currency: String,
    val conId: Long? = null,
    val localSymbol: String? = null,
    val tradingClass: String? = null
)

internal object InstrumentIdentityPersistence {
    fun toDomain(record: InstrumentIdentityRecord?): InstrumentIdentity? =
        record?.let {
            InstrumentIdentity(
                symbol = it.symbol,
                secType = it.secType,
                exchange = it.exchange,
                primaryExch = it.primaryExch,
                currency = it.currency,
                conId = it.conId,
                localSymbol = it.localSymbol,
                tradingClass = it.tradingClass
            )
        }

    fun toRecord(identity: InstrumentIdentity?): InstrumentIdentityRecord? =
        identity?.let {
            InstrumentIdentityRecord(
                symbol = it.symbol,
                secType = it.secType,
                exchange = it.exchange,
                primaryExch = it.primaryExch,
                currency = it.currency,
                conId = it.conId,
                localSymbol = it.localSymbol,
                tradingClass = it.tradingClass
            )
        }
}
