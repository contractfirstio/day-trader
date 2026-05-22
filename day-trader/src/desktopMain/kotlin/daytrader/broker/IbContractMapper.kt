package daytrader.broker

import com.ib.client.Contract
import com.ib.client.protobuf.ContractProto

internal object IbContractMapper {
    fun clone(contract: Contract): Contract {
        val copy = Contract()
        if (contract.conid() > 0) copy.conid(contract.conid())
        val symbol = contract.symbol()
        if (!symbol.isNullOrBlank()) copy.symbol(symbol)
        val secType = contract.getSecType()
        if (!secType.isNullOrBlank()) copy.secType(secType)
        val exchange = contract.exchange()
        if (!exchange.isNullOrBlank()) copy.exchange(exchange)
        val currency = contract.currency()
        if (!currency.isNullOrBlank()) copy.currency(currency)
        val primaryExch = contract.primaryExch()
        if (!primaryExch.isNullOrBlank()) copy.primaryExch(primaryExch)
        val localSymbol = contract.localSymbol()
        if (!localSymbol.isNullOrBlank()) copy.localSymbol(localSymbol)
        val tradingClass = contract.tradingClass()
        if (!tradingClass.isNullOrBlank()) copy.tradingClass(tradingClass)
        return copy
    }

    /** Contract suitable for market-data and contract-details requests. */
    fun forDataRequest(contract: Contract): Contract {
        val copy = clone(contract)
        if (copy.exchange().isNullOrBlank()) copy.exchange("SMART")
        if (copy.currency().isNullOrBlank()) {
            val primary = copy.primaryExch().orEmpty().uppercase()
            val exchange = copy.exchange().orEmpty().uppercase()
            val currency = when {
                primary.contains("LSE") || exchange.contains("LSE") -> "GBP"
                exchange == "SEHK" -> "HKD"
                else -> "USD"
            }
            copy.currency(currency)
        }
        return copy
    }

    fun fromProto(proto: ContractProto.Contract): Contract {
        val contract = Contract()
        if (proto.hasConId()) contract.conid(proto.conId)
        if (proto.hasSymbol()) contract.symbol(proto.symbol)
        if (proto.hasSecType()) contract.secType(proto.secType)
        if (proto.hasExchange()) contract.exchange(proto.exchange)
        if (proto.hasCurrency()) contract.currency(proto.currency)
        if (proto.hasPrimaryExch()) contract.primaryExch(proto.primaryExch)
        if (proto.hasLocalSymbol()) contract.localSymbol(proto.localSymbol)
        if (proto.hasTradingClass()) contract.tradingClass(proto.tradingClass)
        return contract
    }

    fun toProto(contract: Contract): ContractProto.Contract {
        val builder = ContractProto.Contract.newBuilder()
        if (contract.conid() > 0) builder.conId = contract.conid()
        builder.symbol = contract.symbol()
        builder.secType = contract.getSecType()
        builder.exchange = contract.exchange()
        builder.currency = contract.currency()
        if (contract.primaryExch().isNotBlank()) builder.primaryExch = contract.primaryExch()
        if (contract.localSymbol().isNotBlank()) builder.localSymbol = contract.localSymbol()
        if (contract.tradingClass().isNotBlank()) builder.tradingClass = contract.tradingClass()
        return builder.build()
    }
}
