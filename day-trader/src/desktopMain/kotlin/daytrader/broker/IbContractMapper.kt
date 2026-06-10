package daytrader.broker

import com.ib.client.Contract
import com.ib.client.protobuf.ContractProto
import daytrader.domain.InstrumentIdentity

internal object IbContractMapper {
    fun fromIdentity(identity: InstrumentIdentity): Contract {
        val contract = Contract()
        identity.conId?.takeIf { it > 0 }?.let { contract.conid(it.toInt()) }
        contract.symbol(identity.symbol.uppercase())
        contract.secType(identity.secType.ifBlank { "STK" })
        contract.exchange(identity.exchange.ifBlank { "SMART" })
        contract.currency(identity.currency.uppercase())
        identity.primaryExch?.takeIf { it.isNotBlank() }?.let { contract.primaryExch(it) }
        identity.localSymbol?.takeIf { it.isNotBlank() }?.let { contract.localSymbol(it) }
        identity.tradingClass?.takeIf { it.isNotBlank() }?.let { contract.tradingClass(it) }
        return contract
    }

    fun contractForSymbol(symbol: String, instrument: InstrumentIdentity?): Contract =
        instrument?.let { forDataRequest(fromIdentity(it)) }
            ?: forDataRequest(stockForHistorical(symbol))
    fun usStock(symbol: String): Contract {
        val contract = Contract()
        contract.symbol(symbol.uppercase())
        contract.secType("STK")
        contract.exchange("SMART")
        contract.currency("USD")
        return contract
    }

    /** Contract for historical bars — routes numeric symbols to SEHK (Hong Kong). */
    fun stockForHistorical(symbol: String): Contract =
        if (SymbolMarkets.isHongKong(symbol)) hkStock(symbol) else usStock(symbol)

    /** Primary home-market index contract for 200-day regime requests. */
    fun macroBenchmarkContract(symbol: String): Contract =
        macroBenchmarkContractCandidates(symbol).first()

    /**
     * Ordered IB contract candidates for home-market index history.
     * Tries fallbacks when the primary exchange returns no bars (common for HSI/UKX).
     */
    fun macroBenchmarkContractCandidates(symbol: String): List<Contract> =
        when (symbol.trim().uppercase()) {
            "HSI" -> listOf(
                indexContract("HSI", "HKFE", "HKD"),
                indexContract("HSI", "HKEX", "HKD"),
                indexContract("HSI", "SMART", "HKD", primaryExch = "HKFE")
            )
            "UKX" -> listOf(
                indexContract("UKX", "ICEEU", "GBP"),
                indexContract("UKX", "SMART", "GBP", primaryExch = "ICEEU"),
                // FTSE 100 tracker ETF when cash index contract is unavailable on the account.
                lseStock("ISF")
            )
            else -> listOf(stockForHistorical(symbol))
        }

    private fun indexContract(
        symbol: String,
        exchange: String,
        currency: String,
        primaryExch: String? = null
    ): Contract = Contract().apply {
        symbol(symbol)
        secType("IND")
        exchange(exchange)
        currency(currency)
        primaryExch?.let { primaryExch(it) }
    }

    private fun lseStock(symbol: String): Contract =
        smartStock(symbol, "GBP").apply { primaryExch("LSE") }

    fun smartStock(symbol: String, currency: String): Contract {
        val contract = Contract()
        contract.symbol(symbol.trim().uppercase())
        contract.secType("STK")
        contract.exchange("SMART")
        contract.currency(currency.uppercase())
        return contract
    }

    /**
     * IB requires currency on SMART [reqContractDetails] (error 200 otherwise).
     * Dual-listed symbols need separate USD and GBP lookups to surface NYSE + LSE.
     */
    fun describe(contract: Contract): String =
        "symbol=${contract.symbol()} secType=${contract.getSecType()} " +
            "exchange=${contract.exchange()} primary=${contract.primaryExch()} " +
            "currency=${contract.currency()} conId=${contract.conid()}"

    fun contractDetailsLookupContracts(symbol: String): List<Contract> {
        val trimmed = symbol.trim().uppercase()
        if (SymbolMarkets.isHongKong(trimmed)) {
            return listOf(forDataRequest(hkStock(trimmed)))
        }
        return listOf(
            forDataRequest(smartStock(trimmed, "USD")),
            forDataRequest(smartStock(trimmed, "GBP"))
        )
    }

    fun hkStock(symbol: String): Contract {
        val digits = symbol.trim().uppercase().removeSuffix(".HK")
        val ibSymbol = digits.toLongOrNull()?.toString() ?: digits.trimStart('0').ifEmpty { digits }
        val contract = Contract()
        contract.symbol(ibSymbol)
        contract.secType("STK")
        contract.exchange("SEHK")
        contract.currency("HKD")
        contract.primaryExch("SEHK")
        return contract
    }

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
