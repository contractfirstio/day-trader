package daytrader.broker.emulator

/**
 * Deterministic instrument catalog and opening portfolio for the in-memory broker.
 */
internal object EmulatorSeedCatalog {
    fun instruments(): Map<String, EmulatorInstrument> = listOf(
        EmulatorInstrument("AAPL", "Apple Inc.", "USD", 178.50, 181.10),
        EmulatorInstrument("TSLA", "Tesla Inc.", "USD", 205.00, 198.30),
        EmulatorInstrument("NVDA", "NVIDIA Corp.", "USD", 470.00, 485.25),
        EmulatorInstrument("MSFT", "Microsoft Corp.", "USD", 385.00, 389.50),
        EmulatorInstrument("AMD", "Advanced Micro Devices", "USD", 110.00, 108.40),
        EmulatorInstrument("AMZN", "Amazon.com Inc.", "USD", 148.00, 151.20),
        EmulatorInstrument("SPY", "SPDR S&P 500 ETF Trust", "USD", 518.00, 521.60),
        EmulatorInstrument("HSI", "Hang Seng Index", "HKD", 17_200.0, 17_450.0),
        EmulatorInstrument("UKX", "FTSE 100 Index", "GBP", 8_150.0, 8_220.0),
        EmulatorInstrument("QQQ", "Invesco QQQ Trust", "USD", 448.00, 451.25),
        EmulatorInstrument("META", "Meta Platforms Inc.", "USD", 480.00, 492.80),
        EmulatorInstrument("700", "Tencent Holdings Ltd.", "HKD", 375.00, 382.40),
        EmulatorInstrument("9988", "Alibaba Group Holding Ltd.", "HKD", 78.50, 76.20)
    ).associateBy { it.symbol }

    fun initialPositions(accountId: String, prices: Map<String, Double>): List<EmulatorPosition> {
        val catalog = instruments()
        return listOf(
            position(accountId, catalog["AAPL"]!!, qty = 150, avg = 175.20, market = prices),
            position(accountId, catalog["TSLA"]!!, qty = -80, avg = 210.50, market = prices),
            position(accountId, catalog["NVDA"]!!, qty = 65, avg = 450.00, market = prices),
            position(accountId, catalog["MSFT"]!!, qty = 110, avg = 380.10, market = prices),
            position(accountId, catalog["AMD"]!!, qty = 120, avg = 112.00, market = prices),
            position(accountId, catalog["AMZN"]!!, qty = 200, avg = 145.00, market = prices),
            position(accountId, catalog["SPY"]!!, qty = 100, avg = 519.80, market = prices),
            position(accountId, catalog["700"]!!, qty = 500, avg = 378.00, market = prices)
        )
    }

    fun initialOrders(
        catalog: Map<String, EmulatorInstrument>,
        prices: Map<String, Double>,
        nextOrderId: () -> Int
    ): List<EmulatorOrder> {
        val spy = catalog["SPY"]!!
        val spyMkt = prices[spy.symbol] ?: spy.referencePrice
        val parentId = nextOrderId()
        val stopId = nextOrderId()
        val limitId = nextOrderId()
        return listOf(
            EmulatorOrder(
                orderId = parentId,
                symbol = spy.symbol,
                action = "BUY",
                quantity = 50,
                filled = 0,
                remaining = 50,
                orderType = "LMT",
                limitPrice = spyMkt - 0.40,
                stopPrice = null,
                status = "Submitted",
                currency = spy.currency,
                parentId = 0
            ),
            EmulatorOrder(
                orderId = stopId,
                symbol = spy.symbol,
                action = "SELL",
                quantity = 50,
                filled = 0,
                remaining = 50,
                orderType = "STP",
                limitPrice = null,
                stopPrice = spyMkt - 1.20,
                status = "PreSubmitted",
                currency = spy.currency,
                parentId = parentId
            ),
            EmulatorOrder(
                orderId = limitId,
                symbol = spy.symbol,
                action = "SELL",
                quantity = 50,
                filled = 0,
                remaining = 50,
                orderType = "LMT",
                limitPrice = spyMkt + 1.80,
                stopPrice = null,
                status = "PreSubmitted",
                currency = spy.currency,
                parentId = parentId
            ),
            EmulatorOrder(
                orderId = nextOrderId(),
                symbol = "QQQ",
                action = "BUY",
                quantity = 40,
                filled = 0,
                remaining = 40,
                orderType = "LMT",
                limitPrice = (prices["QQQ"] ?: catalog["QQQ"]!!.referencePrice) - 0.55,
                stopPrice = null,
                status = "Submitted",
                currency = "USD",
                parentId = 0
            ),
            EmulatorOrder(
                orderId = nextOrderId(),
                symbol = "TSLA",
                action = "BUY",
                quantity = 25,
                filled = 10,
                remaining = 15,
                orderType = "LMT",
                limitPrice = (prices["TSLA"] ?: catalog["TSLA"]!!.referencePrice) + 0.25,
                stopPrice = null,
                status = "Submitted",
                currency = "USD",
                parentId = 0
            ),
            EmulatorOrder(
                orderId = nextOrderId(),
                symbol = "700",
                action = "SELL",
                quantity = 200,
                filled = 0,
                remaining = 200,
                orderType = "LMT",
                limitPrice = (prices["700"] ?: catalog["700"]!!.referencePrice) + 2.0,
                stopPrice = null,
                status = "Submitted",
                currency = "HKD",
                parentId = 0
            )
        )
    }

    private fun position(
        accountId: String,
        instrument: EmulatorInstrument,
        qty: Int,
        avg: Double,
        market: Map<String, Double>
    ): EmulatorPosition {
        val mkt = market[instrument.symbol] ?: instrument.referencePrice
        return EmulatorPosition(
            account = accountId,
            instrument = instrument,
            quantity = qty,
            avgPrice = avg,
            marketPrice = mkt
        )
    }
}

internal data class EmulatorPosition(
    val account: String,
    val instrument: EmulatorInstrument,
    val quantity: Int,
    val avgPrice: Double,
    val marketPrice: Double
) {
    fun toAccountPosition(
        bid: Double = marketPrice,
        ask: Double = marketPrice,
        last: Double = marketPrice
    ): daytrader.gateway.AccountPosition {
        val pnl = daytrader.domain.InstrumentPriceScale.unrealizedPnL(
            quantity = quantity,
            avgPriceRaw = avgPrice,
            marketPriceRaw = marketPrice,
            currency = instrument.currency,
            primaryExch = instrument.primaryExch
        )
        return daytrader.gateway.AccountPosition(
            account = account,
            symbol = instrument.symbol,
            companyName = instrument.companyName,
            quantity = quantity,
            avgPrice = avgPrice,
            marketPrice = marketPrice,
            bidPrice = bid,
            askPrice = ask,
            lastTradePrice = last,
            priorClose = instrument.priorClose,
            totalUnrealizedPnL = pnl,
            currency = instrument.currency
        )
    }
}

internal data class EmulatorOrder(
    val orderId: Int,
    val symbol: String,
    val action: String,
    val quantity: Int,
    val filled: Int,
    val remaining: Int,
    val orderType: String,
    val limitPrice: Double?,
    val stopPrice: Double?,
    val status: String,
    val currency: String,
    val parentId: Int = 0,
    val trailTriggerPrice: Double? = null,
    val trailAmount: Double? = null,
    val trailingArmed: Boolean = false,
    /** Bid/ask when trailing first armed — baseline for measuring further favorable movement. */
    val trailAnchorPrice: Double? = null,
    /** Best bid (long) or ask (short) since trailing armed. */
    val trailExtremePrice: Double? = null,
    /** Stop price when trailing first arms (entry). */
    val trailArmStopPrice: Double? = null
) {
    fun toWorkingOrder(): daytrader.gateway.WorkingOrder = daytrader.gateway.WorkingOrder(
        orderId = orderId,
        permId = orderId.toLong(),
        parentOrderId = parentId,
        symbol = symbol,
        action = action,
        quantity = quantity,
        filled = filled,
        remaining = remaining,
        orderType = orderType,
        limitPrice = limitPrice,
        stopPrice = stopPrice,
        status = status,
        currency = currency,
        trailTriggerPrice = trailTriggerPrice,
        trailAmount = trailAmount
    )

    fun isTerminal(): Boolean = status in TERMINAL_STATUSES

    companion object {
        val TERMINAL_STATUSES = setOf("Filled", "Cancelled", "Inactive", "ApiCancelled")
    }
}
